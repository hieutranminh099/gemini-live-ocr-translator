"""Real Android retry regression with public text; never calls an active cloud backend.

Requires the installed Windows >=1.5.0 consumer CLI guard, an active paired Android
capture session, the native TestScene APK, and the existing Chinese/game/fast fixture.
Keeps ROI/settings/pairing unchanged. Observe >60s to cross the old retry deadline.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'research'))
from android_cache_benchmark import CacheBenchmark
from benchmark_subtitle_pipeline import source_hash


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',default='127.0.0.1:5555')
    parser.add_argument('--hold-seconds',type=float,default=66)
    parser.add_argument('--output',default='docs/results/configuration-retry-v161.json')
    args=parser.parse_args()
    if args.hold_seconds < 61: parser.error('Observe at least61 seconds to cross old60s retry deadline')
    args.phase='configuration-retry-v161'; args.roi=[233,496,809,538]
    args.width=1600; args.height=900; args.subtitle_sp=24; args.timeout=15
    args.resume=None; args.n=0; args.source='zh'; args.profile='game'; args.mode='fast'
    bench=CacheBenchmark(args)
    data=dict(measurement='Correctness regression for permanent configuration errors; not a translation speed benchmark',
        roi_preserved=args.roi, checks={}, limitations=['Controlled public native scene, not continuous video or cloud translation.'])
    def save():
        data['events']=bench.events()
        (ROOT/args.output).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    try:
        before=bench.status(); bench.guard(before)
        data['before']=bench.safe_status(before)
        apk=bench.run('shell','pm','path','com.gemini.ocr').decode().strip().removeprefix('package:')
        data['installed_apk_sha256']=bench.run('shell','sha256sum',apk).decode().split()[0]
        assert data['installed_apk_sha256']==hashlib.sha256((ROOT/'dist/GeminiOCR-Android.apk').read_bytes()).hexdigest()
        pkg=bench.run('shell','dumpsys','package','com.gemini.ocr').decode()
        assert 'versionName=1.6.1' in pkg and 'versionCode=12 ' in pkg
        missing='连接未配置'
        response=bench.client.post('/api/translate',json=dict(request_id='retry-public-cache-miss',source='zh',profile='game',mode='fast',
            blocks=[dict(id='b0',text=missing,box=dict(x=0,y=0,width=.8,height=.1))]))
        assert response.status_code==400 and 'Google không hỗ trợ dùng Antigravity CLI' in response.json().get('detail',''),response.status_code
        data['checks']['guarded_public_cache_miss']='cli_configuration'
        cached='风有点大。'
        response=bench.client.post('/api/translate',json=dict(request_id='retry-public-cache-hit',source='zh',profile='game',mode='fast',
            blocks=[dict(id='b0',text=cached,box=dict(x=0,y=0,width=.8,height=.1))]))
        assert response.status_code==200
        cached_block=response.json()['blocks'][0]
        assert cached_block['cached'] is True and cached_block['text'].strip()
        expected_translation_hash=hashlib.sha256(cached_block['text'].encode()).hexdigest()[:16]
        data['cache_expected_translation_hash']=expected_translation_hash
        scene=bench.scene(missing)
        failure=bench.wait(lambda e:e.get('event')=='request_failed' and e.get('code')=='cli_configuration' and e['at_ms']>=scene['at_ms'])
        assert failure['automatic_retry'] is False
        data['first_failure']=failure
        screenshot=ROOT/'docs/images/configuration-error-v161.png'
        screenshot.write_bytes(bench.run('exec-out','screencap','-p'))
        print('Configuration error received; observing beyond old60s retry deadline.',flush=True)
        started=time.monotonic()
        while time.monotonic()-started < args.hold_seconds:
            bench.events()
            time.sleep(.75)
        events=bench.events()
        same=[e for e in events if e.get('event')=='request_sent' and e['at_ms']>=scene['at_ms']]
        assert len(same)==1,{'unexpected_request_count':len(same)}
        assert not any(e.get('event')=='overlay_drawn' and e['at_ms']>=scene['at_ms'] for e in events)
        data['checks']['same_sentence_requests_over_hold']=len(same)
        data['hold_seconds']=round(time.monotonic()-started,2)
        print('No repeated request after old retry deadline; testing existing server cache.',flush=True)
        scene2=bench.scene(cached)
        overlay=bench.overlay(cached,scene2['at_ms'])
        result=bench.wait(lambda e:e.get('event')=='result_received' and e.get('request_id')==overlay['request_id'] and e['at_ms']>=scene2['at_ms'])
        assert result['server_timings_ms']['provider']==0
        assert overlay['translation_hash']==expected_translation_hash
        data['cached_sentence_overlay']=overlay
        data['checks']['new_sentence_server_cache_still_renders']=True
        scene3=bench.scene(missing)
        bench.wait(lambda e:e.get('event')=='source_changed' and e.get('source_hash')==source_hash(missing) and e['at_ms']>=scene3['at_ms'])
        time.sleep(2)
        assert not any(e.get('event')=='request_sent' and e['at_ms']>=scene3['at_ms'] for e in bench.events())
        data['checks']['return_to_failed_sentence_does_not_resend']=True
        (ROOT/'docs/images/configuration-error-after-hold-v161.png').write_bytes(bench.run('exec-out','screencap','-p'))
        final=bench.status(); data['after']=bench.safe_status(final)
        assert final['usage']['requests']==before['usage']['requests']
        data['checks']['new_ai_calls']=0
        data['status']='passed'
    except Exception as exc:
        data['status']='failed'; data['error']=type(exc).__name__+': '+str(exc)
        raise
    finally:
        save(); bench.client.close()
    print(json.dumps({k:v for k,v in data.items() if k not in ('events','before','after')},ensure_ascii=False),flush=True)


if __name__=='__main__': main()
