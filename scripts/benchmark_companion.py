"""Benchmark the installed Companion with public dialogue; never print its admin token.

This performs real Gemini calls under the existing provider/quota settings. It does
not clear caches, change settings or force a cold session. Cache and fresh samples
are reported separately; CLI timing is available only on the upgraded server.
"""
import argparse
import json
import math
import os
from pathlib import Path
import time
from urllib.parse import urlsplit

import httpx


PUBLIC_DIALOGUE = [
    '你好，先生，请问有什么可以帮你的吗？',
    '我想找一个安静的地方坐一会儿。',
    '窗边还有空位，你可以坐在那里。',
    '谢谢，请给我一杯热茶，不要加糖。',
    '好的，你需要再点一些吃的吗？',
    '我还在等朋友，他很快就到了。',
    '没问题，需要的时候请告诉我。',
    '谢谢你的帮助，今天的天气真好。',
]


def percentiles(values):
    ordered = sorted(values)
    if not ordered:
        return {'n': 0}
    return dict(n=len(ordered), **{
        f'p{p}_ms': round(ordered[max(0, math.ceil(len(ordered) * p / 100) - 1)], 3)
        for p in (50, 90, 95)
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--legacy', action='store_true', help='Omit new fields for the pre-upgrade baseline.')
    parser.add_argument('--sentences', type=Path, help='UTF-8 JSON list of public test sentences.')
    parser.add_argument('--source', choices=('en', 'zh', 'ja', 'ko'), default='zh')
    parser.add_argument('--limit', type=int, default=8)
    args = parser.parse_args()
    texts = json.loads(args.sentences.read_text(encoding='utf-8-sig')) if args.sentences else PUBLIC_DIALOGUE
    if not isinstance(texts, list) or not texts or any(not isinstance(t, str) or not t.strip() for t in texts):
        raise SystemExit('Sentences must be a nonempty JSON list of strings.')
    if not 1 <= args.limit <= 40:
        raise SystemExit('Limit must be between 1 and 40.')
    dashboard = Path(os.environ['LOCALAPPDATA']) / 'GeminiOCR/data/dashboard.url'
    address = urlsplit(dashboard.read_text(encoding='utf-8-sig').strip())
    if address.hostname not in ('localhost', '127.0.0.1') or not address.fragment:
        raise SystemExit('Installed local Companion dashboard is not available.')
    output = dict(description='Public consecutive dialogue; HTTP admin route, not Android capture-to-draw.',
                  percentile_method='nearest rank', samples=[], timestamp_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
    with httpx.Client(base_url=f'http://{address.netloc}', headers={'Authorization': 'Bearer ' + address.fragment}, timeout=90) as client:
        status = client.get('/api/status')
        status.raise_for_status()
        body = status.json()
        settings = body.get('settings', {})
        if settings.get('provider') != 'cli':
            raise SystemExit('Existing provider is not Google account CLI. No settings changed and no calls made.')
        output['server_version'] = body.get('version')
        output['provider'] = 'cli'
        output['mode'] = 'fast'
        for index, text in enumerate(texts[:args.limit], 1):
            request = dict(type='translate', request_id=f'benchmark-{index}', source=args.source, profile='game', mode='fast',
                           blocks=[dict(id='subtitle', text=text, box=dict(x=.1, y=.7, width=.8, height=.1))])
            if not args.legacy:
                request.update(region_id='public-benchmark', screen_generation=index)
            started = time.perf_counter()
            response = client.post('/api/translate', json=request)
            elapsed = round((time.perf_counter() - started) * 1000, 3)
            try:
                result = response.json()
            except ValueError:
                result = {'error': 'Non-JSON server response'}
            sample = dict(index=index, source=text, http_status=response.status_code, roundtrip_ms=elapsed, result=result)
            if response.is_success:
                sample['cache'] = all(b.get('cached', False) for b in result.get('blocks', []))
                sample['transport_overhead_ms'] = round(max(0, elapsed - result.get('latency_ms', 0)), 3)
            output['samples'].append(sample)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(dict(index=index, status=response.status_code, roundtrip_ms=elapsed,
                                  cache=sample.get('cache'), timings_ms=result.get('timings_ms'),
                                  diagnostics=result.get('diagnostics')), ensure_ascii=False), flush=True)
    for name, predicate in [('fresh', lambda s: s.get('cache') is False), ('cache', lambda s: s.get('cache') is True)]:
        output[name] = percentiles([s['roundtrip_ms'] for s in output['samples'] if s['http_status'] == 200 and predicate(s)])
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: output[k] for k in ('fresh', 'cache')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
