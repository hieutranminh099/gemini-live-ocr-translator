import asyncio
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import sys
from unittest.mock import AsyncMock, patch

import pytest

from app.video_media import (INPUT_FORMATS, SubtitleStyle, Toolchain, VideoCancelled,
    VideoMediaError, VideoSegment, discover_tools, export_ass, export_srt,
    extract_subtitle_stream, preview_frame, probe_video, render_video, run_tool,
    validate_roi, wait_process_ready, parse_extracted_subtitles)
from app.video_ocr import OcrObservation, SubtitleAccumulator, ocr_video, iter_video_frames


def run(awaitable):
    return asyncio.run(awaitable)


def test_srt_unicode_timestamps_order_bilingual_and_escaping():
    segments = [VideoSegment('b', 2000, 3040, '再见', translation='Tạm biệt.'),
                VideoSegment('a', 150, 1600, '你好', translation='Xin chào <bạn>.')]
    output = export_srt(segments, bilingual=True)
    assert output.startswith('1\n00:00:00,150 --> 00:00:01,600\n你好\nXin chào &lt;bạn&gt;.')
    assert '00:00:02,000 --> 00:00:03,040' in output


def test_ass_never_executes_injected_override_and_wraps_unicode():
    segment = VideoSegment('a', 10, 1020, '你好', translation='Xin chào {\\pos(1,1)} và chúc bạn một ngày vui vẻ.')
    ass = export_ass([segment], style=SubtitleStyle(line_chars=20), bilingual=True)
    assert '0:00:00.01,0:00:01.02' in ass
    assert '{\\pos' not in ass
    assert '｛＼pos(1,1)｝' in ass
    assert r'\N' in ass
    assert 'PlayResX: 1920' in ass


@pytest.mark.parametrize('style', [SubtitleStyle(font='Arial,Injected'), SubtitleStyle(font_size=1),
    SubtitleStyle(color='red'), SubtitleStyle(position='bad'), SubtitleStyle(background_opacity=2)])
def test_invalid_style_rejected(style):
    with pytest.raises(VideoMediaError):
        export_ass([], style=style)


@pytest.mark.parametrize('roi', [(0, .5, 1, .5), (-1, 0, 1, 1), (0, 0, 2, 1), (0, 0, float('nan'), 1), (0, 0, .001, 1)])
def test_invalid_roi_rejected(roi):
    with pytest.raises(VideoMediaError):
        validate_roi(roi)


def test_untranslated_or_bad_timeline_is_not_exported():
    with pytest.raises(VideoMediaError, match='chưa có bản dịch'):
        export_srt([VideoSegment('a', 0, 1000, '你好')])
    with pytest.raises(VideoMediaError, match='Thời gian'):
        export_srt([VideoSegment('a', 1000, 0, '你好', translation='Xin chào')])


def test_accumulator_merges_continuous_frames_but_keeps_later_occurrences():
    accumulator = SubtitleAccumulator(language='zh')
    for timestamp, text in [(0, '你好'), (250, '你好'), (500, '你好'), (750, ''), (1000, ''),
                            (1250, '你好'), (1500, '再见'), (1750, '再见')]:
        accumulator.add(timestamp, OcrObservation(text, .95))
    segments = accumulator.finish(2000)
    assert [(s.start_ms, s.end_ms, s.text) for s in segments] == [(0, 750, '你好'), (1250, 1500, '你好'), (1500, 2000, '再见')]
    assert all(s.confidence == .95 and s.source_type == 'OCR' for s in segments)


def test_accumulator_one_missing_ocr_does_not_split_but_terminal_blank_ends_source():
    accumulator = SubtitleAccumulator()
    for timestamp, text in [(0, 'A'), (250, ''), (500, 'A'), (750, '')]:
        accumulator.add(timestamp, OcrObservation(text, .9))
    assert [(s.start_ms, s.end_ms) for s in accumulator.finish(1000)] == [(0, 750)]


def test_accumulator_does_not_fuzzy_merge_negations_or_numbers():
    accumulator = SubtitleAccumulator()
    for timestamp, text in [(0, '不要去'), (250, '要去'), (500, '12元'), (750, '13元')]:
        accumulator.add(timestamp, OcrObservation(text, .9))
    assert len(accumulator.finish(1000)) == 4


