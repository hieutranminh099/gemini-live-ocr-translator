"""Opt-in synthetic-media integration; never reads personal films or calls AI."""
import asyncio
import os
from pathlib import Path
import subprocess

import pytest

from app.personal_video import PersonalVideoRuntime
from app.video_media import discover_tools, probe_video


pytestmark = pytest.mark.skipif(os.environ.get('PERSONAL_REAL_MEDIA') != '1', reason='Opt-in native media integration')
MEDIA_ROOT = Path('C:/Users/AD/Documents/ChatGPT/App/.tools/video-runtime')


class SyntheticPlan:
    configured = True
    selected_model = 'synthetic-model'
    models = [dict(id='synthetic-model', name='Synthetic model')]
    calls = 0
    async def translate_rows(self, rows, source):
        self.calls += 1
        return {r['id']: 'Xin chào bạn.' for r in rows}, {}
    async def close(self):
        pass


async def native_workflow(tmp_path, *, burned):
    tools = discover_tools([MEDIA_ROOT])
    assert tools is not None
    srt = tmp_path/'source.zh.srt'
    srt.write_text('1\n00:00:00,000 --> 00:00:03,800\n你好朋友\n', encoding='utf-8-sig')
    source = tmp_path/('burned.mp4' if burned else 'text.mkv')
    command = [str(tools.ffmpeg), '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
        '-i', 'color=c=0x142035:s=640x360:r=12:d=4']
    if burned:
        escaped = str(srt.resolve()).replace('\\', '/').replace(':', r'\:')
        command += ['-vf', "subtitles=filename='"+escaped+"':force_style='FontName=Microsoft YaHei,FontSize=30,MarginV=22'"]
    else:
        command += ['-i', str(srt), '-c:s', 'srt', '-metadata:s:s:0', 'language=chi']
    command += ['-c:v', 'libx264', '-threads', '2', '-pix_fmt', 'yuv420p', str(source)]
    result = subprocess.run(command, capture_output=True, timeout=45)
    assert result.returncode == 0, result.stderr.decode(errors='replace')[-1000:]
    client = SyntheticPlan()
    runtime = PersonalVideoRuntime(tmp_path/'personal', client=client, media_roots=[MEDIA_ROOT])
    try:
        with source.open('rb') as film:
            async def reader(size):
                return film.read(size)
            jobs = await runtime.import_and_start(source.name, reader)
        async with asyncio.timeout(120):
            await runtime.runner
        job = await runtime.service.detail(jobs[0]['id'])
        assert job['status'] == 'completed', (job.get('error_code'), job.get('error'))
        assert job['progress']['translated'] > 0 and client.calls > 0
        assert {o['format'] for o in job['outputs']} == {'srt', 'mp4'}
        for row in job['segments']:
            assert row['quality'] == 'draft' and row['translation'] == 'Xin chào bạn.'
            assert 0 <= row['start_ms'] < row['end_ms'] <= 4250
        output = runtime.service._path(job['id'])/runtime.service._job(job['id'])['outputs']['mp4']
        metadata = await probe_video(output, tools)
        assert 3700 <= metadata.duration_ms <= 4500
        assert (metadata.width, metadata.height) == (640, 360)
        assert source.is_file() and source.stat().st_size > 0
        return job
    finally:
        await runtime.close()


async def test_native_text_subtitle_to_srt_and_mp4(tmp_path):
    await native_workflow(tmp_path, burned=False)


async def test_native_burned_chinese_ocr_to_srt_and_mp4(tmp_path):
    await native_workflow(tmp_path, burned=True)
