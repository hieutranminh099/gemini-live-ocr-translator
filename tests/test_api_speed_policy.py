"""Offline request policy tests. Every HTTP request uses MockTransport."""
import asyncio
from datetime import datetime, timedelta, timezone
import json

import httpx
import pytest

from app.manager import TranslationManager
from app.provider import (GeminiProvider, ProviderRouter, TranslationError, api_generation_config,
                          api_system, retry_delay_ms, translation_system)
from app.storage import Store
from test_system import frame


def reply(*translations):
    return httpx.Response(200, json={
        'candidates': [{'finishReason': 'STOP', 'content': {'parts': [
            {'text': json.dumps({'translations': list(translations)}, ensure_ascii=False)}]}}],
        'usageMetadata': {'promptTokenCount': 12, 'candidatesTokenCount': 8}})


def retry_info(value):
    return {'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': value}


@pytest.mark.parametrize(('header', 'details', 'expected'), [
    ('2', [], 2000),
    ('1.25', [retry_info('2.5s')], 2500),
    ('8', [retry_info('2s')], 8000),
    ('invalid', [retry_info('3.125s')], 3125),
    ('invalid', [retry_info('bad-s'), None, retry_info('4s')], 4000),
    ('NaN', [retry_info('Infinitys'), retry_info('-1s')], 15000),
    ('', [], 15000),
    ('0.001', [], 1000),
    ('99999', [], 900000),
])
def test_retry_metadata_numeric_fraction_fallback_and_clamps(header, details, expected):
    response = httpx.Response(429, headers={'Retry-After': header}, json={'error': {'details': details}})
    assert retry_delay_ms(response) == expected


def test_retry_http_date_and_malformed_body_are_safe():
    future = datetime.now(timezone.utc) + timedelta(seconds=90)
    response = httpx.Response(429, headers={'Retry-After': future.strftime('%a, %d %b %Y %H:%M:%S GMT')}, text='not JSON')
    assert 88000 <= retry_delay_ms(response) <= 90000
    assert retry_delay_ms(httpx.Response(429, json={'error': {'details': None}}), fallback_ms=30000) == 30000


@pytest.mark.parametrize(('model', 'thinking'), [
    ('gemini-3.5-flash-lite', 'minimal'),
    ('gemini-3.8-flash', 'low'),
    ('gemini-2.0-flash', None),
])
def test_fast_output_budget_and_supported_thinking_policy(model, thinking):
    config = api_generation_config(['你好'], 'fast', model, [])
    assert config['maxOutputTokens'] == 512
    assert config['responseMimeType'] == 'application/json'
    assert config.get('thinkingConfig') == ({'thinkingLevel': thinking} if thinking else None)
    assert api_generation_config(['字' * 2000] * 40, 'fast', model, [])['maxOutputTokens'] == 8192
    assert api_generation_config(['主人' * 10], 'fast', model,
                                 [{'source': '主人', 'target': 'Một tên rất dài ' * 20}])['maxOutputTokens'] > 512


@pytest.mark.parametrize('mode', ['smart', 'quality'])
def test_smart_quality_keep_original_system_and_full_budget(mode):
    assert api_system(mode) == translation_system()
    config = api_generation_config(['你好'], mode, 'gemini-3.5-flash-lite', [])
    assert config['maxOutputTokens'] == 8192
    assert 'thinkingConfig' not in config
    assert len(api_system('fast')) < len(api_system(mode))


async def test_fast_payload_retains_glossary_context_order_and_reuses_client():
    captured = []

    def handler(request):
        captured.append(json.loads(request.content))
        return reply('Xin chào.', 'Tạm biệt.')

    provider = GeminiProvider(lambda: 'offline-placeholder', httpx.MockTransport(handler))
    client = provider.client
    kwargs = dict(source='zh', profile='game', mode='fast', model='gemini-3.5-flash-lite',
                  glossary=[{'source': '先生', 'target': 'ngài', 'profile': 'game'}],
                  context=[{'source': '你是谁', 'vi': 'Bạn là ai?'}])
    try:
        output, _, _ = await provider.translate(['你好。', '再见。'], **kwargs)
        assert output == ['Xin chào.', 'Tạm biệt.']
        assert provider.client is client
        data = json.loads(captured[0]['contents'][0]['parts'][0]['text'])
        assert data['items'] == ['你好。', '再见。']
        assert data['glossary'] == [{'source': '先生', 'target': 'ngài'}]
        assert data['recent_conversation'] == kwargs['context']
        assert captured[0]['generationConfig']['thinkingConfig']['thinkingLevel'] == 'minimal'
        await provider.translate(['你好。', '再见。'], **{**kwargs, 'glossary': [], 'context': []})
        assert provider.client is client
        minimal_data = json.loads(captured[1]['contents'][0]['parts'][0]['text'])
        assert 'glossary' not in minimal_data and 'recent_conversation' not in minimal_data
        assert len(captured) == 2
    finally:
        await provider.close()