def test_accumulator_rejects_nonmonotonic_time_and_bounds_segments():
    accumulator = SubtitleAccumulator(max_segments=1)
    accumulator.add(200, OcrObservation('A', .9))
    with pytest.raises(VideoMediaError, match='tăng đều'):
        accumulator.add(199, OcrObservation('A', .9))
    accumulator.add(300, OcrObservation('B', .9))
    with pytest.raises(VideoMediaError, match='quá nhiều'):
        accumulator.finish(400)


def test_long_embedded_timeline_uses_existing_parser_without_3000_cue_truncation():
    from app.video_media import _srt_time
    raw = '\n\n'.join(f'{i+1}\n{_srt_time(i*1000)} --> {_srt_time(i*1000+800)}\n你好 {i}' for i in range(3500)).encode('utf-8')
    segments = parse_extracted_subtitles(raw, language='zh')
    assert len(segments) == 3500
    assert len({s.id for s in segments}) == 3500
    assert segments[-1].start_ms == 3499000
    assert segments[-1].text == '你好 3499'


def test_subtitle_image_stream_rejected_without_ffmpeg_ocr(tmp_path):
    source = tmp_path / 'video.mkv'
    source.touch()
    from app.video_media import VideoMetadata
    metadata = VideoMetadata(3000, 320, 180, 12, (dict(index=2, type='subtitle', is_text=False, language='zh'),), 0, 'matroska')
    async def scenario():
        with patch('app.video_media.probe_video', AsyncMock(return_value=metadata)), patch('app.video_media.run_tool', AsyncMock()) as tool:
            with pytest.raises(VideoMediaError, match='dạng ảnh'):
                await extract_subtitle_stream(source, 2, Toolchain(Path('ffmpeg'), Path('ffprobe')))
            tool.assert_not_called()
    run(scenario())


def test_probe_selects_real_video_and_display_rotation_and_restricts_demuxers(tmp_path):
    source = tmp_path / "nhạc & phim ' có dấu.mov"
    source.write_bytes(b'fixture')
    raw = json.dumps(dict(streams=[
        dict(index=0, codec_type='video', codec_name='mjpeg', width=100, height=100, disposition=dict(attached_pic=1)),
        dict(index=2, codec_type='video', codec_name='h264', width=720, height=1280,
             avg_frame_rate='30000/1001', side_data_list=[dict(rotation=90)]),
        dict(index=3, codec_type='subtitle', codec_name='hdmv_pgs_subtitle')],
        format=dict(duration='2.100', format_name='mov'))).encode()
    async def scenario():
        with patch('app.video_media.run_tool', AsyncMock(return_value=raw)) as tool:
            metadata = await probe_video(source, Toolchain(Path('ffmpeg'), Path('ffprobe')))
            assert (metadata.width, metadata.height, metadata.video_stream_index) == (1280, 720, 2)
            assert metadata.duration_ms == 2100
            assert metadata.streams[-1]['is_image'] and not metadata.streams[-1]['is_text']
            command = tool.call_args.args[0]
            assert command[-1] == source.resolve()
            assert command[command.index('-protocol_whitelist')+1] == 'file,pipe'
            assert command[command.index('-format_whitelist')+1] == INPUT_FORMATS
    run(scenario())


def test_priority_gate_suspends_owned_process_then_resumes_and_cancellation_cleans_up():
    class Process:
        returncode = None
    async def scenario():
        release, cancel = asyncio.Event(), asyncio.Event()
        with patch('app.video_media._suspend_process') as suspension:
            task = asyncio.create_task(wait_process_ready(Process(), release.wait, cancel))
            await asyncio.sleep(.05)
            assert suspension.call_args_list[0].args[1] is True
            release.set()
            await task
            assert suspension.call_args_list[-1].args[1] is False
        release.clear()
        with patch('app.video_media._suspend_process') as suspension:
            task = asyncio.create_task(wait_process_ready(Process(), release.wait, cancel))
            await asyncio.sleep(.05)
            cancel.set()
            with pytest.raises(VideoCancelled):
                await task
            assert suspension.call_args_list[-1].args[1] is False
    run(scenario())


