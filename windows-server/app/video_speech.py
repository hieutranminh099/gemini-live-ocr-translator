"""Optional offline ASR using an explicitly installed local faster-whisper model.

Audio is decoded in bounded mono PCM chunks with half-second overlap. A single
CPU INT8 child reuses its model for one video; the existing Live scheduler can
suspend it and cancellation releases the process. Project-private checkpoints
are keyed by source bytes and model/configuration bytes, never global TM.
The ASR model recognizes source text; Gemini remains the translation provider.
No model download, network request, GPU allocation or API credential is used.
"""

from __future__ import annotations

import asyncio
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import time
import traceback
import wave

from .video_media import (INPUT_FORMATS, VideoMediaError, VideoSegment, _input,
                          _ready, run_tool)


LANGUAGES = frozenset({'en', 'zh', 'ja', 'ko'})
CHUNK_MS = 30000
OVERLAP_MS = 500
CHECKPOINT_REVISION = 2
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
                cpu_threads=2, chunk_seconds=30, overlap_ms=OVERLAP_MS,
                model_reuse='one_video', languages=sorted(LANGUAGES))


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


async def _fingerprint(path, model_dir, source, duration_ms, wait_ready, cancel_event):
    digest = hashlib.sha256(json.dumps([CHECKPOINT_REVISION, source, duration_ms,
        CHUNK_MS, OVERLAP_MS, 'cpu-int8-vad-beam5']).encode())
    for current in (path, *(model_dir / name for name in MODEL_FILES)):
        digest.update(current.name.encode('utf-8'))
        with current.open('rb') as stream:
            while True:
                await _ready(wait_ready, cancel_event)
                chunk = await asyncio.to_thread(stream.read, 1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
    return digest.hexdigest()


def _load_checkpoint(path, fingerprint, duration_ms, source):
    if not path.exists():
        return [], 0
    try:
        if path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError('checkpoint too large')
        state = json.loads(path.read_text(encoding='utf-8'))
        if state.get('revision') != CHECKPOINT_REVISION or state.get('fingerprint') != fingerprint:
            raise VideoMediaError('speech_checkpoint_changed',
                'Nguồn hoặc mô hình ASR đã đổi. Checkpoint được giữ; nhập dự án mới để nhận diện lại.')
        end = state['completed_ms']
        if not isinstance(end, int) or not 0 <= end <= duration_ms or (end != duration_ms and end % CHUNK_MS):
            raise ValueError('invalid checkpoint position')
        rows = state['segments']
        if not isinstance(rows, list) or len(rows) > MAX_SEGMENTS:
            raise ValueError('invalid checkpoint size')
        segments, previous_end, ids = [], 0, set()
        for row in rows:
            segment = VideoSegment(**row)
            if (segment.source_type != 'SPEECH' or segment.language != source or
                    not isinstance(segment.start_ms, int) or not isinstance(segment.end_ms, int) or
                    not previous_end <= segment.start_ms < segment.end_ms <= duration_ms or
                    not isinstance(segment.text, str) or not segment.text.strip() or len(segment.text) > 2000 or
                    not isinstance(segment.id, str) or not segment.id or len(segment.id) > 80 or segment.id in ids or
                    (segment.confidence is not None and (not isinstance(segment.confidence, (int, float)) or
                        not math.isfinite(segment.confidence) or not 0 <= segment.confidence <= 1))):
                raise ValueError('invalid checkpoint segment')
            ids.add(segment.id)
            previous_end = segment.end_ms
            segments.append(segment)
        return segments, end
    except VideoMediaError:
        raise
    except (OSError, ValueError, KeyError, TypeError):
        raise VideoMediaError('invalid_speech_checkpoint',
            'Checkpoint ASR không hợp lệ. Dữ liệu được giữ; nhập dự án mới để nhận diện lại.') from None


def _save_checkpoint(path, fingerprint, completed_ms, segments):
    temporary = path.with_suffix(path.suffix + '.tmp')
    value = dict(revision=CHECKPOINT_REVISION, fingerprint=fingerprint,
        completed_ms=completed_ms, segments=[item.as_dict() for item in segments])
    size = 0
    try:
        with temporary.open('wb') as target:
            for part in json.JSONEncoder(ensure_ascii=False).iterencode(value):
                chunk = part.encode('utf-8')
                size += len(chunk)
                if size > 64 * 1024 * 1024:
                    raise VideoMediaError('speech_checkpoint_limit',
                        'Checkpoint ASR vượt giới hạn 64 MiB. Bản trước được giữ; chia video thành dự án nhỏ hơn.')
                target.write(chunk)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    temporary.replace(path)


def _session_path(path, sequence):
    return path.with_name(f'{path.stem}-{sequence:04d}{path.suffix}')


class _SpeechSession:
    """One owned, suspendable process; audio/request/result files stay bounded."""
    def __init__(self, directory, model_dir, source, *, wait_ready, cancel_event, chunks):
        self.input = directory / 'input.json'
        self.output = directory / 'output.json'
        self.heartbeat = self.input.with_suffix('.heartbeat')
        self.model_dir, self.source = model_dir, source
        self.wait_ready, self.cancel_event = wait_ready, cancel_event
        self.chunks, self.sequence, self.task, self.pulse = chunks, 0, None, None

    async def _heartbeat(self):
        while True:
            self.heartbeat.touch()
            await asyncio.sleep(1)

    async def recognize(self, audio_path):
        self.sequence += 1
        request_path = _session_path(self.input, self.sequence)
        output_path = _session_path(self.output, self.sequence)
        temporary = request_path.with_suffix('.tmp')
        temporary.write_text(json.dumps(dict(session=True, sequence=self.sequence,
            audio_path=str(audio_path), model_dir=str(self.model_dir), language=self.source)), encoding='utf-8')
        temporary.replace(request_path)
        if self.task is None:
            self.input.write_text('{"session":true}', encoding='utf-8')
            self.heartbeat.touch()
            self.pulse = asyncio.create_task(self._heartbeat())
            self.task = asyncio.create_task(run_tool(worker_command(self.input, self.output),
                wait_ready=self.wait_ready, cancel_event=self.cancel_event,
                timeout=900 * self.chunks + 180, limit=65536))
        deadline = asyncio.get_running_loop().time() + 900
        while True:
            gate_started = asyncio.get_running_loop().time()
            await _ready(self.wait_ready, self.cancel_event)
            deadline += asyncio.get_running_loop().time() - gate_started
            if output_path.exists():
                try:
                    if output_path.stat().st_size > 512 * 1024:
                        raise ValueError('too much output')
                    output = json.loads(output_path.read_text(encoding='utf-8'))
                    if output.get('sequence') == self.sequence:
                        rows = output['segments']
                        output_path.unlink()
                        request_path.unlink()
                        return rows
                except (OSError, ValueError, KeyError, TypeError):
                    raise VideoMediaError('invalid_speech_output', 'Không đọc được transcript có timestamp từ ASR.') from None
            if self.task.done():
                try:
                    await self.task
                except VideoMediaError as error:
                    if error.code in ('cancelled', 'pause_failed', 'tool_timeout'):
                        raise
                raise VideoMediaError('speech_failed', 'Bộ ASR đã dừng. Checkpoint trước được giữ; kiểm tra mô hình và thư viện Speech.')
            if asyncio.get_running_loop().time() > deadline:
                raise VideoMediaError('tool_timeout', 'ASR xử lý quá thời gian cho một đoạn; checkpoint trước được giữ.')
            await asyncio.sleep(.05)

    async def close(self):
        if self.task is not None:
            if not self.task.done():
                request_path = _session_path(self.input, self.sequence + 1)
                temporary = request_path.with_suffix('.tmp')
                temporary.write_text(json.dumps(dict(session=True, sequence=self.sequence + 1, stop=True)), encoding='utf-8')
                temporary.replace(request_path)
                await asyncio.wait({self.task}, timeout=1)
            if not self.task.done():
                self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
        if self.pulse is not None:
            self.pulse.cancel()
            await asyncio.gather(self.pulse, return_exceptions=True)


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
                       cancel_event=None, progress=None, checkpoint=None):
    path = _input(path)
    if source not in LANGUAGES:
        raise VideoMediaError('speech_language', 'ASR hiện hỗ trợ tiếng Trung, Nhật, Hàn và Anh.')
    if not any(stream['type'] == 'audio' for stream in metadata.streams):
        raise VideoMediaError('no_audio_stream', 'Video không có luồng âm thanh để nhận dạng lời nói.')
    model_dir = _require_model(model_dir)
    if not isinstance(metadata.duration_ms, int) or not 0 < metadata.duration_ms <= 24 * 3600000:
        raise VideoMediaError('invalid_duration', 'Thời lượng video không hợp lệ để nhận dạng lời nói.')
    audio = next(stream for stream in metadata.streams if stream['type'] == 'audio')
    await _ready(wait_ready, cancel_event)
    fingerprint = await _fingerprint(path, model_dir, source, metadata.duration_ms, wait_ready, cancel_event)
    checkpoint_path = path.parent / 'source.asr-checkpoint-v2.json'
    segments, completed_ms = await asyncio.to_thread(_load_checkpoint, checkpoint_path,
        fingerprint, metadata.duration_ms, source)
    if checkpoint is not None and completed_ms:
        await checkpoint(list(segments), completed_ms)
    if completed_ms == metadata.duration_ms:
        if progress is not None:
            await progress(1.)
        return segments
    if not speech_capability(model_dir)['dependency_installed']:
        raise VideoMediaError('speech_dependency_missing', 'Chưa cài faster-whisper. Hãy cài bộ Speech cục bộ.')
    # Project-owned temporary folder only; no raw audio is written to TM/cache.
    with tempfile.TemporaryDirectory(prefix='.video-speech-', dir=path.parent) as temporary:
        directory = Path(temporary)
        wav_path = directory / 'chunk.wav'
        session = _SpeechSession(directory, model_dir, source, wait_ready=wait_ready,
            cancel_event=cancel_event, chunks=math.ceil((metadata.duration_ms-completed_ms)/CHUNK_MS))
        try:
            for offset in range(completed_ms, metadata.duration_ms, CHUNK_MS):
                await _ready(wait_ready, cancel_event)
                core_end = min(offset + CHUNK_MS, metadata.duration_ms)
                decode_start = max(0, offset - OVERLAP_MS)
                decode_end = min(core_end + OVERLAP_MS, metadata.duration_ms)
                duration = decode_end - decode_start
                # Only the current <=31s PCM chunk exists. Overlap adds context;
                # midpoint ownership and temporal exact dedup protect the seam.
                await run_tool([tools.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
                    '-ss', f'{decode_start / 1000:.3f}', '-protocol_whitelist', 'file,pipe',
                    '-format_whitelist', INPUT_FORMATS, '-threads', '1', '-i', path,
                    '-t', f'{duration / 1000:.3f}', '-map', f'0:{audio["index"]}', '-vn',
                    '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-threads', '1', '-f', 'wav', wav_path],
                    wait_ready=wait_ready, cancel_event=cancel_event, timeout=180)
                rows = await session.recognize(wav_path)
                chunk_segments = _rows_to_segments(rows, offset_ms=decode_start, duration_ms=duration, source=source)
                for item in chunk_segments:
                    midpoint = (item.start_ms + item.end_ms) / 2
                    if not offset <= midpoint < core_end:
                        continue
                    if segments and item.start_ms < segments[-1].end_ms:
                        if item.text == segments[-1].text:
                            continue
                        item = VideoSegment(item.id, segments[-1].end_ms, item.end_ms,
                            item.text, item.language, item.confidence, item.source_type)
                        if item.end_ms <= item.start_ms:
                            continue
                    segments.append(item)
                if len(segments) > MAX_SEGMENTS:
                    raise VideoMediaError('speech_segment_limit', 'Transcript vượt 50.000 đoạn; hãy chia video thành nhiều dự án.')
                await asyncio.to_thread(_save_checkpoint, checkpoint_path, fingerprint, core_end, segments)
                if checkpoint is not None:
                    await checkpoint(list(segments), core_end)
                if progress is not None:
                    await progress(core_end / metadata.duration_ms)
                await asyncio.sleep(0)
        finally:
            await session.close()
    return segments


