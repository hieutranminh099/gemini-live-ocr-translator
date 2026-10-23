import asyncio
import json
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import pytest

import app.video_jobs as video_module
from app.manager import TranslationManager
from app.models import Settings, TranslateRequest
from app.provider import TranslationError
from app.storage import Store
from app.video_jobs import VideoJobService
from app.video_media import Toolchain, VideoMediaError, VideoMetadata, VideoSegment


class FakeVideoApi:
    configured = True

    def __init__(self):
        self.calls = []
        self.block_on_call = None
        self.fail_on_call = None
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def translate(self, texts, **kwargs):
        self.calls.append((list(texts), kwargs))
        count = len(self.calls)
        if count == self.block_on_call:
            self.started.set()
            await self.release.wait()
        if count == self.fail_on_call:
            raise TranslationError('temporary', 'Synthetic provider unavailable')
        return ['VI:' + text for text in texts], 25, 12


def source_segments(count=3, texts=None):
    return [VideoSegment(id=f'cue-{index}', start_ms=index * 1000,
                         end_ms=index * 1000 + 900,
                         text=(texts[index] if texts else f'你好朋友{index}'),
                         language='zh', confidence=1.0, source_type='SUBTITLE')
            for index in range(count)]


def metadata(duration_ms=120000):
    return VideoMetadata(duration_ms=duration_ms, width=1280, height=720, fps=24,
        streams=({'index': 0, 'type': 'video', 'codec': 'h264', 'language': '', 'title': '', 'is_text': False},
                 {'index': 1, 'type': 'audio', 'codec': 'aac', 'language': 'zh', 'title': '', 'is_text': False},
                 {'index': 2, 'type': 'subtitle', 'codec': 'subrip', 'language': 'zh', 'title': 'Chinese', 'is_text': True}),
        size_bytes=100, format_name='matroska')


@pytest.fixture
async def video_env(tmp_path, monkeypatch):
    store = Store(tmp_path / 'state')
    store.set('settings', Settings(requests_per_minute=120, daily_request_limit=1000).model_dump(exclude={'api_key'}))
    api = FakeVideoApi()
    manager = TranslationManager(store, api)
    tools = Toolchain(tmp_path / 'ffmpeg.exe', tmp_path / 'ffprobe.exe')
    extracted = []
    env = SimpleNamespace(store=store, api=api, manager=manager, tools=tools,
                          rows=source_segments(), extracted=extracted, probed=[])

    async def probe(path, _tools, **kwargs):
        if kwargs.get('wait_ready'):
            await kwargs['wait_ready']()
        env.probed.append(Path(path))
        return metadata()

    async def extract(path, stream_index, _tools, **kwargs):
        extracted.append((Path(path), stream_index))
        if kwargs.get('wait_ready'):
            await kwargs['wait_ready']()
        return list(env.rows)

    monkeypatch.setattr(video_module, 'probe_video', probe)
    monkeypatch.setattr(video_module, 'extract_subtitle_stream', extract)
    env.service = VideoJobService(tmp_path / 'projects', manager, tools=tools)
    yield env
    await env.service.close()
    await manager.close()
    store.close()


async def upload(service, name='Hội thoại & tên có dấu.mkv', data=b'synthetic local source'):
    offset = 0
    reads = []

    async def reader(size):
        nonlocal offset
        reads.append(size)
        chunk = data[offset:offset + size]
        offset += len(chunk)
        return chunk

    result = await service.import_upload(name, reader)
    return result, reads


async def finished(service, ident):
    async with asyncio.timeout(3):
        task = service.task
        if task:
            await asyncio.shield(task)
    return await service.detail(ident, limit=2000)


async def test_speech_job_restores_typed_metadata_without_computed_ui_fields(video_env):
    env=video_env
    called=[]
    async def recognize(path,meta,tools,**kwargs):
        assert isinstance(meta,VideoMetadata)
        assert meta.duration_ms==120000 and kwargs['source']=='en'
        called.append(path)
        return [VideoSegment('speech-1',0,900,'Hello world',language='en',source_type='SPEECH')]
    env.service.speech_processor=recognize
    job,_=await upload(env.service)
    await env.service.start(job['id'],dict(source='en',method='speech',private=True,mode='fast'))
    result=await finished(env.service,job['id'])
    assert result['status']=='ready' and called
    assert result['segments'][0]['source_type']=='SPEECH'
    assert result['progress']['ai_calls']==1