def test_real_owned_child_stops_work_while_priority_gate_is_closed(tmp_path):
    """Real process suspension; this does not claim a Live OCR latency benchmark."""
    from app.video_media import _process_options, stop_process
    async def scenario():
        counter = tmp_path / 'counter.txt'
        code = 'import pathlib,sys,time\np=pathlib.Path(sys.argv[1])\nfor i in range(500):\n p.write_text(str(i));time.sleep(.02)'
        process = await asyncio.create_subprocess_exec(sys.executable, '-c', code, str(counter),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, **_process_options())
        gate = asyncio.Event()
        task = None
        try:
            # The child is below normal priority; parallel packaging/antivirus
            # can delay its first instruction. Wait for readiness, not 2 s of
            # assumed startup speed, before measuring actual suspension.
            deadline = asyncio.get_running_loop().time() + 15
            while asyncio.get_running_loop().time() < deadline:
                if counter.exists() and counter.read_text().strip():
                    break
                await asyncio.sleep(.02)
            assert counter.exists(), 'Generated child must start before checking suspension.'
            task = asyncio.create_task(wait_process_ready(process, gate.wait))
            await asyncio.sleep(.08)
            frozen = counter.read_text()
            await asyncio.sleep(.12)
            assert counter.read_text() == frozen
            gate.set()
            await task
            await asyncio.sleep(.10)
            assert int(counter.read_text()) > int(frozen)
        finally:
            if task is not None and not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            await stop_process(process)
    run(scenario())


TOOLS = discover_tools([Path(__file__).resolve().parents[1] / '.tools' / 'video-runtime' / 'ffmpeg'])


@pytest.fixture
def tiny_video(tmp_path):
    if TOOLS is None:
        pytest.skip('Portable FFmpeg/ffprobe are not installed; no real media test claimed.')
    directory = tmp_path / "Tiếng Việt & khoảng trắng ' thử"
    directory.mkdir()
    subtitle = directory / 'phụ đề.srt'
    subtitle.write_text('1\n00:00:00,400 --> 00:00:01,300\n你好世界\n\n2\n00:00:01,600 --> 00:00:02,600\n再见世界\n', encoding='utf-8')
    video = directory / 'nguồn & video.mkv'
    run(run_tool([TOOLS.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
        '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=12', '-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=44100',
        '-i', subtitle, '-t', '3', '-map', '0:v:0', '-map', '1:a:0', '-map', '2:s:0',
        '-c:v', 'libx264', '-preset', 'ultrafast', '-threads', '1', '-c:a', 'aac', '-c:s', 'srt', video]))
    return video


def test_real_embedded_subtitles_extracted_directly_without_ocr(tiny_video):
    async def scenario():
        with patch('app.video_ocr.RapidOcrRecognizer', side_effect=AssertionError('OCR must not run')):
            metadata = await probe_video(tiny_video, TOOLS)
            stream = next(s for s in metadata.streams if s['type'] == 'subtitle')
            assert stream['is_text'] and not stream['is_image']
            segments = await extract_subtitle_stream(tiny_video, stream['index'], TOOLS, language='zh')
            assert [s.text for s in segments] == ['你好世界', '再见世界']
            # Some FFmpeg builds shift the mux timeline by AAC encoder delay.
            assert all(abs(actual.start_ms - expected) <= 60 for actual, expected in zip(segments, (400, 1600)))
            assert [s.end_ms-s.start_ms for s in segments] == [900, 1000]
            assert all(s.confidence == 1 for s in segments)
    run(scenario())


def test_real_export_preserves_source_audio_fps_and_duration_with_unicode_path(tiny_video):
    async def scenario():
        original_hash = hashlib.sha256(tiny_video.read_bytes()).hexdigest()
        before = await probe_video(tiny_video, TOOLS)
        segments = [VideoSegment('one', 400, 1300, '你好世界', translation='Xin chào thế giới.'),
                    VideoSegment('two', 1600, 2600, '再见世界', translation='Tạm biệt thế giới.')]
        output = tiny_video.parent / "xuất phim tiếng Việt & '.mp4"
        progress = []
        async def record(value):
            progress.append(value)
        await render_video(tiny_video, output, segments, TOOLS, style=SubtitleStyle(font_size=20, margin_v=15), quality='fast', progress=record)
        after = await probe_video(output, TOOLS)
        assert after.width == before.width and after.height == before.height and after.fps == before.fps
        assert abs(after.duration_ms - before.duration_ms) <= 100
        assert next(s['codec'] for s in after.streams if s['type'] == 'video') == 'h264'
        assert next(s['codec'] for s in after.streams if s['type'] == 'audio') == 'aac'
        assert progress[-1] == 1. and progress == sorted(progress)
        audio = []
        for source in (tiny_video, output):
            audio.append(await run_tool([TOOLS.ffmpeg, '-v', 'error', '-i', source, '-map', '0:a:0', '-c:a', 'copy', '-f', 'adts', 'pipe:1']))
        assert audio[0] == audio[1], 'AAC packets must be copied, not reencoded.'
        assert hashlib.sha256(tiny_video.read_bytes()).hexdigest() == original_hash
        preview = await preview_frame(output, TOOLS, 900, width=320)
        assert preview.startswith(b'\xff\xd8') and len(preview) > 1000
        (tiny_video.parent / 'render-preview.jpg').write_bytes(preview)
        # Burn-in must change subtitle pixels during the cue, not merely mux an
        # unused ASS stream. Compare against source while allowing H264 noise.
        import io
        import numpy as np
        from PIL import Image
        source_preview = await preview_frame(tiny_video, TOOLS, 900, width=320)
        source_pixels = np.asarray(Image.open(io.BytesIO(source_preview))).astype('float32')
        output_pixels = np.asarray(Image.open(io.BytesIO(preview))).astype('float32')
        difference = np.abs(source_pixels - output_pixels).mean(axis=2)
        assert difference[-65:].mean() > difference[:80].mean() + 2
        with pytest.raises(VideoMediaError, match='tệp xuất mới'):
            await render_video(tiny_video, output, segments, TOOLS)
    run(scenario())


