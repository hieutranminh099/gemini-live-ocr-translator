"""Local, bounded FFmpeg operations for the optional video workflow.

All processes use argument arrays (never a shell), and rendering writes a new
file. The original video is never modified. Callers own access checks and the
job directory; no URL or arbitrary executable is accepted from an HTTP client.
"""
from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass, fields
from fractions import Fraction
import html
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import tempfile
from typing import Awaitable, Callable

from .subtitle_import import parse_subtitle_file

Ready = Callable[[], Awaitable[None]]
Progress = Callable[[float], Awaitable[None]]
MAX_TOOL_OUTPUT = 8 * 1024 * 1024
TEXT_SUBTITLE_CODECS = frozenset({'subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text', 'microdvd', 'sami', 'realtext', 'jacosub', 'subviewer', 'subviewer1', 'vplayer', 'pjs', 'mpl2'})
INPUT_FORMATS = 'mov,matroska,webm,avi,mpegts,mpeg,asf,flv,ogg'


class VideoMediaError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class VideoCancelled(VideoMediaError):
    def __init__(self):
        super().__init__('cancelled', 'Đã hủy xử lý video.')


@dataclass(frozen=True)
class Toolchain:
    ffmpeg: Path
    ffprobe: Path


def discover_tools(search_roots=()) -> Toolchain | None:
    """Only deployment-owned directories and PATH; never download tools."""
    extension = '.exe' if os.name == 'nt' else ''
    for root in search_roots:
        root = Path(root)
        for directory in (root, root / 'bin', root / 'ffmpeg' / 'bin'):
            ffmpeg, ffprobe = directory / ('ffmpeg' + extension), directory / ('ffprobe' + extension)
            if ffmpeg.is_file() and ffprobe.is_file():
                return Toolchain(ffmpeg.resolve(), ffprobe.resolve())
    ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
    return Toolchain(Path(ffmpeg), Path(ffprobe)) if ffmpeg and ffprobe else None


@dataclass(frozen=True)
class VideoSegment:
    id: str
    start_ms: int
    end_ms: int
    text: str
    language: str = ''
    confidence: float | None = None
    source_type: str = 'SUBTITLE'
    translation: str = ''

    def as_dict(self):
        return asdict(self)


@dataclass(frozen=True)
class VideoMetadata:
    duration_ms: int
    width: int
    height: int
    fps: float
    streams: tuple[dict, ...]
    size_bytes: int
    format_name: str
    warnings: tuple[str, ...] = ()
    video_stream_index: int = 0

    def as_dict(self):
        return asdict(self)


def _input(path: Path | str) -> Path:
    path = Path(path).resolve()
    if not path.is_file():
        raise VideoMediaError('video_missing', 'Không tìm thấy video nguồn trong dự án.')
    return path


async def _ready(wait_ready=None, cancel_event=None):
    if cancel_event is not None and cancel_event.is_set():
        raise VideoCancelled()
    if wait_ready is not None:
        await wait_ready()
    if cancel_event is not None and cancel_event.is_set():
        raise VideoCancelled()


async def stop_process(process):
    if process.returncode is None:
        try:
            process.terminate()
        except ProcessLookupError:
            return
        try:
            await asyncio.wait_for(process.wait(), 3)
        except asyncio.TimeoutError:
            process.kill()
            await process.wait()


def _suspend_process(process, suspend):
    """Pause only the child process created by this module, never another app."""
    if process.returncode is not None:
        return
    if os.name != 'nt':
        os.kill(process.pid, signal.SIGSTOP if suspend else signal.SIGCONT)
        return
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x0800, False, process.pid)  # PROCESS_SUSPEND_RESUME
    if not handle:
        raise VideoMediaError('pause_failed', 'Không thể tạm dừng tiến trình video để ưu tiên Live OCR.')
    try:
        ntdll = ctypes.WinDLL('ntdll')
        call = ntdll.NtSuspendProcess if suspend else ntdll.NtResumeProcess
        call.argtypes, call.restype = [wintypes.HANDLE], wintypes.LONG
        if call(handle) != 0 and process.returncode is None:
            raise VideoMediaError('pause_failed', 'Không thể thay đổi trạng thái tiến trình video.')
    finally:
        kernel.CloseHandle(handle)


