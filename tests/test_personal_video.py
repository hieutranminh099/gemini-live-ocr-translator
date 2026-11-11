import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

import app.video_jobs as media_jobs
from app.chatgpt_plan import ChatGPTError
from app.personal_video import PersonalVideoRuntime, personal_video_app
from app.video_jobs import VideoJobService
from app.video_media import Toolchain, VideoMetadata, VideoSegment


class FakePlan:
    configured = True
    selected_model = 'test-model'
    models = [dict(id='test-model', name='Test model')]
    last_error = ''
    def __init__(self):
        self.calls = []
        self.fail = False
        self.block = None
    def public_state(self):
        return dict(connected=True, plan_enabled=self.configured, model=self.selected_model, models=self.models)
    async def translate_rows(self, rows, source):
        self.calls.append(rows)
        if self.block:
            await self.block.wait()
        if self.fail and len(self.calls) == 2:
            raise ChatGPTError('CHATGPT_USAGE_LIMIT', 'Hạn mức thử nghiệm')
        return {r['id']: 'Bản dịch '+r['text'] for r in rows}, dict(input_tokens=10, output_tokens=10)
    async def list_models(self):
        return self.models
    async def close(self):
        pass


@pytest.fixture
async def personal(tmp_path, monkeypatch):
    metadata = VideoMetadata(duration_ms=30000, width=640, height=360, fps=24,
        streams=({'index': 0, 'type': 'video', 'codec': 'h264', 'is_text': False},
            {'index': 1, 'type': 'subtitle', 'codec': 'subrip', 'is_text': True}), size_bytes=100, format_name='matroska')
    rows = [VideoSegment(id=f'cue-{i}', start_ms=i*1000, end_ms=i*1000+900, text=f'你好朋友{i}', language='zh') for i in range(23)]
    extracted, rendered = [], []
    async def probe(*args, **kwargs):
        return metadata
    async def extract(*args, **kwargs):
        extracted.append(True)
        return rows
    async def render(input_path, output, segments, tools, **kwargs):
        assert len(segments) == 23 and all(s.translation for s in segments)
        assert [s.start_ms for s in segments] == [r.start_ms for r in rows]
        rendered.append(True)
        Path(output).write_bytes(b'synthetic-mp4')
    monkeypatch.setattr(media_jobs, 'probe_video', probe)
    monkeypatch.setattr(media_jobs, 'extract_subtitle_stream', extract)
    monkeypatch.setattr(media_jobs, 'render_video', render)
    client = FakePlan()
    def service(directory, manager, **kwargs):
        return VideoJobService(directory, manager, tools=Toolchain(tmp_path/'ffmpeg', tmp_path/'ffprobe'))
    runtime = PersonalVideoRuntime(tmp_path/'app', client=client, service_factory=service)
    env = SimpleNamespace(runtime=runtime, plan=client, extracted=extracted, rendered=rendered)
    yield env
    await runtime.close()


async def submit(runtime):
    chunks = [b'synthetic-media']
    async def reader(size):
        return chunks.pop(0) if chunks else b''
    jobs = await runtime.import_and_start('phim.mkv', reader)
    return jobs[0]['id']


async def finish(runtime):
    async with asyncio.timeout(3):
        await runtime.runner


async def test_selecting_film_automatically_extracts_translates_and_exports(personal):
    runtime = personal.runtime
    ident = await submit(runtime)
    await finish(runtime)
    job = await runtime.service.detail(ident, limit=2000)
    assert job['status'] == 'completed'
    assert job['progress']['translated'] == 23
    assert {o['format'] for o in job['outputs']} == {'srt', 'mp4'}
    assert len(personal.extracted) == 1 and len(personal.rendered) == 1
    assert [len(c) for c in personal.plan.calls] == [10, 10, 3]
    assert all(r['quality'] == 'draft' for r in job['segments'])
    assert all(r['id'] == f'cue-{i}' for i, r in enumerate(job['segments']))
    assert runtime.store.db.execute('SELECT count(*) FROM translation_memory').fetchone()[0] == 0


async def test_quota_preserves_checkpoint_and_resume_only_translates_missing(personal):
    personal.plan.fail = True
    ident = await submit(personal.runtime)
    await finish(personal.runtime)
    job = await personal.runtime.service.detail(ident, limit=2000)
    assert job['status'] == 'failed' and job['error_code'] == 'CHATGPT_USAGE_LIMIT'
    assert job['progress']['translated'] == 10 and not personal.rendered
    saved = job['segments'][:10]
    personal.plan.fail = False
    await personal.runtime.resume(ident)
    await finish(personal.runtime)
    job = await personal.runtime.service.detail(ident, limit=2000)
    assert job['status'] == 'completed'
    assert job['segments'][:10] == saved
    assert len(personal.extracted) == 1
    assert [len(c) for c in personal.plan.calls] == [10, 10, 10, 3]


