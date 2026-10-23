import asyncio
from pathlib import Path

import httpx
import pytest

from app.server import Runtime, admin_app
from test_subtitle_jobs import FakeApi, frame, subtitle


@pytest.fixture
def runtime(tmp_path):
    state = Runtime(tmp_path, FakeApi())
    yield state
    state.store.close()


def transport(runtime):
    static = Path(__file__).resolve().parents[1] / 'windows-server' / 'static'
    return httpx.ASGITransport(app=admin_app(runtime, static))


async def test_subtitle_admin_routes_reject_unauthorized_actions_before_provider_call(runtime):
    body = dict(filename='test.srt', content=subtitle(['你好']))
    async with httpx.AsyncClient(transport=transport(runtime), base_url='http://127.0.0.1') as client:
        assert (await client.post('/api/subtitles/preview', json=body)).status_code == 401
        assert (await client.post('/api/subtitles/jobs', json=body)).status_code == 401
        assert (await client.get('/api/subtitles/jobs')).status_code == 401
        assert (await client.delete('/api/subtitles/jobs/unknown')).status_code == 401
    assert not runtime.provider.calls and runtime.store.usage()['requests'] == 0
    assert runtime.subtitle_jobs.list_jobs() == []
    await runtime.subtitle_jobs.close()


async def test_authenticated_preview_then_job_poll_and_live_cache_reuse(runtime):
    body = dict(filename='test.srt', content=subtitle(['你好', '再见', '你好']))
    headers = {'Authorization': 'Bearer ' + runtime.admin_token}
    async with httpx.AsyncClient(transport=transport(runtime), base_url='http://127.0.0.1', headers=headers) as client:
        preview = await client.post('/api/subtitles/preview', json=body)
        assert preview.status_code == 200
        assert preview.json()['cue_count'] == 3 and preview.json()['unique_text_count'] == 2
        assert not runtime.provider.calls and runtime.store.usage()['requests'] == 0
        start = await client.post('/api/subtitles/jobs', json=dict(body, source='zh', mode='fast', profile='game'))
        assert start.status_code == 200
        job_id = start.json()['job_id']
        async with asyncio.timeout(2):
            while True:
                response = await client.get('/api/subtitles/jobs')
                assert response.status_code == 200
                current = next(job for job in response.json() if job['job_id'] == job_id)
                if current['status'] not in ('queued', 'running'):
                    break
                await asyncio.sleep(.001)
        assert current['status'] == 'completed' and current['completed_cues'] == 3
        assert current['completed_batches'] == 1 and current['translated_items'] == 2
    assert len(runtime.provider.calls) == 1
    result = await runtime.manager.translate(frame(['你好', '再见']), 'real-android')
    assert all(block['cached'] for block in result['blocks'])
    assert len(runtime.provider.calls) == 1
    await runtime.subtitle_jobs.close()


async def test_invalid_upload_source_and_unknown_cancel_are_validation_errors_without_ai(runtime):
    headers = {'Authorization': 'Bearer ' + runtime.admin_token}
    body = dict(filename='test.srt', content=subtitle(['你好']))
    async with httpx.AsyncClient(transport=transport(runtime), base_url='http://127.0.0.1', headers=headers) as client:
        assert (await client.post('/api/subtitles/jobs', json=dict(body, source='vi'))).status_code == 422
        assert (await client.post('/api/subtitles/jobs', json=dict(body, unexpected='field'))).status_code == 422
        assert (await client.post('/api/subtitles/preview', json=dict(body, content='bad data'))).status_code == 400
        assert (await client.delete('/api/subtitles/jobs/no-such-job')).status_code == 404
    assert not runtime.provider.calls and runtime.store.usage()['requests'] == 0
    await runtime.subtitle_jobs.close()
