"""100 actual Android cache revisits; never an AI/cache-miss or quality benchmark.

Requires an already active capture session and safe Windows consumer-CLI guard.
Does not change the selected ROI/settings/pairing or start a capture session.
Uses source/onDraw and overlay/onDraw timestamps from the same Android clock.
"""
import argparse
from bisect import bisect_right
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from benchmark_subtitle_pipeline import Benchmark, SAMPLES, percentiles, source_hash


class CacheBenchmark(Benchmark):
    def __init__(self, args):
        self.seen = {}
        super().__init__(args)
        self.output.update(requested_samples=args.n, unique_sentences=len(SAMPLES),
            scenario='Six previously translated public Chinese sentences revisited in a cycle',
            limitations=[
                'Cache-only actual screen-to-overlay benchmark; not 100 unique sentences, live Gemini, quality or full video.',
                'No source/OCR text corrections are accepted. Hash mismatches are failures, not successful samples.',
                'Provider internal compute and pure network RTT are not measurable from these events.',
                'Capture sampling wait estimate is a residual, not direct capture-ready instrumentation.',
                'One reused server cache plus Android RAM cache; these populations are reported separately.',
                'Cache hit rate is 100% by fixture construction, not an estimate of real-video cache hit rate.',
                'onDraw completion is a native Canvas callback; final GPU/display presentation is not separately instrumented.',
                'P99 is based on at most 100 successful samples; report failures alongside success-only quantiles.'],
            samples=[], checks={}, events=[])

    def events(self):
        for event in super().events():
            self.seen[json.dumps(event, sort_keys=True)] = event
        return sorted(self.seen.values(), key=lambda e: e['at_ms'])

    @staticmethod
    def safe_status(status):
        return dict(windows_version=status['version'], provider=status['settings']['provider'],
            api_configured=status.get('api_configured'),
            supported_cli_backend=status.get('cli', {}).get('supported_backend'),
            usage_requests=status['usage']['requests'], frames=status['metrics']['frames'],
            cache_hits=status['metrics']['cache_hits'], errors=status['metrics']['errors'])

    def guard(self, status):
        version = tuple(int(part) for part in status['version'].split('.'))
        experimental = (status['settings'].get('personal_cli_enabled', False) or
            status.get('experimental_cli_enabled', False) or status.get('cli', {}).get('experimental_enabled', False) or
            status.get('translation_available', False) or status.get('backend_mode') == 'cli_experimental')
        if version < (1, 5, 0) or status['settings']['provider'] != 'cli' or status.get('cli', {}).get('supported_backend') is not False or experimental:
            raise RuntimeError('Requires Windows >=1.5.0 with CLI supported_backend=false; no cache requests were sent')

    def verify_cache(self):
        """Guarded local cache lookup; never starts a cloud provider on cache miss."""
        self.expected = {}
        for i, source in enumerate(SAMPLES):
            result = self.client.post('/api/translate', json=dict(request_id=f'cache-only-{self.started_ms}-{i}',
                source=self.args.source, profile=self.args.profile, mode=self.args.mode,
                blocks=[dict(id='cache-check', text=source, box=dict(x=0, y=0, width=.9, height=.1))]))
            if result.status_code != 200:
                raise RuntimeError(f'Cache preflight failed for public sample {i + 1}; HTTP {result.status_code}; no cloud fallback allowed')
            blocks = result.json().get('blocks', [])
            if len(blocks) != 1 or blocks[0].get('cached') is not True or not blocks[0].get('text', '').strip():
                raise RuntimeError(f'Public sample {i + 1} is not an existing server-cache hit')
            text = blocks[0]['text']
            self.expected[source] = dict(translation=text, translation_hash=hashlib.sha256(text.encode()).hexdigest()[:16])
        self.output['cache_preflight'] = self.expected

    def save(self):
        events = self.events()
        self.output['events'] = events
        good = [s for s in self.output['samples'] if s.get('passed')]
        self.output['completed_samples'] = len(self.output['samples'])
        self.output['successful_samples'] = len(good)
        self.output['failed_samples'] = len(self.output['samples']) - len(good)
        self.output['success_only_e2e_ms'] = percentiles([s['source_to_overlay_ms'] for s in good])
        self.output['phone_ram_cache_e2e_ms'] = percentiles([s['source_to_overlay_ms'] for s in good if s['overlay_drawn'].get('phone_cache')])
        self.output['server_cache_e2e_ms'] = percentiles([s['source_to_overlay_ms'] for s in good if not s['overlay_drawn'].get('phone_cache')])
        stages = {}
        for sample in good:
            for key, value in sample.get('stage_ms', {}).items():
                if value is not None:
                    stages.setdefault(key, []).append(value)
        self.output['stage_success_only_percentiles_ms'] = {key: percentiles(values) for key, values in stages.items()}
        sources = [e for e in events if e.get('event') == 'source_drawn' and e.get('id', '').startswith(self.args.phase + '-')]
        known_sources = [e for e in events if e.get('event') == 'source_changed']
        def mismatches(reference):
            if not reference:
                return []
            times = [s['at_ms'] for s in reference]
            bad = []
            for event in events:
                if event.get('event') != 'overlay_drawn':
                    continue
                index = bisect_right(times, event['at_ms']) - 1
                if index >= 0 and event.get('source_hash') != reference[index].get('source_hash'):
                    bad.append(dict(overlay=event, latest_source=reference[index]))
            return bad
        self.output['physical_source_mismatches'] = mismatches(sources)
        self.output['known_ocr_source_mismatches'] = mismatches(known_sources)
        destination = ROOT / self.args.output
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(self.output, ensure_ascii=False, indent=2), encoding='utf-8')

    def execute(self):
        initial = self.status()
        self.output['initial'] = self.safe_status(initial)
        self.output['android_version'] = self.run('shell', 'dumpsys', 'package', 'com.gemini.ocr').decode().split('versionName=')[1].splitlines()[0].strip()
        try:
            self.guard(initial)
            self.verify_cache()
            blank = self.scene('')
            # An already empty source produces no *new* source_changed transition.
            # This settling delay is outside all measured source/overlay samples.
            time.sleep(.35)
            for index in range(self.args.n):
                source = SAMPLES[index % len(SAMPLES)]
                before = self.status()
                row = dict(index=index + 1, source=source, source_hash=source_hash(source), passed=False)
                self.output['samples'].append(row)
                scene = self.scene(source)
                row['source_drawn'] = scene
                try:
                    overlay = self.overlay(source, scene['at_ms'])
                except TimeoutError:
                    row['error'] = 'No matching overlay within 3 seconds; no fallback or transcription correction'
                    raise
                row['overlay_drawn'] = overlay
                row['source_to_overlay_ms'] = overlay['at_ms'] - scene['at_ms']
                matches = [e for e in self.events() if e.get('event') == 'source_changed' and
                    e.get('source_hash') == source_hash(source) and e.get('screen_generation') == overlay.get('screen_generation') and e['at_ms'] >= scene['at_ms']]
                changed = matches[-1] if matches else None
                results = [e for e in self.events() if e.get('event') == 'result_received' and
                    e.get('request_id') == overlay.get('request_id') and e['at_ms'] >= scene['at_ms']]
                result = results[-1] if results else None
                row.update(source_changed=changed, result_received=result)
                after = self.status()
                row.update(new_ai_calls=after['usage']['requests'] - before['usage']['requests'],
                    new_server_frames=after['metrics']['frames'] - before['metrics']['frames'],
                    new_errors=after['metrics']['errors'] - before['metrics']['errors'])
                stage = {key: (changed or {}).get(key) for key in ('capture_ms', 'ocr_queue_ms', 'ocr_ms', 'source_image_age_ms', 'stabilization_ms', 'adaptive_period_ms')}
                if changed:
                    estimate = changed['at_ms'] - scene['at_ms'] - sum(changed.get(key, 0) for key in ('capture_ms', 'ocr_queue_ms', 'ocr_ms'))
                    stage['sampling_and_detection_residual_ms'] = estimate
                stage.update({key: overlay.get(key) for key in ('overlay_ms', 'observed_to_draw_ms')})
                # Android uses zero as a sentinel when no response was received
                # for a phone-RAM hit; it is not a measured 0ms network stage.
                if not overlay.get('phone_cache'):
                    stage['received_to_draw_ms'] = overlay.get('received_to_draw_ms')
                if result:
                    stage.update({key: result.get(key) for key in ('round_trip_ms', 'transport_residual_ms')})
                    stage.update({'server_' + key + '_ms': value for key, value in result.get('server_timings_ms', {}).items()})
                row['stage_ms'] = stage
                row['translation_matches_server_cache'] = overlay.get('translation_hash') == self.expected[source]['translation_hash']
                row['passed'] = bool(changed) and row['translation_matches_server_cache'] and row['new_ai_calls'] == 0 and row['new_errors'] == 0
                print(json.dumps({k: row.get(k) for k in ('index', 'source', 'source_to_overlay_ms', 'new_ai_calls', 'passed')}, ensure_ascii=False), flush=True)
                self.save()
                if not row['passed']:
                    raise AssertionError('Cache sample failed correlation, translation hash, cloud-call or error checks; aborted')
            self.output['status'] = 'measured_cache_only'
        except Exception as error:
            self.output['status'] = 'incomplete'
            self.output['failure'] = dict(type=type(error).__name__, message=str(error))
            raise
        finally:
            final = self.status()
            self.output['final'] = self.safe_status(final)
            self.output['new_ai_calls_total'] = final['usage']['requests'] - initial['usage']['requests']
            self.output['checks']['zero_new_ai_calls'] = self.output['new_ai_calls_total'] == 0
            self.save()
            self.client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--n', type=int, default=100)
    parser.add_argument('--serial', default='127.0.0.1:5555')
    parser.add_argument('--roi', nargs=4, type=int, default=[233, 496, 809, 538])
    parser.add_argument('--width', type=int, default=1600)
    parser.add_argument('--height', type=int, default=900)
    parser.add_argument('--subtitle-sp', type=float, default=24)
    parser.add_argument('--source', default='zh')
    parser.add_argument('--profile', default='game')
    parser.add_argument('--mode', default='fast')
    args = parser.parse_args()
    if not 1 <= args.n <= 200:
        parser.error('--n must be 1..200')
    args.timeout, args.resume = 3, None
    CacheBenchmark(args).execute()


if __name__ == '__main__':
    main()
