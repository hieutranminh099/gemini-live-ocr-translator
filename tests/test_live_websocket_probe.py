"""Purely offline protocol/usage tests for the isolated Live benchmark."""
import importlib.util
from pathlib import Path

import pytest


spec = importlib.util.spec_from_file_location('live_probe',
    Path(__file__).resolve().parents[1] / 'research/benchmark_live_websocket_v171.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def test_partial_transcript_does_not_count_until_generation_and_turn_complete():
    turn = probe.TurnResult(probe.PUBLIC_CUES[0])
    assert not turn.accept({'serverContent': {'outputTranscription': {'text': 'Xin hãy '},
        'modelTurn': {'parts': [{'inlineData': {'mimeType': 'audio/pcm;rate=24000', 'data': 'YWJj'}}]}}})
    assert 'full_transcript' not in turn.row['timings_ms']
    assert not turn.accept({'serverContent': {'outputTranscription': {'text': 'đóng cửa sổ lại.'},
        'generationComplete': True}})
    assert turn.accept({'serverContent': {'turnComplete': True}})
    row = turn.finish()
    assert row['status'] == 'ok' and row['protocol_complete']
    assert row['translation'] == 'Xin hãy đóng cửa sổ lại.'
    assert row['audio_ignored_bytes'] == 3 and row['audio_chunks'] == 1
    assert 'full_transcript' in row['timings_ms']
    assert row['stop_finish_reason'] is None


def test_interrupted_output_never_counts_as_complete_translation():
    turn = probe.TurnResult(probe.PUBLIC_CUES[0])
    turn.accept({'serverContent': {'outputTranscription': {'text': 'Xin hãy'},
        'interrupted': True, 'turnComplete': True}})
    row = turn.finish()
    assert row['status'] == 'incomplete'
    assert not row['protocol_complete'] and not row['full_transcript_within_1s']


def test_usage_snapshots_record_final_once_not_sum_partial_or_duplicate():
    class Store:
        def __init__(self):
            self.calls = []

        def add_tokens(self, a, b, day):
            self.calls.append((a, b, day))

    turn = probe.TurnResult(probe.PUBLIC_CUES[0])
    turn.accept({'usageMetadata': {'promptTokenCount': 50, 'responseTokenCount': 10}})
    turn.accept({'usageMetadata': {'promptTokenCount': 50, 'responseTokenCount': 25}})
    turn.accept({'usageMetadata': {'promptTokenCount': 50, 'responseTokenCount': 25}})
    store = Store()
    probe.record_usage_once(store, turn.row, '2026-10-23')
    probe.record_usage_once(store, turn.row, '2026-10-23')
    assert store.calls == [(50, 25, '2026-10-23')]
    assert turn.row['usage_messages'] == 3


def test_missing_usage_is_unknown_and_not_written_as_fake_receipt():
    class Store:
        def add_tokens(self, *args, **kwargs):
            raise AssertionError('No receipt was returned')

    turn = probe.TurnResult(probe.PUBLIC_CUES[0])
    probe.record_usage_once(Store(), turn.row, '2026-10-23')
    assert not turn.row['usage_input_known'] and not turn.row['usage_output_known']
    assert probe.usage_snapshot({'promptTokenCount': True, 'responseTokenCount': -1,
        'raw_error': 'PRIVATE'}) == {}


def test_public_cues_serial_result_objects_cannot_reuse_old_transcript():
    a, b = (probe.TurnResult(cue) for cue in probe.PUBLIC_CUES[:2])
    a.accept({'serverContent': {'outputTranscription': {'text': 'A'}}})
    b.accept({'serverContent': {'outputTranscription': {'text': 'B'}}})
    assert a.finish()['translation'] == 'A'
    assert b.finish()['translation'] == 'B'
    with pytest.raises(probe.ProbeError):
        probe.decoded_audio_size('not valid base64!')