async def wait_process_ready(process, wait_ready=None, cancel_event=None):
    """Suspend CPU consumption while the scheduler gate waits; always resume."""
    if wait_ready is None:
        return await _ready(cancel_event=cancel_event)
    task = asyncio.create_task(_ready(wait_ready, cancel_event))
    suspended = False
    try:
        await asyncio.wait({task}, timeout=.02)
        if not task.done() and process.returncode is None:
            _suspend_process(process, True)
            suspended = True
        while not task.done():
            await _ready(cancel_event=cancel_event)
            await asyncio.wait({task}, timeout=.1)
        await task
    finally:
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        if suspended:
            try:
                _suspend_process(process, False)
            except (OSError, VideoMediaError):
                await stop_process(process)


def _process_options():
    # CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS affects this owned child
    # only; it never changes system-wide or Live OCR process priorities.
    return {'creationflags': 0x08004000} if os.name == 'nt' else {}


async def _bounded_read(reader, limit):
    chunks, size = [], 0
    while chunk := await reader.read(65536):
        size += len(chunk)
        if size > limit:
            raise VideoMediaError('tool_output_limit', 'Dữ liệu phụ đề hoặc metadata vượt giới hạn an toàn.')
        chunks.append(chunk)
    return b''.join(chunks)


async def run_tool(arguments, *, cwd=None, cancel_event=None, wait_ready=None, limit=MAX_TOOL_OUTPUT, timeout=120):
    """Bound stdout/stderr and stop a child on cancellation, failure or timeout."""
    await _ready(wait_ready, cancel_event)
    try:
        process = await asyncio.create_subprocess_exec(*map(str, arguments), cwd=cwd,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE, **_process_options())
    except (OSError, NotImplementedError) as error:
        raise VideoMediaError('tool_unavailable', 'Không chạy được FFmpeg/ffprobe. Kiểm tra bộ công cụ video đã cài.') from error
    async def collect():
        out, err = await asyncio.gather(_bounded_read(process.stdout, limit), _bounded_read(process.stderr, 256 * 1024))
        await process.wait()
        return out, err
    task = asyncio.create_task(collect())
    try:
        deadline = asyncio.get_running_loop().time() + timeout
        while not task.done():
            gate_started = asyncio.get_running_loop().time()
            await wait_process_ready(process, wait_ready, cancel_event)
            deadline += asyncio.get_running_loop().time() - gate_started
            if asyncio.get_running_loop().time() > deadline:
                raise VideoMediaError('tool_timeout', 'Công cụ video xử lý quá thời gian cho phép.')
            await asyncio.wait({task}, timeout=.1)
        output, error_output = await task
        if process.returncode:
            # Do not return FFmpeg's absolute paths or arbitrary input text.
            raise VideoMediaError('invalid_media', 'FFmpeg không đọc được nội dung hoặc định dạng video/phụ đề này.')
        return output
    finally:
        await stop_process(process)
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


def _finite(value, default=0.):
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (ValueError, TypeError):
        return default


