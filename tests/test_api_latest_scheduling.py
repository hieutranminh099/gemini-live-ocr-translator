"""Offline websocket scheduling tests; providers never access Google."""
import asyncio
import threading

from fastapi.testclient import TestClient

from app.server import Runtime, device_app
from app.provider import TranslationError
from app.manager import TranslationManager
from app.storage import Store
from app.models import Settings
from test_pipeline_telemetry import latest
from test_system import FakeProvider, frame


class ApiGates(FakeProvider):
    def __init__(self):
        super().__init__()
        self.started = {text: threading.Event() for text in ('A', 'B', 'C', 'D')}
        self.release = {text: threading.Event() for text in self.started}
        self.completed = {text: threading.Event() for text in self.started}
        self.cancelled = {text: threading.Event() for text in self.started}
        self.running = 0
        self.max_running = 0

    async def translate(self, texts, **kwargs):
        text = texts[0]
        self.calls.append((texts, kwargs))
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        self.started[text].set()
        try:
            while not self.release[text].is_set():
                await asyncio.sleep(.005)
            self.completed[text].set()
            return ['VI: ' + item for item in texts], 10, 5
        except asyncio.CancelledError:
            self.cancelled[text].set()
            raise
        finally:
            self.running -= 1


def connect(client, token):
    return client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token})


def test_api_b_starts_before_obsolete_a_finishes_and_a_cannot_overwrite_b(tmp_path):
    provider = ApiGates()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            assert 'api_latest_parallel_v1' in ws.receive_json()['capabilities']
            ws.send_json(latest('A', 1))
            assert provider.started['A'].wait(3)
            ws.send_json(latest('B', 2))
            assert provider.started['B'].wait(3)
            assert not provider.completed['A'].is_set()
            provider.release['B'].set()
            result = ws.receive_json()
            assert result['request_id'] == '2'
            assert result['blocks'][0]['text'] == 'VI: B'
            provider.release['A'].set()
            assert provider.completed['A'].wait(3)
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
    assert provider.max_running == 2
    runtime.store.close()


def test_api_two_obsolete_calls_cannot_delay_newest_frame(tmp_path):
    provider = ApiGates()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started['A'].wait(3)
            ws.send_json(latest('B', 2))
            assert provider.started['B'].wait(3)
            ws.send_json(latest('C', 3))
            assert provider.started['C'].wait(3)
            assert provider.cancelled['A'].wait(3)
            assert not provider.completed['A'].is_set()
            assert not provider.completed['B'].is_set()
            ws.send_json(latest('D', 4))
            assert provider.started['D'].wait(3)
            assert provider.cancelled['B'].wait(3)
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            provider.release['C'].set()
            assert provider.completed['C'].wait(3)
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            provider.release['D'].set()
            result = ws.receive_json()
            assert result['request_id'] == '4'
            assert result['screen_generation'] == 4
            assert result['blocks'][0]['text'] == 'VI: D'
    assert [call[0] for call in provider.calls] == [['A'], ['B'], ['C'], ['D']]
    assert provider.max_running == 2
    assert runtime.manager.metrics['stale_cancels'] == 2
    assert runtime.store.usage()['requests'] == 4  # HTTP cancellation does not refund a call.
    runtime.store.close()


def test_api_identical_inflight_frames_do_not_consume_second_call(tmp_path):
    provider = ApiGates()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started['A'].wait(3)
            for sequence in range(2, 102):
                request = latest(' A \n' if sequence % 2 else 'A', sequence)
                request['blocks'][0]['box']['x'] = sequence / 1000
                ws.send_json(request)
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            assert len(provider.calls) == 1
            provider.release['A'].set()
            result = ws.receive_json()
            assert result['request_id'] == '101'
            assert result['blocks'][0]['cached']
            assert result['blocks'][0]['box']['x'] == .101
    assert len(provider.calls) == 1
    runtime.store.close()


