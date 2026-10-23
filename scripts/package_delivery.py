"""Create a source archive excluding local tools, generated secrets and test data."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
DIST=ROOT/'dist'
DIST.mkdir(exist_ok=True)
files=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode('utf-8').split('\0')
with zipfile.ZipFile(DIST/'GeminiOCR-Source.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for name in sorted(set(files)):
        if not name: continue
        path=ROOT/name
        if path.is_file(): archive.write(path,arcname='GeminiOCR/'+name)
shutil.copy2(ROOT/'README.md',DIST/'HUONG-DAN.md')
shutil.copy2(ROOT/'docs/VERIFICATION.md',DIST/'KIEM-THU.md')
shutil.copytree(ROOT/'docs',DIST/'docs',dirs_exist_ok=True)
artifacts=['GeminiOCR-Android.apk','GeminiOCR.exe','GeminiOCR-Windows-Setup.exe','GeminiOCR-Source.zip']
checksums='\n'.join(hashlib.sha256((DIST/name).read_bytes()).hexdigest()+'  '+name for name in artifacts)+'\n'
(DIST/'SHA256SUMS.txt').write_text(checksums,encoding='utf-8')
print('Delivery ready: '+str(DIST))
