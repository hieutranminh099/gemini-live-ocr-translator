import asyncio
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest

import app.video_jobs as video_module
from app.models import Settings
from app.server import Runtime, admin_app
from app.video_media import Toolchain
from test_video_jobs import FakeVideoApi, metadata, source_segments


@pytest.fixture
async def video_runtime(tmp_path, monkeypatch):
    runtime = Runtime(tmp_path, FakeVideoApi())
    runtime.store.set('settings', Settings(requests_per_minute=120).model_dump(exclude={'api_key'}))
    runtime.video_jobs.tools = Toolchain(tmp_path / 'ffmpeg.exe', tmp_path / 'ffprobe.exe')
    env = SimpleNamespace(runtime=runtime, rows=source_segments(43), probed=[], extracted=[])

    async def probe(path, tools, **kwargs):
        env.probed.append(Path(path))
        if kwargs.get('wait_ready'):
            await kwargs['wait_ready']()
        return metadata()

    async def extract(path, index, tools, **kwargs):
        env.extracted.append(index)
        if kwargs.get('wait_ready'):
            await kwargs['wait_ready']()
        return list(env.rows)

    monkeypatch.setattr(video_module, 'probe_video', probe)
    monkeypatch.setattr(video_module, 'extract_subtitle_stream', extract)
    static = Path(__file__).resolve().parents[1] / 'windows-server' / 'static'
    env.transport = httpx.ASGITransport(app=admin_app(runtime, static))
    env.headers = {'Authorization': 'Bearer ' + runtime.admin_token}
    yield env
    await runtime.video_jobs.close()
    await runtime.subtitle_jobs.close()
    await runtime.manager.close()
    runtime.store.close()


def client(env, *, authenticated=True):
    return httpx.AsyncClient(transport=env.transport, base_url='http://127.0.0.1',
                            headers=env.headers if authenticated else {})


async def import_project(http, name='Hội thoại & ký tự [1].mkv'):
    response = await http.post('/api/video/import', files={'file': (name, b'synthetic-local-video-0123456789', 'video/x-matroska')})
    assert response.status_code == 200, response.text
    return response.json()


async def translate_project(env, http, project, **options):
    response = await http.post('/api/video/jobs/' + project['id'] + '/start', json=options)
    assert response.status_code == 200, response.text
    async with asyncio.timeout(3):
        await asyncio.shield(env.runtime.video_jobs.task)
    detail = await http.get('/api/video/jobs/' + project['id'] + '?limit=2000')
    assert detail.status_code == 200
    assert detail.json()['status'] == 'ready', detail.text
    return detail.json()


async def test_unauthorized_video_and_memory_controls_never_probe_or_call_ai(video_runtime):
    env = video_runtime
    async with client(env, authenticated=False) as http:
        for method, url, body in [
            ('GET', '/api/video/capabilities', None),
            ('GET', '/api/video/jobs', None),
            ('GET', '/api/video/jobs/' + '0' * 24, None),
            ('POST', '/api/video/jobs/' + '0' * 24 + '/start', {}),
            ('POST', '/api/video/jobs/' + '0' * 24 + '/export', {'format': 'srt'}),
            ('PUT', '/api/video/jobs/' + '0' * 24 + '/timeline', {'segments': [{'id': 'x'}]}),
            ('GET', '/api/memory', None),
            ('PUT', '/api/memory/settings', {'enabled': True}),
            ('DELETE', '/api/memory/projects/example', None),
        ]:
            assert (await http.request(method, url, json=body)).status_code == 401
        uploaded = await http.post('/api/video/import', files={'file': ('x.mkv', b'not authorized', 'video/x-matroska')})
        assert uploaded.status_code == 401
    assert not env.probed and not env.runtime.provider.calls
    assert env.runtime.video_jobs.list_jobs() == []
    assert env.runtime.manager.settings().memory_enabled is False


