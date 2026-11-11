import asyncio
import json
from pathlib import Path
import wave
import threading
import time

import pytest

from app import video_speech as speech
from app.video_media import Toolchain, VideoCancelled, VideoMediaError, VideoMetadata


@pytest.fixture
def model(tmp_path):
    directory = tmp_path / 'mô hình có dấu'
    directory.mkdir()
    for name in speech.MODEL_FILES:
        (directory / name).write_bytes(b'local-model-fixture')
    return directory


def metadata(duration=61000, audio=True):
    streams = [dict(index=0, type='video')]
    if audio:
        streams.append(dict(index=2, type='audio'))
    return VideoMetadata(duration, 640, 360, 25., tuple(streams), 1234, 'mov')


@pytest.fixture
def video(tmp_path):
    result = tmp_path / 'video đường dẫn [đặc biệt].mp4'
    result.write_bytes(b'fake-video')
    return result


def mock_tools(monkeypatch, rows=None, fail_decode=None):
    commands, requests = [], []
    async def tool(command, **options):
        commands.append((list(command), options))
        if '--worker' not in command:
            if fail_decode is not None:
                fail_decode(command)
            return b''
        input_path, output = Path(command[-2]), Path(command[-1])
        sequence = 0
        while True:
            request_path = speech._session_path(input_path, sequence+1)
            if request_path.exists():
                request = json.loads(request_path.read_text(encoding='utf-8'))
                sequence = request['sequence']
                if request.get('stop'):
                    return b''
                requests.append(request)
                result = rows(sequence) if callable(rows) else (rows or [])
                target = speech._session_path(output,sequence)
                temporary = target.with_suffix('.tmp')
                temporary.write_text(json.dumps(dict(sequence=sequence, segments=result)), encoding='utf-8')
                temporary.replace(target)
            await asyncio.sleep(.001)
    monkeypatch.setattr(speech, 'run_tool', tool)
    return commands, requests


async def test_chunked_audio_real_offset_mapping_bounded_and_cleanup(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    commands, requests = mock_tools(monkeypatch, [dict(start=.2, end=1.8, text='你好', confidence=.82)])
    progress = []
    async def changed(ratio):
        progress.append(ratio)
    result = await asyncio.wait_for(speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg.exe'), Path('ffprobe.exe')),
        source='zh', model_dir=model, progress=changed), 5)
    assert [segment.start_ms for segment in result] == [200, 29700, 59700]
    assert [segment.end_ms for segment in result] == [1800, 31300, 61000]
    assert all(segment.source_type == 'SPEECH' and segment.language == 'zh' for segment in result)
    assert len(commands) == 4 and len(requests) == 3 and len(progress) == 3 and progress[-1] == 1.
    assert all(request['language'] == 'zh' and request['model_dir'] == str(model) for request in requests)
    ffmpeg = [command for command, _ in commands if str(command[0]) == 'ffmpeg.exe']
    assert all(command[command.index('-map') + 1] == '0:2' for command in ffmpeg)
    assert [command[command.index('-t') + 1] for command in ffmpeg] == ['30.500', '31.000', '1.500']
    assert not list(video.parent.glob('.video-speech-*'))
    assert video.read_bytes() == b'fake-video'


async def test_cancel_cleans_project_owned_audio_folder(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    cancelled = asyncio.Event()
    async def tool(command, **options):
        cancelled.set()
        raise VideoCancelled()
    monkeypatch.setattr(speech, 'run_tool', tool)
    with pytest.raises(VideoCancelled):
        await speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg'), Path('ffprobe')),
            source='en', model_dir=model, cancel_event=cancelled)
    assert not list(video.parent.glob('.video-speech-*'))


@pytest.mark.parametrize('source,audio,model_present,dependency,code', [
    ('vi', True, True, True, 'speech_language'), ('ja', False, True, True, 'no_audio_stream'),
    ('ko', True, False, True, 'speech_model_missing'), ('zh', True, True, False, 'speech_dependency_missing')])
async def test_configuration_errors_never_start_tool(video, model, monkeypatch, source, audio, model_present, dependency, code):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object() if dependency else None)
    async def tool(*args, **kwargs):
        raise AssertionError('No process should run')
    monkeypatch.setattr(speech, 'run_tool', tool)
    with pytest.raises(VideoMediaError) as error:
        await speech.speech_video(video, metadata(audio=audio), Toolchain(Path('ffmpeg'), Path('ffprobe')),
            source=source, model_dir=model if model_present else None)
    assert error.value.code == code