async def probe_video(path, tools: Toolchain, *, cancel_event=None, wait_ready=None) -> VideoMetadata:
    path = _input(path)
    raw = await run_tool([tools.ffprobe, '-v', 'error', '-protocol_whitelist', 'file,pipe',
                         '-format_whitelist', INPUT_FORMATS,
                         '-show_format', '-show_streams', '-of', 'json', path], cancel_event=cancel_event, wait_ready=wait_ready)
    try:
        result = json.loads(raw)
    except (ValueError, UnicodeError):
        raise VideoMediaError('invalid_metadata', 'ffprobe trả metadata không hợp lệ.') from None
    source_streams = result.get('streams', [])
    videos = [s for s in source_streams if s.get('codec_type') == 'video' and not s.get('disposition', {}).get('attached_pic')]
    if not videos:
        raise VideoMediaError('no_video_stream', 'Tệp không có luồng hình ảnh video.')
    video = videos[0]
    duration = _finite(result.get('format', {}).get('duration'))
    if duration <= 0:
        duration = max((_finite(s.get('duration')) for s in source_streams), default=0)
    if duration <= 0 or duration > 24 * 3600:
        raise VideoMediaError('invalid_duration', 'Video cần có thời lượng xác định, tối đa 24 giờ.')
    width, height = int(video.get('width', 0)), int(video.get('height', 0))
    if width < 2 or height < 2 or width > 16384 or height > 16384:
        raise VideoMediaError('invalid_dimensions', 'Kích thước video không hợp lệ hoặc vượt giới hạn 16K.')
    rotation = next((_finite(item.get('rotation')) for item in video.get('side_data_list', []) if 'rotation' in item),
                    _finite(video.get('tags', {}).get('rotate')))
    if round(abs(rotation)) % 180 == 90:
        width, height = height, width
    try:
        fps = float(Fraction(video.get('avg_frame_rate', '0/1')))
    except (ValueError, ZeroDivisionError):
        fps = 0.
    streams = []
    for stream in source_streams:
        kind = stream.get('codec_type', 'unknown')
        codec = stream.get('codec_name', 'unknown')
        streams.append(dict(index=int(stream['index']), type=kind, codec=codec,
            language=str(stream.get('tags', {}).get('language', ''))[:40],
            title=str(stream.get('tags', {}).get('title', ''))[:200],
            is_text=kind == 'subtitle' and codec in TEXT_SUBTITLE_CODECS,
            is_image=kind == 'subtitle' and codec not in TEXT_SUBTITLE_CODECS))
    return VideoMetadata(round(duration * 1000), width, height, fps, tuple(streams),
                         path.stat().st_size, str(result.get('format', {}).get('format_name', '')),
                         video_stream_index=int(video['index']))


async def extract_subtitle_stream(path, stream_index: int, tools: Toolchain, *,
                                  language='', wait_ready=None, cancel_event=None) -> list[VideoSegment]:
    path = _input(path)
    metadata = await probe_video(path, tools, cancel_event=cancel_event, wait_ready=wait_ready)
    stream = next((s for s in metadata.streams if s['index'] == stream_index and s['type'] == 'subtitle'), None)
    if not stream:
        raise VideoMediaError('subtitle_missing', 'Luồng phụ đề đã chọn không tồn tại.')
    if not stream['is_text']:
        raise VideoMediaError('image_subtitle', 'Luồng này là phụ đề dạng ảnh; bản hiện tại chưa OCR luồng ảnh. OCR vùng video chỉ đọc chữ đã ghi cứng trong hình.')
    await _ready(wait_ready, cancel_event)
    raw = await run_tool([tools.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
        '-protocol_whitelist', 'file,pipe', '-format_whitelist', INPUT_FORMATS, '-i', path, '-map', f'0:{int(stream_index)}',
        '-f', 'srt', '-c:s', 'srt', 'pipe:1'], cancel_event=cancel_event, wait_ready=wait_ready, limit=8 * 1024 * 1024)
    if not raw.strip():
        return []
    return parse_extracted_subtitles(raw, language=language or stream['language'])


def parse_extracted_subtitles(raw: bytes, *, language='') -> list[VideoSegment]:
    """Reuse the strict importer in bounded chunks, allowing a long video timeline.

    The file-import UI's 3,000-cue limit remains unchanged. FFmpeg-created SRT is
    split at complete cue boundaries, retaining every original timestamp.
    """
    if len(raw) > 8 * 1024 * 1024:
        raise VideoMediaError('subtitle_too_large', 'Phụ đề trích xuất vượt giới hạn 8 MiB.')
    try:
        text = raw.decode('utf-8-sig', errors='strict').replace('\r\n', '\n').replace('\r', '\n')
        blocks = re.split(r'\n[ \t]*\n', text.strip())
        if len(blocks) > 20000:
            raise VideoMediaError('too_many_segments', 'Tối đa 20.000 câu trong một dự án video.')
        result = []
        for start in range(0, len(blocks), 128):
            document = parse_subtitle_file('captions.srt', '\n\n'.join(blocks[start:start+128]))
            for cue in document.cues:
                result.append(VideoSegment(f'sub-{len(result)+1:06}', cue.start_ms, cue.end_ms,
                                           cue.text, language, 1., 'SUBTITLE'))
        return result
    except VideoMediaError:
        raise
    except (ValueError, UnicodeError) as error:
        raise VideoMediaError('invalid_subtitle', str(error)) from error


