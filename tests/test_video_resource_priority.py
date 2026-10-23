import asyncio

import pytest

from app.resources import ResourceManager


async def test_live_capture_holds_video_cpu_gate_but_live_permit_available():
    resources = ResourceManager()
    resources.set_live_active('phone', True)
    background = asyncio.create_task(resources.wait_background_ready())
    async with resources.permit('live'):
        await asyncio.sleep(0)
        assert not background.done()
    resources.set_live_active('phone', False)
    await asyncio.wait_for(background, .2)


async def test_two_live_slots_and_only_one_video_inflight():
    resources = ResourceManager()
    entered = []
    release = asyncio.Event()
    async def worker(priority, name):
        async with resources.permit(priority):
            entered.append(name)
            await release.wait()
    first = asyncio.create_task(worker('video', 'video1'))
    await asyncio.sleep(0)
    second = asyncio.create_task(worker('video', 'video2'))
    live = asyncio.create_task(worker('live', 'live'))
    await asyncio.sleep(.01)
    assert entered == ['video1', 'live']
    assert resources.status()['active_video_calls'] == 1
    release.set()
    await asyncio.gather(first, second, live)
    assert resources.status()['active_calls'] == 0


async def test_waiting_live_admitted_before_waiting_video():
    resources = ResourceManager()
    live_release = asyncio.Event()
    order = []
    async def first_live():
        async with resources.permit('live'):
            await live_release.wait()
    first = asyncio.create_task(first_live())
    second = asyncio.create_task(first_live())
    await asyncio.sleep(0)
    async def queued(priority):
        async with resources.permit(priority):
            order.append(priority)
    video = asyncio.create_task(queued('video'))
    live = asyncio.create_task(queued('live'))
    await asyncio.sleep(0)
    live_release.set()
    await asyncio.gather(first, second, video, live)
    assert order == ['live', 'video']


async def test_cancel_waiters_restores_slots_and_live_counters():
    resources = ResourceManager()
    resources.set_live_active('phone', True)
    async def blocked():
        async with resources.permit('video'):
            raise AssertionError('video should stay paused')
    waiter = asyncio.create_task(blocked())
    await asyncio.sleep(0)
    waiter.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiter
    resources.set_live_active('phone', False)
    async with resources.permit('video'):
        assert resources.status()['active_calls'] == 1
    assert resources.status()['active_calls'] == 0


async def test_multiple_capture_owners_and_invalid_priority():
    resources = ResourceManager()
    resources.set_live_active('one', True)
    resources.set_live_active('two', True)
    resources.set_live_active('one', False)
    assert resources.active_live and resources.status()['live_sessions'] == 1
    resources.set_live_active('two', False)
    assert not resources.active_live
    with pytest.raises(ValueError):
        async with resources.permit('unbounded'):
            pass
