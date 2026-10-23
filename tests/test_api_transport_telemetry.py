"""Offline transport-stage contracts. No real provider or credential access."""
import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

import app.provider as provider_module
from app.provider import GeminiProvider, TranslationError
from app.telemetry import current_trace


KWARGS = dict(source='zh', profile='game', mode='fast',
    model='gemini-3.5-flash-lite', glossary=[], context=[])


def body(text='Xin chào'):
    return json.dumps({'candidates': [{'finishReason': 'STOP', 'content': {
        'parts': [{'text': json.dumps({'translations': [text]}, ensure_ascii=False)}]}}],
        'usageMetadata': {'promptTokenCount': 12, 'candidatesTokenCount': 6,
                          'thoughtsTokenCount': 0}}, ensure_ascii=False).encode()


async def test_transport_stages_are_separate_and_never_retain_trace_metadata(monkeypatch):
    now = [0.0]
    monkeypatch.setattr(provider_module, 'time', SimpleNamespace(monotonic=lambda: now[0]))
    secret = 'PRIVATE_HEADER_URL_EXCEPTION_CONTENT'
    closed = []

    class TimedBody(httpx.AsyncByteStream):
        def __init__(self, trace):
            self.trace = trace

        async def __aiter__(self):
            await self.trace('http2.receive_response_body.started', {'request': secret})
            now[0] += .04
            yield body()
            now[0] += .02
            await self.trace('http2.receive_response_body.complete', {'return_value': secret})

        async def aclose(self):
            closed.append(True)

    async def handler(request):
        trace = request.extensions['trace']
        for operation, seconds in (('connection.connect_tcp', .01),
                ('connection.start_tls', .02), ('http2.send_request_headers', .003),
                ('http2.send_request_body', .007), ('http2.receive_response_headers', .05)):
            await trace(operation + '.started', {'headers': secret, 'url': secret})
            now[0] += seconds
            await trace(operation + '.complete', {'return_value': secret})
        return httpx.Response(200, stream=TimedBody(trace), extensions={'http_version': b'HTTP/2'})

    provider = GeminiProvider(lambda: secret, httpx.MockTransport(handler))
    trace = {'timings_ms': {}, 'diagnostics': {}}
    token = current_trace.set(trace)
    try:
        assert (await provider.translate(['你好'], **KWARGS))[0] == ['Xin chào']
    finally:
        current_trace.reset(token)
        await provider.close()
    timings = trace['timings_ms']
    assert timings['api_dns_tcp'] == 10
    assert timings['api_tls'] == 20
    assert timings['api_send_headers'] == 3
    assert timings['api_send_body'] == 7
    assert timings['api_wait_headers'] == 50
    assert timings['api_read_body'] == 60
    assert timings['api_headers'] == 90
    assert timings['api_body'] == 60
    assert timings['api_http_total'] == timings['api_total'] == 150
    assert timings['api_parse'] == 0
    assert trace['diagnostics']['api_connection'] == 'new'
    assert trace['diagnostics']['api_http_version'] == 'HTTP/2'
    assert trace['diagnostics']['api_trace_available'] is True
    assert trace['diagnostics']['api_dns_separate'] is False
    assert trace['diagnostics']['api_thought_tokens'] == 0
    assert 'api_cached_input_tokens' not in trace['diagnostics']
    assert secret not in json.dumps(trace)
    assert closed == [True]


