import asyncio, sys, time, tempfile, pathlib, statistics
sys.path.insert(0, 'windows-server')
from app.storage import Store
from app.manager import TranslationManager
from app.models import TranslateRequest

# --- 1. cost of authenticate() per WebSocket message (SELECT + UPDATE + commit)
d = pathlib.Path(tempfile.mkdtemp())
s = Store(d)
ident, token = s.pair('t')
ts = []
for _ in range(300):
    t = time.perf_counter(); s.authenticate(token); ts.append((time.perf_counter()-t)*1000)
print(f"authenticate(): median {statistics.median(ts):.3f} ms, p95 {sorted(ts)[int(.95*len(ts))]:.3f} ms, max {max(ts):.2f} ms (tmpfs/sandbox disk)")

# --- 2. glossary hash + dict rebuild per request
import json, hashlib
for n in (100, 2000, 10000):
    g = [{'id': i, 'source': f'term{i}', 'target': f'tu{i}', 'profile': 'all'} for i in range(n)]
    t = time.perf_counter()
    for _ in range(20):
        hashlib.sha256(json.dumps(g, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        {x['source']: x['target'] for x in g}
    print(f"glossary {n:>6} entries: {(time.perf_counter()-t)/20*1000:.2f} ms per request")

# --- 3. lost context update with two concurrent live frames
class Fake:
    configured = True
    async def translate(self, texts, **kw):
        await asyncio.sleep(0.05 if texts[0]=='A' else 0.15)   # A finishes first, B last
        return [f'vi-{t}' for t in texts], 1, 1
def req(i, text):
    return TranslateRequest.model_validate(dict(request_id=f'r{i}', region_id='r', screen_generation=1,
        source='en', profile='game', mode='fast', blocks=[dict(id='b', text=text, box=dict(x=0,y=0,width=1,height=1))]))
async def main():
    m = TranslationManager(s, Fake())
    await asyncio.gather(m.translate(req(1,'A'), 'dev'), m.translate(req(2,'B'), 'dev'))
    (key, (ts_, ctx)), = m.contexts.items()
    print("context after two concurrent frames A,B:", [c['source'] for c in ctx])
    await m.close()
try:
    asyncio.run(main())
except Exception as e:
    print("repro 3 failed to run:", type(e).__name__, e)
