import asyncio
from collections import defaultdict, deque
import hashlib
import json
import time
from .models import Settings
from .provider import TranslationError
from .storage import normalize
from .telemetry import current_trace, duration
from .resources import ResourceManager
from .translation_memory import MemoryEntry, TranslationMemoryManager


class TranslationManager:
    def __init__(self, store, provider):
        self.store, self.provider = store, provider
        self.semaphore = asyncio.Semaphore(2)
        self.resources = ResourceManager(self.semaphore)
        self.memory = TranslationMemoryManager(store)
        self.calls = deque()
        self.contexts = {}
        self.metrics = dict(frames=0, cache_hits=0, translated_blocks=0, latency_ms=0, errors=0,
                            stale_cancels=0)
        self.events = deque(maxlen=80)
        self.revision = 0
        self._flights = {}

    def invalidate(self):
        self.revision += 1
        self.contexts.clear()

    def require_revision(self, revision):
        if revision != self.revision:
            raise TranslationError('configuration_changed', 'Cấu hình hoặc dữ liệu đã đổi. Đang lấy bản dịch mới.')

    def settings(self):
        return Settings(**self.store.get('settings', {}))

    def event(self, message, level='info'):
        # Status events intentionally omit OCR text, API keys, codes and device tokens.
        self.events.appendleft(dict(time=time.time(), level=level, message=message))

    async def translate_cached(self, request, device_id, *, queued_at=None):
        """Probe the full normal cache namespace without queuing or calling AI."""
        return await self.translate(request, device_id, queued_at=queued_at, cache_only=True)

    async def translate(self, request, device_id, *, queued_at=None, cache_only=False, is_current=None,
                        priority='live', allow_cache=True, persist_memory=True, use_memory=True,
                        context_scope='global', source_type='LIVE', project_id='', allow_context=True,
                        context_information=None, confidence_metadata=None):
        if priority not in ('live', 'video') or source_type not in ('LIVE', 'VIDEO'):
            raise ValueError('Invalid translation source/priority')
        trace = {'timings_ms': {'queue': 0.0, 'cache': 0.0, 'provider': 0.0}, 'diagnostics': {}}
        token = current_trace.set(trace)
        start = time.monotonic()
        trace['timings_ms']['mailbox_queue'] = max(0.0, round((start - queued_at) * 1000, 3)) if queued_at is not None else 0.0
        try:
            result = await self._translate(request, device_id, cache_only=cache_only, is_current=is_current,
                priority=priority, allow_cache=allow_cache, persist_memory=persist_memory, use_memory=use_memory,
                context_scope=context_scope, source_type=source_type, project_id=project_id,
                allow_context=allow_context, context_information=context_information,
                confidence_metadata=confidence_metadata)
            if result is None:
                return None
            trace['timings_ms']['queue'] += trace['timings_ms']['mailbox_queue']
            duration('server', start)
            trace['timings_ms']['server'] += trace['timings_ms']['mailbox_queue']
            result['latency_ms'] = round(trace['timings_ms']['server'])
            self.metrics['latency_ms'] = result['latency_ms']
            result.update(region_id=request.region_id, screen_generation=request.screen_generation,
                          timings_ms=trace['timings_ms'], diagnostics=trace['diagnostics'])
            self.metrics['timings_ms'] = dict(trace['timings_ms'])
            self.metrics['diagnostics'] = dict(trace['diagnostics'])
            return result
        except TranslationError as error:
            trace['timings_ms']['queue'] += trace['timings_ms']['mailbox_queue']
            duration('server', start)
            trace['timings_ms']['server'] += trace['timings_ms']['mailbox_queue']
            error.timings_ms = dict(trace['timings_ms'])
            error.diagnostics = dict(trace['diagnostics'])
            self.metrics['timings_ms'] = dict(trace['timings_ms'])
            self.metrics['diagnostics'] = dict(trace['diagnostics'])
            raise
        finally:
            current_trace.reset(token)

    async def _translate(self, request, device_id, *, cache_only=False, is_current=None,
                         priority='live', allow_cache=True, persist_memory=True, use_memory=True,
                         context_scope='global', source_type='LIVE', project_id='', allow_context=True,
                         context_information=None, confidence_metadata=None):
        start = time.monotonic()
        revision = self.revision
        settings = self.settings()
        model = settings.cli_model if settings.provider == 'cli' else getattr(settings, request.mode + '_model')
        if hasattr(self.provider, 'effective_model'):
            model = self.provider.effective_model(model, request.mode)
        glossary = self.store.glossary(request.profile)
        glossary_hash = hashlib.sha256(json.dumps(glossary, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        namespace = f'v2|{settings.provider}|{request.source}|{request.profile}|{request.mode}|{model}|{glossary_hash}|'
        cache_enabled = settings.cache_enabled and allow_cache
        memory_opted_in = getattr(settings, 'memory_enabled', False)
        memory_lookup_enabled = memory_opted_in and use_memory
        memory_write_enabled = memory_opted_in and persist_memory
        context_enabled = settings.context_enabled and allow_context
        texts = [normalize(b.text) for b in request.blocks]
        if any(not t for t in texts):
            raise TranslationError('empty_text', 'Không có văn bản hợp lệ để dịch.')
        keys = [hashlib.sha256((namespace + t).encode()).hexdigest() for t in texts]
        results = {}
        cached_keys = set()
        missing = {}
        exact = {normalize(g['source']): g['target'] for g in glossary}
        def lookup(text, key):
            hit = exact.get(text)
            if hit is None and memory_lookup_enabled:
                memory_hit = self.memory.lookup(namespace, text, context_scope=context_scope)
                if memory_hit is not None:
                    hit = memory_hit['translation']
            if hit is None and cache_enabled:
                hit = self.store.cached(key)
            return hit
        for text, key in zip(texts, keys):
            hit = lookup(text, key)
            if hit is not None:
                results[key] = hit
                cached_keys.add(key)
            else:
                missing[key] = text
        context_key = ((device_id, request.profile, request.source) if context_scope == 'global' else
                       (device_id, request.profile, request.source, context_scope))
        duration('cache', start)
        if missing and cache_only:
            return None
        if missing:
            now = time.monotonic()
            # A follower joins before acquiring an API permit. Otherwise two
            # copies of A can occupy both slots while unrelated B cannot start.
            self.contexts = {k: v for k, v in self.contexts.items() if now - v[0] < 600}
            context = self.contexts.get(context_key, (now, []))[1] if context_enabled else []
            count = {'fast': 2, 'smart': 6, 'quality': 12}[request.mode]
            session_key = ((device_id, revision, namespace) if context_scope == 'global' else
                           (device_id, revision, namespace, context_scope)) if context_enabled else None
            # Priority is part of admission identity: Live must never join a
            # background leader paused by the active Live capture session.
            flight_key = (revision, namespace, context_scope, allow_cache, persist_memory, use_memory, priority,
                          tuple(missing.values()), json.dumps(context[-count:], ensure_ascii=False, sort_keys=True),
                          session_key)
            pending_items = list(missing.items())
            def recheck():
                return [lookup(text, key) for key, text in pending_items]
            response = await self._provider_output(flight_key, settings, list(missing.values()),
                priority=priority, revision=revision, is_current=is_current, lookup=recheck,
                source=request.source, profile=request.profile, mode=request.mode,
                model=model, glossary=glossary, context=context[-count:], session_key=session_key)
            if response is None:
                return None
            output, refreshed = response
            # Privacy/configuration actions win over results already in flight.
            self.require_revision(revision)
            additions = []
            for index, ((key, original), translated) in enumerate(zip(pending_items, output)):
                results[key] = translated
                if index in refreshed:
                    cached_keys.add(key)
                    continue
                if cache_enabled:
                    self.store.put_cache(key, translated)
                if memory_write_enabled:
                    original_raw = next(b.text for b in request.blocks if normalize(b.text) == original)
                    self.memory.enqueue_draft(MemoryEntry(namespace=namespace,
                        original_text=original_raw, translation=translated, source_language=request.source,
                        profile=request.profile, mode=request.mode, provider=settings.provider, model=model,
                        glossary_version=glossary_hash, context_scope=context_scope,
                        source_type=source_type, project_id=project_id,
                        context_information=context_information or {},
                        confidence_metadata=confidence_metadata or {}))
                additions.append({'source': original, 'vi': translated})
            if additions and context_enabled and (is_current is None or is_current()):
                self.contexts[context_key] = (now, (context + additions)[-12:])
        latency = round((time.monotonic() - start) * 1000)
        self.metrics['frames'] += 1
        self.metrics['cache_hits'] += sum(k in cached_keys for k in keys)
        self.metrics['translated_blocks'] += len(keys)
        self.metrics['latency_ms'] = latency
        return dict(type='result', request_id=request.request_id, latency_ms=latency, model=model,
                    blocks=[dict(id=b.id, text=results[k], box=b.box.model_dump(), cached=k in cached_keys)
                            for b, k in zip(request.blocks, keys)])

    async def close(self):
        await self.memory.close()

    async def _provider_output(self, key, settings, texts, *, priority='live', revision,
                               is_current=None, lookup=None, **kwargs):
        """Collapse identical in-flight batches without reserving quota twice.

        Context/session and privacy boundaries are part of the identity. A
        waiter may cancel without cancelling another frame's provider request.
        There is no persistent source text added by this temporary dictionary.
        """
        existing = self._flights.get(key)
        if existing is not None:
            started = time.monotonic()
            trace = current_trace.get()
            if trace is not None:
                trace['diagnostics']['singleflight_joined'] = True
            retry = False
            try:
                result = await asyncio.shield(existing)
                # An obsolete queued leader may leave without calling Google.
                # Its still-current follower becomes a new leader immediately.
                retry = result is None and (is_current is None or is_current())
            except asyncio.CancelledError:
                # Cancellation of the leader (for example its device leaving)
                # must not silently abort a still-current follower. Its own
                # task cancellation continues to propagate normally.
                if not asyncio.current_task().cancelling() and existing.cancelled():
                    retry = True
                else:
                    raise
            finally:
                duration('singleflight_queue', started)
            if retry:
                if is_current is not None and not is_current():
                    return None
                return await self._provider_output(key, settings, texts, priority=priority,
                    revision=revision, is_current=is_current, lookup=lookup, **kwargs)
            return result
        future = asyncio.get_running_loop().create_future()
        self._flights[key] = future
        try:
            queued = time.monotonic()
            async with self.resources.permit(priority):
                duration('queue', queued)
                self.require_revision(revision)
                # Keep positional mapping when another batch fills only part
                # of the cache while this leader is waiting for its permit.
                output = lookup() if lookup is not None else [None] * len(texts)
                refreshed = {index for index, value in enumerate(output) if value is not None}
                missing = [index for index, value in enumerate(output) if value is None]
                if not missing:
                    result = (output, refreshed)
                    future.set_result(result)
                    return result
                if is_current is not None and not is_current():
                    future.set_result(None)
                    return None
                if not getattr(self.provider, 'configured', True):
                    if hasattr(self.provider, 'configuration_error'):
                        raise self.provider.configuration_error()
                    raise TranslationError('no_api_key', 'Hãy nhập khóa Gemini API trong mục Cài đặt trên Windows.')
                availability = getattr(self.provider, 'availability_error', lambda: None)()
                if availability is not None:
                    raise availability
                now = time.monotonic()
                while self.calls and now - self.calls[0] >= 60:
                    self.calls.popleft()
                if priority=='video':
                    reserve=max(1,min(5,settings.requests_per_minute//4))
                    if len(self.calls)>=max(1,settings.requests_per_minute-reserve):
                        wait=max(1000,round((60-(now-self.calls[0]))*1000)) if self.calls else 60000
                        raise TranslationError('rate_limited','Tạm dừng batch để giữ hạn mức/phút cho Live OCR. Chờ rồi nhấn Khôi phục.',retry_after_ms=wait)
                    remaining=settings.daily_request_limit-self.store.usage()['requests']
                    if remaining<=min(10,settings.daily_request_limit//10):
                        raise TranslationError('daily_limit','Batch đã dùng hết ngân sách cho phép; giữ lại lượt gọi dự phòng cho Live OCR. Có thể điều chỉnh giới hạn trong Cài đặt.')
                if len(self.calls) >= settings.requests_per_minute:
                    raise TranslationError('rate_limited', 'Đạt giới hạn lượt gọi/phút đã cài. Bản dịch sẽ tự thử lại.')
                reserved_day = self.store.day()
                if not self.store.reserve_call(settings.daily_request_limit, day=reserved_day):
                    raise TranslationError('daily_limit', 'Đã đạt giới hạn lượt gọi trong ngày (UTC). Tăng giới hạn trong Cài đặt nếu cần.')
                self.calls.append(now)
                trace=current_trace.get()
                if trace is not None:
                    trace['diagnostics']['provider_calls']=trace['diagnostics'].get('provider_calls',0)+1
                started = time.monotonic()
                try:
                    translated, input_tokens, output_tokens = await self.provider.translate(
                        [texts[index] for index in missing], **kwargs)
                finally:
                    # Timeout, cancellation and HTTP failures are latency too.
                    duration('provider', started)
                self.store.add_tokens(input_tokens, output_tokens, day=reserved_day)
                if trace is not None:
                    trace['diagnostics']['input_tokens']=input_tokens
                    trace['diagnostics']['output_tokens']=output_tokens
                if len(translated) != len(missing) or any(not isinstance(text, str) or not text.strip() for text in translated):
                    raise TranslationError('invalid_response', 'Gemini chưa trả đủ bản dịch cho các đoạn văn bản.')
                for index, value in zip(missing, translated):
                    output[index] = value
                result = (output, refreshed)
                future.set_result(result)
                return result
        except BaseException as error:
            if isinstance(error, asyncio.CancelledError):
                future.cancel()
            else:
                future.set_exception(error)
                future.exception()  # Consume if no other request joined this flight.
            raise
        finally:
            if self._flights.get(key) is future:
                del self._flights[key]
