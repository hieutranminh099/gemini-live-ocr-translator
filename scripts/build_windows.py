import os
from pathlib import Path
import subprocess
import sys
import ssl
import hashlib
import json
import argparse
import io

ROOT = Path(__file__).resolve().parents[1]
env = os.environ.copy()
deps = ROOT / '.python-deps'
if deps.exists(): env['PYTHONPATH'] = str(deps)


def build():
    parser = argparse.ArgumentParser()
    parser.add_argument('--installer-only', action='store_true')
    parser.add_argument('--incremental', action='store_true', help='Reuse PyInstaller analysis when rebuilding the same release after a source repair.')
    args = parser.parse_args()
    from PIL import Image, ImageDraw
    icon = ROOT / 'windows-server/static/app.ico'
    im = Image.new('RGBA',(256,256),(16,28,34,255)); draw=ImageDraw.Draw(im)
    draw.rounded_rectangle((12,12,244,244),radius=55,fill='#9ceacb')
    for y,x1,x2 in [(78,62,194),(124,83,173),(169,106,150)]:
        draw.line((x1,y,x2,y),fill='#16382b',width=17)
    icon_bytes = io.BytesIO()
    im.save(icon_bytes,format='ICO',sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
    if not icon.exists() or icon.read_bytes() != icon_bytes.getvalue():
        icon.write_bytes(icon_bytes.getvalue())
    common = [sys.executable,'-m','PyInstaller','--noconfirm',*([] if args.incremental else ['--clean']),'--onefile','--windowed',
              '--distpath',str(ROOT/'dist'),'--workpath',str(ROOT/'build/windows'),'--specpath',str(ROOT/'build'),
              '--icon',str(icon)]
    # Preserve the bundled runtime's additional CA distrust policy in the frozen standard library.
    runtime_ca_policy = Path(ssl.__file__).with_name('codex-rejected-cas.pem')
    if runtime_ca_policy.exists():
        common += ['--add-data',str(runtime_ca_policy)+';.']
    if not args.installer_only:
        build_companion = common + ['--name','GeminiOCR','--paths',str(ROOT/'windows-server'),
        '--add-data',str(ROOT/'windows-server/static')+';static',
        '--collect-submodules','uvicorn','--collect-submodules','websockets',
        '--collect-all','rapidocr_onnxruntime','--collect-all','onnxruntime',
        '--collect-all','faster_whisper','--collect-all','ctranslate2',
        # NumPy 2.5 imports this module from its C extension; the current
        # PyInstaller NumPy hook cannot discover that dependency statically.
        '--hidden-import','numpy._core._exceptions',
        '--copy-metadata','faster-whisper','--copy-metadata','rapidocr-onnxruntime']
        video_runtime=ROOT/'.tools/video-runtime'
        for name in ('ffmpeg.exe','ffprobe.exe'):
            tool=video_runtime/'ffmpeg/bin'/name
            if not tool.is_file():
                raise RuntimeError('Run scripts/setup_video_tools.py before building the video release.')
            build_companion += ['--add-binary',str(tool)+';video-runtime/ffmpeg/bin']
        for name in ('ocr','ffmpeg/licenses'):
            build_companion += ['--add-data',str(video_runtime/name)+';video-runtime/'+name]
        speech=video_runtime/'whisper-tiny'
        if speech.is_dir():
            for file in speech.iterdir():
                if file.is_file():
                    build_companion += ['--add-data',str(file)+';video-runtime/whisper-tiny']
        build_companion += [str(ROOT/'windows-server/launcher.py')]
        subprocess.run(build_companion,cwd=ROOT,env=env,check=True)
    subprocess.run(common + ['--name','GeminiOCR-Windows-Setup',
        '--add-binary',str(ROOT/'dist/GeminiOCR.exe')+';payload',
        str(ROOT/'windows-server/installer.py')],cwd=ROOT,env=env,check=True)
    print('Built portable Windows app and per-user installer in dist/.')


if __name__ == '__main__': build()