def test_real_ocr_frame_pts_bounded_and_cancelled_before_decode(tiny_video):
    async def scenario():
        frames = []
        async for frame in iter_video_frames(tiny_video, TOOLS, roi=(0, .5, 1, 1), sample_ms=250):
            assert len(frame.rgb) == frame.width * frame.height * 3
            frames.append(frame.timestamp_ms)
        assert 10 <= len(frames) <= 13
        assert frames == sorted(set(frames))
        assert all(b-a >= 249 for a, b in zip(frames, frames[1:]))
        cancel = asyncio.Event()
        cancel.set()
        with pytest.raises(VideoCancelled):
            await ocr_video(tiny_video, TOOLS, recognizer=lambda _: OcrObservation('A', .9), cancel_event=cancel)
    run(scenario())


def test_real_decode_ocr_pipeline_merges_consecutive_results(tiny_video):
    async def scenario():
        def recognize(frame):
            return OcrObservation('你好' if frame.timestamp_ms < 1000 else '再见' if frame.timestamp_ms < 2000 else '', .9)
        segments = await ocr_video(tiny_video, TOOLS, recognizer=recognize, roi=(0, .5, 1, 1))
        assert [s.text for s in segments] == ['你好', '再见']
        assert 990 <= segments[0].end_ms <= 1100
        assert 1990 <= segments[1].end_ms <= 2100
    run(scenario())


def test_real_burned_in_chinese_ocr_timing_no_duplicate_cues(tmp_path):
    """Test B uses real FFmpeg + RapidOCR, not the deterministic recognizer above."""
    if TOOLS is None:
        pytest.skip('Portable FFmpeg/ffprobe are not installed.')
    pytest.importorskip('rapidocr_onnxruntime')
    Image = pytest.importorskip('PIL.Image')
    ImageDraw = pytest.importorskip('PIL.ImageDraw')
    ImageFont = pytest.importorskip('PIL.ImageFont')
    font_path = Path('C:/Windows/Fonts/msyh.ttc')
    if not font_path.is_file():
        pytest.skip('Chinese Unicode fixture font is unavailable.')
    directory = tmp_path / '字幕 OCR'
    directory.mkdir()
    font = ImageFont.truetype(str(font_path), 44)
    for second, text in enumerate(('你好世界', '', '再见世界')):
        image = Image.new('RGB', (960, 540), '#14242c')
        if text:
            ImageDraw.Draw(image).text((330, 380), text, font=font, fill='white', stroke_width=1)
        for frame in range(12):
            image.save(directory / f'f{second*12+frame:03}.png')
    source = directory / 'chinese.mp4'
    run(run_tool([TOOLS.ffmpeg, '-hide_banner', '-loglevel', 'error', '-framerate', '12',
                  '-i', directory/'f%03d.png', '-c:v', 'libx264', '-preset', 'ultrafast',
                  '-pix_fmt', 'yuv420p', '-threads', '1', source]))
    segments = run(ocr_video(source, TOOLS, language='zh', roi=(.05, .65, .95, .9), sample_ms=250))
    assert [s.text for s in segments] == ['你好世界', '再见世界']
    assert [(s.start_ms, s.end_ms) for s in segments] == [(0, 1000), (2000, 3000)]
    assert all(s.confidence > .95 for s in segments)
