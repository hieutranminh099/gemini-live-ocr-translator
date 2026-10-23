"""Optional offline ASR using an explicitly installed local faster-whisper model.

Audio is decoded in 30-second mono PCM chunks. A separate CPU INT8 worker
handles one chunk, so the live scheduler can suspend it and the OS can reclaim
all model memory after each chunk. This MVP reloads the model per chunk; that
trades offline throughput for a simple, bounded and cancellable resource model.
The ASR model recognizes source text; Gemini remains the translation provider.
No model download, network request, GPU allocation or API credential is used.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import traceback
import wave

from .video_media import (INPUT_FORMATS, VideoMediaError, VideoSegment, _input,
                          _ready, run_tool)


LANGUAGES = frozenset({'en', 'zh', 'ja', 'ko'})
CHUNK_MS = 30000
MAX_SEGMENTS = 50000
MODEL_FILES = ('model.bin', 'config.json', 'tokenizer.json')


def speech_capability(model_dir=None):
    try:
        installed = importlib.util.find_spec('faster_whisper') is not None
    except (ImportError, ValueError):
        installed = False
    path = Path(model_dir).resolve() if model_dir else None
    local_model = path is not None and all((path / name).is_file() for name in MODEL_FILES)
    return dict(available=bool(installed and local_model), dependency_installed=installed,
                model_installed=local_model, device='cpu', compute_type='int8',
                cpu_threads=2, chunk_seconds=30, languages=sorted(LANGUAGES))


def _require_model(model_dir):
    directory = Path(model_dir).resolve() if model_dir else None
    if directory is None or not all((directory / name).is_file() for name in MODEL_FILES):
        raise VideoMediaError('speech_model_missing',
            'Chưa có mô hình ASR cục bộ. Cài bộ Speech và mô hình faster-whisper tiny/base trước khi dùng.')
    return directory


def worker_command(input_json, output_json):
    if getattr(sys, 'frozen', False):
        return [sys.executable, '--speech-worker', str(input_json), str(output_json)]
    return [sys.executable, '-m', 'app.video_speech', '--worker', str(input_json), str(output_json)]


def _rows_to_segments(rows, *, offset_ms, duration_ms, source, previous_end=0):
    if not isinstance(rows, list) or len(rows) > 500:
        raise VideoMediaError('invalid_speech_output', 'ASR trả số đoạn không hợp lệ cho một khung âm thanh.')
    result = []
    end_limit = offset_ms + duration_ms
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get('text'), str):
            raise VideoMediaError('invalid_speech_output', 'ASR trả transcript không hợp lệ.')
        text = row['text'].strip()
        if not text:
            continue
        if len(text) > 2000:
            raise VideoMediaError('speech_segment_too_long', 'Một đoạn lời nói quá dài; hãy kiểm tra hoặc chia nhỏ transcript.')
        try:
            start, end = float(row['start']), float(row['end'])
        except (KeyError, TypeError, ValueError):
            raise VideoMediaError('invalid_speech_output', 'ASR trả timestamp không hợp lệ.') from None
        if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start:
            raise VideoMediaError('invalid_speech_output', 'ASR trả timestamp không hợp lệ.')
        start_ms = max(previous_end, offset_ms + round(start * 1000))
        end_ms = min(end_limit, offset_ms + round(end * 1000))
        if end_ms <= start_ms:
            continue
        confidence = row.get('confidence')
        if confidence is not None:
            try:
                confidence = float(confidence)
            except (TypeError, ValueError):
                confidence = None
            if confidence is not None and (not math.isfinite(confidence) or not 0 <= confidence <= 1):
                confidence = None
        result.append(VideoSegment(f'asr-{start_ms}-{end_ms}-{len(result)}', start_ms, end_ms,
                                   text, source, confidence, 'SPEECH'))
        previous_end = end_ms
    return result


async def speech_video(path, metadata, tools, *, source, model_dir, wait_ready=None,
                       cancel_event=None, progress=None):
    path = _input(path)
    if source not in LANGUAGES:
        raise VideoMediaError('speech_language', 'ASR hiện hỗ trợ tiếng Trung, Nhật, Hàn và Anh.')
    if not any(stream['type'] == 'audio' for stream in metadata.streams):
        raise VideoMediaError('no_audio_stream', 'Video không có luồng âm thanh để nhận dạng lời nói.')
    model_dir = _require_model(model_dir)
    if not speech_capability(model_dir)['dependency_installed']:
        raise VideoMediaError('speech_dependency_missing', 'Chưa cài faster-whisper. Hãy cài bộ Speech cục bộ.')
    if not isinstance(metadata.duration_ms, int) or not 0 < metadata.duration_ms <= 24 * 3600000:
        raise VideoMediaError('invalid_duration', 'Thời lượng video không hợp lệ để nhận dạng lời nói.')
    audio = next(stream for stream in metadata.streams if stream['type'] == 'audio')
    segments = []
    await _ready(wait_ready, cancel_event)
    # Project-owned temporary folder only; no raw audio is written to TM/cache.
    with tempfile.TemporaryDirectory(prefix='.video-speech-', dir=path.parent) as temporary:
        directory = Path(temporary)
        wav_path, input_json, output_json = (directory / name for name in ('chunk.wav', 'input.json', 'output.json'))
        for offset in range(0, metadata.duration_ms, CHUNK_MS):
            await _ready(wait_ready, cancel_event)
            duration = min(CHUNK_MS, metadata.duration_ms - offset)
            # Accurate seek discards decoder preroll; each WAV starts at zero.
            await run_tool([tools.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
                '-ss', f'{offset / 1000:.3f}', '-protocol_whitelist', 'file,pipe',
                '-format_whitelist', INPUT_FORMATS, '-threads', '1', '-i', path,
                '-t', f'{duration / 1000:.3f}', '-map', f'0:{audio["index"]}', '-vn',
                '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-threads', '1', '-f', 'wav', wav_path],
                wait_ready=wait_ready, cancel_event=cancel_event, timeout=180)
            input_json.write_text(json.dumps(dict(audio_path=str(wav_path), model_dir=str(model_dir),
                language=source), ensure_ascii=False), encoding='utf-8')
            if output_json.exists():
                output_json.unlink()
            try:
                await run_tool(worker_command(input_json, output_json), wait_ready=wait_ready,
                               cancel_event=cancel_event, timeout=900, limit=65536)
            except VideoMediaError as error:
                if error.code in ('cancelled', 'pause_failed', 'tool_timeout'):
                    raise
                diagnosis = ''
                try:
                    failure = json.loads(output_json.read_text(encoding='utf-8'))
                    kind = failure.get('error_type', '')
                    if isinstance(kind, str) and kind.isidentifier() and len(kind) <= 80:
                        diagnosis = f' (ASR: {kind})'
                except (OSError, ValueError, TypeError):
                    pass
                raise VideoMediaError('speech_failed',
                    'Bộ ASR không nhận dạng được đoạn âm thanh. Kiểm tra mô hình và thư viện Speech đã cài.' + diagnosis) from error
            try:
                if output_json.stat().st_size > 512 * 1024:
                    raise ValueError('Too much output')
                output = json.loads(output_json.read_text(encoding='utf-8'))
                rows = output['segments']
            except (OSError, ValueError, KeyError, TypeError):
                raise VideoMediaError('invalid_speech_output', 'Không đọc được transcript có timestamp từ ASR.') from None
            chunk_segments = _rows_to_segments(rows, offset_ms=offset, duration_ms=duration,
                source=source, previous_end=segments[-1].end_ms if segments else 0)
            segments.extend(chunk_segments)
            if len(segments) > MAX_SEGMENTS:
                raise VideoMediaError('speech_segment_limit', 'Transcript vượt 50.000 đoạn; hãy chia video thành nhiều dự án.')
            if progress is not None:
                await progress((offset + duration) / metadata.duration_ms)
            await asyncio.sleep(0)
    return segments


def _recognize(audio_path, model_dir, language):
    """Run only inside the cancellable child process."""
    for name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS'):
        os.environ[name] = '2'
    import numpy as np
    from faster_whisper import WhisperModel
    model = WhisperModel(str(model_dir), device='cpu', compute_type='int8',
                         cpu_threads=2, num_workers=1, local_files_only=True)
    # FFmpeg already decoded exactly the required PCM format. Reading that
    # bounded chunk directly avoids a second decode and faster-whisper 1.2.x's
    # incompatible av.open(metadata_errors=...) call with PyAV 19.
    with wave.open(str(audio_path), 'rb') as audio:
        samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768.
    segments, _ = model.transcribe(samples, language=language, task='transcribe',
        vad_filter=True, beam_size=5, best_of=1, temperature=0.,
        condition_on_previous_text=False, word_timestamps=False)
    rows = []
    for segment in segments:
        # A log-probability-derived confidence is useful review metadata, not a
        # calibrated accuracy score or permission to mark a translation verified.
        logprob = float(segment.avg_logprob)
        confidence = min(1., max(0., math.exp(min(0., logprob)))) if math.isfinite(logprob) else None
        rows.append(dict(start=segment.start, end=segment.end, text=segment.text, confidence=confidence))
        if len(rows) > 500:
            raise ValueError('Too many speech segments')
    return rows


def speech_worker_main(argv):
    if len(argv) != 2:
        return 2
    try:
        input_path, output_path = (Path(value).resolve() for value in argv)
        if input_path.parent != output_path.parent or input_path.stat().st_size > 16384:
            return 2
        request = json.loads(input_path.read_text(encoding='utf-8'))
        audio_path = Path(request['audio_path']).resolve()
        model_dir = _require_model(request['model_dir'])
        language = request['language']
        if language not in LANGUAGES or audio_path.parent != input_path.parent:
            return 2
        with wave.open(str(audio_path), 'rb') as audio:
            if (audio.getnchannels() != 1 or audio.getframerate() != 16000 or
                    audio.getsampwidth() != 2 or audio.getnframes() > 31 * 16000):
                return 2
            empty = audio.getnframes() == 0
        # Some valid videos have an audio stream ending before the picture.
        # The final decoded chunk is then empty, not an ASR failure.
        rows = [] if empty else _recognize(audio_path, model_dir, language)
        _rows_to_segments(rows, offset_ms=0, duration_ms=CHUNK_MS, source=language)
        temporary = output_path.with_suffix('.tmp')
        temporary.write_text(json.dumps(dict(segments=rows), ensure_ascii=False), encoding='utf-8')
        temporary.replace(output_path)
        return 0
    except Exception as error:
        # Keep an actionable, bounded diagnosis without transcript, absolute
        # paths, exception messages or any application's credentials.
        try:
            diagnosis = dict(error_type=type(error).__name__,
                frames=[dict(module=Path(frame.filename).name, function=frame.name)
                        for frame in traceback.extract_tb(error.__traceback__)[-8:]])
            if isinstance(error, ImportError) and error.name and all(
                    piece.isidentifier() for piece in error.name.split('.')):
                diagnosis['missing_import'] = error.name[:160]
            if isinstance(error, OSError):
                diagnosis['errno'] = error.errno
                diagnosis['winerror'] = getattr(error, 'winerror', None)
            output_path.write_text(json.dumps(diagnosis), encoding='utf-8')
        except (OSError, NameError, TypeError):
            pass
        return 2


if __name__ == '__main__':
    raise SystemExit(speech_worker_main(sys.argv[2:]) if len(sys.argv) > 1 and sys.argv[1] == '--worker' else 2)
