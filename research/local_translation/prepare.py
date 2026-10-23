"""Download public inputs into this project; never changes global Python or CUDA.

Install requirements.txt with pip --target .tools/local-translation-deps first.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import random
import shutil
import sys
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MODELS = ROOT / '.tools/local-translation-model'
REVISION = '5509af44a74016748c26f41ab164b64f48445f3f'
MODEL_URL = 'https://object.pouta.csc.fi/Tatoeba-MT-models/zho-vie/opus-2020-06-17.zip'
CORPUS_URL = f'https://raw.githubusercontent.com/Helsinki-NLP/Tatoeba-Challenge/{REVISION}/data/test/vie-zho/test.txt'


def fetch(url, destination, expected):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        with urlopen(url, timeout=60) as source, destination.open('wb') as target:
            shutil.copyfileobj(source, target)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    if digest != expected:
        raise RuntimeError(f'SHA256 mismatch: {destination}; preserve the file and investigate')


def main():
    fetch(MODEL_URL, MODELS / 'original.zip', '4051997e89f4a016fd043eb4a59603868f70e1200331c11169de7ee0e91911bf')
    fetch(CORPUS_URL, HERE / 'tatoeba-vie-zho.tsv', '637d6b47c5e8ac5c31348f8bc63ef0ca4ee35299e795ac92acb21d992c64ecb5')
    original = MODELS / 'original'
    original.mkdir(exist_ok=True)
    with zipfile.ZipFile(MODELS / 'original.zip') as archive:
        # The official archive has flat files. Explicitly reject path traversal.
        for info in archive.infolist():
            if '/' in info.filename or '\\' in info.filename or info.filename.startswith('.'):
                raise RuntimeError(f'Unexpected archive member: {info.filename}')
            archive.extract(info, original)
    rows = []
    seen = set()
    for line_number, line in enumerate((HERE / 'tatoeba-vie-zho.tsv').read_text(encoding='utf-8').splitlines(), 1):
        vi_lang, zh_lang, vi, zh = line.split('\t')
        if any(x in zh for x in '我你您咱?？') and 3 <= len(zh) <= 42 and zh not in seen:
            seen.add(zh)
            rows.append({'id': f'tatoeba-{line_number}', 'source': zh, 'reference': vi,
                         'source_language': zh_lang, 'reference_language': vi_lang,
                         'provenance': 'Helsinki-NLP/Tatoeba-Challenge data/test/vie-zho/test.txt',
                         'original_line': line_number})
    random.Random(20261023).shuffle(rows)
    (HERE / 'dialogue100.jsonl').write_text('\n'.join(json.dumps(x, ensure_ascii=False) for x in rows[:100])+'\n', encoding='utf-8')
    sys.path.insert(0, str(ROOT / '.tools/local-translation-deps'))
    import ctranslate2
    converter = ctranslate2.converters.MarianConverter(
        str(next(original.glob('*.npz'))), [str(original / 'opus.spm32k-spm32k.vocab.yml')]*2)
    for quantization in ['int8', 'float32']:
        destination = MODELS / quantization
        if not destination.exists():
            converter.convert(str(destination), quantization=quantization)
    licenses = HERE / 'licenses'
    licenses.mkdir(exist_ok=True)
    shutil.copyfile(original / 'LICENSE', licenses / 'OPUS-original-CC-BY-4.0.txt')
    print('Prepared verified public inputs; no production application files changed.')


if __name__ == '__main__':
    main()