async def test_ui_import_probe_streams_and_pagination_contract(video_runtime):
    env = video_runtime
    async with client(env) as http:
        caps = (await http.get('/api/video/capabilities')).json()
        assert caps['ffmpeg'] is True and 'ocr' in caps and 'speech' in caps
        project = await import_project(http)
        assert project['name'] == 'Hội thoại & ký tự [1].mkv'
        assert project['status'] == 'imported' and 'preview_url' in project
        assert project['metadata']['streams'][2]['is_text'] is True
        assert not env.runtime.provider.calls
        await translate_project(env, http, project, source='zh', method='auto', mode='fast', private=True)
        detail = await http.get('/api/video/jobs/' + project['id'] + '?offset=40&limit=40')
        data = detail.json()
        assert data['total_segments'] == 43 and data['segment_offset'] == 40 and data['segment_limit'] == 40
        assert len(data['segments']) == 3 and data['segments'][0]['id'] == 'cue-40'
        assert all({'id', 'start_ms', 'end_ms', 'text', 'translation', 'quality', 'source_type'} <= s.keys() for s in data['segments'])
        assert len(env.runtime.provider.calls) == 5
        listed = (await http.get('/api/video/jobs')).json()
        assert len(listed) == 1 and listed[0]['progress']['translated'] == 43
        assert 'segments' not in listed[0] and 'input' not in listed[0] and 'namespace' not in listed[0]
        for query in ('offset=-1', 'limit=0', 'limit=2001'):
            assert (await http.get('/api/video/jobs/' + project['id'] + '?' + query)).status_code == 400


async def test_media_source_range_requires_project_capability_and_not_admin_key(video_runtime):
    env = video_runtime
    async with client(env) as admin:
        first = await import_project(admin)
        second = await import_project(admin)
    async with client(env, authenticated=False) as http:
        source = await http.get(first['preview_url'], headers={'Range': 'bytes=0-8'})
        assert source.status_code == 206 and source.content == b'synthetic'
        assert source.headers['content-range'].startswith('bytes 0-8/')
        assert source.headers['accept-ranges'] == 'bytes'
        assert 'inline' in source.headers['content-disposition']
        wrong = first['preview_url'].replace(first['id'], second['id'])
        assert (await http.get(wrong)).status_code == 403
        assert (await http.get('/api/video/files/' + first['id'] + '/source')).status_code == 403
        assert (await http.get('/api/video/files/' + first['id'] + '/source', params={'token': env.runtime.admin_token})).status_code == 403
        token = parse_qs(urlsplit(first['preview_url']).query)['token'][0]
        assert (await http.get('/api/video/files/' + first['id'] + '/project.json', params={'token': token})).status_code == 404
        assert (await http.get(first['preview_url'], headers={'Range': 'bytes=999999-'})).status_code == 416


async def test_ui_timeline_partial_update_merges_and_overlap_is_atomic(video_runtime):
    env = video_runtime
    async with client(env) as http:
        project = await import_project(http)
        result = await translate_project(env, http, project)
        endpoint = '/api/video/jobs/' + project['id'] + '/timeline'
        response = await http.put(endpoint, json={'segments': [{'id': 'cue-40', 'translation': 'Bản do người dùng xác nhận.', 'quality': 'user_approved'}]})
        assert response.status_code == 200
        current = (await http.get('/api/video/jobs/' + project['id'] + '?offset=40&limit=40')).json()
        assert current['segments'][0]['translation'] == 'Bản do người dùng xác nhận.'
        assert current['segments'][0]['quality'] == 'user_approved'
        assert current['segments'][1] == result['segments'][41]
        overlap = await http.put(endpoint, json={'segments': [{'id': 'cue-40', 'start_ms': 39000}]})
        assert overlap.status_code == 400
        unchanged = (await http.get('/api/video/jobs/' + project['id'] + '?offset=40&limit=40')).json()
        assert unchanged['segments'] == current['segments']
        missing = await http.put(endpoint, json={'segments': [{'id': 'different-project', 'translation': 'No'}]})
        assert missing.status_code == 400


async def test_source_edit_invalidates_old_translation_until_user_reconfirms(video_runtime):
    env = video_runtime
    env.rows = source_segments(1)
    async with client(env) as http:
        project = await import_project(http)
        result = await translate_project(env, http, project)
        old = result['segments'][0]
        response = await http.put('/api/video/jobs/' + project['id'] + '/timeline', json={'segments': [dict(old, text='文字已修改', quality='draft')]})
        assert response.status_code == 200
        changed = response.json()['segments'][0]
        assert changed['translation'] == '' and changed['quality'] == 'draft'


