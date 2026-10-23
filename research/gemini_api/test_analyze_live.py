"""Offline analysis-contract tests; synthetic records are never benchmark evidence."""
import copy
import unittest

from analyze_live import analyze, quantiles


def fake_evidence():
    corpus = [dict(id='1', source='你好。', reference='Xin chào.')]
    rows = []
    for stream in (False, True):
        rows.append(dict(id='1', source='你好。', reference='Xin chào.', translation='Xin chào.',
            scenario=dict(model='gemini-3.5-flash-lite', level='minimal', stream=stream,
                endpoint='generateContent', prompt='compact', store=None, max_tokens=256),
            cache='application_cache_bypassed', status='ok', http_status=200,
            timings_ms=dict(headers=10, first_text=20, first_useful_complete_translation=30,
                completion=40, request_wall=41), usage=dict(promptTokenCount=5, candidatesTokenCount=3)))
    return dict(status='measured', sentences=1, corpus_sha256='test-hash', planned_requests=2, requests_sent=2, rows=rows), corpus


class AnalysisTests(unittest.TestCase):
    def test_nearest_rank_and_empty(self):
        self.assertEqual(quantiles(list(range(1, 101))), dict(n=100, p50=50, p90=90, p95=95, p99=99))
        self.assertIsNone(quantiles([])['p95'])

    def test_incomplete_duplicate_or_wrong_reference_rejected(self):
        report, corpus = fake_evidence()
        for change in ('partial', 'missing', 'wrong_reference'):
            edited = copy.deepcopy(report)
            if change == 'partial':
                edited['status'] = 'not_run'
            elif change == 'missing':
                edited['rows'].pop()
            else:
                edited['rows'][0]['reference'] = 'A different reference'
            with self.assertRaises(ValueError):
                analyze(edited, corpus, 'test-hash', expected_count=1)

    def test_token_absence_and_syntactic_usefulness_not_quality_claim(self):
        report, corpus = fake_evidence()
        result = analyze(report, corpus, 'test-hash', expected_count=1)
        condition = result['conditions'][0]
        self.assertEqual(condition['reference_scores_successes_only']['chrf']['score'], 100)
        self.assertIsNone(condition['token_usage']['cachedContentTokenCount']['total'])
        self.assertEqual(condition['first_useful_to_final_gap_ms']['p95'], 10)
        self.assertFalse(result['android_pipeline_target_proven'])
        self.assertFalse(result['human_quality_review_complete'])

    def test_failures_excluded_from_latency_and_scored_empty_separately(self):
        report, corpus = fake_evidence()
        report['rows'][0].update(status='error', http_status=429, error='http_error', timings_ms={'headers': 100, 'request_wall': 101})
        result = analyze(report, corpus, 'test-hash', expected_count=1)
        condition = result['conditions'][0]
        self.assertEqual(condition['errors'], 1)
        self.assertEqual(condition['successful_client_durations_ms']['completion']['n'], 0)
        self.assertEqual(condition['reference_scores_all_attempts_failed_as_empty']['n'], 1)
        self.assertIsNone(condition['all_attempts_first_useful_bound_ms']['p95'])
        self.assertEqual(result['reliability']['http_status_counts'], {'429': 1, '200': 1})


if __name__ == '__main__':
    unittest.main()
