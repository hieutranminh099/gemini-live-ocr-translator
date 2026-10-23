import asyncio

from fastapi.testclient import TestClient
import pytest

from app.server import Runtime, device_app
from test_video_jobs import FakeVideoApi


@pytest.fixture
def priority_runtime(tmp_path):
    runtime = Runtime(tmp_path, FakeVideoApi())
    yield runtime
    runtime.store.close()


def connect(client, token):
    return client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token})


def fence(ws):
    ws.send_json({'type': 'ping'})
    assert ws.receive_json()['type'] == 'pong'


def status(client, runtime):
    return client.portal.call(runtime.manager.resources.status)


async def wait_for_owners(resources, expected):
    async with asyncio.timeout(1):
        while resources.status()['live_sessions'] != expected:
            await asyncio.sleep(.001)


def test_capable_pause_releases_video_gate_without_disconnecting_android(priority_runtime):
    runtime = priority_runtime
    ident, token = runtime.store.pair('Priority phone')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            hello = ws.receive_json()
            assert 'capture_state_v1' in hello['capabilities']
            background = client.portal.start_task_soon(runtime.manager.resources.wait_background_ready)
            assert status(client, runtime)['live_active'] is True
            assert not background.done()
            ws.send_json({'type': 'capture_state', 'active': False})
            fence(ws)
            background.result(timeout=1)
            assert ident in runtime.sockets
            assert status(client, runtime)['live_active'] is False
            ws.send_json({'type': 'capture_state', 'active': True})
            fence(ws)
            blocked_again = client.portal.start_task_soon(runtime.manager.resources.wait_background_ready)
            assert status(client, runtime)['live_active'] is True
            assert not blocked_again.done()
            ws.send_json({'type': 'capture_state', 'active': False})
            fence(ws)
            blocked_again.result(timeout=1)
    assert runtime.manager.resources.active_live is False
    assert not runtime.provider.calls


def test_legacy_client_and_source_cancel_remain_live_until_pause_or_disconnect(priority_runtime):
    runtime = priority_runtime
    _, token = runtime.store.pair('Legacy capture')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            assert status(client, runtime)['live_active'] is True
            ws.send_json({'type': 'cancel', 'preserve_session': True})
            fence(ws)
            assert status(client, runtime)['live_active'] is True
        client.portal.call(wait_for_owners, runtime.manager.resources, 0)
        assert status(client, runtime)['live_active'] is False
    assert not runtime.provider.calls


@pytest.mark.parametrize('invalid', [None, 0, 1, 'false', [], {}])
def test_non_boolean_capture_state_is_rejected_without_changing_priority(priority_runtime, invalid):
    runtime = priority_runtime
    _, token = runtime.store.pair('Invalid capture state')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json({'type': 'capture_state', 'active': False})
            fence(ws)
            ws.send_json({'type': 'capture_state', 'active': invalid})
            error = ws.receive_json()
            assert error['type'] == 'error' and error['code'] == 'invalid_request'
            assert status(client, runtime)['live_active'] is False
            ws.send_json({'type': 'capture_state', 'active': True})
            fence(ws)
            assert status(client, runtime)['live_active'] is True
    assert not runtime.provider.calls


def test_old_connection_cleanup_cannot_clear_replacement_capture_owner(priority_runtime):
    runtime = priority_runtime
    ident, token = runtime.store.pair('Same phone reconnect')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as old:
            old.receive_json()
            with connect(client, token) as replacement:
                replacement.receive_json()
                old.close()
                client.portal.call(wait_for_owners, runtime.manager.resources, 1)
                fence(replacement)
                assert status(client, runtime)['live_active'] is True
                assert ident in runtime.sockets
                replacement.send_json({'type': 'capture_state', 'active': False})
                fence(replacement)
                assert status(client, runtime)['live_active'] is False
    assert runtime.manager.resources.status()['live_sessions'] == 0


def test_one_paused_device_never_releases_another_active_capture(priority_runtime):
    runtime = priority_runtime
    _, first_token = runtime.store.pair('Phone one')
    _, second_token = runtime.store.pair('Phone two')
    with TestClient(device_app(runtime)) as client:
        with connect(client, first_token) as first, connect(client, second_token) as second:
            first.receive_json()
            second.receive_json()
            first.send_json({'type': 'capture_state', 'active': False})
            fence(first)
            assert status(client, runtime)['live_sessions'] == 1
            background = client.portal.start_task_soon(runtime.manager.resources.wait_background_ready)
            assert not background.done()
            second.send_json({'type': 'capture_state', 'active': False})
            fence(second)
            background.result(timeout=1)
            assert status(client, runtime)['live_active'] is False
    assert runtime.manager.resources.status()['live_sessions'] == 0
