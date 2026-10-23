"""Start the project-owned headless emulator and wait for Android to boot."""
import json
import os
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
meta=json.loads((ROOT/'.tools/android-tools.json').read_text())
sdk=Path(meta['sdk']); adb=sdk/'platform-tools/adb.exe'
env=os.environ.copy(); env.update(ANDROID_HOME=str(sdk),ANDROID_AVD_HOME=str(ROOT/'.tools/avd'),ANDROID_USER_HOME=str(ROOT/'.tools/android-user'))
serial='emulator-5580'
def shell(*args):
    return subprocess.run([str(adb),'-s',serial,*args],capture_output=True,text=True,timeout=10)
if shell('get-state').stdout.strip()!='device':
    log=(ROOT/'build/emulator-runtime.log').open('w')
    process=subprocess.Popen([str(sdk/'emulator/emulator.exe'),'-avd','GeminiOCR_API35','-port','5580','-no-window','-no-audio',
        '-no-boot-anim','-no-snapshot','-gpu','swiftshader','-accel','on','-memory','2048','-cores','2'],
        env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000)
    (ROOT/'.tools/emulator-process.json').write_text(json.dumps({'pid':process.pid,'serial':serial}))
    print('Headless emulator started.',flush=True)
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    if shell('shell','getprop','sys.boot_completed').stdout.strip()=='1':
        print('Android 15 booted: '+serial,flush=True)
        break
    time.sleep(2)
else: raise RuntimeError('Android did not finish booting; see build/emulator-runtime.log')
shell('shell','settings','put','global','window_animation_scale','0')
shell('shell','settings','put','global','transition_animation_scale','0')
shell('shell','settings','put','global','animator_duration_scale','0')