async def test_ui_replace_all_count_and_download_urls_do_not_expose_admin_token(video_runtime):
    env = video_runtime
    env.rows = source_segments(3)
    async with client(env) as http:
        project = await import_project(http)
        await translate_project(env, http, project)
        base = '/api/video/jobs/' + project['id']
        response = await http.post(base + '/replace', json={'search': 'VI:', 'replacement': 'Xin chào: '})
        assert response.status_code == 200 and response.json()['changed'] == 3
        no_match = await http.post(base + '/replace', json={'search': 'not present', 'replacement': ''})
        assert no_match.json()['changed'] == 0
        for fmt in ('srt', 'ass'):
            response = await http.post(base + '/export', json={'format': fmt, 'style': {'font_name': 'Arial', 'position': 'middle', 'font_size': 40}, 'bilingual': True, 'quality': 'balanced'})
            assert response.status_code == 200, response.text
            output = next(o for o in response.json()['outputs'] if o['format'] == fmt)
            assert env.runtime.admin_token not in output['url']
            async with client(env, authenticated=False) as downloader:
                data = await downloader.get(output['url'])
                assert data.status_code == 200
                assert 'attachment' in data.headers['content-disposition']
                assert 'Xin chào:' in data.content.decode('utf-8-sig')


async def test_options_and_export_validation_do_not_start_ai(video_runtime):
    env = video_runtime
    async with client(env) as http:
        project = await import_project(http)
        base = '/api/video/jobs/' + project['id']
        for payload, status in [({'source': 'vi'}, 422), ({'method': 'arbitrary'}, 422),
                                ({'private': True, 'save_memory': True}, 400),
                                ({'model': 'not-configured'}, 400), ({'unknown': True}, 422)]:
            assert (await http.post(base + '/start', json=payload)).status_code == status
        assert (await http.post(base + '/export', json={'format': 'exe'})).status_code == 422
        assert (await http.post(base + '/export', json={'format': 'srt'})).status_code == 400
        assert (await http.put(base + '/timeline', json={'segments': []})).status_code == 422
        assert not env.runtime.provider.calls


async def test_memory_opt_in_edit_approval_and_project_delete_preserve_other_settings(video_runtime):
    env = video_runtime
    env.rows = source_segments(1)
    async with client(env) as http:
        before = env.runtime.manager.settings()
        assert (await http.get('/api/memory/settings')).json() == {'enabled': False}
        assert (await http.put('/api/memory/settings', json={'enabled': True})).status_code == 200
        assert env.runtime.manager.settings().memory_enabled is True
        assert env.runtime.manager.settings().fast_model == before.fast_model
        project = await import_project(http)
        await translate_project(env, http, project, private=False, save_memory=True)
        await env.runtime.manager.memory.flush()
        entries = (await http.get('/api/memory', params={'source': 'zh', 'project_id': project['id']})).json()
        assert len(entries) == 1 and entries[0]['quality'] == 'draft'
        ident = entries[0]['content_hash']
        reject = await http.patch('/api/memory/' + ident, json={'translation': 'Không tự xác minh.', 'quality': 'verified'})
        assert reject.status_code == 422
        approved = await http.patch('/api/memory/' + ident, json={'translation': 'Bản dịch được xác nhận.', 'quality': 'user_approved'})
        assert approved.status_code == 200
        entry = (await http.get('/api/memory', params={'quality': 'user_approved'})).json()[0]
        assert entry['translation'] == 'Bản dịch được xác nhận.'
        assert (await http.get('/api/memory', params={'quality': 'forged'})).status_code == 400
        assert (await http.delete('/api/memory/projects/' + project['id'])).status_code == 200
        assert (await http.get('/api/memory')).json() == []
        assert project['id'] in env.runtime.video_jobs.jobs


async def test_private_video_even_approved_never_persists_to_global_memory(video_runtime):
    env = video_runtime
    env.rows = source_segments(1)
    async with client(env) as http:
        await http.put('/api/memory/settings', json={'enabled': True})
        project = await import_project(http)
        await translate_project(env, http, project, private=True, save_memory=False)
        response = await http.put('/api/video/jobs/' + project['id'] + '/timeline', json={'segments': [{'id': 'cue-0', 'translation': 'Riêng tư, đã xác nhận.', 'quality': 'user_approved'}]})
        assert response.status_code == 200
        await env.runtime.manager.memory.flush()
        assert (await http.get('/api/memory')).json() == []
        assert env.runtime.store.cache_size() == 0


async def test_delete_project_revokes_media_url_and_preserves_other_project(video_runtime):
    env = video_runtime
    async with client(env) as http:
        first = await import_project(http)
        second = await import_project(http)
        assert (await http.delete('/api/video/jobs/' + first['id'])).status_code == 200
        assert (await http.get('/api/video/jobs/' + first['id'])).status_code == 404
        assert (await http.get('/api/video/jobs/' + second['id'])).status_code == 200
    async with client(env, authenticated=False) as http:
        assert (await http.get(first['preview_url'])).status_code == 404
        assert (await http.get(second['preview_url'])).status_code == 200
