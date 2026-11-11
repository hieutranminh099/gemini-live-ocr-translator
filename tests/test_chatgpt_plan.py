import asyncio
import base64
import json
import time
from urllib.parse import parse_qs, urlsplit

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa
import httpx
import pytest

from app.chatgpt_plan import AUTH, RESOURCE, PLAN_SCOPE, ChatGPTError, ChatGPTPlanClient, _b64


def encoded(value):
    return _b64(json.dumps(value, separators=(',', ':')).encode())


@pytest.fixture
async def auth_env(tmp_path):
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    numbers = private.public_key().public_numbers()
    jwks = {'keys': [dict(kty='RSA', kid='test-key', n=_b64(numbers.n.to_bytes(256, 'big')),
        e=_b64(numbers.e.to_bytes(3, 'big')))]}
    env = dict(calls=[], overrides={}, nonce='', subject='user-one', scope=PLAN_SCOPE+' openid profile email offline_access resource.invoke')
    def jwt(**overrides):
        claims = dict(iss=AUTH, aud='oaiapp_test', sub=env['subject'], exp=time.time()+3600, nonce=env['nonce'], email='test@example.invalid')
        claims.update(overrides)
        first = encoded(dict(alg='RS256', kid='test-key'))+'.'+encoded(claims)
        return first+'.'+_b64(private.sign(first.encode(), padding.PKCS1v15(), hashes.SHA256()))
    def handler(request):
        env['calls'].append(request)
        key = request.url.path
        if key in env['overrides']:
            return env['overrides'][key](request)
        if key.endswith('/jwks.json'):
            return httpx.Response(200, json=jwks)
        if key.endswith('/oauth/token'):
            return httpx.Response(200, json=dict(access_token='ACCESS_SENTINEL', refresh_token='REFRESH_SENTINEL',
                id_token=jwt(), token_type='Bearer', expires_in=3600, scope=env['scope']))
        if key == '/v1/models':
            return httpx.Response(200, json=dict(models=[dict(slug='test-model', display_name='Model test', visibility='list'),
                dict(slug='hidden-model', visibility='hidden')]))
        raise AssertionError('Unexpected network request: '+str(request.url))
    client = ChatGPTPlanClient(tmp_path, transport=httpx.MockTransport(handler),
        protect=lambda s: base64.b64encode(s.encode()).decode(), unprotect=lambda s: base64.b64decode(s).decode(),
        sleep=lambda _: asyncio.sleep(0))
    env.update(client=client, jwt=jwt, path=tmp_path)
    yield env
    await client.close()


async def authorize(env):
    client = env['client']
    url = client.begin_sign_in('http://127.0.0.1:1455/auth/callback')
    params = parse_qs(urlsplit(url).query)
    env['nonce'] = params['nonce'][0]
    await client.finish_sign_in(dict(state=params['state'][0], code='ONE_TIME_CODE', client_id='oaiapp_test'))
    return params


async def test_official_login_pkce_scope_identity_and_secret_boundaries(auth_env):
    env, client = auth_env, auth_env['client']
    params = await authorize(env)
    assert params['client_id'] == ['dynamic_agent_client']
    assert params['ext_agent_host_id'] == [client.host_id]
    assert params['code_challenge_method'] == ['S256']
    assert len(params['code_challenge'][0]) == 43
    assert PLAN_SCOPE in params['scope'][0].split()
    assert client.configured
    assert 'ACCESS_SENTINEL' not in json.dumps(client.public_state())
    assert 'REFRESH_SENTINEL' not in client.credential_path.read_text()
    assert 'ONE_TIME_CODE' not in client.credential_path.read_text()
    exchange = next(r for r in env['calls'] if r.url.path.endswith('/oauth/token'))
    form = parse_qs(exchange.content.decode())
    assert form['client_id'] == ['oaiapp_test']
    assert form['redirect_uri'] == ['http://127.0.0.1:1455/auth/callback']
    assert form['resource'] == [RESOURCE]
    host = client.host_id
    url = client.begin_sign_in('http://127.0.0.1:23456/auth/callback')
    assert parse_qs(urlsplit(url).query)['client_id'] == ['oaiapp_test']
    assert 'agent_name_hint' not in parse_qs(urlsplit(url).query)
    assert client.host_id == host


