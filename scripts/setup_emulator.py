"""Provision an isolated Android 15 emulator for native OCR tests; no driver/Windows feature changes."""
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
meta=json.loads((ROOT/'.tools/android-tools.json').read_text())
sdk=Path(meta['sdk'])
env=os.environ.copy()
env.update(JAVA_HOME=meta['java'],ANDROID_HOME=str(sdk),ANDROID_AVD_HOME=str(ROOT/'.tools/avd'),ANDROID_USER_HOME=str(ROOT/'.tools/android-user'))
for name in ['avd','android-user']:(ROOT/'.tools'/name).mkdir(exist_ok=True)
package='system-images;android-35;default;x86_64'
manager=sdk/'cmdline-tools/12.0/bin/sdkmanager.bat'
subprocess.run([str(manager),'--sdk_root='+str(sdk),'emulator',package],env=env,check=True)
subprocess.run([str(sdk/'emulator/emulator.exe'),'-accel-check'],env=env,check=True)
avd=ROOT/'.tools/avd/GeminiOCR_API35.avd'
if not avd.exists():
    subprocess.run([str(sdk/'cmdline-tools/12.0/bin/avdmanager.bat'),'create','avd','--name','GeminiOCR_API35','--package',package,'--device','pixel_6'],input='no\n',text=True,env=env,check=True)
config=avd/'config.ini'
values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
values.update({'hw.ramSize':'2048','disk.dataPartition.size':'2G','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader','showDeviceFrame':'no'})
config.write_text('\n'.join(k+'='+v for k,v in values.items())+'\n')
print('Isolated Android 15 emulator ready.')
