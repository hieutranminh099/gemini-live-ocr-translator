"""Guarded native Chinese-source removal check with Vietnamese text remaining in ROI."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import time

from PIL import Image

from android_cache_benchmark import CacheBenchmark, source_hash

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk-sha256', help='Installed APK hash independently verified before this check')
cli = parser.parse_args()
args = SimpleNamespace(serial='127.0.0.1:5555', phase='source-removal-v160',
    roi=[233, 496, 809, 538], resume=None, n=1, width=1600, height=900,
    subtitle_sp=24, source='zh', profile='game', mode='fast', timeout=3,
    output='research/results/android-source-disappearance-v160.json')
b = CacheBenchmark(args)
report = dict(measurement='Native selected-Chinese source removed while Vietnamese remains in the same ROI',
    roi=args.roi, source='风有点大。', remaining_text='Xin chào', status='incomplete',
    installed_apk_sha256=cli.apk_sha256,
    limitations=['Correctness check while build runs; not a latency benchmark.',
        'Pixel check uses controlled neutral-scene background above ROI; it is not OCR quality for arbitrary videos.'])


def screenshot(label):
    raw = b.run('exec-out', 'screencap', '-p')
    path = ROOT / f'research/results/android-source-disappearance-{label}.png'
    path.write_bytes(raw)
    # Native neutral scene in this band is uniform blue (34,65,82). A caption's
    # dark backing is distinguishable without relying on screenshot OCR.
    im = Image.open(io.BytesIO(raw)).convert('RGB')
    area = (233, 360, 809, 496)
    dark = sum(1 for r, g, blue in im.crop(area).get_flattened_data() if r < 25 and g < 30 and blue < 35)
    return dict(path=str(path.relative_to(ROOT)).replace('\\', '/'), sha256=hashlib.sha256(raw).hexdigest(),
        inspected_rectangle=list(area), dark_caption_backing_pixels=dark)


initial = b.status()
report['initial'] = b.safe_status(initial)
report['android_version'] = b.run('shell', 'dumpsys', 'package', 'com.gemini.ocr').decode().split('versionName=')[1].splitlines()[0].strip()
try:
    b.guard(initial)
    b.verify_cache()
    source = b.scene(report['source'])
    overlay = b.overlay(report['source'], source['at_ms'])
    report.update(source_drawn=source, original_overlay=overlay, before_image=screenshot('before'))
    remaining = b.scene(report['remaining_text'])
    clear = b.wait(lambda event: event.get('event') == 'source_changed' and event.get('empty') is True and
        event.get('source_hash') == source_hash('') and event['at_ms'] >= remaining['at_ms'], 5)
    time.sleep(1.5)
    events = b.events()
    stale = [event for event in events if event.get('event') == 'overlay_drawn' and event['at_ms'] >= clear['at_ms']]
    requests = [event for event in events if event.get('event') == 'request_sent' and event['at_ms'] >= remaining['at_ms']]
    report.update(remaining_drawn=remaining, source_cleared=clear,
        empty_state_update_ms=clear['at_ms'] - remaining['at_ms'], after_image=screenshot('after'),
        stale_overlay_draws_after_source_cleared=stale, translation_requests_after_vietnamese_only=requests)
    final = b.status()
    report['final'] = b.safe_status(final)
    report['new_ai_calls'] = final['usage']['requests'] - initial['usage']['requests']
    report['new_errors'] = final['metrics']['errors'] - initial['metrics']['errors']
    report['passed'] = report['new_ai_calls'] == 0 and report['new_errors'] == 0 and not stale and not requests and (
        report['before_image']['dark_caption_backing_pixels'] > 1000 and
        report['after_image']['dark_caption_backing_pixels'] == 0)
    report['status'] = 'passed' if report['passed'] else 'failed'
except Exception as error:
    report['failure'] = dict(type=type(error).__name__, message=str(error))
    report['passed'] = False
finally:
    report['events'] = b.events()
    report['final'] = b.safe_status(b.status())
    b.client.close()
    (ROOT / args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({key: report.get(key) for key in ('status', 'passed', 'new_ai_calls', 'new_errors', 'empty_state_update_ms')}, ensure_ascii=False))
if not report.get('passed'):
    raise SystemExit(1)