@pytest.mark.parametrize('callback', ['http://localhost:1455/auth/callback', 'https://127.0.0.1:1455/auth/callback',
    'http://127.0.0.1:1455/callback', 'http://example.invalid:1455/auth/callback'])
async def test_non_loopback_callbacks_rejected(auth_env, callback):
    with pytest.raises(ValueError):
        auth_env['client'].begin_sign_in(callback)


async def test_wrong_state_denial_and_replay_never_exchange_codes(auth_env):
    client = auth_env['client']
    client.begin_sign_in('http://127.0.0.1:1455/auth/callback')
    state = client.pending['state']
    with pytest.raises(ChatGPTError, match='không hợp lệ'):
        await client.finish_sign_in(dict(state='wrong', code='code', client_id='oaiapp_test'))
    assert client.pending and not auth_env['calls']
    with pytest.raises(ChatGPTError):
        await client.finish_sign_in(dict(state='状態不明', code='code', client_id='oaiapp_test'))
    assert client.pending and not auth_env['calls']
    with pytest.raises(ChatGPTError) as denied:
        await client.finish_sign_in(dict(state=state, error='access_denied'))
    assert denied.value.code == 'CHATGPT_CONSENT_DECLINED'
    with pytest.raises(ChatGPTError):
        await client.finish_sign_in(dict(state=state, code='code', client_id='oaiapp_test'))
    assert not auth_env['calls']


@pytest.mark.parametrize('claim,value', [('nonce', 'wrong'), ('aud', 'other-client'), ('iss', 'https://attacker.invalid'), ('exp', 1)])
async def test_oidc_verification_prevents_invalid_identity(auth_env, claim, value):
    env, client = auth_env, auth_env['client']
    client.begin_sign_in('http://127.0.0.1:1455/auth/callback')
    env['nonce'] = client.pending['nonce']
    with pytest.raises(ChatGPTError) as error:
        await client._verify_identity(env['jwt'](**{claim: value}), 'oaiapp_test', env['nonce'])
    assert error.value.code == 'CHATGPT_IDENTITY_INVALID'
    assert not client.configured


async def test_invalid_signature_is_rejected(auth_env):
    token = auth_env['jwt']()
    head, payload, signature = token.split('.')
    raw = bytearray(base64.urlsafe_b64decode(signature+'='*(-len(signature)%4)))
    raw[0] ^= 1
    with pytest.raises(ChatGPTError):
        await auth_env['client']._verify_identity(head+'.'+payload+'.'+_b64(raw), 'oaiapp_test')


async def test_identity_only_login_does_not_enable_plan_or_call_models(auth_env):
    auth_env['scope'] = 'openid profile email'
    await authorize(auth_env)
    client = auth_env['client']
    assert client.public_state()['connected'] and not client.configured
    with pytest.raises(ChatGPTError) as error:
        await client.list_models()
    assert error.value.code == 'CHATGPT_PLAN_DISABLED'
    assert not any(r.url.path == '/v1/models' for r in auth_env['calls'])


async def test_reauthorization_cannot_overwrite_other_identity(auth_env):
    await authorize(auth_env)
    client = auth_env['client']
    before = dict(client.profile)
    auth_env['subject'] = 'different-user'
    with pytest.raises(ChatGPTError) as error:
        await authorize(auth_env)
    assert error.value.code == 'CHATGPT_ACCOUNT_CHANGED'
    assert client.profile == before


async def test_expired_code_preserves_registration_and_existing_session(auth_env):
    await authorize(auth_env)
    client = auth_env['client']
    before = dict(client.profile)
    auth_env['overrides']['/api/accounts/oauth/token'] = lambda r: httpx.Response(400, json={'error': 'invalid_grant'})
    with pytest.raises(ChatGPTError) as error:
        await authorize(auth_env)
    assert error.value.code == 'CHATGPT_SIGN_IN_EXPIRED'
    assert client.profile == before


