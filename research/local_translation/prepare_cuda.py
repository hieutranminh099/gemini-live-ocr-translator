"""Extract official NVIDIA cuBLAS wheel to a private directory, no system install."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
wheel = next((ROOT/'.tools/local-translation-wheels').glob('nvidia_cublas_cu12-12.9.2.10-*.whl'))
target = (ROOT/'.tools/local-translation-cuda').resolve()
with zipfile.ZipFile(wheel) as archive:
    for info in archive.infolist():
        if info.filename.endswith('.dll') or 'license' in info.filename.lower():
            destination = (target/info.filename).resolve()
            if not destination.is_relative_to(target):
                raise RuntimeError(f'Unsafe member: {info.filename}')
            archive.extract(info, target)
print('Private DLLs ready; benchmark preloads exact paths in its own process.')
