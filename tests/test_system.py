import asyncio
import hashlib
import json
import time
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.manager import TranslationManager
from app.models import Settings, TranslateRequest
from app.provider import GeminiProvider, TranslationError
from app.security import Pairing, ensure_certificate, protect_secret, unprotect_secret
from app.server import Runtime, admin_app, device_app
from app.storage import Store


class FakeProvider:
    def __init__(self):
        self.calls = []
        self.delay = 0
    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        if self.delay:
            await asyncio.sleep(self.delay)
        return ['VI: ' + text for text in texts], 25, 12
    async def close(self):
        pass


def frame(texts=('Hello there',), **kwargs):
    return TranslateRequest.model_validate(dict(type='translate', request_id='r1', source='en', profile='game', mode='smart',
        blocks=[dict(id=str(i), text=t, box=dict(x=0, y=0, width=.5, height=.2)) for i, t in enumerate(texts)], **kwargs))


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path)
    yield s
    s.close()


async def test_normalization_cache_dedup_and_usage(store):
    provider = FakeProvider()
    m = TranslationManager(store, provider)
    first = await m.translate(frame(('Hello  there', 'Hello there')), 'd')
    second = await m.translate(frame(('Hello\nthere',)), 'd')
    assert len(provider.calls) == 1 and provider.calls[0][0] == ['Hello there']
    assert len(first['blocks']) == 2 and second['blocks'][0]['cached']
    assert store.usage()['requests'] == 1 and store.usage()['input_tokens'] == 25
    assert store.cache_size() == 1
    assert 'Hello there' not in repr([tuple(r) for r in store.db.execute('SELECT * FROM cache')]).replace('VI: Hello there', '')


async def test_glossary_exact_bypasses_provider_and_edit_invalidates(store):
    provider = FakeProvider()
    m = TranslationManager(store, provider)
    await m.translate(frame(), 'd')
    store.put_glossary('Hello there', 'Xin chào', 'game')
    r = await m.translate(frame(), 'd')
    assert r['blocks'][0]['text'] == 'Xin chào' and len(provider.calls) == 1
    store.put_glossary('Hello there', 'Chào bạn', 'game')
    assert (await m.translate(frame(), 'd'))['blocks'][0]['text'] == 'Chào bạn'
    store.delete_glossary(store.glossary()[0]['id'])
    assert (await m.translate(frame(), 'd'))['blocks'][0]['text'].startswith('VI:')


async def test_profile_and_mode_have_separate_caches(store):
    p = FakeProvider(); m = TranslationManager(store, p)
    for mode, profile in [('smart','game'),('fast','game'),('smart','web')]:
        r = frame(); r.mode = mode; r.profile = profile
        await m.translate(r, 'd')
    assert len(p.calls) == 3


async def test_context_isolation_and_expiration(store):
    p = FakeProvider(); m = TranslationManager(store, p)
    await m.translate(frame(('One',)), 'a')
    await m.translate(frame(('Two',)), 'b')
    assert p.calls[-1][1]['context'] == []
    await m.translate(frame(('Three',)), 'a')
    assert p.calls[-1][1]['context'][0]['source'] == 'One'
    m.contexts[('a','game','en')] = (time.monotonic() - 601, [{'source':'expired','vi':'old'}])
    await m.translate(frame(('Four',)), 'a')
    assert p.calls[-1][1]['context'] == []


async def test_cache_disabled_and_context_disabled(store):
    store.set('settings', Settings(cache_enabled=False, context_enabled=False).model_dump(exclude={'api_key'}))
    p = FakeProvider(); m = TranslationManager(store, p)
    await m.translate(frame(), 'd'); await m.translate(frame(), 'd')
    assert len(p.calls) == 2 and store.cache_size() == 0 and not m.contexts


async def test_rpm_and_daily_limit(store):
    store.set('settings', Settings(requests_per_minute=1).model_dump(exclude={'api_key'}))
    p = FakeProvider(); m = TranslationManager(store, p)
    await m.translate(frame(('First',)), 'd')
    with pytest.raises(TranslationError, match='phút'):
        await m.translate(frame(('Second',)), 'd')
    store.set('settings', Settings(daily_request_limit=1).model_dump(exclude={'api_key'}))
    m.calls.clear()
    with pytest.raises(TranslationError, match='trong ngày'):
        await m.translate(frame(('Third',)), 'd')
    assert len(p.calls) == 1


async def test_cancellation_does_not_cache_partial_result(store):
    p = FakeProvider(); p.delay = 1
    m = TranslationManager(store, p)
    task = asyncio.create_task(m.translate(frame(), 'd'))
    await asyncio.sleep(.02); task.cancel()
    with pytest.raises(asyncio.CancelledError): await task
    assert store.cache_size() == 0 and store.usage()['requests'] == 1


def test_protocol_rejects_large_and_ambiguous_frames():
    with pytest.raises(ValidationError): frame(('',))
    r = frame().model_dump(); r['blocks'][0]['box']['x'] = .8
    with pytest.raises(ValidationError): TranslateRequest.model_validate(r)
    r = frame().model_dump(); r['blocks'] *= 2
    with pytest.raises(ValidationError): TranslateRequest.model_validate(r)
    r = frame().model_dump(); r['source'] = 'vi'
    with pytest.raises(ValidationError): TranslateRequest.model_validate(r)