def _load_model(model_dir):
    """Run only inside the cancellable child process."""
    for name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS'):
        os.environ[name] = '2'
    from faster_whisper import WhisperModel
    return WhisperModel(str(model_dir), device='cpu', compute_type='int8',
        cpu_threads=2, num_workers=1, local_files_only=True)


def _recognize(audio_path, model_dir, language, *, model=None):
    import numpy as np
    model = model if model is not None else _load_model(model_dir)
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
        no_speech = float(getattr(segment, 'no_speech_prob', 0.))
        if no_speech > .6 and logprob < -.4:
            continue
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
        session = request.get('session') is True
        sequence, identity, model = 0, None, None
        while True:
            if session:
                # The parent updates this even during Live suspension. An
                # orphaned idle child releases model memory after two minutes.
                heartbeat = input_path.with_suffix('.heartbeat')
                if time.time() - heartbeat.stat().st_mtime > 120:
                    return 2
                request_path = _session_path(input_path, sequence + 1)
                if not request_path.exists():
                    time.sleep(.05)
                    continue
                if request_path.stat().st_size > 16384:
                    return 2
                request = json.loads(request_path.read_text(encoding='utf-8'))
                current = request.get('sequence')
                if (not isinstance(current, int) or isinstance(current, bool) or
                        not 1 <= current <= 3000 or request.get('session') is not True):
                    return 2
                if current != sequence + 1:
                    return 2
                sequence = current
                if request.get('stop') is True:
                    return 0
            audio_path = Path(request['audio_path']).resolve()
            model_dir = _require_model(request['model_dir'])
            language = request['language']
            if language not in LANGUAGES or audio_path.parent != input_path.parent:
                return 2
            current_identity = (str(model_dir), language)
            if identity is not None and current_identity != identity:
                return 2
            identity = current_identity
            with wave.open(str(audio_path), 'rb') as audio:
                if (audio.getnchannels() != 1 or audio.getframerate() != 16000 or
                        audio.getsampwidth() != 2 or audio.getnframes() > 31 * 16000):
                    return 2
                empty = audio.getnframes() == 0
            # A stream ending before the picture yields a valid empty tail.
            if empty:
                rows = []
            elif session:
                if model is None:
                    model = _load_model(model_dir)
                rows = _recognize(audio_path, model_dir, language, model=model)
            else:
                rows = _recognize(audio_path, model_dir, language)
            _rows_to_segments(rows, offset_ms=0, duration_ms=CHUNK_MS + 2 * OVERLAP_MS, source=language)
            result = dict(segments=rows)
            if session:
                result['sequence'] = sequence
            target = _session_path(output_path, sequence) if session else output_path
            temporary = target.with_suffix('.tmp')
            temporary.write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
            temporary.replace(target)
            if not session:
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
