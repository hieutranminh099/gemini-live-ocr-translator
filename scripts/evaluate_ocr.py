"""Measure OCR character/word error rates against a manually labeled dataset.

Input: JSON array of {"language":"ja", "reference":"...", "prediction":"..."}.
This script does not capture or transmit screen content.
"""
import argparse
import json
from pathlib import Path
import unicodedata


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, left in enumerate(a, 1):
        current = [i]
        for j, right in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j-1] + (left != right)))
        previous = current
    return previous[-1]


def evaluate(rows):
    groups = {}
    for row in rows:
        reference = unicodedata.normalize('NFKC', row['reference'])
        predicted = unicodedata.normalize('NFKC', row['prediction'])
        if not reference:
            raise ValueError('Reference must not be empty')
        for language in {row.get('language', 'unknown'), 'all'}:
            g = groups.setdefault(language, dict(samples=0, character_errors=0, reference_characters=0, word_errors=0, reference_words=0))
            g['samples'] += 1
            g['character_errors'] += distance(reference, predicted)
            g['reference_characters'] += len(reference)
            g['word_errors'] += distance(reference.split(), predicted.split())
            g['reference_words'] += len(reference.split())
    return {k: dict(v, cer=round(v['character_errors']/max(1,v['reference_characters']),4),
                    wer=round(v['word_errors']/max(1,v['reference_words']),4)) for k,v in groups.items()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset',type=Path)
    parser.add_argument('--output',type=Path)
    args = parser.parse_args()
    report = json.dumps(evaluate(json.loads(args.dataset.read_text(encoding='utf-8'))),ensure_ascii=False,indent=2)
    if args.output: args.output.write_text(report,encoding='utf-8')
    print(report)