def test_pairing_single_use_expiry_and_bruteforce():
    p = Pairing(); code = p.create()
    assert p.consume(code) and not p.consume(code)
    code = p.create(); p.expires = time.monotonic() - 1
    assert not p.consume(code)
    code = p.create()
    for _ in range(8): p.consume('000000' if code != '000000' else '111111')
    assert not p.consume(code)


def test_tokens_hashed_and_revocable(store):
    ident, token = store.pair('My phone')
    assert store.authenticate(token)['id'] == ident
    assert store.authenticate('bad') is None
    assert token not in str([tuple(r) for r in store.db.execute('SELECT * FROM devices')])
    store.revoke(ident)
    assert store.authenticate(token) is None


def test_dpapi_round_trip():
    secret = 'test-secret-not-a-real-api-key'
    encrypted = protect_secret(secret)
    assert secret not in encrypted and unprotect_secret(encrypted) == secret


def test_certificate_identity_persists(tmp_path):
    c, k, p = ensure_certificate(tmp_path)
    assert c.exists() and k.exists() and len(p) == 64
    assert ensure_certificate(tmp_path)[2] == p


def test_admin_auth_settings_clear_and_glossary(tmp_path):
    r = Runtime(tmp_path, FakeProvider())
    static = Path(__file__).resolve().parents[1] / 'windows-server/static'
    with TestClient(admin_app(r, static)) as client:
        assert client.get('/api/status').status_code == 401
        assert client.get('/').status_code == 200
        h = {'Authorization':'Bearer '+r.admin_token}
        settings = Settings(api_key='fake-test-key').model_dump()
        assert client.put('/api/settings', headers=h, json=settings).status_code == 200
        status = client.get('/api/status', headers=h)
        assert status.status_code == 200 and status.json()['api_configured']
        assert 'fake-test-key' not in status.text
        assert 'fake-test-key' not in (tmp_path/'translator.db').read_bytes().decode(errors='ignore')
        assert client.post('/api/glossary',headers=h,json={'source':'Hero','target':'Dũng sĩ','profile':'game'}).status_code == 200
        assert len(client.get('/api/glossary',headers=h).json()) == 1
        assert client.delete('/api/cache',headers=h).status_code == 200
        assert client.get('/api/status',headers=h).json()['cache_entries'] == 0
    r.store.close()


def test_device_pair_and_websocket_translation(tmp_path):
    r = Runtime(tmp_path, FakeProvider())
    with TestClient(device_app(r)) as client:
        assert client.post('/v1/pair',json={'name':'Android','code':'123456'}).status_code == 403
        code = r.pairing.create()
        pair = client.post('/v1/pair',json={'name':'Android','code':code}).json()
        assert client.post('/v1/pair',json={'name':'Android','code':code}).status_code == 403
        with client.websocket_connect('/v1/stream',headers={'Authorization':'Bearer '+pair['token']}) as ws:
            assert ws.receive_json()['type'] == 'hello'
            ws.send_json(frame().model_dump())
            result = ws.receive_json()
            assert result['request_id'] == 'r1' and result['blocks'][0]['text'] == 'VI: Hello there'
            ws.send_json({'type':'ping'})
            assert ws.receive_json()['type'] == 'pong'
            ws.send_json({'type':'translate'})
            assert ws.receive_json()['code'] == 'invalid_request'
    r.store.close()


@pytest.mark.parametrize('status,expected',[(429,'quota_exceeded'),(403,'provider_configuration'),(500,'provider_unavailable')])
async def test_provider_errors_are_redacted(status, expected):
    transport = httpx.MockTransport(lambda request: httpx.Response(status,json={'error':'secret-key'}))
    p = GeminiProvider(lambda:'secret-key',transport)
    with pytest.raises(TranslationError) as error:
        await p.translate(['abc'],source='en',profile='game',mode='smart',model='model',glossary=[],context=[])
    assert error.value.code == expected and 'secret-key' not in str(error.value)
    await p.close()


async def test_provider_structured_response_and_output_mapping():
    def handler(request):
        assert request.headers['x-goog-api-key'] == 'test'
        assert json.loads(request.content)['generationConfig']['responseMimeType'] == 'application/json'
        return httpx.Response(200,json={'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':'{"translations":["Xin chào"]}' }]}}]})
    p = GeminiProvider(lambda:'test',httpx.MockTransport(handler))
    assert (await p.translate(['Hello'],source='en',profile='game',mode='smart',model='model',glossary=[],context=[]))[0] == ['Xin chào']
    with pytest.raises(TranslationError,match='đủ'):
        await p.translate(['Hello','World'],source='en',profile='game',mode='smart',model='model',glossary=[],context=[])
    await p.close()


def test_clear_cache_removes_persistent_translations(store):
    store.put_cache('hash','bản dịch')
    store.clear_cache()
    assert store.cache_size() == 0
