import asyncio
import json

import pytest

from app.manager import TranslationManager
from app.models import Settings, TranslateRequest
from app.provider import TranslationError
from app.storage import Store, normalize
from app.subtitle_import import SubtitleImportError
from app.subtitle_jobs import SubtitleJobError, SubtitleJobService


def subtitle(rows):
    return '\n\n'.join(f'{index + 1}\n00:00:{index:02d},000 --> 00:01:{index:02d},000\n{text}'
                       for index, text in enumerate(rows))


def frame(texts, *, source='zh', profile='game', mode='fast'):
    return TranslateRequest.model_validate(dict(request_id='live', source=source, profile=profile, mode=mode,
        blocks=[dict(id=str(index), text=text, box=dict(x=0, y=0, width=1, height=1))
                for index, text in enumerate(texts)]))


class FakeApi:
    configured = True

    def __init__(self):
        self.calls = []
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.block_first = False
        self.error = None
        self.active = 0
        self.max_active = 0

    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        self.active += 1
        self.max_active = max(self.active, self.max_active)
        try:
            if self.block_first and len(self.calls) == 1:
                self.started.set()
                await self.release.wait()
            if self.error is not None:
                raise self.error
            return ['VI:' + text for text in texts], 20, 10
        finally:
            self.active -= 1


@pytest.fixture
def resources(tmp_path):
    store = Store(tmp_path)
    api = FakeApi()
    manager = TranslationManager(store, api)
    yield store, api, manager
    store.close()


async def finished(service, job_id):
    async with asyncio.timeout(2):
        while service.get(job_id)['status'] in ('queued', 'running'):
            await asyncio.sleep(.001)
    return service.get(job_id)


async def test_preview_is_free_offline_and_contains_no_subtitle_text(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager)
    preview = service.preview('private-name.srt', subtitle(['你好', '你好', '再见']))
    assert preview['cue_count'] == 3 and preview['unique_text_count'] == 2
    assert preview['expected_batches'] == 1 and preview['duplicate_cue_count'] == 1
    assert '你好' not in json.dumps(preview, ensure_ascii=False)
    assert 'private-name' not in json.dumps(preview)
    assert not api.calls and not service.list_jobs() and store.usage()['requests'] == 0
    await service.close()


@pytest.mark.parametrize('settings,configured,expected', [
    ({'provider': 'cli'}, True, 'api_required'),
    ({'cache_enabled': False}, True, 'cache_disabled'),
    ({}, False, 'no_api_key'),
])
async def test_start_requires_supported_configured_api_and_cache(resources, settings, configured, expected):
    store, api, manager = resources
    store.set('settings', Settings(**settings).model_dump(exclude={'api_key'}))
    api.configured = configured
    service = SubtitleJobService(manager)
    with pytest.raises(SubtitleJobError) as error:
        await service.start('x.srt', subtitle(['你好']))
    assert error.value.code == expected
    assert not api.calls and not service.list_jobs() and store.usage()['requests'] == 0
    await service.close()


async def test_invalid_file_or_options_do_not_start_partial_job(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager)
    with pytest.raises(SubtitleImportError):
        await service.start('x.srt', 'bad subtitle')
    with pytest.raises(SubtitleJobError) as error:
        await service.start('x.srt', subtitle(['你好']), source='vi')
    assert error.value.code == 'invalid_options'
    assert not api.calls and not service.list_jobs() and store.usage()['requests'] == 0
    await service.close()


