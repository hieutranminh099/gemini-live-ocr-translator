"""Install portable FFmpeg under the project; never edit system PATH."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import httpx

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.tools/video-runtime/ffmpeg'
URL = 'https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip'
SHA256 = '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba'


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    archive_path = DEST / 'essentials.zip'
    with httpx.Client(follow_redirects=True, timeout=120) as client:
        # Pinned official release asset digest (GitHub release 9.0.2).
        checksum = SHA256
        if len(checksum) != 64 or any(c not in '0123456789abcdef' for c in checksum):
            raise RuntimeError('Invalid published FFmpeg checksum')
        if not archive_path.exists() or hashlib.sha256(archive_path.read_bytes()).hexdigest() != checksum:
            with client.stream('GET', URL) as response:
                response.raise_for_status()
                with archive_path.open('wb') as target:
                    for chunk in response.iter_bytes(1024*1024):
                        target.write(chunk)
        assert hashlib.sha256(archive_path.read_bytes()).hexdigest() == checksum
    written=[]
    with zipfile.ZipFile(archive_path) as archive:
        for name in archive.namelist():
            leaf=Path(name).name
            if leaf not in ('ffmpeg.exe','ffprobe.exe','LICENSE','LICENSE.txt','README.txt'):
                continue
            target=DEST/('bin' if leaf.endswith('.exe') else 'licenses')/leaf
            target.parent.mkdir(parents=True,exist_ok=True)
            with archive.open(name) as source,target.open('wb') as out:
                shutil.copyfileobj(source,out)
            written.append(str(target.relative_to(ROOT)))
    (DEST/'manifest.json').write_text(json.dumps(dict(url=URL,sha256=checksum,files=written),indent=2),encoding='utf-8')
    print(json.dumps(dict(ffmpeg_archive_sha256=checksum,files=written)))


if __name__=='__main__':
    main()