def _segments(segments):
    result = []
    for segment in segments:
        if isinstance(segment, dict):
            try:
                segment = VideoSegment(**{f.name: segment[f.name] for f in fields(VideoSegment) if f.name in segment})
            except (TypeError, KeyError):
                raise VideoMediaError('invalid_timeline', 'Một mục phụ đề thiếu ID, nội dung hoặc thời gian.') from None
        if not isinstance(segment, VideoSegment) or not isinstance(segment.text, str) or not isinstance(segment.translation, str):
            raise VideoMediaError('invalid_timeline', 'Dữ liệu câu phụ đề không hợp lệ.')
        if not isinstance(segment.start_ms, int) or not isinstance(segment.end_ms, int) or segment.start_ms < 0 or segment.end_ms <= segment.start_ms:
            raise VideoMediaError('invalid_timeline', 'Thời gian phụ đề phải hợp lệ và kết thúc sau khi bắt đầu.')
        if len(segment.text) > 4000 or len(segment.translation) > 8000:
            raise VideoMediaError('subtitle_too_long', 'Một câu phụ đề quá dài; hãy tách câu trước khi xuất.')
        if len(result) >= 20000:
            raise VideoMediaError('too_many_segments', 'Tối đa 20.000 câu trong một dự án video.')
        result.append(segment)
    return sorted(result, key=lambda s: (s.start_ms, s.end_ms))


def _display_text(segment, bilingual):
    if not segment.translation.strip():
        raise VideoMediaError('untranslated', 'Còn câu chưa có bản dịch; hãy hoàn tất hoặc chỉnh sửa trước khi xuất.')
    return (segment.text.strip() + '\n' if bilingual else '') + segment.translation.strip()


def _srt_time(ms):
    seconds, ms = divmod(ms, 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours:02}:{minutes:02}:{seconds:02},{ms:03}'


def export_srt(segments, *, bilingual=False) -> str:
    result = []
    for index, segment in enumerate(_segments(segments), 1):
        text = html.escape(_display_text(segment, bilingual), quote=False).replace('\r', '')
        result.append(f'{index}\n{_srt_time(segment.start_ms)} --> {_srt_time(segment.end_ms)}\n{text}\n')
    return '\n'.join(result)


@dataclass(frozen=True)
class SubtitleStyle:
    font: str = 'Arial'
    font_size: int = 40
    color: str = '#FFFFFF'
    outline_color: str = '#000000'
    outline: float = 2.
    shadow: float = 1.
    position: str = 'bottom'
    margin_v: int = 30
    background: bool = True
    background_opacity: float = .65
    cover_roi: tuple[float, float, float, float] | None = None
    line_chars: int = 42

    def validate(self):
        if not re.fullmatch(r'[\w .\-]{1,80}', self.font, re.UNICODE):
            raise VideoMediaError('invalid_style', 'Tên phông chữ không hợp lệ.')
        if not 12 <= self.font_size <= 160 or not 0 <= self.margin_v <= 1000 or not 0 <= self.outline <= 12 or not 0 <= self.shadow <= 12:
            raise VideoMediaError('invalid_style', 'Cỡ chữ, viền, bóng hoặc lề không hợp lệ.')
        if self.position not in ('bottom', 'top', 'center') or not 0 <= self.background_opacity <= 1 or not 15 <= self.line_chars <= 100:
            raise VideoMediaError('invalid_style', 'Vị trí hoặc nền phụ đề không hợp lệ.')
        for color in (self.color, self.outline_color):
            if not re.fullmatch(r'#[0-9a-fA-F]{6}', color):
                raise VideoMediaError('invalid_style', 'Màu phụ đề phải có dạng #RRGGBB.')
        if self.cover_roi is not None:
            validate_roi(self.cover_roi)


def validate_roi(roi):
    if len(roi) != 4 or any(not isinstance(v, (int, float)) or not math.isfinite(v) for v in roi):
        raise VideoMediaError('invalid_roi', 'Vùng phụ đề cần bốn tọa độ chuẩn hóa hữu hạn.')
    left, top, right, bottom = roi
    if not 0 <= left < right <= 1 or not 0 <= top < bottom <= 1 or right - left < .02 or bottom - top < .02:
        raise VideoMediaError('invalid_roi', 'Vùng phụ đề phải nằm trong video và có kích thước hợp lệ.')
    return tuple(float(v) for v in roi)


