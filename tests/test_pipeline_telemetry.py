import asyncio
import threading
import time

from fastapi.testclient import TestClient
import pytest
from pydantic import ValidationError

from app.manager import TranslationManager
from app.models import Settings, TranslateRequest
from app.server import Runtime, device_app
from app.storage import Store
from test_system import FakeProvider, frame


def latest(text, sequence):
    request = frame((text,), region_id='subtitle-1', screen_generation=sequence, latest_only=True)
    request.request_id = str(sequence)
    return request.model_dump()


class GateProvider(FakeProvider):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self.cancelled = False

    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        self.started.set()
        try:
            while not self.release.is_set():
                await asyncio.sleep(.005)
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        return ['VI: ' + t for t in texts], 25, 12


def test_a_latest_mailbox_drops_old_reply_and_intermediate_request(tmp_path):
    provider = GateProvider()
    runtime = Runtime(tmp_path, provider)
    runtime.store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
    _, token = runtime.store.pair('Public pipeline test')
    with TestClient(device_app(runtime)) as client:
        with client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token}) as ws:
            assert 'latest_pending' in ws.receive_json()['capabilities']
            ws.send_json(latest('A', 1))
            assert provider.started.wait(3)
            ws.send_json(latest('B', 2))
            ws.send_json(latest('C', 3))
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'  # B/C have reached the mailbox.
            time.sleep(.02)
            provider.release.set()
            result = ws.receive_json()
            assert result['request_id'] == '3'
            assert result['region_id'] == 'subtitle-1' and result['screen_generation'] == 3
            assert result['blocks'][0]['text'] == 'VI: C'
            assert result['timings_ms']['mailbox_queue'] >= 10
            assert result['timings_ms']['queue'] >= result['timings_ms']['mailbox_queue']
    assert [call[0] for call in provider.calls] == [['A'], ['C']]
    assert not provider.cancelled  # A completes without killing/restarting the official CLI.
    runtime.store.close()


def test_b_empty_source_clears_mailbox_and_suppresses_active_reply(tmp_path):
    provider = GateProvider()
    runtime = Runtime(tmp_path, provider)
    runtime.store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
    _, token = runtime.store.pair('Public pipeline test')
    with TestClient(device_app(runtime)) as client:
        with client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token}) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started.wait(3)
            ws.send_json(latest('B', 2))
            ws.send_json({'type': 'cancel', 'request_id': '2', 'preserve_session': True})
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            provider.release.set()
            deadline = time.monotonic() + 3
            while runtime.store.cache_size() == 0 and time.monotonic() < deadline:
                time.sleep(.01)
            assert runtime.store.cache_size() == 1
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'  # No A/B result remains in the output queue.
    assert [call[0] for call in provider.calls] == [['A']]
    assert not provider.cancelled
    runtime.store.close()


def test_c_repeated_identical_frames_share_completed_cache(tmp_path):
    provider = GateProvider()
    runtime = Runtime(tmp_path, provider)
    runtime.store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
    _, token = runtime.store.pair('Public pipeline test')
    with TestClient(device_app(runtime)) as client:
        with client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token}) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started.wait(3)
            for i in range(2, 12):
                ws.send_json(latest('A', i))
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            provider.release.set()
            result = ws.receive_json()
            assert result['request_id'] == '11' and result['blocks'][0]['cached']
    assert len(provider.calls) == 1
    runtime.store.close()


async def test_request_scoped_timings_separate_queue_provider_and_cache(tmp_path):
    store = Store(tmp_path)
    provider = FakeProvider()
    provider.delay = .025
    manager = TranslationManager(store, provider)
    await manager.semaphore.acquire()
    await manager.semaphore.acquire()
    task = asyncio.create_task(manager.translate(frame(), 'test'))
    # Begin the measurement only after the task has reached admission; on a
    # loaded Windows host settings/cache setup may consume the timer interval.
    async def wait_for_admission():
        while manager.resources.status()['active_calls'] != 1:
            await asyncio.sleep(0)
    await asyncio.wait_for(wait_for_admission(), 1)
    await asyncio.sleep(.025)
    manager.semaphore.release()
    manager.semaphore.release()
    fresh = await task
    cached = await manager.translate(frame(), 'test')
    # Windows event-loop timers can wake at the previous system tick. This test
    # checks stage separation, not the precision of the operating-system timer.
    assert fresh['timings_ms']['queue'] > 0
    assert fresh['timings_ms']['provider'] > 0
    assert cached['timings_ms']['provider'] == 0
    assert cached['timings_ms']['queue'] == 0
    assert fresh['timings_ms'] is not cached['timings_ms']
    assert cached['blocks'][0]['cached']
    store.close()


def test_correlation_fields_are_optional_and_validated():
    assert frame().region_id is None
    with pytest.raises(ValidationError):
        frame(screen_generation=-1)
    with pytest.raises(ValidationError):
        frame(region_id='')
    assert TranslateRequest.model_validate(latest('A', 1)).latest_only