def frame(text, **kwargs):
    return TranslateRequest.model_validate(dict(request_id='live-test', source='zh', profile='game', mode='fast',
        blocks=[dict(id='source', text=text, box=dict(x=0, y=0, width=1, height=1))], **kwargs))


async def test_video_import_streams_in_bounded_chunks_and_never_calls_ai(video_env):
    env = video_env
    project, reads = await upload(env.service, '..\\Ảnh riêng tư & ký tự.mkv', b'x' * (1024 * 1024 + 3))
    assert project['name'] == 'Ảnh riêng tư & ký tự.mkv'
    assert project['status'] == 'imported'
    assert project['size_bytes'] == 1024 * 1024 + 3
    assert reads == [1024 * 1024] * 3
    assert project['options']['private'] is True and project['options']['save_memory'] is False
    assert project['metadata']['streams'][2]['codec_type'] == 'subtitle'
    assert project['metadata']['streams'][2]['codec_name'] == 'subrip'
    assert project['metadata']['streams'][2]['is_text'] is True
    assert not env.api.calls and env.store.usage()['requests'] == 0
    assert 'input' not in project and 'namespace' not in project
    assert env.probed[0].parent == env.service.directory / project['id']


async def test_embedded_text_preferred_without_ocr_and_duplicate_cues_translated_once(video_env):
    env = video_env
    env.rows = source_segments(3, ['你好', '再见', '你好'])
    project, _ = await upload(env.service)
    await env.service.start(project['id'], dict(method='auto', source='zh', mode='fast'))
    result = await finished(env.service, project['id'])
    assert result['status'] == 'ready' and result['progress']['translated'] == 3
    assert len(env.extracted) == 1 and env.extracted[0][1] == 2
    assert [call[0] for call in env.api.calls] == [['你好', '再见']]
    assert [s['translation'] for s in result['segments']] == ['VI:你好', 'VI:再见', 'VI:你好']
    assert [s['start_ms'] for s in result['segments']] == [0, 1000, 2000]
    assert all(s['quality'] == 'draft' for s in result['segments'])


@pytest.mark.parametrize('options,expected', [
    ({'private': True, 'save_memory': True}, 'private_memory'),
    ({'private': False, 'save_memory': True}, 'memory_disabled'),
    ({'model': 'unconfigured-model'}, 'model_mismatch'),
    ({'roi': [0.8, 0.6, 0.2, 0.9]}, 'invalid_roi'),
])
async def test_invalid_privacy_model_and_roi_fail_before_extraction_or_ai(video_env, options, expected):
    env = video_env
    project, _ = await upload(env.service)
    with pytest.raises(VideoMediaError) as error:
        await env.service.start(project['id'], options)
    assert error.value.code == expected
    assert not env.extracted and not env.api.calls


async def test_private_video_never_enters_global_cache_memory_or_context(video_env):
    env = video_env
    settings = env.manager.settings().model_dump(exclude={'api_key'})
    env.store.set('settings', dict(settings, memory_enabled=True))
    project, _ = await upload(env.service)
    await env.service.start(project['id'], dict(private=True, use_memory=True))
    result = await finished(env.service, project['id'])
    await env.manager.memory.flush()
    assert result['status'] == 'ready'
    assert env.manager.memory.list_entries() == []
    assert env.store.cache_size() == 0
    assert not any(key[0] == 'video:' + project['id'] for key in env.manager.contexts)


async def test_video_drafts_require_user_approval_before_live_reuse(video_env):
    env = video_env
    settings = env.manager.settings().model_dump(exclude={'api_key'})
    env.store.set('settings', dict(settings, memory_enabled=True))
    env.rows = source_segments(1, ['请坐'])
    project, _ = await upload(env.service)
    await env.service.start(project['id'], dict(private=False, save_memory=True))
    result = await finished(env.service, project['id'])
    await env.manager.memory.flush()
    memory = env.manager.memory.list_entries()
    assert len(memory) == 1 and memory[0]['quality'] == 'draft'
    assert memory[0]['project_id'] == project['id'] and memory[0]['source_type'] == 'VIDEO'
    assert env.store.cache_size() == 0
    await env.service.edit(project['id'], [dict(id=result['segments'][0]['id'], translation='Mời ngồi.', quality='user_approved')])
    approved = env.manager.memory.list_entries()[0]
    assert approved['quality'] == 'user_approved' and approved['translation'] == 'Mời ngồi.'
    before = len(env.api.calls)
    live = await env.manager.translate(frame('请坐'), 'live-device', allow_cache=False)
    assert live['blocks'][0]['text'] == 'Mời ngồi.' and live['blocks'][0]['cached'] is True
    assert len(env.api.calls) == before


