"""Check benchmark evidence integrity, not quality certification."""
import hashlib
import json
import math
from pathlib import Path
from benchmark import percentiles

HERE = Path(__file__).resolve().parent
corpus = [json.loads(x) for x in (HERE/'dialogue100.jsonl').read_text(encoding='utf-8').splitlines()]
assert len(corpus) == 100 and len({x['source'] for x in corpus}) == 100
digest = hashlib.sha256((HERE/'dialogue100.jsonl').read_bytes()).hexdigest()
count = 0
for file in (HERE/'results').glob('*.json'):
    data = json.loads(file.read_text(encoding='utf-8'))
    if data.get('status') != 'completed':
        continue
    assert data['corpus_sha256'] == digest, file
    assert len(data['rows']) == 100, file
    assert [r['source'] for r in data['rows']] == [r['source'] for r in corpus], file
    assert all(math.isfinite(r['latency_ms']) and r['latency_ms'] > 0 for r in data['rows']), file
    assert all(r['translation'].strip() and r['output_tokens'] < 128 for r in data['rows']), file
    assert percentiles([r['latency_ms'] for r in data['rows']]) == data['latency_ms'], file
    assert data['batch_size'] == 1 and not data['production_hybrid_approved'], file
    count += 1
review = json.loads((HERE/'results/agent-review100.json').read_text(encoding='utf-8'))
assert len(review['rows']) == 100
assert [r['source'] for r in review['rows']] == [r['source'] for r in corpus]
assert sum(r['agent_severity']=='critical' for r in review['rows']) == review['critical_negation_or_number_errors']
print(f'Validated {count} actual runs, {count*100} batch-one inference records, 100 inspection annotations.')
