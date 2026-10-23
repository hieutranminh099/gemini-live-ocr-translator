"""SIMULATED provider benchmark of the real websocket scheduler, never Gemini.

Uses the existing serial CLI scheduler as an architectural baseline and the new
bounded API scheduler. Every provider call sleeps 250 ms and returns synthetic
text. This measures queue/correctness only, not translation quality or AI speed.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import threading
import time

from fastapi.testclient import TestClient

from app.models import Settings
from app.server import Runtime, device_app


def percentile(values, p):
    values = sorted(values)
    position = (len(values) - 1) * p / 100
    lower = int(position)
    return round(values[lower] + (values[min(lower + 1, len(values) - 1)] - values[lower]) * (position - lower), 3)


class ControlledProvider:
    def __init__(self, service_ms):
        self.service_ms = service_ms
        self.calls = []
        self.running = 0
        self.max_running = 0
        self.origin = None

    async def translate(self, texts, **kwargs):
        row = dict(cue=int(texts[0].split('-')[1]), started_ms=(time.perf_counter() - self.origin) * 1000)
        self.calls.append(row)
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        try:
            await asyncio.sleep(self.service_ms / 1000)
            row['completed_ms'] = (time.perf_counter() - self.origin) * 1000
            return ['SIMULATED-VI: ' + text for text in texts], 0, 0
        finally:
            self.running -= 1


def run_one(mode, run, cue_count, period_ms, service_ms):
    provider = ControlledProvider(service_ms)
    sent, received = [], []
    latest_index = [-1]
    lock, done = threading.Lock(), threading.Event()
    with tempfile.TemporaryDirectory(prefix='scheduler-sim-') as directory:
        runtime = Runtime(Path(directory), provider)
        runtime.store.set('settings', Settings(provider=mode, requests_per_minute=120,
                           daily_request_limit=100000, cache_enabled=False).model_dump(exclude={'api_key'}))
        _, token = runtime.store.pair('Offline scheduler simulation')
        try:
            with TestClient(device_app(runtime)) as client:
                with client.websocket_connect('/v1/stream', headers={'Authorization': 'Bearer ' + token}) as ws:
                    ws.receive_json()
                    provider.origin = time.perf_counter()

                    def receiver():
                        try:
                            while True:
                                result = ws.receive_json()
                                elapsed = (time.perf_counter() - provider.origin) * 1000
                                with lock:
                                    index = latest_index[0]
                                received.append(dict(request_id=result.get('request_id'), type=result['type'],
                                                     received_ms=elapsed, current_cue=index,
                                                     stale=result.get('request_id') != str(index),
                                                     timings_ms=result.get('timings_ms', {})))
                                if result.get('request_id') == str(cue_count - 1):
                                    break
                        finally:
                            done.set()

                    thread = threading.Thread(target=receiver, daemon=True)
                    thread.start()
                    for index in range(cue_count):
                        deadline = provider.origin + index * period_ms / 1000
                        wait = deadline - time.perf_counter()
                        if wait > 0:
                            time.sleep(wait)
                        with lock:
                            latest_index[0] = index
                        sent.append(dict(cue=index, sent_ms=(time.perf_counter() - provider.origin) * 1000))
                        ws.send_json(dict(type='translate', request_id=str(index), region_id='simulated-subtitle',
                                          screen_generation=index, latest_only=True, source='zh', profile='game', mode='fast',
                                          blocks=[dict(id='subtitle', text=f'cue-{index:02}',
                                                       box=dict(x=.1, y=.8, width=.8, height=.1))]))
                    if not done.wait(10):
                        raise RuntimeError('Simulation did not complete final cue within 10 seconds')
                    thread.join(timeout=1)
        finally:
            runtime.store.close()
    if any(row['type'] != 'result' for row in received):
        raise RuntimeError('Simulation returned an unexpected scheduler error')
    sent_by_cue = {row['cue']: row['sent_ms'] for row in sent}
    for row in provider.calls:
        row['queue_ms'] = row['started_ms'] - sent_by_cue[row['cue']]
        row['observed_service_ms'] = row['completed_ms'] - row['started_ms']
        row['superseded_before_completion'] = (row['cue'] + 1 < cue_count and
                                              row['completed_ms'] > sent_by_cue[row['cue'] + 1])
    for row in received:
        row['client_result_ms'] = row['received_ms'] - sent_by_cue[int(row['request_id'])]
    final_call = next(row for row in provider.calls if row['cue'] == cue_count - 1)
    final_result = next(row for row in received if row['request_id'] == str(cue_count - 1))
    return dict(mode=mode, run=run, input_cues=cue_count, provider_calls=len(provider.calls),
                max_concurrent_provider_calls=provider.max_running, results_sent=len(received),
                stale_results_sent=sum(row['stale'] for row in received),
                superseded_provider_completions=sum(row['superseded_before_completion'] for row in provider.calls),
                final_cue_queue_ms=final_call['queue_ms'], final_cue_client_result_ms=final_result['client_result_ms'],
                cues_sent=sent, provider_intervals=provider.calls, received_results=received)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--cues', type=int, default=20)
    parser.add_argument('--period-ms', type=int, default=150)
    parser.add_argument('--service-ms', type=int, default=250)
    parser.add_argument('--output', type=Path, default=Path('research/results/scheduler-simulation-v163.json'))
    args = parser.parse_args()
    records = [run_one(mode, run, args.cues, args.period_ms, args.service_ms)
               for run in range(1, args.runs + 1) for mode in ('cli', 'api')]
    summary = {}
    for mode in ('cli', 'api'):
        runs = [row for row in records if row['mode'] == mode]
        queue = [row['final_cue_queue_ms'] for row in runs]
        final = [row['final_cue_client_result_ms'] for row in runs]
        summary[mode] = dict(runs=len(runs), total_input_cues=sum(row['input_cues'] for row in runs),
                             total_provider_calls=sum(row['provider_calls'] for row in runs),
                             total_results_sent=sum(row['results_sent'] for row in runs),
                             stale_results_sent=sum(row['stale_results_sent'] for row in runs),
                             max_concurrent_provider_calls=max(row['max_concurrent_provider_calls'] for row in runs),
                             final_cue_queue_p50_ms=percentile(queue, 50), final_cue_queue_p95_ms=percentile(queue, 95),
                             final_cue_client_result_p50_ms=percentile(final, 50), final_cue_client_result_p95_ms=percentile(final, 95))
    result = dict(kind='SIMULATED_NOT_GEMINI', created_utc=datetime.now(timezone.utc).isoformat(),
                  method='Actual websocket server + controlled asyncio.sleep fake provider; serial CLI baseline versus bounded API workers.',
                  limitations=['No Google calls, real translation, OCR, Android, capture, or overlay measured.',
                               '250 ms service exceeds 150 ms cue life. Only final cue is expected to remain current long enough to render.',
                               'This demonstrates less queueing, not ability to translate every cue or a production latency target.',
                               'Three final-cue samples per mode; percentiles describe these samples only; Windows timer jitter remains.'],
                  fixed_provider_service_ms=args.service_ms, cue_period_ms=args.period_ms, cues_per_run=args.cues,
                  real_google_requests=0, summary=summary, records=records)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(kind=result['kind'], output=str(args.output), summary=summary), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
