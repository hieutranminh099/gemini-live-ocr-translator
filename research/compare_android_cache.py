"""Derive a comparison from retained real cache cohorts without touching the device."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent / 'results'
extra = [
    'Cache hit rate is 100% by fixture construction, not an estimate of real-video cache hit rate.',
    'onDraw completion is a native Canvas callback; final GPU/display presentation is not separately instrumented.']
files = ['android-cache-before-v151.json', 'android-cache-after-v160.json']
reports = []
for name in files:
    path = root / name
    report = json.loads(path.read_text(encoding='utf-8'))
    report['limitations'].extend(item for item in extra if item not in report['limitations'])
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    reports.append(report)
keys = ['android_version', 'installed_apk_sha256', 'successful_samples', 'failed_samples', 'success_only_e2e_ms',
        'phone_ram_cache_e2e_ms', 'server_cache_e2e_ms', 'stage_success_only_percentiles_ms', 'new_ai_calls_total']
output = dict(
    measurement='Same Android native source onDraw to overlay onDraw; controlled cache-only revisits',
    conditions=dict(source_sentences_unique=6, samples_each_phase=100, roi=[233, 496, 809, 538],
        server_version='1.5.0', provider_new_calls=0, api_benchmark='not_run',
        cli_benchmark='No new calls; consumer wrapping unsupported'),
    before={key: reports[0][key] for key in keys},
    after={key: reports[1][key] for key in keys},
    stale_draws={files[i]: dict(physical=len(report['physical_source_mismatches']),
        known_ocr=len(report['known_ocr_source_mismatches'])) for i, report in enumerate(reports)},
    limitations=extra + [
        '100% application cache by design; not evidence for new Gemini/API/local translation latency or language quality.',
        'One cohort per APK; small improvement may include run-to-run emulator variability.',
        'No 100-case cloud misses measured; no P95 cloud target claim.'])
(root / 'android-cache-comparison.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
print('Wrote cache-only comparison from retained 100 + 100 measurements')
