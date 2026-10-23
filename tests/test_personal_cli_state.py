"""State transitions without credentials, network calls or real Google CLI execution."""
import asyncio
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.models import Settings
from app.server import Runtime, admin_app, device_app


def set_cli(runtime, enabled):
    runtime.store.set('settings', Settings(provider='cli', personal_cli_enabled=enabled).model_dump(exclude={'api_key'}))


def test_settings_migration_keeps_personal_cli_off():
    assert not Settings(provider='cli').personal_cli_enabled
    assert not Settings.model_validate({'provider': 'cli', 'cli_model': 'auto'}).personal_cli_enabled


async def test_status_and_invalidate_publish_current_opt_in_not_stale_check(tmp_path, monkeypatch):
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)
    runtime = Runtime(tmp_path / 'data')
    runtime.cli.runtime = tmp_path / 'test-runtime'
    runtime.cli.runtime.mkdir()
    (runtime.cli.runtime / 'agy.exe').write_bytes(b'not executable; metadata test only')
    set_cli(runtime, False)
    runtime.cli.checked = True
    static = Path(__file__).resolve().parents[1] / 'windows-server/static'
    headers = {'Authorization': 'Bearer ' + runtime.admin_token}

    class Socket:
        def __init__(self):
            self.sent = []
            self.received = asyncio.Event()

        async def send_json(self, data):
            self.sent.append(data)
            self.received.set()

    socket = Socket()
    try:
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=admin_app(runtime, static)), base_url='http://testserver') as client:
            before = (await client.get('/api/status', headers=headers)).json()
            assert before['provider'] == 'cli' and before['backend_mode'] == 'cache_only'
            assert not before['translation_available'] and not before['provider_configured']
            assert not before['experimental_cli_enabled'] and before['cli']['checked']
            runtime.sockets['test'] = socket
            enabled = Settings(provider='cli', personal_cli_enabled=True).model_dump(exclude={'api_key'})
            assert (await client.put('/api/settings', headers=headers, json=enabled)).status_code == 200
            await asyncio.wait_for(socket.received.wait(), 1)
            notice = socket.sent[-1]
            assert notice['type'] == 'invalidate' and notice['backend_mode'] == 'cli_experimental'
            assert notice['translation_available'] and notice['experimental_cli_enabled']
            runtime.cli.checked = False
            after = (await client.get('/api/status', headers=headers)).json()
            assert after['provider_configured'] and after['translation_available']
            assert after['cli']['supported_backend'] is False and after['cli']['experimental_enabled']
            assert not after['cli']['checked']  # Installed/allowed does not claim verified authentication.
            assert runtime.store.usage()['requests'] == 0
            assert runtime.store.get('settings')['provider'] == 'cli'
            runtime.store.set('settings', Settings(provider='api', personal_cli_enabled=True).model_dump(exclude={'api_key'}))
            assert runtime.provider_state() == dict(provider='api', backend_mode='api',
                translation_available=False, experimental_cli_enabled=True)
    finally:
        runtime.sockets.clear()
        await runtime.provider.close()
        runtime.store.close()


@pytest.mark.parametrize('enabled,mode', [(False, 'cache_only'), (True, 'cli_experimental')])
def test_device_hello_announces_explicit_backend_mode(tmp_path, enabled, mode):
    runtime = Runtime(tmp_path)
    set_cli(runtime, enabled)
    _, token = runtime.store.pair('Metadata test')
    with TestClient(device_app(runtime)) as client:
        with client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token}) as ws:
            hello = ws.receive_json()
            assert hello['type'] == 'hello' and hello['provider'] == 'cli'
            assert hello['backend_mode'] == mode and hello['experimental_cli_enabled'] is enabled
            assert hello['translation_available'] is runtime.cli.configured
            assert runtime.store.usage()['requests'] == 0
    asyncio.run(runtime.provider.close())
    runtime.store.close()