async def test_batch_deduplication_and_live_cache_use_identical_namespace(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager)
    rows = [f'你好，朋友{index}' for index in range(21)]
    start = await service.start('x.srt', subtitle(rows + rows[:5]))
    result = await finished(service, start['job_id'])
    assert result['status'] == 'completed'
    assert result['total_cues'] == result['completed_cues'] == 26
    assert result['unique_texts'] == result['completed_items'] == result['translated_items'] == 21
    assert result['completed_batches'] == result['new_translation_batches'] == 3
    assert [len(call[0]) for call in api.calls] == [10, 10, 1]
    assert all(call[1]['source'] == 'zh' and call[1]['profile'] == 'game'
               and call[1]['mode'] == 'fast' for call in api.calls)
    assert all(call[1]['model'] == manager.settings().fast_model for call in api.calls)
    assert store.cache_size() == 21 and store.usage()['requests'] == 3
    live = await manager.translate(frame(rows[:3]), 'real-device')
    assert all(block['cached'] for block in live['blocks'])
    assert [block['text'] for block in live['blocks']] == ['VI:' + normalize(row) for row in rows[:3]]
    assert len(api.calls) == 3
    # Different mode/profile/source must never reuse a translation accidentally.
    for kwargs in ({'source': 'ja'}, {'profile': 'web'}, {'mode': 'smart'}):
        response = await manager.translate(frame(rows[:1], **kwargs), 'real-device')
        assert not response['blocks'][0]['cached']
    assert len(api.calls) == 6
    assert all(not key[0].startswith('subtitle-prewarm:') for key in manager.contexts)
    public = json.dumps(result, ensure_ascii=False)
    assert rows[0] not in public and 'x.srt' not in public and 'fingerprint' not in public
    assert service._jobs[start['job_id']].plan is None
    await service.close()


async def test_existing_cache_is_counted_without_another_api_request(resources):
    store, api, manager = resources
    await manager.translate(frame(['你好', '再见']), 'live')
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle(['你好', '再见', '你好']))
    result = await finished(service, start['job_id'])
    assert result['status'] == 'completed' and result['cached_items'] == 2
    assert result['translated_items'] == result['new_translation_batches'] == 0
    assert result['completed_cues'] == 3 and len(api.calls) == 1
    await service.close()


async def test_only_one_background_job_and_foreground_slot_remains_available(resources):
    store, api, manager = resources
    api.block_first = True
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle([f'背景{index}' for index in range(21)]))
    await asyncio.wait_for(api.started.wait(), 1)
    with pytest.raises(SubtitleJobError) as error:
        await service.start('another.srt', subtitle(['不应发送']))
    assert error.value.code == 'job_active'
    foreground = await asyncio.wait_for(manager.translate(frame(['前景字幕']), 'live'), 1)
    assert foreground['blocks'][0]['text'] == 'VI:前景字幕'
    assert api.max_active == 2 and api.active == 1
    api.release.set()
    assert (await finished(service, start['job_id']))['status'] == 'completed'
    assert [len(call[0]) for call in api.calls] == [10, 1, 10, 1]
    await service.close()


async def test_cancel_before_task_first_instruction_and_restart(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle(['你好']))
    cancelled = await service.cancel(start['job_id'])
    assert cancelled['status'] == 'cancelled' and cancelled['attempted_batches'] == 0
    assert not api.calls and service._active is None
    again = await service.start('x.srt', subtitle(['再见']))
    assert (await finished(service, again['job_id']))['status'] == 'completed'
    await service.close()


async def test_cancel_inflight_does_not_run_following_batches_or_clear_existing_data(resources):
    store, api, manager = resources
    store.put_glossary('one', 'một', 'all')
    api.block_first = True
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle([f'句{index}' for index in range(21)]))
    await asyncio.wait_for(api.started.wait(), 1)
    cancelled = await service.cancel(start['job_id'])
    assert cancelled['status'] == 'cancelled' and cancelled['completed_batches'] == 0
    assert len(api.calls) == 1 and api.active == 0
    assert store.cache_size() == 0 and store.glossary()[0]['target'] == 'một'
    # Cancellation cannot refund a request already submitted to a provider.
    assert store.usage()['requests'] == 1
    await service.close()


