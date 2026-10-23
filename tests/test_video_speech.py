import asyncio
import json
from pathlib import Path
import wave

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


async def test_chunked_audio_real_offset_mapping_bounded_and_cleanup(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    commands, progress = [], []
    async def tool(command, **options):
        commands.append((list(command), options))
        if '--worker' in command:
            source = json.loads(Path(command[-2]).read_text(encoding='utf-8'))
            assert source['language'] == 'zh' and source['model_dir'] == str(model)
            Path(command[-1]).write_text(json.dumps(dict(segments=[
                dict(start=.2, end=1.8, text='你好', confidence=.82)])), encoding='utf-8')
        return b''
    async def changed(ratio):
        progress.append(ratio)
    monkeypatch.setattr(speech, 'run_tool', tool)
    result = await speech.speech_video(video, metadata(), Toolchain(Path('ffmpeg.exe'), Path('ffprobe.exe')),
        source='zh', model_dir=model, progress=changed)
    assert [segment.start_ms for segment in result] == [200, 30200, 60200]
    assert [segment.end_ms for segment in result] == [1800, 31800, 61000]
    assert all(segment.source_type == 'SPEECH' and segment.language == 'zh' for segment in result)
    assert len(commands) == 6 and len(progress) == 3 and progress[-1] == 1.
    ffmpeg = [command for command, _ in commands if str(command[0]) == 'ffmpeg.exe']
    assert all(command[command.index('-map') + 1] == '0:2' for command in ffmpeg)
    assert [command[command.index('-t') + 1] for command in ffmpeg] == ['30.000', '30.000', '1.000']
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
    async def tool(command, **options):
        if '--worker' in command:
            Path(command[-1]).write_text('{"segments":[]}', encoding='utf-8')
        return b''
    monkeypatch.setattr(speech, 'run_tool', tool)
    result = await speech.speech_video(video, metadata(duration=1000), Toolchain(Path('ffmpeg'), Path('ffprobe')),
        source='ko', model_dir=model)
    assert result == []


async def test_wait_ready_gate_checked_before_any_heavy_work(video, model, monkeypatch):
    monkeypatch.setattr(speech.importlib.util, 'find_spec', lambda name: object())
    entered = asyncio.Event()
    resume = asyncio.Event()
    calls = []
    async def gate():
        entered.set()
        await resume.wait()
    async def tool(command, **options):
        calls.append(command)
        if '--worker' in command:
            Path(command[-1]).write_text('{"segments":[]}', encoding='utf-8')
        return b''
    monkeypatch.setattr(speech, 'run_tool', tool)
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
