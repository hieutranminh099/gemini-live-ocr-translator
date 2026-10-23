"""Download an isolated Android build toolchain into this project (Windows x64)."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)


def read(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def download(url, name, digest=None, algorithm='sha256'):
    target = TOOLS / name
    if not target.exists():
        print(f'Downloading {name}', flush=True)
        with urllib.request.urlopen(url, timeout=180) as src, target.with_suffix('.part').open('wb') as out:
            while data := src.read(1024 * 1024):
                out.write(data)
        target.with_suffix('.part').replace(target)
    if digest and hashlib.new(algorithm, target.read_bytes()).hexdigest().lower() != digest.lower():
        raise RuntimeError(f'Checksum mismatch: {target}')
    return target


def main():
    jdk = next((p for p in TOOLS.iterdir() if p.is_dir() and (p / 'bin/javac.exe').exists()), None)
    if jdk is None:
        assets = json.loads(read('https://api.azul.com/metadata/v1/zulu/packages/?java_version=17&os=windows&arch=x86&archive_type=zip&java_package_type=jdk&latest=true&release_status=ga'))
        asset = next(a for a in assets if 'win_x64' in a['download_url'] and '-crac-' not in a['download_url'] and '-fx-' not in a['download_url'])
        archive = download(asset['download_url'], 'jdk.zip')
        with zipfile.ZipFile(archive) as z:
            z.extractall(TOOLS)
        jdk = next(p for p in TOOLS.iterdir() if p.is_dir() and (p / 'bin/javac.exe').exists())
    gradle = TOOLS / 'gradle-8.11.1'
    if not gradle.exists():
        url = 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip'
        archive = download(url, 'gradle.zip', read(url + '.sha256').decode().strip())
        with zipfile.ZipFile(archive) as z:
            z.extractall(TOOLS)
    sdk = TOOLS / 'android-sdk'
    manager = sdk / 'cmdline-tools/12.0/bin/sdkmanager.bat'
    if not manager.exists():
        repo = ET.fromstring(read('https://dl.google.com/android/repository/repository2-1.xml'))
        package = next(p for p in repo.findall('remotePackage') if p.get('path') == 'cmdline-tools;12.0')
        archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') == 'windows')
        relative = archive.findtext('complete/url')
        checksum = archive.find('complete/checksum')
        zip_path = download('https://dl.google.com/android/repository/' + relative, 'commandline-12.zip', checksum.text, checksum.get('type', 'sha1'))
        staging = TOOLS / 'sdk-extract-12'
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(staging)
        manager.parent.parent.parent.mkdir(parents=True, exist_ok=True)
        (staging / 'cmdline-tools').rename(manager.parent.parent)
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['ANDROID_HOME'] = str(sdk)
    env['GRADLE_USER_HOME'] = str(ROOT / '.gradle-home')
    print('Installing Android SDK 35 and build tools; Android SDK license acceptance applies.', flush=True)
    subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'], input='y\n' * 100, text=True, env=env, check=True)
    subprocess.run([str(manager), '--sdk_root=' + str(sdk), 'platforms;android-35', 'build-tools;35.0.0', 'platform-tools'], env=env, check=True)
    (ROOT / 'android-app').mkdir(exist_ok=True)
    (ROOT / 'android-app/local.properties').write_text('sdk.dir=' + sdk.as_posix() + '\n')
    metadata = {'java': str(jdk), 'gradle': str(gradle / 'bin/gradle.bat'), 'sdk': str(sdk)}
    (TOOLS / 'android-tools.json').write_text(json.dumps(metadata))
    print('Android toolchain ready.', flush=True)


if __name__ == '__main__':
    main()
