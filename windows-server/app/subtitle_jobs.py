"""Bounded, cancellable subtitle cache prewarming through TranslationManager.

Parsing/preview is free and offline. Starting a job is an explicit API action;
the job never falls back to CLI, changes configuration, or retries quota errors.
"""

import asyncio
from collections import OrderedDict
from dataclasses import dataclass
import secrets
import time

from .models import TranslateRequest
from .provider import TranslationError
from .subtitle_import import parse_subtitle_file, plan_subtitles


_MESSAGES = {
    'api_required': 'Dịch trước phụ đề chỉ dùng Gemini API chính thức. Hãy chọn API trong Cài đặt.',
    'no_api_key': 'Hãy nhập khóa Gemini API trong Cài đặt trước khi dịch trước phụ đề.',
    'cache_disabled': 'Hãy bật cache trước khi dịch trước phụ đề.',
    'job_active': 'Đang có một tác vụ dịch trước. Hãy chờ hoặc hủy tác vụ đó.',
    'job_not_found': 'Không tìm thấy tác vụ dịch trước này.',
    'configuration_changed': 'Cấu hình hoặc dữ liệu đã thay đổi. Tác vụ dừng; bản dịch cũ không được ghi tiếp.',
    'quota_exceeded': 'Gemini đang giới hạn lượt gọi. Tác vụ đã dừng, không tự gửi lại.',
    'rate_limited': 'Đã chạm giới hạn lượt gọi/phút. Tác vụ đã dừng, không tự gửi lại.',
    'daily_limit': 'Đã chạm giới hạn lượt gọi/ngày. Tác vụ đã dừng.',
    'provider_configuration': 'Kiểm tra khóa API, quyền truy cập và tên mô hình trong Cài đặt.',
    'provider_unavailable': 'Không thể kết nối Gemini. Tác vụ đã dừng, không tự gửi lại.',
    'invalid_response': 'Gemini chưa trả đủ bản dịch cho một batch. Tác vụ đã dừng.',
    'closed': 'Dịch trước phụ đề đã dừng vì ứng dụng đang đóng.',
    'invalid_options': 'Ngôn ngữ, hồ sơ hoặc chế độ dịch trước không hợp lệ.',
}


class SubtitleJobError(TranslationError):
    def __init__(self, code):
        super().__init__(code, _MESSAGES[code])


@dataclass
class _Job:
    job_id: str
    source: str
    profile: str
    mode: str
    model: str
    revision: int
    fingerprint: tuple
    plan: object
    created_at: float
    created_monotonic: float
    total_cues: int
    unique_texts: int
    total_batches: int
    status: str = 'queued'
    completed_batches: int = 0
    attempted_batches: int = 0
    completed_items: int = 0
    completed_cues: int = 0
    cached_items: int = 0
    translated_items: int = 0
    new_translation_batches: int = 0
    started_at: float | None = None
    finished_at: float | None = None
    elapsed_ms: int = 0
    error_code: str | None = None
    error_message: str | None = None
    retry_after_ms: int | None = None
    task: object = None

    def public(self):
        # Do not expose subtitle text, filenames, paths, API keys, or provider bodies.
        result = {name: getattr(self, name) for name in (
            'job_id', 'source', 'profile', 'mode', 'model', 'status', 'created_at',
            'started_at', 'finished_at', 'total_cues', 'unique_texts', 'total_batches',
            'attempted_batches', 'completed_batches', 'completed_items', 'completed_cues',
            'cached_items', 'translated_items', 'new_translation_batches',
            'error_code', 'error_message', 'retry_after_ms')}
        result['elapsed_ms'] = (self.elapsed_ms if self.finished_at is not None else
                                round((time.monotonic() - self.created_monotonic) * 1000))
        result['progress_percent'] = round(100 * self.completed_items / self.unique_texts, 1)
        return result