async def test_checkpoint_survives_shutdown_and_does_not_retranslate_completed_batch(video_env):
    env = video_env
    env.rows = source_segments(21)
    env.api.block_on_call = 2
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    await asyncio.wait_for(env.api.started.wait(), 2)
    await env.service.close()
    before = await env.service.detail(project['id'])
    assert before['status'] == 'interrupted'
    assert sum(bool(s['translation']) for s in before['segments']) == 10
    assert len(env.api.calls) == 2
    env.service = VideoJobService(env.service.directory, env.manager, tools=env.tools)
    await env.service.action(project['id'], 'recover')
    result = await finished(env.service, project['id'])
    assert result['status'] == 'ready' and result['progress']['translated'] == 21
    assert [len(call[0]) for call in env.api.calls] == [10, 10, 10, 1]
    assert len(env.extracted) == 1
    assert env.api.calls[2][0][0] == '你好朋友10'


async def test_cancel_before_first_task_instruction_clears_active_owner(video_env):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    result = await env.service.action(project['id'], 'cancel')
    assert result['status'] == 'cancelled'
    assert env.service.active_id is None
    assert not env.api.calls
    await env.service.start(project['id'])
    assert (await finished(env.service, project['id']))['status'] == 'ready'


@pytest.mark.parametrize('competing_action', ['start', 'export'])
async def test_concurrent_admission_starts_only_one_video_worker(video_env, monkeypatch, competing_action):
    env = video_env
    first, _ = await upload(env.service)
    second, _ = await upload(env.service)
    if competing_action == 'export':
        await env.service.start(second['id'])
        await finished(env.service, second['id'])
    save_entered = asyncio.Event()
    release_save = asyncio.Event()
    worker_release = asyncio.Event()
    workers = set()
    original_save = env.service._save
    save_calls = 0

    async def gated_save(job):
        nonlocal save_calls
        save_calls += 1
        if save_calls == 1:
            save_entered.set()
            await release_save.wait()
        await original_save(job)

    async def held_worker(*args, **kwargs):
        workers.add(asyncio.current_task())
        await worker_release.wait()

    monkeypatch.setattr(env.service, '_save', gated_save)
    monkeypatch.setattr(env.service, '_run', held_worker)
    monkeypatch.setattr(video_module, 'render_video', held_worker)
    leader = asyncio.create_task(env.service.start(first['id']))
    await asyncio.wait_for(save_entered.wait(), 2)
    contender = asyncio.create_task(env.service.start(second['id']) if competing_action == 'start' else
                                    env.service.export(second['id'], {'format': 'mp4'}))
    await asyncio.sleep(0)
    release_save.set()
    try:
        results = await asyncio.gather(leader, contender, return_exceptions=True)
        assert isinstance(results[0], dict)
        assert isinstance(results[1], VideoMediaError) and results[1].code == 'video_busy'
        await asyncio.sleep(0)
        assert len(workers) == 1
        assert env.service.active_id == first['id']
    finally:
        for task in workers:
            task.cancel()
        await asyncio.gather(*workers, return_exceptions=True)


async def test_closed_service_rejects_new_worker(video_env):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.close()
    with pytest.raises(VideoMediaError) as error:
        await env.service.start(project['id'])
    assert error.value.code == 'video_closed'
    assert env.service.task is None and not env.api.calls


async def test_live_gate_blocks_extraction_and_gemini_until_capture_pauses(video_env):
    env = video_env
    project, _ = await upload(env.service)
    env.manager.resources.set_live_active('device', True)
    await env.service.start(project['id'])
    async with asyncio.timeout(2):
        while env.service.jobs[project['id']]['status'] != 'waiting_live':
            await asyncio.sleep(.001)
    assert not env.extracted and not env.api.calls
    await env.service.action(project['id'], 'pause')
    env.manager.resources.set_live_active('device', False)
    async with asyncio.timeout(2):
        while env.service.jobs[project['id']]['status'] != 'paused':
            await asyncio.sleep(.001)
    assert not env.api.calls
    await env.service.action(project['id'], 'resume')
    assert (await finished(env.service, project['id']))['status'] == 'ready'