async def test_revision_change_inflight_prevents_cache_write_and_stops_job(resources):
    store, api, manager = resources
    api.block_first = True
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle([f'句{index}' for index in range(21)]))
    await asyncio.wait_for(api.started.wait(), 1)
    manager.invalidate()
    api.release.set()
    result = await finished(service, start['job_id'])
    assert result['status'] == 'failed' and result['error_code'] == 'configuration_changed'
    assert len(api.calls) == 1 and store.cache_size() == 0
    await service.close()


async def test_model_change_without_revision_stops_remaining_batches(resources):
    store, api, manager = resources
    api.block_first = True
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle([f'句{index}' for index in range(21)]))
    await asyncio.wait_for(api.started.wait(), 1)
    settings = manager.settings().model_dump(exclude={'api_key'})
    settings['fast_model'] = 'changed-model'
    store.set('settings', settings)
    api.release.set()
    result = await finished(service, start['job_id'])
    assert result['error_code'] == 'configuration_changed' and len(api.calls) == 1
    # Manager's first batch belonged to the old model namespace, never the new one.
    cached = await manager.translate_cached(frame(['句0']), 'live')
    assert cached is None
    await service.close()


async def test_configuration_change_before_worker_starts_spends_no_quota(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle(['你好']))
    manager.invalidate()
    result = await finished(service, start['job_id'])
    assert result['error_code'] == 'configuration_changed'
    assert not api.calls and store.usage()['requests'] == 0
    await service.close()


@pytest.mark.parametrize('code,retry,expected', [
    ('quota_exceeded', 2300, 2300), ('rate_limited', None, 60000),
    ('daily_limit', None, None), ('provider_unavailable', None, None),
])
async def test_quota_or_network_failure_stops_without_automatic_retry(resources, code, retry, expected):
    store, api, manager = resources
    api.error = TranslationError(code, 'private-provider-body-with-source', retry_after_ms=retry)
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle([f'句{index}' for index in range(21)]))
    result = await finished(service, start['job_id'])
    for _ in range(20):
        await asyncio.sleep(0)
    assert result['status'] == 'failed' and result['error_code'] == code
    assert result['retry_after_ms'] == expected
    assert result['completed_batches'] == 0 and len(api.calls) == 1
    assert 'private-provider-body' not in json.dumps(result)
    assert store.cache_size() == 0
    await service.close()


async def test_unexpected_error_does_not_leak_input_or_provider_details(resources):
    store, api, manager = resources
    api.error = RuntimeError('api-secret-and-private-source-text')
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle(['你好']))
    result = await finished(service, start['job_id'])
    assert result['status'] == 'failed' and result['error_code'] == 'prewarm_failed'
    assert 'api-secret' not in json.dumps(result) and 'private-source' not in json.dumps(result)
    await service.close()


async def test_recent_jobs_are_bounded_and_terminal_cancellation_is_idempotent(resources):
    store, api, manager = resources
    service = SubtitleJobService(manager, retained_jobs=2)
    ids = []
    for text in ('A', 'B', 'C'):
        start = await service.start('x.srt', subtitle([text]))
        ids.append(start['job_id'])
        assert (await finished(service, start['job_id']))['status'] == 'completed'
    assert [job['job_id'] for job in service.list_jobs()] == ids[:0:-1]
    with pytest.raises(SubtitleJobError) as error:
        service.get(ids[0])
    assert error.value.code == 'job_not_found'
    assert (await service.cancel(ids[-1]))['status'] == 'completed'
    await service.close()
    with pytest.raises(SubtitleJobError) as error:
        await service.start('x.srt', subtitle(['D']))
    assert error.value.code == 'closed'


async def test_close_cancels_only_own_background_task(resources):
    store, api, manager = resources
    api.block_first = True
    service = SubtitleJobService(manager)
    start = await service.start('x.srt', subtitle(['后台']))
    await asyncio.wait_for(api.started.wait(), 1)
    await service.close()
    assert service.get(start['job_id'])['status'] == 'cancelled'
    assert not any(task.get_name().startswith('subtitle-prewarm-')
                   for task in asyncio.all_tasks() if task is not asyncio.current_task())