class SubtitleJobService:
    def __init__(self, manager, *, retained_jobs=10):
        if not 1 <= retained_jobs <= 50:
            raise ValueError('retained_jobs must be between 1 and 50')
        self.manager = manager
        self.retained_jobs = retained_jobs
        self._jobs = OrderedDict()
        self._active = None
        self._lock = asyncio.Lock()
        self._closed = False

    @staticmethod
    def preview(filename, content):
        plan = plan_subtitles(parse_subtitle_file(filename, content))
        return dict(format=plan.document.format, cue_count=len(plan.document.cues),
                    unique_text_count=len(plan.unique_items),
                    duplicate_cue_count=len(plan.document.cues) - len(plan.unique_items),
                    expected_batches=len(plan.batches),
                    estimated_input_tokens=sum(batch.estimated_input_tokens for batch in plan.batches),
                    warnings=list(plan.document.warnings) + [
                        'Ước lượng token chưa gồm prompt/ngữ cảnh/đầu ra; không phải số token thanh toán.',
                        'Chọn phụ đề ngôn ngữ nguồn. Cache chỉ khớp khi văn bản OCR giống câu đã dịch trước.',
                        'Chọn cùng ngôn ngữ, hồ sơ, chế độ và mô hình khi xem video; thay đổi sẽ tạo cache riêng.',
                    ])

    def _current(self, mode):
        settings = self.manager.settings()
        if settings.provider != 'api':
            raise SubtitleJobError('api_required')
        if not settings.cache_enabled:
            raise SubtitleJobError('cache_disabled')
        if not getattr(self.manager.provider, 'configured', False):
            raise SubtitleJobError('no_api_key')
        model = getattr(settings, mode + '_model')
        if hasattr(self.manager.provider, 'effective_model'):
            model = self.manager.provider.effective_model(model, mode)
        # Never retain credentials in a job. Any settings or provider change
        # stops remaining batches, even if an endpoint forgot to invalidate.
        settings_fingerprint = repr(sorted(settings.model_dump(exclude={'api_key'}).items()))
        return model, (settings_fingerprint, id(self.manager.provider))

    async def start(self, filename, content, *, source='zh', profile='game', mode='fast'):
        if source not in ('en', 'ja', 'zh', 'ko') or profile not in (
                'game', 'manga', 'document', 'web', 'technical') or mode not in ('fast', 'smart', 'quality'):
            raise SubtitleJobError('invalid_options')
        # Complete parsing before allocating any task or consuming provider quota.
        plan = plan_subtitles(parse_subtitle_file(filename, content))
        async with self._lock:
            if self._closed:
                raise SubtitleJobError('closed')
            if self._active is not None:
                raise SubtitleJobError('job_active')
            model, fingerprint = self._current(mode)
            now = time.time()
            job = _Job(secrets.token_hex(12), source, profile, mode, model,
                       self.manager.revision, fingerprint, plan, now, time.monotonic(),
                       len(plan.document.cues), len(plan.unique_items), len(plan.batches))
            while len(self._jobs) >= self.retained_jobs:
                self._jobs.popitem(last=False)
            self._jobs[job.job_id] = job
            self._active = job.job_id
            job.task = asyncio.create_task(self._run(job), name=f'subtitle-prewarm-{job.job_id}')
            return job.public()

    def get(self, job_id):
        job = self._jobs.get(job_id)
        if job is None:
            raise SubtitleJobError('job_not_found')
        return job.public()

    def list_jobs(self):
        return [job.public() for job in reversed(self._jobs.values())]

    def _require_snapshot(self, job):
        self.manager.require_revision(job.revision)
        try:
            model, fingerprint = self._current(job.mode)
        except SubtitleJobError:
            raise SubtitleJobError('configuration_changed') from None
        if model != job.model or fingerprint != job.fingerprint:
            raise SubtitleJobError('configuration_changed')

    async def _run(self, job):
        job.status = 'running'
        job.started_at = time.time()
        try:
            for batch in job.plan.batches:
                self._require_snapshot(job)
                request = TranslateRequest.model_validate(dict(
                    request_id=f'prewarm-{job.job_id}-{batch.batch_id}',
                    source=job.source, profile=job.profile, mode=job.mode,
                    blocks=[dict(id=item.item_id, text=item.text,
                                 box=dict(x=0, y=0, width=1, height=1)) for item in batch.items]))
                job.attempted_batches += 1
                # Exactly one translate call at a time. TranslationManager's
                # global two-slot semaphore still leaves room for a live frame.
                result = await self.manager.translate(request, f'subtitle-prewarm:{job.job_id}',priority='video',
                    persist_memory=False,source_type='VIDEO',project_id=job.job_id)
                self._require_snapshot(job)
                blocks = result.get('blocks', [])
                if (len(blocks) != len(batch.items) or
                        [block.get('id') for block in blocks] != [item.item_id for item in batch.items]):
                    raise TranslationError('invalid_response', 'Invalid prewarm output mapping')
                cached = sum(bool(block.get('cached')) for block in blocks)
                job.cached_items += cached
                job.translated_items += len(blocks) - cached
                job.new_translation_batches += int(cached != len(blocks))
                job.completed_items += len(blocks)
                job.completed_cues += sum(len(item.cue_ids) for item in batch.items)
                job.completed_batches += 1
                await asyncio.sleep(0)  # Allow foreground work between batches.
            job.status = 'completed'
        except asyncio.CancelledError:
            job.status = 'cancelled'
        except TranslationError as error:
            job.status = 'failed'
            job.error_code = error.code
            job.error_message = _MESSAGES.get(error.code, 'Không thể hoàn thành dịch trước phụ đề.')
            retry = getattr(error, 'retry_after_ms', None)
            if retry is None and error.code in ('quota_exceeded', 'rate_limited'):
                retry = 60000
            if isinstance(retry, (int, float)):
                job.retry_after_ms = min(86400000, max(0, round(retry)))
        except Exception:
            # Do not leak an untrusted provider body or subtitle text via status.
            job.status = 'failed'
            job.error_code = 'prewarm_failed'
            job.error_message = 'Không thể hoàn thành dịch trước phụ đề.'
        finally:
            job.finished_at = time.time()
            job.elapsed_ms = round((time.monotonic() - job.created_monotonic) * 1000)
            job.plan = None  # No raw subtitle retained after a terminal status.
            # Only this job's temporary context is discarded; device contexts,
            # configuration, existing cache, and user data are untouched.
            self.manager.contexts.pop((f'subtitle-prewarm:{job.job_id}', job.profile, job.source), None)
            async with self._lock:
                if self._active == job.job_id:
                    self._active = None

    async def cancel(self, job_id):
        async with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise SubtitleJobError('job_not_found')
            task = job.task
            if job.finished_at is None and task is not None:
                task.cancel()
        if task is not None and not task.done():
            try:
                await task
            except asyncio.CancelledError:
                # A task cancelled before its first instruction cannot execute
                # _run's finally block; finalize it here as well.
                pass
        async with self._lock:
            if job.finished_at is None:
                job.status = 'cancelled'
                job.finished_at = time.time()
                job.elapsed_ms = round((time.monotonic() - job.created_monotonic) * 1000)
                job.plan = None
            if self._active == job_id:
                self._active = None
            return job.public()

    async def close(self):
        self._closed = True
        if self._active is not None:
            await self.cancel(self._active)