async def test_pause_keeps_source_and_stops_automatic_exports(personal):
    personal.plan.block = asyncio.Event()
    ident = await submit(personal.runtime)
    while not personal.plan.calls:
        await asyncio.sleep(.01)
    await personal.runtime.pause(ident)
    job = await personal.runtime.service.detail(ident)
    assert job['status'] == 'cancelled' and not personal.rendered and not personal.runtime.busy
    personal.plan.block = None
    await personal.runtime.resume(ident)
    await finish(personal.runtime)
    assert personal.runtime.service._job(ident)['status'] == 'completed'
    assert len(personal.extracted) == 1


async def test_unauthorized_upload_blocked_before_reading_source(personal):
    personal.plan.configured = False
    async def reader(size):
        raise AssertionError('Must not read film before consent')
    with pytest.raises(ChatGPTError):
        await personal.runtime.import_and_start('film.mp4', reader)
    assert not personal.runtime.service.jobs


async def test_loopback_api_requires_session_token_and_does_not_expose_credentials(personal):
    root = Path(__file__).resolve().parents[1]/'windows-server/personal-video'
    app = personal_video_app(personal.runtime, root)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://127.0.0.1') as browser:
        assert (await browser.get('/api/state')).status_code == 401
        assert (await browser.get('/api/state', headers={b'Authorization': b'Bearer \xff'})).status_code == 401
        auth = {'Authorization': 'Bearer '+personal.runtime.admin_token}
        state = await browser.get('/api/state', headers=auth)
        assert state.status_code == 200 and 'access_token' not in state.text
        html = await browser.get('/')
        assert html.status_code == 200 and personal.runtime.admin_token not in html.text
        assert "frame-ancestors 'none'" in html.headers['content-security-policy']
        forbidden = await browser.get('/api/state', headers=dict(auth, Host='attacker.invalid'))
        assert forbidden.status_code == 400
        uploaded = await browser.post('/api/films', content=b'synthetic-media', headers=dict(auth, **{'X-Video-Name': 'film.mkv', 'Content-Type': 'application/octet-stream'}))
        assert uploaded.status_code == 200
        await finish(personal.runtime)
        job = personal.runtime.service.list_jobs()[0]
        assert (await browser.get('/api/video/files/'+job['id']+'/mp4')).status_code == 400
        output = next(o for o in job['outputs'] if o['format'] == 'mp4')
        downloaded = await browser.get(output['url'])
        assert downloaded.status_code == 200 and downloaded.content == b'synthetic-mp4'
        assert 'filename*=utf-8' not in downloaded.headers['content-disposition']
        assert 'filename="film.vi.mp4"' in downloaded.headers['content-disposition']


async def test_busy_blocks_second_film_and_keeps_existing_job(personal):
    personal.plan.block = asyncio.Event()
    ident = await submit(personal.runtime)
    with pytest.raises(Exception) as error:
        await submit(personal.runtime)
    assert getattr(error.value, 'code') == 'video_busy'
    assert len(personal.runtime.service.jobs) == 1
    await personal.runtime.pause(ident)


async def test_browser_refresh_uses_http_only_session_and_rejects_cross_origin_changes(personal):
    root = Path(__file__).resolve().parents[1]/'windows-server/personal-video'
    app = personal_video_app(personal.runtime, root)
    origin = 'http://127.0.0.1'
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=origin) as browser:
        auth = {'Authorization': 'Bearer '+personal.runtime.admin_token, 'Origin': origin}
        session = await browser.post('/api/session', headers=auth)
        assert session.status_code == 200
        cookie = session.headers['set-cookie'].lower()
        assert 'httponly' in cookie and 'samesite=strict' in cookie and 'path=/api/' in cookie
        # After a refresh there is no launcher fragment/header, only the browser session.
        state = await browser.get('/api/state')
        assert state.status_code == 200
        assert personal.runtime.browser_token not in state.text
        assert (await browser.post('/api/close', headers={'Origin': 'https://unrelated.invalid'})).status_code == 403
        assert (await browser.post('/api/close')).status_code == 403
        assert (await browser.post('/api/close', headers={'Origin': origin})).status_code == 200
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=origin) as stranger:
        assert (await stranger.get('/api/state')).status_code == 401
        assert (await stranger.post('/api/session')).status_code == 401


async def test_completed_film_resume_is_a_no_op_without_extra_ai_or_render(personal):
    ident = await submit(personal.runtime)
    await finish(personal.runtime)
    before = await personal.runtime.service.detail(ident, limit=2000)
    calls = len(personal.plan.calls)
    await personal.runtime.resume(ident)
    assert await personal.runtime.service.detail(ident, limit=2000) == before
    assert len(personal.plan.calls) == calls and len(personal.rendered) == 1


async def test_pausing_an_old_film_cannot_cancel_another_active_film(personal):
    old = await submit(personal.runtime)
    await finish(personal.runtime)
    personal.plan.block = asyncio.Event()
    current = await submit(personal.runtime)
    while len(personal.plan.calls) < 4:
        await asyncio.sleep(.01)
    with pytest.raises(Exception) as error:
        await personal.runtime.pause(old)
    assert getattr(error.value, 'code') == 'video_not_active'
    assert personal.runtime.active_id == current and personal.runtime.busy
    assert not personal.runtime.runner.done()
    await personal.runtime.pause(current)
    assert not personal.runtime.busy and personal.runtime.active_id is None