def test_api_many_distinct_subtitles_preempt_without_exceeding_two_http_calls(tmp_path):
    class ManyGates(FakeProvider):
        def __init__(self):
            super().__init__()
            self.release = threading.Event()
            self.latest_started = threading.Event()
            self.running = 0
            self.max_running = 0

        async def translate(self, texts, **kwargs):
            self.calls.append((texts, kwargs))
            self.running += 1
            self.max_running = max(self.max_running, self.running)
            if texts == ['public cue 49']:
                self.latest_started.set()
            try:
                while not self.release.is_set():
                    await asyncio.sleep(.005)
                return ['VI: ' + text for text in texts], 1, 1
            finally:
                self.running -= 1

    provider = ManyGates()
    runtime = Runtime(tmp_path, provider)
    runtime.store.set('settings', Settings(requests_per_minute=120).model_dump(exclude={'api_key'}))
    _, token = runtime.store.pair('Offline jitter test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            for index in range(50):
                ws.send_json(latest(f'public cue {index}', index + 1))
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            assert provider.latest_started.wait(3)
            assert provider.max_running == 2
            assert runtime.manager.resources.status()['active_calls'] <= 2
            assert runtime.manager.metrics['stale_cancels'] > 0
            provider.release.set()
            result = ws.receive_json()
            assert result['request_id'] == '50'
            assert result['screen_generation'] == 50
            assert result['blocks'][0]['text'] == 'VI: public cue 49'
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
    assert provider.running == 0
    assert len(provider.calls) <= 50
    assert runtime.store.usage()['requests'] == len(provider.calls)
    assert not runtime.manager._flights
    runtime.store.close()


def test_api_preempts_oldest_request_instead_of_oldest_reused_worker(tmp_path):
    class Turns(FakeProvider):
        def __init__(self):
            super().__init__()
            self.started = [threading.Event() for _ in range(4)]
            self.release = [threading.Event() for _ in self.started]
            self.cancelled = [threading.Event() for _ in self.started]

        async def translate(self, texts, **kwargs):
            index = len(self.calls)
            self.calls.append((texts, kwargs))
            self.started[index].set()
            try:
                while not self.release[index].is_set():
                    await asyncio.sleep(.005)
                return ['VI: ' + text for text in texts], 1, 1
            except asyncio.CancelledError:
                self.cancelled[index].set()
                raise

    provider = Turns()
    runtime = Runtime(tmp_path, provider)
    runtime.store.set('settings', Settings(cache_enabled=False).model_dump(exclude={'api_key'}))
    _, token = runtime.store.pair('Offline reused worker test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started[0].wait(3)
            ws.send_json(latest('B', 2))
            assert provider.started[1].wait(3)
            ws.send_json(latest('A', 3))
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            provider.release[0].set()
            assert provider.started[2].wait(3)  # A's worker now owns a newer turn.
            ws.send_json(latest('C', 4))
            assert provider.started[3].wait(3)
            assert provider.cancelled[1].wait(3)  # B is the oldest request.
            assert not provider.cancelled[2].is_set()
            provider.release[3].set()
            assert ws.receive_json()['request_id'] == '4'
    assert [call[0] for call in provider.calls] == [['A'], ['B'], ['A'], ['C']]
    assert runtime.manager.metrics['stale_cancels'] == 1
    runtime.store.close()


def test_api_empty_source_cancels_active_and_discards_waiting_even_preserve_session(tmp_path):
    provider = ApiGates()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started['A'].wait(3)
            ws.send_json(latest('B', 2))
            assert provider.started['B'].wait(3)
            ws.send_json(latest('C', 3))
            ws.send_json({'type': 'cancel', 'request_id': '3', 'preserve_session': True})
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            assert provider.cancelled['A'].wait(3)
            assert provider.cancelled['B'].wait(3)
            # Latest C may have preempted A immediately before the empty-source
            # notification; cancellation must stop it as well.
            if provider.started['C'].is_set():
                assert provider.cancelled['C'].wait(3)
    assert runtime.store.cache_size() == 0
    runtime.store.close()


def test_api_disconnect_cancels_every_worker(tmp_path):
    provider = ApiGates()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            assert provider.started['A'].wait(3)
            ws.send_json(latest('B', 2))
            assert provider.started['B'].wait(3)
        assert provider.cancelled['A'].wait(3)
        assert provider.cancelled['B'].wait(3)
    runtime.store.close()


def test_api_cache_hit_bypasses_both_busy_provider_workers(tmp_path):
    provider = ApiGates()
    provider.release['D'].set()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('D', 1))
            assert ws.receive_json()['request_id'] == '1'
            ws.send_json(latest('A', 2))
            assert provider.started['A'].wait(3)
            ws.send_json(latest('B', 3))
            assert provider.started['B'].wait(3)
            ws.send_json(latest('D', 4))
            result = ws.receive_json()
            assert result['request_id'] == '4'
            assert result['blocks'][0]['cached']
            assert result['blocks'][0]['text'] == 'VI: D'
            assert not provider.completed['A'].is_set()
            assert not provider.completed['B'].is_set()
    assert [call[0] for call in provider.calls] == [['D'], ['A'], ['B']]
    runtime.store.close()


def test_api_superseded_requests_waiting_global_semaphore_never_call_provider(tmp_path):
    provider = ApiGates()
    provider.release['C'].set()
    runtime = Runtime(tmp_path, provider)
    _, token = runtime.store.pair('API gate test')
    with TestClient(device_app(runtime)) as client:
        # Pretend another device is occupying the two global API slots.
        client.portal.call(runtime.manager.semaphore.acquire)
        client.portal.call(runtime.manager.semaphore.acquire)
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 1))
            ws.send_json(latest('B', 2))
            ws.send_json(latest('C', 3))
            ws.send_json({'type': 'ping'})
            assert ws.receive_json()['type'] == 'pong'
            assert not provider.calls
            client.portal.call(runtime.manager.semaphore.release)
            result = ws.receive_json()
            assert result['request_id'] == '3'
            assert result['blocks'][0]['text'] == 'VI: C'
        client.portal.call(runtime.manager.semaphore.release)
    assert [call[0] for call in provider.calls] == [['C']]
    runtime.store.close()


