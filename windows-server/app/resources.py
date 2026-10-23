"""Cooperative live-first admission for Gemini and expensive video stages.

No running HTTP call is killed when capture starts: at most one video call may
already be in flight, leaving the other slot for live work. CPU/GPU video work
must call wait_background_ready between bounded chunks and before spawning a
heavy subprocess; the video job runner owns pausing an existing subprocess.
"""

import asyncio
from contextlib import asynccontextmanager


class ResourceManager:
    def __init__(self, semaphore=None, *, slots=2):
        self.semaphore = semaphore or asyncio.Semaphore(slots)
        self.slots = slots
        self._live_owners = set()
        self._live_waiters = 0
        self._active = 0
        self._video_active = 0
        self._changed = asyncio.Event()

    @property
    def active_live(self):
        return bool(self._live_owners)

    def _notify(self):
        self._changed.set()
        self._changed = asyncio.Event()

    def set_live_active(self, owner, active):
        if active:
            self._live_owners.add(owner)
        else:
            self._live_owners.discard(owner)
        self._notify()

    async def wait_background_ready(self):
        while self.active_live or self._live_waiters or self._active>self._video_active:
            changed = self._changed
            await changed.wait()

    @asynccontextmanager
    async def permit(self, priority='live'):
        if priority not in ('live', 'video'):
            raise ValueError('Invalid translation priority')
        live = priority == 'live'
        admitted = False
        acquired = False
        if live:
            self._live_waiters += 1
            self._notify()
        try:
            while (self._active >= self.slots or
                   (not live and (self._video_active or self.active_live or self._live_waiters or self._active>self._video_active))):
                changed = self._changed
                await changed.wait()
            self._active += 1
            self._video_active += int(not live)
            admitted = True
            if live:
                self._live_waiters -= 1
                live = False  # The waiter has been accounted for.
            self._notify()
            await self.semaphore.acquire()
            acquired = True
            yield
        finally:
            if acquired:
                self.semaphore.release()
            if admitted:
                self._active -= 1
                self._video_active -= int(priority == 'video')
            elif live:
                self._live_waiters -= 1
            self._notify()

    def status(self):
        return dict(live_active=self.active_live, live_sessions=len(self._live_owners),
                    live_waiters=self._live_waiters, active_calls=self._active,
                    active_video_calls=self._video_active, max_calls=self.slots,
                    max_video_calls=1)