async def test_refresh_reuses_issued_client_and_clears_only_invalid_refresh(auth_env):
    await authorize(auth_env)
    client = auth_env['client']
    client.profile['expires_at'] = 0
    assert await client.access_token() == 'ACCESS_SENTINEL'
    last = [r for r in auth_env['calls'] if r.url.path.endswith('/oauth/token')][-1]
    data = parse_qs(last.content.decode())
    assert data['grant_type'] == ['refresh_token'] and data['client_id'] == ['oaiapp_test']
    client.profile['expires_at'] = 0
    auth_env['overrides']['/api/accounts/oauth/token'] = lambda r: httpx.Response(503, json={'error': {'code': 'temporary'}})
    with pytest.raises(ChatGPTError):
        await client.access_token()
    assert client.profile['refresh_token'] == 'REFRESH_SENTINEL'
    auth_env['overrides']['/api/accounts/oauth/token'] = lambda r: httpx.Response(400, json={'error': 'invalid_grant'})
    with pytest.raises(ChatGPTError) as error:
        await client.access_token()
    assert error.value.code == 'CHATGPT_REFRESH_INVALID'
    assert not client.configured and client.profile['client_id'] == 'oaiapp_test'


async def test_model_catalog_is_account_specific_and_selection_validated(auth_env):
    await authorize(auth_env)
    client = auth_env['client']
    assert await client.list_models() == [dict(id='test-model', name='Model test')]
    assert client.selected_model == 'test-model'
    with pytest.raises(ChatGPTError):
        client.select_model('hidden-model')


def stream_response(*events):
    return httpx.Response(200, headers={'Content-Type': 'text/event-stream'},
        content=''.join('data: '+json.dumps(e)+'\n\n' for e in events).encode())


def completed(rows):
    return dict(type='response.completed', response=dict(status='completed', output=[dict(type='message', content=[
        dict(type='output_text', text=json.dumps(dict(translations=rows)))])], usage=dict(input_tokens=12, output_tokens=9)))


async def ready(env):
    await authorize(env)
    await env['client'].list_models()


async def test_stream_requires_terminal_completed_and_exact_ids(auth_env):
    await ready(auth_env)
    env, client = auth_env, auth_env['client']
    def reply(request):
        payload = json.loads(request.content)
        assert str(request.url) == RESOURCE+'/responses'
        assert payload['store'] is False and payload['stream'] is True
        assert not any(k in payload for k in ('max_output_tokens', 'temperature', 'background', 'tools'))
        assert payload['text']['format']['schema']['properties']['translations']['items']['properties']['id']['enum'] == ['a', 'b']
        return stream_response(dict(type='response.output_text.delta', delta='untrusted partial'),
            completed([dict(id='b', translation='Tạm biệt'), dict(id='a', translation='Xin chào')]))
    env['overrides']['/v1/responses'] = reply
    accepted, usage = await client.translate_rows([dict(id='a', text='你好'), dict(id='b', text='再见')], 'zh')
    assert accepted == dict(a='Xin chào', b='Tạm biệt') and usage['input_tokens'] == 12
    env['overrides']['/v1/responses'] = lambda r: stream_response(dict(type='response.output_text.delta', delta='looks complete'))
    with pytest.raises(ChatGPTError) as error:
        await client.translate_rows([dict(id='a', text='你好')], 'zh')
    assert error.value.code == 'CHATGPT_STREAM_INTERRUPTED'


@pytest.mark.parametrize('rows', [[dict(id='wrong', translation='Xin chào')], [],
    [dict(id='a', translation='')], [dict(id='a', translation='Xin'), dict(id='a', translation='Chào')]])
async def test_ambiguous_or_missing_results_never_accepted(auth_env, rows):
    await ready(auth_env)
    auth_env['overrides']['/v1/responses'] = lambda r: stream_response(completed(rows))
    with pytest.raises(ChatGPTError) as error:
        await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert error.value.code == 'CHATGPT_INVALID_MAPPING'


