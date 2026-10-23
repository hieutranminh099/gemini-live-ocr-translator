"""Isolated OPUS-MT/CTranslate2 research; never imported by production.

One sentence per call, no cache, serial decoding. Latencies include
SentencePiece encode + native inference + decode, excluding model load.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import sys
import threading
import time
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.tools/local-translation-deps'))
# Optional official NVIDIA wheel, extracted privately; no PATH/driver changes.
DLL_HANDLES = []
if os.name == 'nt':
    for binary in (ROOT / '.tools/local-translation-cuda').glob('**/bin'):
        DLL_HANDLES.append(os.add_dll_directory(str(binary)))
import ctranslate2
import psutil
import sacrebleu
import sentencepiece as spm


def percentiles(values):
    vals = sorted(values)
    def q(p):
        i = (len(vals)-1)*p
        lo, hi = math.floor(i), math.ceil(i)
        return round(vals[lo] + (vals[hi]-vals[lo])*(i-lo), 3)
    return {k: q(p) for k, p in [('p50', .5), ('p90', .9), ('p95', .95), ('p99', .99)]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cpu')
    parser.add_argument('--compute-type', default='int8')
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--beam', type=int, default=1)
    parser.add_argument('--model', default='.tools/local-translation-model/int8')
    parser.add_argument('--corpus', default='research/local_translation/dialogue100.jsonl')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    rows = [json.loads(x) for x in (ROOT / args.corpus).read_text(encoding='utf-8').splitlines()]
    assert len(rows) >= 100
    assert len({r['source'] for r in rows}) == len(rows)
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    proc = psutil.Process()
    baseline = proc.memory_info().rss
    peak = [baseline]
    stop = threading.Event()
    def monitor():
        while not stop.wait(.02):
            peak[0] = max(peak[0], proc.memory_info().rss)
    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    report = {'schema': 1, 'configuration': vars(args), 'count': len(rows),
              'ctranslate2': ctranslate2.__version__, 'sentencepiece': spm.__version__,
              'sacrebleu': sacrebleu.__version__, 'python': platform.python_version(),
              'cache': 'disabled; each source unique', 'batch_size': 1,
              'quality_gate_predeclared': {'chrF_min': 55, 'critical_negation_or_number_errors_max': 0,
                                           'manual_review_required': True},
              'corpus_sha256': hashlib.sha256((ROOT / args.corpus).read_bytes()).hexdigest(),
              'baseline_rss_mib': baseline/1048576, 'rows': []}
    try:
        if args.device == 'cuda' and os.name == 'nt':
            import ctypes
            # CT2's native loader does not consult AddDllDirectory in this build.
            # Preload exact project-local paths; keep handles for this process.
            for library in ['cublasLt64_12.dll', 'cublas64_12.dll']:
                found = list((ROOT / '.tools/local-translation-cuda').glob(f'**/{library}'))
                if found:
                    DLL_HANDLES.append(ctypes.WinDLL(str(found[0])))
        begin = time.perf_counter_ns()
        original = ROOT / '.tools/local-translation-model/original'
        source = spm.SentencePieceProcessor(model_file=str(original/'source.spm'))
        target = spm.SentencePieceProcessor(model_file=str(original/'target.spm'))
        translator = ctranslate2.Translator(str(ROOT / args.model), device=args.device,
                                            compute_type=args.compute_type,
                                            inter_threads=1, intra_threads=args.threads)
        report['load_ms'] = (time.perf_counter_ns()-begin)/1e6
        def translate(text):
            # Marian training preprocessing replaces fullwidth ASCII punctuation.
            # NFKC also normalizes fullwidth digits; no source content is removed.
            text = unicodedata.normalize('NFKC', text).strip()
            tokens = source.encode(text, out_type=str)
            result = translator.translate_batch([tokens], beam_size=args.beam,
                                                max_decoding_length=128,
                                                return_scores=False)[0]
            return target.decode(result.hypotheses[0]), len(tokens), len(result.hypotheses[0])
        begin = time.perf_counter_ns()
        cold, _, _ = translate('你好，请问你需要什么帮助？')
        report['first_inference_ms'] = (time.perf_counter_ns()-begin)/1e6
        report['first_inference_text'] = cold
        for i, row in enumerate(rows):
            begin = time.perf_counter_ns()
            translated, src_tokens, out_tokens = translate(row['source'])
            elapsed = (time.perf_counter_ns()-begin)/1e6
            report['rows'].append({**row, 'translation': translated, 'latency_ms': elapsed,
                                   'source_tokens': src_tokens, 'output_tokens': out_tokens})
            if (i+1) % 25 == 0:
                print(f'{args.device} threads={args.threads} beam={args.beam} {i+1}/{len(rows)}', flush=True)
        hypotheses = [r['translation'] for r in report['rows']]
        references = [r['reference'] for r in report['rows']]
        bleu_metric = sacrebleu.metrics.BLEU(tokenize='13a')
        chrf_metric = sacrebleu.metrics.CHRF()
        bleu = bleu_metric.corpus_score(hypotheses, [references])
        chrf = chrf_metric.corpus_score(hypotheses, [references])
        report['bleu'] = {'score': bleu.score, 'signature': str(bleu_metric.get_signature())}
        report['chrf'] = {'score': chrf.score, 'signature': str(chrf_metric.get_signature())}
        report['latency_ms'] = percentiles([r['latency_ms'] for r in report['rows']])
        report['empty_outputs'] = sum(not x.strip() for x in hypotheses)
        report['quality_automatic_gate_passed'] = chrf.score >= 55
        report['manual_review_complete'] = False
        report['production_hybrid_approved'] = False
        report['status'] = 'completed'
    except Exception as e:
        report['status'] = 'failed'
        report['error'] = f'{type(e).__name__}: {e}'
    finally:
        stop.set()
        thread.join(1)
        peak[0] = max(peak[0], proc.memory_info().rss)
        report['peak_rss_mib'] = peak[0]/1048576
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k != 'rows'}, ensure_ascii=False), flush=True)
    return 0 if report['status'] == 'completed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
