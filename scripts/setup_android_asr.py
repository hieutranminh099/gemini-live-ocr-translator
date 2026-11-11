"""Fetch pinned public ASR build dependencies; never reads application/user data."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REV = '2365baeacb507f821a0c8120fcee3d484dba7a07'
MODEL_BASE = f'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/{REV}/'
ASSETS = {
    'sherpa-onnx-static-link-onnxruntime-2.0.1.aar': (
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar',
        '07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575'),
    'model.int8.onnx': (MODEL_BASE + 'model.int8.onnx', 'c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51'),
    'silero_vad.onnx': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx',
        '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'),
    'tokens.txt': (MODEL_BASE + 'tokens.txt', 'f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc'),
    'zh.wav': (MODEL_BASE + 'test_wavs/zh.wav', 'b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373'),
    'BAC009S0764W0121.wav': ('https://isv-data.oss-cn-hangzhou.aliyuncs.com/ics/MaaS/ASR/test_audio/BAC009S0764W0121.wav', '46dbc998c9d1d48111267c40741dd3200f2e5bcf4075f8c4c97f4451160dce50'),
    'BAC009S0916W0489.wav': ('https://isv-data.oss-cn-hangzhou.aliyuncs.com/ics/MaaS/ASR/test_audio/BAC009S0916W0489.wav', '4593e8058d8616cfca49aa4892215a8bd75adaf75562db89b262e57e22ec9ce0'),
}


def fetch(name: str, directory: Path) -> None:
    url, expected = ASSETS[name]
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / name
    if target.exists():
        actual = hashlib.file_digest(target.open('rb'), 'sha256').hexdigest()
        if expected and actual != expected:
            raise ValueError(f'Preserve unexpected existing file: {target.name}')
    else:
        partial = target.with_suffix(target.suffix + '.part')
        if partial.exists():
            raise ValueError(f'Preserve previous partial download: {partial.name}')
        digest = hashlib.sha256()
        request = urllib.request.Request(url, headers={'User-Agent': 'GeminiOCR-ASR-Setup/1.8.7'})
        with urllib.request.urlopen(request, timeout=90) as response, partial.open('xb') as output:
            while data := response.read(1024 * 1024):
                output.write(data)
                digest.update(data)
        actual = digest.hexdigest()
        if expected and actual != expected:
            raise ValueError(f'Download hash mismatch; partial retained: {partial.name}')
        partial.rename(target)
    print(f'{name}: {target.stat().st_size} bytes SHA256 {actual}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verification-models', action='store_true', help='Also fetch official model/VAD/Mandarin sample for isolated native verification')
    args = parser.parse_args()
    library = ROOT / 'android-app/app/libs'
    artifact = 'sherpa-onnx-static-link-onnxruntime-2.0.1.aar'
    fetch(artifact, library)
    # The upstream x86 build uses shared ORT. Preserve the app's existing x86 ORT
    # and bundle Sherpa only on the three ABIs where its ORT is statically linked.
    app_aar = library / 'sherpa-onnx-static-link-onnxruntime-2.0.1-app.aar'
    temporary = app_aar.with_suffix('.part')
    with zipfile.ZipFile(library / artifact) as original, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as repacked:
        for member in original.infolist():
            if not member.filename.startswith('jni/x86/'):
                repacked.writestr(member, original.read(member))
    temporary.replace(app_aar)
    if args.verification_models:
        for asset in ('tokens.txt', 'model.int8.onnx', 'silero_vad.onnx', 'zh.wav', 'BAC009S0764W0121.wav', 'BAC009S0916W0489.wav'):
            fetch(asset, ROOT / 'build/asr-187/public-models')