def test_api_rate_limit_forwards_retry_after_with_correlation(tmp_path):
    class Limited(FakeProvider):
        async def translate(self, texts, **kwargs):
            error = TranslationError('rate_limited', 'Offline quota test')
            error.retry_after_ms = 12345
            raise error

    runtime = Runtime(tmp_path, Limited())
    _, token = runtime.store.pair('API rate test')
    with TestClient(device_app(runtime)) as client:
        with connect(client, token) as ws:
            ws.receive_json()
            ws.send_json(latest('A', 4))
            result = ws.receive_json()
            assert result['type'] == 'error'
            assert result['code'] == 'rate_limited'
            assert result['retry_after_ms'] == 12345
            assert result['request_id'] == '4'
            assert result['screen_generation'] == 4
            assert result['region_id'] == 'subtitle-1'
    runtime.store.close()


async def test_api_old_completion_keeps_cache_but_cannot_revert_newer_context(tmp_path):
    class AsyncGates(FakeProvider):
        def __init__(self):
            super().__init__()
            self.started = {text: asyncio.Event() for text in ('A', 'B')}
            self.release = {text: asyncio.Event() for text in self.started}

        async def translate(self, texts, **kwargs):
            text = texts[0]
            self.calls.append((texts, kwargs))
            self.started[text].set()
            await self.release[text].wait()
            return ['VI: ' + text], 10, 5

    store = Store(tmp_path)
    provider = AsyncGates()
    manager = TranslationManager(store, provider)
    wanted = 'A'
    a = asyncio.create_task(manager.translate(frame(('A',)), 'd', is_current=lambda: wanted == 'A'))
    await provider.started['A'].wait()
    wanted = 'B'
    b = asyncio.create_task(manager.translate(frame(('B',)), 'd', is_current=lambda: wanted == 'B'))
    await provider.started['B'].wait()
    provider.release['B'].set()
    await b
    assert manager.contexts[('d', 'game', 'en')][1] == [{'source': 'B', 'vi': 'VI: B'}]
    provider.release['A'].set()
    await a
    assert manager.contexts[('d', 'game', 'en')][1] == [{'source': 'B', 'vi': 'VI: B'}]
    cached_a = await manager.translate_cached(frame(('A',)), 'd')
    assert cached_a['blocks'][0]['text'] == 'VI: A'
    assert cached_a['blocks'][0]['cached']
    assert len(provider.calls) == 2
    store.close()


async def test_api_cache_probe_miss_cannot_wait_or_reserve_quota(tmp_path):
    store = Store(tmp_path)
    provider = FakeProvider()
    manager = TranslationManager(store, provider)
    await manager.semaphore.acquire()
    await manager.semaphore.acquire()
    try:
        assert await asyncio.wait_for(manager.translate_cached(frame(), 'd'), timeout=1) is None
        assert provider.calls == []
        assert store.usage()['requests'] == 0
        assert manager.metrics['frames'] == 0
    finally:
        manager.semaphore.release()
        manager.semaphore.release()
        store.close()