def test_speech_sanitization_clamps_overlap_and_preserves_gaps():
    result = speech._rows_to_segments([dict(start=0, end=2, text='one'),
        dict(start=1, end=3, text='two'), dict(start=4, end=8, text='three')],
        offset_ms=30000, duration_ms=5000, source='en', previous_end=30100)
    assert [(segment.start_ms, segment.end_ms) for segment in result] == [(30100, 32000), (32000, 33000), (34000, 35000)]
    assert speech._rows_to_segments([], offset_ms=0, duration_ms=30000, source='zh') == []


@pytest.mark.parametrize('rows', [None, [{}], [dict(start=float('nan'), end=1, text='bad')],
    [dict(start=2, end=1, text='bad')], [dict(start=0, end=1, text='x' * 2001)]])
def test_invalid_asr_output_fails_instead_of_creating_bad_timestamps(rows):
    with pytest.raises(VideoMediaError):
        speech._rows_to_segments(rows, offset_ms=0, duration_ms=30000, source='zh')


async def test_silence_empty_transcript_is_not_an_error(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    mock_tools(monkeypatch)
    result = await speech.speech_video(video, metadata(duration=1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='ko', model_dir=model)
    assert result == []


async def test_wait_ready_gate_checked_before_any_heavy_work(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    entered = asyncio.Event()
    resume = asyncio.Event()
    calls, _ = mock_tools(monkeypatch)
    async def gate():
        entered.set()
        await resume.wait()
    task = asyncio.create_task(speech.speech_video(video, metadata(duration=1000),
        Toolchain(Path('ffmpeg'), Path('ffprobe')), source='ja', model_dir=model, wait_ready=gate))
    await entered.wait()
    assert not calls
    resume.set()
    await task
    assert len(calls) == 2


def wav_file(path, seconds=1):
    with wave.open(str(path), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(b'\x00\x00' * round(16000 * seconds))


def test_worker_validates_bounded_local_audio_and_outputs_utf8(model, tmp_path, monkeypatch):
    audio = tmp_path / 'chunk.wav'
    wav_file(audio)
    source, output = tmp_path / 'input.json', tmp_path / 'output.json'
    source.write_text(json.dumps(dict(audio_path=str(audio), model_dir=str(model), language='zh')))
    monkeypatch.setattr(speech, '_recognize', lambda *args: [dict(start=0, end=.8, text='你好', confidence=.9)])
    assert speech.speech_worker_main([str(source), str(output)]) == 0
    assert json.loads(output.read_text(encoding='utf-8'))['segments'][0]['text'] == '你好'
    wav_file(audio, seconds=32)
    output.unlink()
    assert speech.speech_worker_main([str(source), str(output)]) == 2 and not output.exists()


def test_worker_requires_project_audio_path_and_model_not_network(model, tmp_path, monkeypatch):
    audio = tmp_path / 'elsewhere' / 'chunk.wav'
    audio.parent.mkdir()
    wav_file(audio)
    source, output = tmp_path / 'input.json', tmp_path / 'output.json'
    source.write_text(json.dumps(dict(audio_path=str(audio), model_dir=str(model), language='en')))
    monkeypatch.setattr(speech, '_recognize', lambda *args: (_ for _ in ()).throw(AssertionError()))
    assert speech.speech_worker_main([str(source), str(output)]) == 2
    assert not output.exists()


def test_worker_empty_audio_tail_is_valid_without_loading_model(model, tmp_path, monkeypatch):
    audio = tmp_path / 'chunk.wav'
    wav_file(audio, seconds=0)
    source, output = tmp_path / 'input.json', tmp_path / 'output.json'
    source.write_text(json.dumps(dict(audio_path=str(audio), model_dir=str(model), language='en')))
    monkeypatch.setattr(speech, '_recognize', lambda *args: (_ for _ in ()).throw(AssertionError()))
    assert speech.speech_worker_main([str(source), str(output)]) == 0
    assert json.loads(output.read_text(encoding='utf-8'))['segments'] == []


def test_frozen_worker_command_does_not_open_normal_application(tmp_path, monkeypatch):
    monkeypatch.setattr(speech.sys, 'frozen', True, raising=False)
    command = speech.worker_command(tmp_path / 'input.json', tmp_path / 'output.json')
    assert command[1] == '--speech-worker' and '--worker' not in command


def test_worker_failure_diagnosis_excludes_private_content_and_paths(model, tmp_path, monkeypatch):
    audio = tmp_path / 'chunk.wav'
    wav_file(audio)
    source, output = tmp_path / 'input.json', tmp_path / 'output.json'
    source.write_text(json.dumps(dict(audio_path=str(audio), model_dir=str(model), language='en')))
    def failing(*args):
        raise ImportError('private transcript and C:/private/path', name='absent_dependency')
    monkeypatch.setattr(speech, '_recognize', failing)
    assert speech.speech_worker_main([str(source), str(output)]) == 2
    encoded = output.read_text(encoding='utf-8')
    failure = json.loads(encoded)
    assert failure['error_type'] == 'ImportError'
    assert failure['missing_import'] == 'absent_dependency'
    assert 'private transcript' not in encoded and str(tmp_path) not in encoded


async def test_checkpoint_resumes_after_last_finished_chunk_without_retaining_pcm(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    decode_count = 0
    def fail_second(command):
        nonlocal decode_count
        decode_count += 1
        if decode_count == 2:
            raise VideoMediaError('synthetic_decode_failure', 'bounded test failure')
    mock_tools(monkeypatch, [dict(start=.7, end=1.2, text='真实片段', confidence=.7)], fail_second)
    with pytest.raises(VideoMediaError):
        await speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg'), Path('ffprobe')),
            source='zh', model_dir=model)
    stored = json.loads((video.parent/'source.asr-checkpoint-v2.json').read_text(encoding='utf-8'))
    assert stored['completed_ms'] == 30000 and len(stored['segments']) == 1
    assert not list(video.parent.glob('.video-speech-*'))
    commands, _ = mock_tools(monkeypatch, [dict(start=.7, end=1.2, text='真实片段', confidence=.7)])
    observed = []
    async def checkpoint(rows, pts):
        observed.append((len(rows), pts))
    result = await speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='zh', model_dir=model, checkpoint=checkpoint)
    decoded = [command for command, _ in commands if str(command[0]) == 'ffmpeg']
    assert [command[command.index('-ss')+1] for command in decoded] == ['29.500', '59.500']
    assert len(result) == 3 and observed == [(1,30000),(2,60000),(3,61000)]


async def test_completed_project_cache_does_not_load_model_or_start_tools(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    mock_tools(monkeypatch, [dict(start=.1, end=.8, text='你好', confidence=.4)])
    first = await speech.speech_video(video, metadata(1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='zh', model_dir=model)
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: None)
    async def forbidden(*args, **kwargs):
        raise AssertionError('Completed source must reuse its private checkpoint')
    monkeypatch.setattr(speech, 'run_tool', forbidden)
    assert await speech.speech_video(video, metadata(1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='zh', model_dir=model) == first


@pytest.mark.parametrize('changed', ['source', 'model'])
async def test_content_fingerprint_rejects_changed_source_or_model_before_process(video, model, monkeypatch, changed):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    mock_tools(monkeypatch)
    await speech.speech_video(video, metadata(1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='zh', model_dir=model)
    target = video if changed == 'source' else model/'config.json'
    original_stat = target.stat()
    target.write_bytes(b'X' * original_stat.st_size)
    import os
    os.utime(target, ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
    async def forbidden(*args, **kwargs):
        raise AssertionError('Changed fingerprint must not dispatch work')
    monkeypatch.setattr(speech, 'run_tool', forbidden)
    with pytest.raises(VideoMediaError) as error:
        await speech.speech_video(video, metadata(1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
            source='zh', model_dir=model)
    assert error.value.code == 'speech_checkpoint_changed'


async def test_overlap_removes_same_occurrence_but_keeps_repeated_text_later(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    chunks = {1:[dict(start=29.6,end=30.2,text='你好')],
        2:[dict(start=.1,end=.7,text='你好'),dict(start=1.,end=1.5,text='你好')],
        3:[dict(start=.8,end=1.2,text='你好')]}
    mock_tools(monkeypatch, lambda sequence: chunks[sequence])
    result = await speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='zh', model_dir=model)
    assert [(item.start_ms,item.end_ms) for item in result] == [(29600,30200),(30500,31000),(60300,60700)]


def test_session_worker_reuses_one_model_for_two_requests_and_stops(model, tmp_path, monkeypatch):
    audio = tmp_path/'chunk.wav'
    wav_file(audio)
    source, output = tmp_path/'input.json', tmp_path/'output.json'
    source.write_text('{"session":true}',encoding='utf-8')
    source.with_suffix('.heartbeat').touch()
    loads, calls, exit_codes = [], [], []
    retained_model = object()
    def load_model(directory):
        loads.append(directory)
        return retained_model
    def recognize(audio_path, directory, language, *, model):
        assert model is retained_model
        calls.append(language)
        return [dict(start=.1,end=.8,text='你好')]
    monkeypatch.setattr(speech, '_load_model', load_model)
    monkeypatch.setattr(speech, '_recognize', recognize)
    def request(sequence, **kwargs):
        target = speech._session_path(source,sequence)
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(dict(session=True,sequence=sequence,
            audio_path=str(audio),model_dir=str(model),language='zh',**kwargs)),encoding='utf-8')
        temporary.replace(target)
    def await_result(sequence):
        deadline = time.monotonic()+3
        target = speech._session_path(output,sequence)
        while time.monotonic()<deadline:
            if target.exists() and json.loads(target.read_text(encoding='utf-8')).get('sequence')==sequence:
                return
            time.sleep(.01)
        raise AssertionError('Bounded worker request timed out')
    request(1)
    worker = threading.Thread(target=lambda:exit_codes.append(speech.speech_worker_main([str(source),str(output)])))
    worker.start()
    try:
        await_result(1)
        request(2)
        await_result(2)
    finally:
        request(3,stop=True)
        worker.join(3)
    assert not worker.is_alive() and exit_codes == [0]
    assert loads == [model] and calls == ['zh','zh']


def test_recognizer_filters_high_no_speech_low_probability_hallucination(model, tmp_path):
    from types import SimpleNamespace
    audio = tmp_path/'chunk.wav'
    wav_file(audio)
    class RecognitionModel:
        def transcribe(self, samples, **options):
            assert len(samples)==16000 and options['vad_filter'] and not options['condition_on_previous_text']
            return [SimpleNamespace(start=0,end=.4,text='music hallucination',avg_logprob=-1.,no_speech_prob=.98),
                    SimpleNamespace(start=.4,end=.9,text='你好',avg_logprob=-.2,no_speech_prob=.05)], None
    result = speech._recognize(audio,model,'zh',model=RecognitionModel())
    assert len(result)==1 and result[0]['text']=='你好' and .8<result[0]['confidence']<.9


async def test_cancel_while_asr_is_busy_stops_owned_worker_and_removes_pcm(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    entered, stopped = asyncio.Event(), asyncio.Event()
    async def tool(command, **options):
        if '--worker' in command:
            entered.set()
            try:
                await asyncio.Future()
            finally:
                stopped.set()
        return b''
    monkeypatch.setattr(speech,'run_tool',tool)
    task = asyncio.create_task(speech.speech_video(video,metadata(1000),Toolchain(Path('ffmpeg'),Path('ffprobe')),
        source='zh',model_dir=model))
    await asyncio.wait_for(entered.wait(),2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(task,3)
    assert stopped.is_set() and not list(video.parent.glob('.video-speech-*'))
    assert not (video.parent/'source.asr-checkpoint-v2.json').exists()


async def test_corrupt_checkpoint_does_not_publish_an_invalid_timeline(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    mock_tools(monkeypatch,[dict(start=.1,end=.8,text='你好',confidence=.8)])
    await speech.speech_video(video,metadata(1000),Toolchain(Path('ffmpeg'),Path('ffprobe')),source='zh',model_dir=model)
    target = video.parent/'source.asr-checkpoint-v2.json'
    state = json.loads(target.read_text(encoding='utf-8'))
    state['segments'][0]['end_ms'] = -1
    target.write_text(json.dumps(state),encoding='utf-8')
    async def forbidden(*args,**kwargs):
        raise AssertionError('Invalid persisted timestamps must stop before recognition')
    monkeypatch.setattr(speech,'run_tool',forbidden)
    with pytest.raises(VideoMediaError) as error:
        await speech.speech_video(video,metadata(1000),Toolchain(Path('ffmpeg'),Path('ffprobe')),source='zh',model_dir=model)
    assert error.value.code=='invalid_speech_checkpoint'
    assert json.loads(target.read_text(encoding='utf-8'))['segments'][0]['end_ms']==-1
