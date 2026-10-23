"""Run real ML Kit/Android Keystore/WSS tests on the project's Android 15 emulator."""
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit
import httpx

ROOT=Path(__file__).resolve().parents[1]
meta=json.loads((ROOT/'.tools/android-tools.json').read_text())
env=os.environ.copy(); env.update(JAVA_HOME=meta['java'],ANDROID_HOME=meta['sdk'],GRADLE_USER_HOME=str(ROOT/'.gradle-home'),ANDROID_SERIAL='emulator-5580')
adb=Path(meta['sdk'])/'platform-tools/adb.exe'


def command(*args,timeout=180):
    result=subprocess.run([str(adb),'-s','emulator-5580',*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout,check=True)
    return result.stdout


def main():
    assert command('shell','getprop','sys.boot_completed').strip()=='1', 'Run start_emulator.py first'
    subprocess.run([meta['gradle'],'--no-daemon','--console=plain','assembleDebug','assembleDebugAndroidTest'],cwd=ROOT/'android-app',env=env,check=True)
    print(command('install','-r',str(ROOT/'android-app/app/build/outputs/apk/debug/app-debug.apk')),flush=True)
    print(command('install','-r',str(ROOT/'android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')),flush=True)
    url=urlsplit((ROOT/'tests/.runtime-packaged/ui-v11/dashboard.url').read_text())
    with httpx.Client(base_url='http://'+url.netloc,headers={'Authorization':'Bearer '+url.fragment}) as admin:
        status=admin.get('/api/status').json()
        requests_before=status['usage']['requests']
        assert admin.post('/api/glossary',json={'source':'Hello traveler','target':'Xin chào, lữ khách!','profile':'game'}).status_code==200
        paired=admin.post('/api/pairing').json()
        result=command('shell','am','instrument','-w','-r','-e','class','com.gemini.ocr.NativeConnectionTest',
            '-e','serverHost','10.0.2.2:'+str(status['port']),'-e','serverPin',status['fingerprint'],'-e','pairCode',paired['code'],
            'com.gemini.ocr.test/androidx.test.runner.AndroidJUnitRunner')
        (ROOT/'build/native-connection.txt').write_text(result,encoding='utf-8')
        if not re.search(r'OK \(1 test\)',result): raise RuntimeError('Android connection test failed; see build/native-connection.txt')
        print('Native Android: Keystore encryption, pairing, HTTPS/WSS, certificate pin and glossary result PASS',flush=True)
        paired=admin.post('/api/pairing').json()
        result=command('shell','am','instrument','-w','-r','-e','class','com.gemini.ocr.NativeCaptureTest',
            '-e','serverHost','10.0.2.2:'+str(status['port']),'-e','serverPin',status['fingerprint'],'-e','pairCode',paired['code'],
            'com.gemini.ocr.test/androidx.test.runner.AndroidJUnitRunner')
        (ROOT/'build/native-capture.txt').write_text(result,encoding='utf-8')
        if not re.search(r'OK \(1 test\)',result):
            for name in ['capture-permission.xml','capture-permission.png','capture-failure.png']:
                subprocess.run([str(adb),'-s','emulator-5580','pull','/sdcard/Android/data/com.gemini.ocr/files/verification/'+name,str(ROOT/'build'/name)],capture_output=True)
            raise RuntimeError('Native capture test failed; see build/native-capture.txt')
        command('pull','/sdcard/Android/data/com.gemini.ocr/files/verification/native-overlay.png',str(ROOT/'docs/images/android-native-overlay.png'))
        command('pull','/sdcard/Android/data/com.gemini.ocr/files/verification/native-landscape.png',str(ROOT/'docs/images/android-native-landscape.png'))
        print('Native capture: static frame, OCR, subtitle pixels, moving HUD, pause/resume cache, rotation, excluded app, screen lock and stop PASS',flush=True)
        result=command('shell','am','instrument','-w','-r','-e','class','com.gemini.ocr.NativeOcrTest',
            'com.gemini.ocr.test/androidx.test.runner.AndroidJUnitRunner')
        (ROOT/'build/native-ocr.txt').write_text(result,encoding='utf-8')
        if not re.search(r'OK \(5 tests\)',result): raise RuntimeError('Native OCR test failed; see build/native-ocr.txt')
        for device in admin.get('/api/status').json()['devices']:
            if device['name'] in ('Android 15 native test','Android 15 capture test'): admin.delete('/api/devices/'+device['id'])
        print('Native OCR: English, Japanese, Chinese, Korean and numeric HUD filter PASS',flush=True)
        assert admin.get('/api/status').json()['usage']['requests']==requests_before
    (ROOT/'build/native-android-verification.json').write_text(json.dumps({'android':35,'device':'Android Emulator x86_64',
        'connection_tests':1,'capture_tests':1,'ocr_tests':5,'gemini_calls':0,'synthetic_text_only':True,
        'capture_cases':['static_frame','moving_hud','subtitle_screenshot_ocr','pause','local_cache_resume','rotation','excluded_app','screen_lock','stop']},indent=2),encoding='utf-8')


if __name__=='__main__':main()