@pytest.mark.parametrize('change', [{'profile': 'web'}, {'mode': 'quality'}, {'source': 'ja'}])
async def test_changed_namespace_never_relabels_completed_checkpoint(video_env, change):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    await finished(env.service, project['id'])
    with pytest.raises(VideoMediaError) as error:
        await env.service.start(project['id'], change)
    assert error.value.code in ('configuration_changed', 'source_options_changed')
    assert len(env.api.calls) == 1


async def test_timeline_page_edits_merge_and_approved_cue_is_not_overwritten_by_ai(video_env):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    original = await finished(env.service, project['id'])
    first = original['segments'][0]
    edited = await env.service.edit(project['id'], [dict(id=first['id'], translation='Bản dịch đã duyệt.', quality='user_approved')])
    assert edited['segments'][0]['quality'] == 'user_approved'
    assert edited['segments'][1:] == original['segments'][1:]
    rows = env.service._read_timeline(project['id'])
    rows[1]['translation'] = ''
    await env.service._write_timeline(env.service.jobs[project['id']], rows)
    await env.service.start(project['id'])
    result = await finished(env.service, project['id'])
    assert result['segments'][0]['translation'] == 'Bản dịch đã duyệt.'
    assert result['segments'][0]['quality'] == 'user_approved'
    assert env.api.calls[-1][0] == [rows[1]['text']]


async def test_timeline_rejects_overlap_unknown_ids_and_keeps_last_saved_state(video_env):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    original = await finished(env.service, project['id'])
    for changes in ([dict(id='not-in-project', translation='Không được lưu')],
                    [dict(id='cue-0', end_ms=1500)],
                    [dict(id='cue-0', start_ms=-1)]):
        with pytest.raises(VideoMediaError):
            await env.service.edit(project['id'], changes)
        assert (await env.service.detail(project['id']))['segments'] == original['segments']


async def test_replace_all_reports_count_and_exports_srt_ass_with_edited_timestamps(video_env):
    env = video_env
    project, _ = await upload(env.service)
    await env.service.start(project['id'])
    await finished(env.service, project['id'])
    result = await env.service.replace_text(project['id'], 'VI:', 'Bản dịch:')
    assert result['changed'] == 3
    assert all(s['quality'] == 'user_approved' for s in result['segments'])
    assert (await env.service.replace_text(project['id'], 'absent', 'no-op'))['changed'] == 0
    await env.service.edit(project['id'], [dict(id='cue-0', start_ms=100, end_ms=800)])
    for fmt in ('srt', 'ass'):
        result = await env.service.export(project['id'], dict(format=fmt, style=dict(font_name='Arial', position='middle'), bilingual=True))
        output = next(o for o in result['outputs'] if o['format'] == fmt)
        token = parse_qs(urlsplit(output['url']).query)['token'][0]
        path = env.service.file(project['id'], fmt, token)
        text = path.read_text(encoding='utf-8-sig')
        assert 'Bản dịch:' in text and '你好朋友0' in text
        assert ('00:00:00,100 --> 00:00:00,800' if fmt == 'srt' else '0:00:00.10,0:00:00.80') in text


async def test_media_capability_scope_path_integrity_and_restart_revocation(video_env):
    env = video_env
    first, _ = await upload(env.service)
    second, _ = await upload(env.service)
    token = parse_qs(urlsplit(first['preview_url']).query)['token'][0]
    source = env.service.file(first['id'], 'source', token)
    assert source.read_bytes() == b'synthetic local source'
    for ident, kind, key in ((second['id'], 'source', token), (first['id'], 'source', 'bad'),
                             (first['id'], '../project.json', token)):
        with pytest.raises(VideoMediaError):
            env.service.file(ident, kind, key)
    env.service.jobs[first['id']]['input'] = '../../outside.mp4'
    with pytest.raises(VideoMediaError):
        env.service.file(first['id'], 'source', token)
    env.service.jobs[first['id']]['input'] = source.name
    env.service = VideoJobService(env.service.directory, env.manager, tools=env.tools)
    with pytest.raises(VideoMediaError) as error:
        env.service.file(first['id'], 'source', token)
    assert error.value.code == 'file_forbidden'


async def test_delete_removes_only_owned_copy_and_revokes_project_capability(video_env, tmp_path):
    env = video_env
    original = tmp_path / 'source-original.mkv'
    original.write_bytes(b'never modify original')
    project, _ = await upload(env.service, original.name, original.read_bytes())
    await env.service.delete(project['id'])
    assert original.read_bytes() == b'never modify original'
    assert not (env.service.directory / project['id']).exists()
    assert project['id'] not in env.service.jobs and project['id'] not in env.service.tokens
