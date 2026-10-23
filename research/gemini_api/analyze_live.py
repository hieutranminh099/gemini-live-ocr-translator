"""Offline integrity, latency and reference-score analysis of a finished API run.

This script never imports application authentication or sends network requests.
It refuses partial reports and validates the full 100-sentence, two-condition
Flash-Lite run before writing an analysis artifact.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.tools/local-translation-deps'))


def quantiles(values):
    ordered = sorted(values)
    return dict(n=len(ordered), **{f'p{int(p * 100)}':
        ordered[max(0, math.ceil(len(ordered) * p) - 1)] if ordered else None
        for p in (.5, .9, .95, .99)})


def completion_bounds(group, metric):
    """Failed attempts never acquire an accepted translation within this run."""
    completed = sorted(r['timings_ms'][metric] for r in group if r['status'] == 'ok')
    values = {}
    for p in (.5, .9, .95, .99):
        rank = max(0, math.ceil(len(group) * p) - 1)
        values[f'p{int(p * 100)}'] = completed[rank] if rank < len(completed) else None
    return dict(attempts=len(group), completed=len(completed), **values,
        null_semantics='No finite accepted-translation bound observed at this rank; failed requests count as uncompleted, not as fast translations.')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def reference_scores(rows):
    import sacrebleu
    if not rows:
        return dict(n=0, bleu=None, chrf=None)
    hypotheses = [row.get('translation', '') if row['status'] == 'ok' else '' for row in rows]
    references = [row['reference'] for row in rows]
    bleu_metric = sacrebleu.metrics.BLEU(tokenize='13a')
    chrf_metric = sacrebleu.metrics.CHRF()
    bleu = bleu_metric.corpus_score(hypotheses, [references])
    chrf = chrf_metric.corpus_score(hypotheses, [references])
    return dict(n=len(rows), sacrebleu_version=sacrebleu.__version__,
        bleu=dict(score=bleu.score, signature=str(bleu_metric.get_signature())),
        chrf=dict(score=chrf.score, signature=str(chrf_metric.get_signature())))


def analyze(report, corpus, corpus_raw_sha256, expected_count=100):
    require(report.get('status') == 'measured', 'Report is incomplete: status must be measured.')
    require(report.get('sentences') == expected_count, 'Unexpected corpus sentence count.')
    require(len(corpus) == expected_count, 'Reference corpus must have the expected sentence count.')
    require(report.get('corpus_sha256') == corpus_raw_sha256, 'Raw reference-corpus hash mismatch.')
    by_id = {str(row['id']): row for row in corpus}
    require(len(by_id) == expected_count and len({r['source'] for r in corpus}) == expected_count,
        'Corpus IDs and source sentences must be unique.')
    rows = report.get('rows', [])
    require(len(rows) == expected_count * 2 == report.get('requests_sent'), 'Need every request record for both conditions.')
    require(report.get('planned_requests') == len(rows), 'Planned and recorded request counts differ.')
    groups = {False: [], True: []}
    for index, row in enumerate(rows):
        scenario = row.get('scenario', {})
        require(scenario.get('model') == 'gemini-3.5-flash-lite' and scenario.get('level') == 'minimal',
            f'Unexpected model/thinking in row {index}.')
        require(scenario.get('endpoint') == 'generateContent' and scenario.get('prompt') == 'compact',
            f'Unexpected endpoint or prompt in row {index}.')
        require(scenario.get('store') is None and scenario.get('max_tokens') == 256,
            f'Unexpected store or output-token configuration in row {index}.')
        stream = scenario.get('stream')
        require(type(stream) is bool, f'Invalid streaming flag in row {index}.')
        reference = by_id.get(str(row.get('id')))
        require(reference is not None and row.get('source') == reference['source'] and row.get('reference') == reference['reference'],
            f'Row {index} differs from its independent reference corpus.')
        require(row.get('cache') == 'application_cache_bypassed', f'Unexpected cache mode in row {index}.')
        require(row.get('status') in ('ok', 'error'), f'Unexpected result status in row {index}.')
        times = row.get('timings_ms', {})
        require(all(isinstance(v, (int, float)) and math.isfinite(v) and v >= 0
            for v in times.values() if v is not None), f'Invalid timing in row {index}.')
        if row['status'] == 'ok':
            require(row.get('http_status') == 200 and isinstance(row.get('translation'), str)
                and bool(row['translation'].strip()), f'Invalid accepted translation in row {index}.')
            for metric in ('headers', 'first_text', 'first_useful_complete_translation', 'completion', 'request_wall'):
                require(isinstance(times.get(metric), (int, float)), f'Missing {metric} in accepted row {index}.')
            order = [times[m] for m in ('headers', 'first_text', 'first_useful_complete_translation', 'completion', 'request_wall')]
            require(all(a <= b + .001 for a, b in zip(order, order[1:])), f'Non-monotonic timing in row {index}.')
        groups[stream].append(row)
    errors = [r for r in rows if r['status'] != 'ok']
    longest_failure_run = current_failure_run = 0
    for row in rows:
        current_failure_run = current_failure_run + 1 if row['status'] != 'ok' else 0
        longest_failure_run = max(longest_failure_run, current_failure_run)
    result = dict(status='analyzed_complete_run', corpus_sha256=corpus_raw_sha256,
        sentences=expected_count, requests=len(rows), conditions=[], paired_comparison={},
        reliability=dict(success=len(rows) - len(errors), errors=len(errors),
            http_status_counts=dict(Counter(str(r.get('http_status', 'unavailable')) for r in rows)),
            first_error_request_index_1based=next((i + 1 for i, row in enumerate(rows) if row['status'] != 'ok'), None),
            last_success_request_index_1based=max((i + 1 for i, row in enumerate(rows) if row['status'] == 'ok'), default=None),
            longest_consecutive_error_count=longest_failure_run,
            unique_sources_with_at_least_one_success=len({r['id'] for r in rows if r['status'] == 'ok'}),
            ordered_blocks_of_20=[dict(first_request=i + 1, last_request=min(i + 20, len(rows)),
                http_status_counts=dict(Counter(str(r.get('http_status', 'unavailable')) for r in rows[i:i + 20])))
                for i in range(0, len(rows), 20)],
            error_interpretation='HTTP 429 demonstrates provider rejection consistent with rate/quota limits. Exact RPM/TPM/daily limit is unknown: error bodies and retry headers are deliberately absent from the evidence. Order blocks are not wall-clock time windows.'),
        methodology=dict(quantiles='nearest rank; successful calls only; errors reported separately',
            timing_scope='API client durations including network and provider; not Android end-to-end or isolated Gemini compute',
            usefulness='complete validated JSON matching final accepted translation; syntactic validity does not establish semantic quality',
            quality='Independent Tatoeba references; one reference per sentence; dialogue-like isolated sentences, not continuous dialogue. No human quality certification.',
            cache='application cache bypassed; provider input-token cache reported separately',
            first_call='included; original request order retained in raw evidence'))
    metrics = ('headers', 'first_text', 'first_useful_complete_translation', 'completion', 'request_wall')
    token_keys = ('promptTokenCount', 'candidatesTokenCount', 'thoughtsTokenCount', 'cachedContentTokenCount', 'totalTokenCount')
    for stream, group in groups.items():
        require(len(group) == expected_count and {str(r['id']) for r in group} == set(by_id),
            f'Condition stream={stream} must contain exactly one attempt per reference sentence.')
        good = [r for r in group if r['status'] == 'ok']
        errors = [r for r in group if r['status'] != 'ok']
        timing = {metric: quantiles([r['timings_ms'][metric] for r in good]) for metric in metrics}
        gaps = [round(r['timings_ms']['completion'] - r['timings_ms']['first_useful_complete_translation'], 3) for r in good]
        token_usage = {}
        for key in token_keys:
            present = [r['usage'][key] for r in group if isinstance(r.get('usage', {}).get(key), (int, float))]
            token_usage[key] = dict(reported_n=len(present), missing_n=len(group) - len(present),
                total=sum(present) if present else None)
        timely = sum(r['timings_ms']['first_useful_complete_translation'] <= 1000 for r in good)
        result['conditions'].append(dict(scenario=group[0]['scenario'], attempts=len(group), success=len(good), errors=len(errors),
            success_percent=100 * len(good) / len(group),
            errors_by_type=dict(Counter(r.get('error', 'unknown') for r in errors)),
            http_status_counts=dict(Counter(str(r.get('http_status', 'unavailable')) for r in group)),
            returned_model_counts=dict(Counter(str(r.get('returned_model', 'unreported')) for r in good)),
            successful_client_durations_ms=timing,
            first_useful_to_final_gap_ms=quantiles(gaps),
            first_useful_within_1000ms_count=timely,
            first_useful_within_1000ms_percent_of_all_attempts=100 * timely / len(group),
            all_attempts_first_useful_bound_ms=completion_bounds(group, 'first_useful_complete_translation'),
            p95_client_first_useful_at_most_1000ms=timing['first_useful_complete_translation']['p95'] <= 1000 if good else False,
            token_usage=token_usage,
            provider_cached_input_token_positive_count=sum(r.get('usage', {}).get('cachedContentTokenCount', 0) > 0 for r in group),
            reference_scores_successes_only=reference_scores(good),
            reference_scores_all_attempts_failed_as_empty=reference_scores(group),
            error_records=[dict(id=r['id'], http_status=r.get('http_status'), error=r.get('error')) for r in errors]))
    nonstream = {str(r['id']): r for r in groups[False] if r['status'] == 'ok'}
    streamed = {str(r['id']): r for r in groups[True] if r['status'] == 'ok'}
    paired = sorted(set(nonstream) & set(streamed))
    result['paired_comparison'] = dict(n=len(paired),
        delta_semantics='stream minus non-stream for the same source; negative means streaming was faster',
        first_useful_delta_ms=quantiles([round(streamed[i]['timings_ms']['first_useful_complete_translation']
            - nonstream[i]['timings_ms']['first_useful_complete_translation'], 3) for i in paired]),
        final_delta_ms=quantiles([round(streamed[i]['timings_ms']['completion'] - nonstream[i]['timings_ms']['completion'], 3) for i in paired]),
        different_final_translation_count=sum(streamed[i]['translation'] != nonstream[i]['translation'] for i in paired),
        identical_source_subset_reference_scores=dict(
            nonstream=reference_scores([nonstream[i] for i in paired]),
            stream=reference_scores([streamed[i] for i in paired])))
    result['billing_cost'] = 'Not estimated: actual billing and current price schedule were not measured. Actual reported token totals are included.'
    result['android_pipeline_target_proven'] = False
    result['human_quality_review_complete'] = False
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=Path(__file__).with_name('api-flashlite-live.json'))
    parser.add_argument('--corpus', type=Path, default=ROOT / 'research/local_translation/dialogue100.jsonl')
    parser.add_argument('--output', type=Path, default=Path(__file__).with_name('live-analysis.json'))
    args = parser.parse_args()
    corpus_raw = args.corpus.read_bytes()
    corpus = [json.loads(line) for line in corpus_raw.decode('utf-8-sig').splitlines() if line.strip()]
    report_raw = args.input.read_bytes()
    result = analyze(json.loads(report_raw), corpus, hashlib.sha256(corpus_raw).hexdigest())
    result['evidence_file'] = args.input.name
    result['evidence_sha256'] = hashlib.sha256(report_raw).hexdigest()
    result['analysis_generated_utc'] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(status=result['status'], requests=result['requests'],
        conditions=[dict(stream=c['scenario']['stream'], success=c['success'], errors=c['errors'],
            first_useful_ms=c['successful_client_durations_ms']['first_useful_complete_translation'],
            chrf=c['reference_scores_successes_only']['chrf']['score'] if c['success'] else None) for c in result['conditions']]), ensure_ascii=False))


if __name__ == '__main__':
    main()
