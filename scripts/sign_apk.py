"""Sign the release APK with a persistent local key. Never distribute .tools/signing/."""
import json
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
metadata = json.loads((ROOT/'.tools/android-tools.json').read_text())
directory = ROOT/'.tools/signing'
directory.mkdir(parents=True,exist_ok=True)
password = directory/'password.txt'
keystore = directory/'gemini-ocr.p12'
if not keystore.exists():
    password.write_text(secrets.token_urlsafe(32),encoding='utf-8')
    subprocess.run([str(Path(metadata['java'])/'bin/keytool.exe'),'-genkeypair','-keystore',str(keystore),
        '-storetype','PKCS12','-storepass:file',str(password),'-alias','gemini-ocr','-keyalg','RSA','-keysize','3072',
        '-validity','10000','-dname','CN=Gemini OCR Local, O=Personal App, C=VN'],check=True,capture_output=True)
unsigned = ROOT/'android-app/app/build/outputs/apk/release/app-release-unsigned.apk'
signer = Path(metadata['sdk'])/'build-tools/35.0.0/apksigner.bat'
target = ROOT/'dist/GeminiOCR-Android.apk'
env = __import__('os').environ.copy(); env['JAVA_HOME']=metadata['java']
subprocess.run([str(signer),'sign','--ks',str(keystore),'--ks-pass','file:'+str(password),
               '--out',str(target),str(unsigned)],env=env,check=True)
result = subprocess.run([str(signer),'verify','--verbose','--print-certs',str(target)],env=env,check=True,capture_output=True,text=True)
(ROOT/'dist/android-signature.txt').write_text(result.stdout,encoding='utf-8')
print('Signed release APK verified: '+str(target))