async def test_fast_wrong_translation_count_remains_invalid():
    provider = GeminiProvider(lambda: 'offline-placeholder', httpx.MockTransport(lambda request: reply('Chỉ một câu.')))
    try:
        with pytest.raises(TranslationError) as error:
            await provider.translate(['A', 'B'], source='zh', profile='game', mode='fast',
                                     model='gemini-3.5-flash-lite', glossary=[], context=[])
        assert error.value.code == 'invalid_response'
    finally:
        await provider.close()


async def test_quota_cooldown_blocks_http_but_replacement_key_resets_it():
    calls = []
    key = ['offline-key-one']

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(429, headers={'Retry-After': '30'}, json={})
        return reply('Xin chào')

    provider = GeminiProvider(lambda: key[0], httpx.MockTransport(handler))
    kwargs = dict(source='zh', profile='game', mode='fast', model='gemini-3.5-flash-lite', glossary=[], context=[])
    try:
        with pytest.raises(TranslationError) as first:
            await provider.translate(['你好'], **kwargs)
        assert first.value.retry_after_ms == 30000
        with pytest.raises(TranslationError) as blocked:
            await provider.translate(['再见'], **kwargs)
        assert 29000 <= blocked.value.retry_after_ms <= 30000
        assert len(calls) == 1
        key[0] = 'offline-key-two'
        assert provider.availability_error() is None
        assert (await provider.translate(['你好'], **kwargs))[0] == ['Xin chào']
        assert len(calls) == 2 and provider.quota_failures == 0
    finally:
        await provider.close()


async def test_old_key_inflight_429_cannot_block_replacement_key():
    started, release = asyncio.Event(), asyncio.Event()
    key = ['offline-key-one']

    async def handler(request):
        if request.headers['x-goog-api-key'] == 'offline-key-one':
            started.set()
            await release.wait()
            return httpx.Response(429, headers={'Retry-After': '60'}, json={})
        return reply('Xin chào')

    provider = GeminiProvider(lambda: key[0], httpx.MockTransport(handler))
    kwargs = dict(source='zh', profile='game', mode='fast', model='gemini-3.5-flash-lite', glossary=[], context=[])
    old = asyncio.create_task(provider.translate(['你好'], **kwargs))
    try:
        await started.wait()
        key[0] = 'offline-key-two'
        await provider.translate(['你好'], **kwargs)
        release.set()
        with pytest.raises(TranslationError):
            await old
        assert provider.availability_error() is None
        assert provider.cooldown_until == 0.0 and provider.quota_failures == 0
    finally:
        release.set()
        if not old.done():
            old.cancel()
            await asyncio.gather(old, return_exceptions=True)
        await provider.close()


async def test_manager_cooldown_skips_quota_reservation_and_keeps_cache_available(tmp_path):
    calls = []

    def handler(request):
        calls.append(1)
        return reply('Đã lưu') if len(calls) == 1 else httpx.Response(429, headers={'Retry-After': '45'}, json={})

    store = Store(tmp_path)
    provider = GeminiProvider(lambda: 'offline-placeholder', httpx.MockTransport(handler))
    manager = TranslationManager(store, ProviderRouter(lambda: store.get('settings', {}), provider, None))
    try:
        await manager.translate(frame(('cached',)), 'd')
        with pytest.raises(TranslationError):
            await manager.translate(frame(('limited',)), 'd')
        assert store.usage()['requests'] == 2
        with pytest.raises(TranslationError) as blocked:
            await manager.translate(frame(('new',)), 'd')
        assert blocked.value.code == 'quota_exceeded'
        assert blocked.value.retry_after_ms > 0
        assert store.usage()['requests'] == 2 and len(calls) == 2
        cached = await manager.translate(frame(('cached',)), 'd')
        assert cached['blocks'][0]['cached'] and cached['blocks'][0]['text'] == 'Đã lưu'
        assert store.usage()['requests'] == 2 and len(calls) == 2
    finally:
        await provider.close()
        store.close()