@pytest.mark.parametrize('as_stream', [False, True])
async def test_usage_limit_stops_without_retries_or_alternate_provider(auth_env, as_stream):
    await ready(auth_env)
    error = dict(code='subscription_sharing_usage_limit_exceeded', message='RAW_PRIVATE_PROVIDER_DETAIL')
    auth_env['overrides']['/v1/responses'] = lambda r: stream_response(dict(type='response.failed', response=dict(error=error))) if as_stream else httpx.Response(429, json=dict(error=error))
    with pytest.raises(ChatGPTError) as caught:
        await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert caught.value.code == 'CHATGPT_USAGE_LIMIT'
    assert 'RAW_PRIVATE' not in str(caught.value)
    assert len([r for r in auth_env['calls'] if r.url.path == '/v1/responses']) == 1


async def test_transient_recovery_is_bounded_and_does_not_erase_session(auth_env):
    await ready(auth_env)
    auth_env['overrides']['/v1/responses'] = lambda r: httpx.Response(503, json=dict(error=dict(code='temporary')))
    with pytest.raises(ChatGPTError) as error:
        await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert len([r for r in auth_env['calls'] if r.url.path == '/v1/responses']) == 3
    assert error.value.diagnostics['provider_calls'] == 3
    assert auth_env['client'].configured


async def test_retry_after_and_physical_call_count_survive_successful_recovery(auth_env):
    await ready(auth_env)
    delays = []
    async def sleep(seconds):
        delays.append(seconds)
    auth_env['client']._sleep = sleep
    replies = [httpx.Response(503, headers={'Retry-After': '12'}, json=dict(error=dict(code='temporary'))),
        stream_response(completed([dict(id='a', translation='Xin chào')]))]
    auth_env['overrides']['/v1/responses'] = lambda r: replies.pop(0)
    accepted, usage = await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert accepted == {'a': 'Xin chào'}
    assert delays == [12] and usage['provider_calls'] == 2
    assert auth_env['client'].public_state()['retry_seconds'] == 0


async def test_long_explicit_wait_prevents_early_resubmission(auth_env):
    await ready(auth_env)
    auth_env['overrides']['/v1/responses'] = lambda r: httpx.Response(503,
        headers={'Retry-After': '1800'}, json=dict(error=dict(code='temporary')))
    with pytest.raises(ChatGPTError) as error:
        await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert error.value.diagnostics['provider_calls'] == 1
    with pytest.raises(ChatGPTError) as second:
        await auth_env['client'].translate_rows([dict(id='a', text='你好')], 'zh')
    assert second.value.code == 'CHATGPT_RETRY_LATER'
    assert auth_env['client'].public_state()['retry_seconds'] > 1700
    assert len([r for r in auth_env['calls'] if r.url.path == '/v1/responses']) == 1


async def test_real_dpapi_persistence_and_stable_host_id(tmp_path):
    import os
    if os.name != 'nt':
        pytest.skip('Windows DPAPI')
    client = ChatGPTPlanClient(tmp_path)
    client.profile['client_id'] = 'oaiapp_synthetic'
    client._accept_tokens(dict(access_token='SYNTHETIC_ACCESS_PRIVATE', refresh_token='SYNTHETIC_REFRESH_PRIVATE',
        token_type='Bearer', expires_in=3600, scope=PLAN_SCOPE), dict(sub='synthetic-user', iss=AUTH))
    host_id = client.host_id
    contents = client.credential_path.read_text()
    assert 'SYNTHETIC_ACCESS_PRIVATE' not in contents and 'SYNTHETIC_REFRESH_PRIVATE' not in contents
    await client.close()
    reopened = ChatGPTPlanClient(tmp_path)
    try:
        assert reopened.configured and reopened.host_id == host_id
        assert reopened.profile['client_id'] == 'oaiapp_synthetic'
        assert 'SYNTHETIC_ACCESS_PRIVATE' not in json.dumps(reopened.public_state())
    finally:
        await reopened.close()
