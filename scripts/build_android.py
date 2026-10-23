import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
metadata = json.loads((root / '.tools/android-tools.json').read_text())
env = os.environ.copy()
env.update(JAVA_HOME=metadata['java'], ANDROID_HOME=metadata['sdk'], GRADLE_USER_HOME=str(root / '.gradle-home'))
tasks = sys.argv[1:] or ['assembleDebug', 'testDebugUnitTest', 'lintDebug']
subprocess.run([metadata['gradle'], '--no-daemon', '--console=plain', *tasks], cwd=root / 'android-app', env=env, check=True)
apk = root / 'android-app/app/build/outputs/apk/debug/app-debug.apk'
if apk.exists() and any(t.rsplit(':', 1)[-1] in ('assembleDebug', 'assemble') for t in tasks) and 'assembleRelease' not in tasks:
    (root / 'dist').mkdir(exist_ok=True)
    shutil.copy2(apk, root / 'dist/GeminiOCR-Android.apk')
    print('APK: ' + str(root / 'dist/GeminiOCR-Android.apk'))