@pytest.mark.parametrize('cancel', [False, True])
async def test_failed_and_cancelled_body_keep_duration_and_close_response(cancel, monkeypatch):
    now = [0.0]
    monkeypatch.setattr(provider_module, 'time', SimpleNamespace(monotonic=lambda: now[0]))
    reading = asyncio.Event()
    closed = []
    failure = 'PRIVATE_PROVIDER_FAILURE'

    class BrokenBody(httpx.AsyncByteStream):
        def __init__(self, trace):
            self.trace = trace

        async def __aiter__(self):
            await self.trace('http11.receive_response_body.started', {'request': failure})
            reading.set()
            if cancel:
                await asyncio.Event().wait()
            else:
                now[0] += .025
                await self.trace('http11.receive_response_body.failed', {'exception': failure})
                raise httpx.ReadError(failure)
            yield b''

        async def aclose(self):
            closed.append(True)

    provider = GeminiProvider(lambda: 'offline-key', httpx.MockTransport(
        lambda request: httpx.Response(200, stream=BrokenBody(request.extensions['trace']))))
    trace = {'timings_ms': {}, 'diagnostics': {}}
    token = current_trace.set(trace)
    try:
        task = asyncio.create_task(provider.translate(['你好'], **KWARGS))
        await reading.wait()
        if cancel:
            now[0] += .025
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            with pytest.raises(TranslationError) as error:
                await task
            assert error.value.code == 'provider_unavailable'
            assert failure not in str(error.value)
    finally:
        current_trace.reset(token)
        await provider.close()
    assert trace['timings_ms']['api_total'] > 0
    assert trace['timings_ms']['api_http_total'] > 0
    assert trace['timings_ms']['api_body'] > 0
    assert trace['timings_ms']['api_read_body'] > 0
    assert trace['diagnostics']['api_error'] == ('cancelled' if cancel else 'provider_unavailable')
    if cancel:
        assert trace['diagnostics']['api_partial_stages'] == ['api_read_body']
    else:
        assert trace['diagnostics']['api_failed_stage'] == 'api_read_body'
    assert failure not in json.dumps(trace)
    assert closed == [True]


async def test_untraced_transport_does_not_invent_zero_dns_or_tls():
    provider = GeminiProvider(lambda: 'offline-key', httpx.MockTransport(
        lambda request: httpx.Response(200, content=body())))
    trace = {'timings_ms': {}, 'diagnostics': {}}
    token = current_trace.set(trace)
    try:
        await provider.translate(['你好'], **KWARGS)
    finally:
        current_trace.reset(token)
        await provider.close()
    assert trace['diagnostics']['api_trace_available'] is False
    assert 'api_dns_tcp' not in trace['timings_ms']
    assert 'api_tls' not in trace['timings_ms']
    assert 'api_connection' not in trace['diagnostics']


async def test_concurrent_requests_keep_their_own_stage_and_size_diagnostics():
    first_started = asyncio.Event()
    release_first = asyncio.Event()

    async def handler(request):
        data = json.loads(json.loads(request.content)['contents'][0]['parts'][0]['text'])
        if data['items'] == ['A']:
            first_started.set()
            await release_first.wait()
        else:
            release_first.set()
        return httpx.Response(200, content=body(data['items'][0]))

    provider = GeminiProvider(lambda: 'offline-key', httpx.MockTransport(handler))

    async def translate(text, context):
        trace = {'timings_ms': {}, 'diagnostics': {}}
        token = current_trace.set(trace)
        try:
            output = await provider.translate([text], **{**KWARGS, 'context': context})
            return output, trace
        finally:
            current_trace.reset(token)

    try:
        first = asyncio.create_task(translate('A', []))
        await first_started.wait()
        second = asyncio.create_task(translate('BBBB', [{'source': '旧', 'vi': 'Cũ'}]))
        (a, trace_a), (b, trace_b) = await asyncio.gather(first, second)
    finally:
        await provider.close()
    assert a[0] == ['A'] and b[0] == ['BBBB']
    assert trace_a['diagnostics']['api_source_chars'] == 1
    assert trace_b['diagnostics']['api_source_chars'] == 4
    assert trace_a['diagnostics']['api_context_chars'] == 0
    assert trace_b['diagnostics']['api_context_chars'] == 3
    assert trace_a['timings_ms'] is not trace_b['timings_ms']


async def test_invalid_json_records_parse_and_error_without_provider_body():
    provider = GeminiProvider(lambda: 'offline-key', httpx.MockTransport(
        lambda request: httpx.Response(200, text='PRIVATE_INVALID_RESPONSE')))
    trace = {'timings_ms': {}, 'diagnostics': {}}
    token = current_trace.set(trace)
    try:
        with pytest.raises(TranslationError) as error:
            await provider.translate(['你好'], **KWARGS)
        assert error.value.code == 'invalid_response'
    finally:
        current_trace.reset(token)
        await provider.close()
    assert 'api_parse' in trace['timings_ms']
    assert trace['diagnostics']['api_error'] == 'invalid_response'
    assert 'PRIVATE_INVALID_RESPONSE' not in json.dumps(trace)