def _ass_color(rgb, opacity=1.):
    rgb = rgb.lstrip('#')
    alpha = round((1 - opacity) * 255)
    return f'&H{alpha:02X}{rgb[4:6]}{rgb[2:4]}{rgb[0:2]}'


def _ass_time(ms):
    centiseconds = ms // 10
    seconds, centiseconds = divmod(centiseconds, 100)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours}:{minutes:02}:{seconds:02}.{centiseconds:02}'


def _wrap(text, count):
    lines = []
    for paragraph in text.splitlines():
        words, line = paragraph.split(), ''
        for word in words:
            if line and len(line) + len(word) + 1 > count:
                lines.append(line)
                line = ''
            # CJK has no spaces; split long spans without dropping characters.
            while len(word) > count:
                if line:
                    lines.append(line)
                    line = ''
                lines.append(word[:count])
                word = word[count:]
            line = (line + ' ' + word).strip()
        if line:
            lines.append(line)
    return r'\N'.join(line.replace('\\', '＼').replace('{', '｛').replace('}', '｝') for line in lines)


def export_ass(segments, *, width=1920, height=1080, style=None, bilingual=False) -> str:
    style = style or SubtitleStyle()
    style.validate()
    alignment = {'bottom': 2, 'center': 5, 'top': 8}[style.position]
    header = ('[Script Info]\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\n'
              f'PlayResX: {int(width)}\nPlayResY: {int(height)}\n\n[V4+ Styles]\n'
              'Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n'
              f'Style: Default,{style.font},{style.font_size},{_ass_color(style.color)},&H000000FF,{_ass_color(style.outline_color, style.background_opacity if style.background else 1.)},{_ass_color("#000000", style.background_opacity)},0,0,0,0,100,100,0,0,{3 if style.background else 1},{style.outline},{style.shadow},{alignment},30,30,{style.margin_v},1\n\n'
              '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n')
    rows = []
    for segment in _segments(segments):
        rows.append(f'Dialogue: 0,{_ass_time(segment.start_ms)},{_ass_time(segment.end_ms)},Default,,0,0,0,,{_wrap(_display_text(segment, bilingual), style.line_chars)}')
    return header + '\n'.join(rows) + '\n'


async def preview_frame(path, tools: Toolchain, timestamp_ms=0, *, width=960, cancel_event=None, wait_ready=None) -> bytes:
    if not isinstance(timestamp_ms, int) or timestamp_ms < 0 or not 64 <= width <= 1920:
        raise VideoMediaError('invalid_preview', 'Mốc thời gian hoặc độ rộng xem trước không hợp lệ.')
    metadata = await probe_video(path, tools, cancel_event=cancel_event, wait_ready=wait_ready)
    if timestamp_ms >= metadata.duration_ms:
        raise VideoMediaError('invalid_preview', 'Mốc xem trước nằm ngoài thời lượng video.')
    return await run_tool([tools.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
        '-ss', f'{timestamp_ms / 1000:.3f}', '-protocol_whitelist', 'file,pipe', '-format_whitelist', INPUT_FORMATS, '-i', _input(path),
        '-map', f'0:{metadata.video_stream_index}', '-frames:v', '1', '-vf', f'scale=w=min({int(width)}\\,iw):h=-2', '-threads', '1',
        '-f', 'image2pipe', '-vcodec', 'mjpeg', 'pipe:1'], cancel_event=cancel_event, wait_ready=wait_ready, limit=4 * 1024 * 1024)


