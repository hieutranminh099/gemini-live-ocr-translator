"""Explicit developer setup: portable OCR languages and local ASR weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import httpx
import yaml

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'.tools/video-runtime'


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--speech',action='store_true',help='Also download public faster-whisper tiny weights (~75 MiB).')
    args=parser.parse_args()
    DEST.mkdir(parents=True,exist_ok=True)
    with httpx.Client(follow_redirects=True,timeout=120) as client:
        manifest=yaml.safe_load(client.get('https://raw.githubusercontent.com/RapidAI/RapidOCR/main/python/rapidocr/default_models.yaml').raise_for_status().text)
        def entries(obj):
            if isinstance(obj,dict):
                for key,value in obj.items():
                    if key in ('japan_PP-OCRv4_rec_mobile','korean_PP-OCRv4_rec_mobile') and isinstance(value,dict) and '.onnx' in value.get('model_dir',''):
                        yield key,value
                    else:yield from entries(value)
            elif isinstance(obj,list):
                for value in obj:yield from entries(value)
        selected=dict(entries(manifest));ocr=DEST/'ocr';ocr.mkdir(exist_ok=True)
        for name,item in selected.items():
            output=ocr/(name+'.onnx')
            if not output.exists() or hashlib.sha256(output.read_bytes()).hexdigest()!=item['SHA256']:
                with client.stream('GET',item['model_dir']) as response:
                    response.raise_for_status()
                    with output.open('wb') as out:
                        for chunk in response.iter_bytes(1024*1024):out.write(chunk)
            assert hashlib.sha256(output.read_bytes()).hexdigest()==item['SHA256']
            print(json.dumps({'ocr_model':name,'bytes':output.stat().st_size,'sha256':item['SHA256']}),flush=True)
        (ocr/'manifest.json').write_text(json.dumps(selected,indent=2),encoding='utf-8')
    if args.speech:
        os.environ['HF_HOME']=str(DEST/'huggingface-cache')
        from huggingface_hub import HfApi,snapshot_download
        revision=HfApi().model_info('Systran/faster-whisper-tiny',token=False).sha
        target=DEST/'whisper-tiny'
        snapshot_download('Systran/faster-whisper-tiny',revision=revision,token=False,local_dir=target,
            allow_patterns=['model.bin','config.json','tokenizer.json','vocabulary.*','preprocessor_config.json','README.md'])
        hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in target.iterdir() if p.is_file()}
        (target/'manifest.json').write_text(json.dumps(dict(repository='Systran/faster-whisper-tiny',revision=revision,sha256=hashes),indent=2),encoding='utf-8')
        print(json.dumps(dict(speech_model='tiny',revision=revision,files=list(hashes))),flush=True)


if __name__=='__main__':main()
