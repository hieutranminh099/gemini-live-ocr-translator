"""Offline latest-call/singleflight admission regressions; no Google access."""
import asyncio
import hashlib
import json

import pytest

from app.manager import TranslationManager
from app.models import Settings
from app.provider import TranslationError
from app.storage import Store
from test_system import FakeProvider, frame


class Gates(FakeProvider):
    def __init__(self):
        super().__init__()
        self.started = {text: asyncio.Event() for text in ('A', 'B')}
        self.release = {text: asyncio.Event() for text in self.started}
        self.running = 0
        self.max_running = 0

    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        try:
            self.started[texts[0]].set()
            await self.release[texts[0]].wait()
            return ['VI: ' + text for text in texts], 7, 3
        finally:
            self.running -= 1


async def cleanup(manager, store, tasks):
    for task in tasks:
        if not task.done():
            task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    await manager.close()
    store.close()


async def test_one_hundred_duplicate_followers_do_not_occupy_second_http_slot(tmp_path):
    store, provider = Store(tmp_path), Gates()
    manager = TranslationManager(store, provider)
    tasks = []
    try:
        leader = asyncio.create_task(manager.translate(frame(('A',)), 'device'))
        tasks.append(leader)
        await asyncio.wait_for(provider.started['A'].wait(), 1)
        followers = [asyncio.create_task(manager.translate(frame(('A',)), 'device')) for _ in range(100)]
        tasks.extend(followers)
        await asyncio.sleep(0)
        assert manager.resources.status()['active_calls'] == 1
        current = asyncio.create_task(manager.translate(frame(('B',)), 'device'))
        tasks.append(current)
        await asyncio.wait_for(provider.started['B'].wait(), 1)
        assert len(provider.calls) == 2
        assert not leader.done()
        assert manager.resources.status()['active_calls'] == 2
        provider.release['B'].set()
        assert (await current)['blocks'][0]['text'] == 'VI: B'
        # A cancelled follower cannot cancel the shared leader or other followers.
        followers[0].cancel()
        with pytest.raises(asyncio.CancelledError):
            await followers[0]
        provider.release['A'].set()
        remaining = await asyncio.gather(leader, *followers[1:])
        assert all(item['blocks'][0]['text'] == 'VI: A' for item in remaining)
        assert store.usage()['requests'] == 2
        assert store.usage()['input_tokens'] == 14
        assert provider.max_running == 2
        assert not manager._flights
        assert manager.resources.status()['active_calls'] == 0
    finally:
        await cleanup(manager, store, tasks)


async def test_current_follower_replaces_obsolete_queued_leader_without_lost_result(tmp_path):
    store, provider = Store(tmp_path), FakeProvider()
    manager = TranslationManager(store, provider)
    tasks = []
    await manager.semaphore.acquire()
    await manager.semaphore.acquire()
    try:
        old = asyncio.create_task(manager.translate(frame(('A',)), 'device', is_current=lambda: False))
        tasks.append(old)
        await asyncio.sleep(0)
        current = asyncio.create_task(manager.translate(frame(('A',)), 'device', is_current=lambda: True))
        tasks.append(current)
        await asyncio.sleep(0)
        assert manager.resources.status()['active_calls'] == 1
        manager.semaphore.release()
        assert await asyncio.wait_for(old, 1) is None
        assert (await asyncio.wait_for(current, 1))['blocks'][0]['text'] == 'VI: A'
        assert len(provider.calls) == 1
        assert store.usage()['requests'] == 1
        assert not manager._flights
    finally:
        manager.semaphore.release()
        await cleanup(manager, store, tasks)


async def test_cache_filled_partially_while_waiting_preserves_mapping_and_cached_flags(tmp_path):
    store, provider = Store(tmp_path), FakeProvider()
    manager = TranslationManager(store, provider)
    tasks = []
    await manager.semaphore.acquire()
    await manager.semaphore.acquire()
    try:
        queued = asyncio.create_task(manager.translate(frame(('A', 'B')), 'device'))
        tasks.append(queued)
        await asyncio.sleep(0)
        settings = manager.settings()
        glossary_hash = hashlib.sha256(json.dumps([], ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        namespace = f'v2|api|en|game|smart|{settings.smart_model}|{glossary_hash}|'
        key = hashlib.sha256((namespace + 'A').encode()).hexdigest()
        store.put_cache(key, 'A from another completed frame')
        manager.semaphore.release()
        result = await asyncio.wait_for(queued, 1)
        assert provider.calls[0][0] == ['B']
        assert [(item['text'], item['cached']) for item in result['blocks']] == [
            ('A from another completed frame', True), ('VI: B', False)]
        assert store.usage()['requests'] == 1
    finally:
        manager.semaphore.release()
        await cleanup(manager, store, tasks)


async def test_live_does_not_join_video_flight_paused_by_live_capture(tmp_path):
    store, provider = Store(tmp_path), FakeProvider()
    store.set('settings', Settings(context_enabled=False).model_dump(exclude={'api_key'}))
    manager = TranslationManager(store, provider)
    tasks = []
    manager.resources.set_live_active('phone', True)
    try:
        video = asyncio.create_task(manager.translate(frame(('A',)), 'device', priority='video'))
        tasks.append(video)
        await asyncio.sleep(0)
        assert not provider.calls
        live = await asyncio.wait_for(manager.translate(frame(('A',)), 'device'), 1)
        assert live['blocks'][0]['text'] == 'VI: A'
        assert not video.done()
        manager.resources.set_live_active('phone', False)
        result = await asyncio.wait_for(video, 1)
        assert result['blocks'][0]['cached']
        assert len(provider.calls) == 1
        assert store.usage()['requests'] == 1
    finally:
        manager.resources.set_live_active('phone', False)
        await cleanup(manager, store, tasks)


async def test_failed_provider_duration_is_reported_separately_from_queue(tmp_path):
    class Failed(FakeProvider):
        async def translate(self, texts, **kwargs):
            await asyncio.sleep(.02)
            raise TranslationError('provider_unavailable', 'Offline test')
    store = Store(tmp_path)
    manager = TranslationManager(store, Failed())
    try:
        with pytest.raises(TranslationError) as caught:
            await manager.translate(frame(), 'device')
        timings = caught.value.timings_ms
        assert timings['provider'] > 0
        assert timings['server'] >= timings['provider']
        assert caught.value.diagnostics['provider_calls'] == 1
        assert manager.resources.status()['active_calls'] == 0
        assert store.usage()['requests'] == 1
    finally:
        await cleanup(manager, store, [])