async def render_video(input_path, output_path, segments, tools: Toolchain, *, style=None,
                       bilingual=False, quality='balanced', progress=None,
                       wait_ready=None, cancel_event=None) -> Path:
    input_path, output_path = _input(input_path), Path(output_path).resolve()
    if output_path == input_path or output_path.exists():
        raise VideoMediaError('output_exists', 'Hãy chọn tệp xuất mới; video nguồn và tệp hiện có được giữ nguyên.')
    if output_path.suffix.lower() != '.mp4' or quality not in ('balanced', 'fast', 'quality'):
        raise VideoMediaError('invalid_export', 'Chỉ hỗ trợ xuất MP4 và mức chất lượng đã định nghĩa.')
    style = style or SubtitleStyle()
    style.validate()
    metadata = await probe_video(input_path, tools, cancel_event=cancel_event, wait_ready=wait_ready)
    if metadata.width % 2 or metadata.height % 2:
        raise VideoMediaError('odd_dimensions', 'Video có kích thước lẻ; H.264 yuv420p cần kích thước chẵn. Hãy chuyển nguồn trước khi xuất.')
    ass = export_ass(segments, width=metadata.width, height=metadata.height, style=style, bilingual=bilingual)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    await _ready(wait_ready, cancel_event)
    # A fixed relative ASS filename avoids FFmpeg's Windows drive/colon filter escaping.
    with tempfile.TemporaryDirectory(prefix='.video-render-', dir=output_path.parent) as temporary:
        directory = Path(temporary)
        (directory / 'captions.ass').write_text(ass, encoding='utf-8-sig')
        temporary_output = directory / 'render.mp4'
        filters = []
        if style.cover_roi is not None:
            l, t, r, b = style.cover_roi
            filters.append(f'drawbox=x=iw*{l:.6f}:y=ih*{t:.6f}:w=iw*{r-l:.6f}:h=ih*{b-t:.6f}:color=black@{style.background_opacity:.3f}:t=fill')
        filters.append('ass=filename=captions.ass')
        audios = [s for s in metadata.streams if s['type'] == 'audio']
        preset, crf = {'fast': ('veryfast', '23'), 'balanced': ('medium', '20'), 'quality': ('slow', '18')}[quality]
        command = [tools.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
            '-protocol_whitelist', 'file,pipe', '-format_whitelist', INPUT_FORMATS, '-i', input_path, '-map', f'0:{metadata.video_stream_index}', '-map', '0:a?',
            '-vf', ','.join(filters), '-c:v', 'libx264', '-preset', preset, '-crf', crf,
            '-pix_fmt', 'yuv420p', '-fps_mode', 'passthrough', '-threads', '2']
        for index, audio in enumerate(audios):
            command += [f'-c:a:{index}', 'copy' if audio['codec'] == 'aac' else 'aac']
            if audio['codec'] != 'aac':
                command += [f'-b:a:{index}', '192k']
        command += ['-movflags', '+faststart', '-progress', 'pipe:1', '-nostats', temporary_output]
        process = None
        try:
            process = await asyncio.create_subprocess_exec(*map(str, command), cwd=directory,
                stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE, **_process_options())
            error_task = asyncio.create_task(_bounded_read(process.stderr, 256 * 1024))
            async def watch():
                while line := await process.stdout.readline():
                    if line.startswith(b'out_time_us=') and progress is not None:
                        try:
                            ratio = min(.999, max(0., int(line.split(b'=', 1)[1]) / (metadata.duration_ms * 1000)))
                        except ValueError:
                            continue
                        await progress(ratio)
                await process.wait()
                await error_task
            task = asyncio.create_task(watch())
            try:
                while not task.done():
                    await _ready(cancel_event=cancel_event)
                    await wait_process_ready(process, wait_ready, cancel_event)
                    await asyncio.wait({task}, timeout=.1)
                await task
                if process.returncode:
                    raise VideoMediaError('render_failed', 'FFmpeg không xuất được MP4. Kiểm tra bộ FFmpeg có libass, libx264 và phông Unicode.')
            finally:
                await stop_process(process)
                for pending in (task, error_task):
                    if not pending.done():
                        pending.cancel()
                await asyncio.gather(task, error_task, return_exceptions=True)
            await _ready(cancel_event=cancel_event)
            # Same-directory temporary file permits an atomic no-replace link.
            # Unlike POSIX rename, link can never overwrite an existing file.
            try:
                os.link(temporary_output, output_path)
            except FileExistsError:
                raise VideoMediaError('output_exists', 'Tệp đích đã tồn tại; hãy chọn tên khác.') from None
            except OSError as error:
                raise VideoMediaError('finalize_failed', 'Không hoàn tất được tệp xuất. Kiểm tra quyền ghi và ổ đĩa hỗ trợ hard link (NTFS).') from error
            temporary_output.unlink()
            if progress is not None:
                await progress(1.)
            return output_path
        except (OSError, NotImplementedError) as error:
            raise VideoMediaError('tool_unavailable', 'Không chạy được bộ xuất FFmpeg.') from error
        finally:
            if process is not None:
                await stop_process(process)
