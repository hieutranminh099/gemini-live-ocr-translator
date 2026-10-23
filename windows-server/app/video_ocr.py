"""Incremental burned-in subtitle OCR with bounded frame memory and real PTS.

The recognizer is replaceable for deterministic tests. Optional RapidOCR runs
locally on one CPU thread; no video frame is sent to Gemini or another service.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import unicodedata

from .video_media import (INPUT_FORMATS, Toolchain, VideoCancelled, VideoMediaError,
    VideoSegment, _input, _process_options, _ready, probe_video, stop_process,
    validate_roi, wait_process_ready)


@dataclass(frozen=True)
class OcrObservation:
    text: str
    confidence: float


@dataclass(frozen=True)
class VideoFrame:
    timestamp_ms: int
    width: int
    height: int
    rgb: bytes


def ocr_available():
    try:
        import importlib.util
        return importlib.util.find_spec('rapidocr_onnxruntime') is not None
    except (ValueError, ImportError):
        return False


class RapidOcrRecognizer:
    """Lazy-loaded, local Chinese/English CPU recognizer; never downloads weights."""
    def __init__(self, language='zh', *, rec_model_path=None, rec_keys_path=None, model_roots=()):
        self.language=language
        if rec_model_path is None and language in ('ja', 'ko'):
            filename = {'ja': 'japan_PP-OCRv4_rec_mobile.onnx', 'ko': 'korean_PP-OCRv4_rec_mobile.onnx'}[language]
            for root in model_roots:
                for directory in (Path(root), Path(root) / 'ocr'):
                    if (directory / filename).is_file():
                        rec_model_path = directory / filename
                        break
                if rec_model_path is not None:
                    break
        if language not in ('zh', 'en') and rec_model_path is None:
            raise VideoMediaError('ocr_language_unavailable', 'OCR video hiện có mô hình Trung/Anh. Nhật/Hàn cần mô hình nhận dạng riêng; có thể dùng subtitle stream.')
        try:
            from rapidocr_onnxruntime import RapidOCR
            options = dict(det_use_cuda=False, rec_use_cuda=False, cls_use_cuda=False,
                           intra_op_num_threads=1, inter_op_num_threads=1)
            if rec_model_path is not None:
                if not Path(rec_model_path).is_file():
                    raise VideoMediaError('ocr_model_missing', 'Không tìm thấy mô hình OCR đã cấu hình.')
                options['rec_model_path'] = str(rec_model_path)
            if rec_keys_path is not None:
                options['rec_keys_path'] = str(rec_keys_path)
            self.engine = RapidOCR(**options)
        except ImportError as error:
            raise VideoMediaError('ocr_unavailable', 'Chưa có RapidOCR. Cài rapidocr_onnxruntime và onnxruntime CPU trong môi trường ứng dụng; không cần GPU.') from error

    def __call__(self, frame: VideoFrame) -> OcrObservation:
        import numpy as np
        # FFmpeg's raw bytes are RGB; RapidOCR's ndarray convention is BGR.
        image = np.frombuffer(frame.rgb, dtype=np.uint8).reshape(frame.height, frame.width, 3)[:, :, ::-1].copy()
        result, _ = self.engine(image)
        if not result:
            return OcrObservation('', 0.)
        lines = []
        for box, text, confidence in result:
            source_text=str(text).strip()
            source_pattern={'zh':r'[\u3400-\u9fff\uf900-\ufaff]','ja':r'[\u3040-\u30ff\u3400-\u9fff]','ko':r'[\uac00-\ud7af\u1100-\u11ff]'}.get(self.language)
            if source_text and (source_pattern is None or re.search(source_pattern,source_text)):
                lines.append((min(float(point[1]) for point in box), min(float(point[0]) for point in box), str(text).strip(), float(confidence)))
        lines.sort(key=lambda line: (round(line[0] / 15), line[1]))
        return OcrObservation(' '.join(line[2] for line in lines),
                              sum(line[3] for line in lines) / len(lines)) if lines else OcrObservation('', 0.)


def _identity(text):
    # Only typography/whitespace changes merge. Never fuzzy-match names, numbers,
    # negation or pronouns just to make a lower latency/duplicate statistic.
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFKC', text)).strip()


class SubtitleAccumulator:
    """Consecutive identical observations become one occurrence, not a global dedup.

    With two-empty-frame confirmation a momentary OCR failure does not split a
    cue. The cue still ends at the first blank PTS, so disappearance isn't held
    until the second blank. Timestamp accuracy is bounded by sampling interval.
    """
    def __init__(self, *, language='', min_confidence=.5, empty_frames=2, max_segments=20000):
        self.language, self.min_confidence = language, min_confidence
        self.empty_frames, self.max_segments = empty_frames, max_segments
        self.segments = []
        self.current = None
        self.blank_start = None
        self.blank_count = 0
        self.last_timestamp = -1

    def add(self, timestamp_ms: int, observation: OcrObservation):
        if timestamp_ms < self.last_timestamp or timestamp_ms < 0:
            raise VideoMediaError('invalid_pts', 'Mốc thời gian khung OCR không tăng đều.')
        self.last_timestamp = timestamp_ms
        text = _identity(observation.text) if observation.confidence >= self.min_confidence else ''
        if not text:
            if self.current is not None:
                self.blank_start = timestamp_ms if self.blank_start is None else self.blank_start
                self.blank_count += 1
                if self.blank_count >= self.empty_frames:
                    self._close(self.blank_start)
            return
        self.blank_start, self.blank_count = None, 0
        if self.current and self.current['key'] == text:
            self.current['confidence_sum'] += observation.confidence
            self.current['count'] += 1
            return
        if self.current:
            self._close(timestamp_ms)
        self.current = dict(key=text, text=text, start_ms=timestamp_ms,
                            confidence_sum=observation.confidence, count=1)

    def _close(self, end_ms):
        if self.current is not None:
            if len(self.segments) >= self.max_segments:
                raise VideoMediaError('too_many_segments', 'Video có quá nhiều câu; hãy chia thành các dự án nhỏ hơn.')
            if end_ms > self.current['start_ms']:
                self.segments.append(VideoSegment(f'ocr-{len(self.segments)+1:06}',
                    self.current['start_ms'], end_ms, self.current['text'], self.language,
                    round(self.current['confidence_sum'] / self.current['count'], 5), 'OCR'))
        self.current, self.blank_start, self.blank_count = None, None, 0

    def finish(self, duration_ms):
        self._close(self.blank_start if self.blank_start is not None else duration_ms)
        return list(self.segments)


async def iter_video_frames(path, tools: Toolchain, *, metadata=None, roi=(.05, .65, .95, .98),
                            sample_ms=250, max_width=1280, wait_ready=None, cancel_event=None):
    path = _input(path)
    if not 100 <= sample_ms <= 2000 or not 128 <= max_width <= 1920:
        raise VideoMediaError('invalid_sampling', 'Chu kỳ OCR cần 100–2.000 ms; độ rộng tối đa 128–1.920 px.')
    roi = validate_roi(roi)
    await _ready(wait_ready, cancel_event)
    metadata = metadata or await probe_video(path, tools, cancel_event=cancel_event, wait_ready=wait_ready)
    left, top, right, bottom = roi
    x, y = int(left * metadata.width), int(top * metadata.height)
    crop_w, crop_h = max(2, int((right-left)*metadata.width)), max(2, int((bottom-top)*metadata.height))
    width = min(max_width, crop_w)
    height = max(2, round(crop_h * width / crop_w))
    # select uses source frame presentation timestamps. Unlike fps=4 it does not
    # synthesize constant-rate frames or fabricate missing source timestamps.
    filters = (f"select='isnan(prev_selected_t)+gte(t-prev_selected_t,{sample_ms/1000:.6f})',"
               f'crop={crop_w}:{crop_h}:{x}:{y},scale={width}:{height},showinfo,format=rgb24')
    command = [tools.ffmpeg, '-hide_banner', '-loglevel', 'info', '-nostdin', '-threads', '1',
        '-protocol_whitelist', 'file,pipe', '-format_whitelist', INPUT_FORMATS, '-i', path,
        '-map', f'0:{metadata.video_stream_index}', '-an', '-sn', '-vf', filters, '-filter_threads', '1',
        '-fps_mode', 'passthrough', '-threads', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1']
    process = await asyncio.create_subprocess_exec(*map(str, command), stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, **_process_options())
    timestamps = asyncio.Queue(maxsize=8)
    async def read_timestamps():
        try:
            while line := await process.stderr.readline():
                if b'showinfo' in line:
                    match = re.search(rb'\bpts_time:([\-0-9.eE+]+)', line)
                    if match:
                        await timestamps.put(max(0, round(float(match.group(1)) * 1000)))
        finally:
            try:
                timestamps.put_nowait(None)
            except asyncio.QueueFull:
                pass
    reader = asyncio.create_task(read_timestamps())
    try:
        frame_size = width * height * 3
        while True:
            await wait_process_ready(process, wait_ready, cancel_event)
            try:
                raw = await process.stdout.readexactly(frame_size)
            except asyncio.IncompleteReadError as error:
                if error.partial:
                    raise VideoMediaError('truncated_frame', 'FFmpeg trả khung hình OCR không hoàn chỉnh.') from None
                break
            timestamp = await timestamps.get()
            if timestamp is None:
                raise VideoMediaError('missing_pts', 'Không đọc được timestamp gốc của khung OCR.')
            yield VideoFrame(timestamp, width, height, raw)
        await process.wait()
        if process.returncode:
            raise VideoMediaError('decode_failed', 'FFmpeg không giải mã được video để OCR.')
    finally:
        await stop_process(process)
        if not reader.done():
            reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)


async def ocr_video(path, tools: Toolchain, *, language='zh', recognizer=None,
                     roi=(.05, .65, .95, .98), sample_ms=250,
                     wait_ready=None, cancel_event=None, progress=None,
                     checkpoint=None, start_ms=0):
    """OCR one ROI incrementally; callbacks may persist finished cues as checkpoints.

    Identical ROI pixels reuse the last observation for up to one second. This
    does not assume similar-looking subtitles are the same, and changes anywhere
    inside the ROI trigger OCR at the next sample. Frames never accumulate in RAM.
    """
    await _ready(wait_ready, cancel_event)
    metadata = await probe_video(path, tools, cancel_event=cancel_event, wait_ready=wait_ready)
    recognizer = recognizer or await asyncio.to_thread(RapidOcrRecognizer, language)
    accumulator = SubtitleAccumulator(language=language)
    last_hash, last_ocr, last_recognized_ms = None, OcrObservation('', 0.), -1000
    completed = 0
    async for frame in iter_video_frames(path, tools, metadata=metadata, roi=roi,
            sample_ms=sample_ms, wait_ready=wait_ready, cancel_event=cancel_event):
        if frame.timestamp_ms < start_ms:
            continue
        await _ready(wait_ready, cancel_event)
        fingerprint = hashlib.blake2b(frame.rgb, digest_size=16).digest()
        if fingerprint != last_hash or frame.timestamp_ms-last_recognized_ms >= 1000:
            observation = await asyncio.to_thread(recognizer, frame)
            if not isinstance(observation, OcrObservation):
                raise VideoMediaError('invalid_ocr_result', 'Bộ OCR trả dữ liệu không hợp lệ.')
            last_hash, last_ocr, last_recognized_ms = fingerprint, observation, frame.timestamp_ms
        else:
            observation = last_ocr
        accumulator.add(frame.timestamp_ms, observation)
        if checkpoint is not None and len(accumulator.segments) > completed:
            await checkpoint([segment.as_dict() for segment in accumulator.segments[completed:]], frame.timestamp_ms)
            completed = len(accumulator.segments)
        if progress is not None:
            await progress(min(.999, frame.timestamp_ms / metadata.duration_ms))
    result = accumulator.finish(metadata.duration_ms)
    if checkpoint is not None and len(result) > completed:
        await checkpoint([segment.as_dict() for segment in result[completed:]], metadata.duration_ms)
    if progress is not None:
        await progress(1.)
    return result
