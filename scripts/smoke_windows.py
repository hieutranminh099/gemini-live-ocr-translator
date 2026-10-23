"""Run the packaged EXE and verify real HTTPS/WSS, QR, DPAPI, glossary, revocation and shutdown."""
import hashlib
import json
from pathlib import Path
import ssl
import subprocess
import time
import tempfile
import socket
from urllib.parse import urlsplit

import httpx
from websockets.sync.client import connect
from websockets.exceptions import ConnectionClosedError

ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'tests/.runtime-packaged'
SCRATCH.mkdir(parents=True,exist_ok=True)
DATA = Path(tempfile.mkdtemp(prefix='smoke-',dir=SCRATCH))


def main():
    exe = ROOT / 'dist/GeminiOCR.exe'
    process = subprocess.Popen([str(exe), '--data-dir', str(DATA), '--port', '8965', '--admin-port', '8966', '--no-browser'],creationflags=0x08000000)
    try:
        deadline = time.monotonic()+40
        with httpx.Client(base_url='http://127.0.0.1:8966',timeout=5) as admin:
            while time.monotonic()<deadline:
                try:
                    url=(DATA/'dashboard.url').read_text()
                    token=urlsplit(url).fragment
                    admin.headers['Authorization']='Bearer '+token
                    response=admin.get('/api/status')
                    if response.status_code==200: break
                except (OSError,httpx.HTTPError): pass
                if process.poll() is not None: raise RuntimeError('Packaged companion exited unexpectedly')
                time.sleep(.25)
            else: raise RuntimeError('Packaged companion did not become ready')
            status=response.json()
            assert status['version']=='1.7.1'
            assert status['cli']['client']=='Antigravity CLI'
            assert status['settings']['personal_cli_enabled'] is False
            assert status['cli']['experimental_enabled'] is False
            assert status['cli']['supported_backend'] is False
            second=subprocess.run([str(exe),'--data-dir',str(DATA),'--port','8965','--admin-port','8966','--no-browser'],creationflags=0x08000000,timeout=15)
            assert second.returncode==0 and process.poll() is None
            assert urlsplit((DATA/'dashboard.url').read_text()).fragment==token
            page=admin.get('/')
            assert page.status_code==200
            assert 'https://www.antigravity.google/docs/faq/' in page.text
            checkbox=page.text.split('id="personal-cli-enabled"',1)[1].split('>',1)[0]
            assert 'type="checkbox"' in checkbox and 'checked' not in checkbox
            for action in ('install','login','check','turbo'):
                button=page.text.split('id="cli-'+action+'"',1)[1].split('>',1)[0]
                assert 'disabled' in button
            assert admin.get('/assets/app.js').status_code==200
            assert not status['api_configured']
            tls=ssl.create_default_context(cafile=str(DATA/'server.pem'))
            tls.check_hostname=False # The app verifies the explicit paired fingerprint instead of a LAN hostname.
            with httpx.Client(base_url='https://127.0.0.1:8965',verify=tls) as device:
                assert device.get('/health').json()['protocol']==1
                code=admin.post('/api/pairing').json()['code']
                qr=admin.get('/api/pairing/qr',params={'host':status['addresses'][0]})
                assert qr.status_code==200 and b'<svg' in qr.content
                paired=device.post('/v1/pair',json={'name':'Packaged smoke test','code':code}).json()
                assert device.post('/v1/pair',json={'name':'Test','code':code}).status_code==403
                assert admin.post('/api/glossary',json={'source':'Hello test','target':'Xin chào thử nghiệm','profile':'game'}).status_code==200
                imported=admin.post('/api/glossary/import',json={'entries':[{'source':'Quest','target':'Nhiệm vụ','profile':'game'}]})
                assert imported.status_code==200
                quest=next(e for e in admin.get('/api/glossary').json() if e['source']=='Quest')
                assert admin.put('/api/glossary/'+str(quest['id']),json={'source':'Quest updated','target':'Nhiệm vụ mới','profile':'game'}).status_code==200
                with connect('wss://127.0.0.1:8965/v1/stream',ssl=tls,additional_headers={'Authorization':'Bearer '+paired['token']},open_timeout=10) as ws:
                    assert json.loads(ws.recv(timeout=5))['type']=='hello'
                    certificate=ws.socket.getpeercert(binary_form=True)
                    assert hashlib.sha256(certificate).hexdigest()==status['fingerprint']
                    request={'type':'translate','request_id':'one','source':'en','profile':'game','mode':'smart',
                        'blocks':[{'id':'1','text':'Hello test','box':{'x':0,'y':0,'width':1,'height':.2}}]}
                    ws.send(json.dumps(request)); result=json.loads(ws.recv(timeout=5))
                    assert result['blocks'][0]['text']=='Xin chào thử nghiệm'
                    assert admin.get('/api/status').json()['usage']['requests']==0
                    ws.send(json.dumps({'type':'ping'})); assert json.loads(ws.recv(timeout=5))['type']=='pong'
                    request['request_id']='two'; request['blocks'][0]['text']='This should report a missing API key'
                    ws.send(json.dumps(request)); error=json.loads(ws.recv(timeout=5))
                    assert error['code']=='no_api_key'
                    assert admin.get('/api/status').json()['usage']['requests']==0
                    settings=status['settings']
                    assert admin.put('/api/settings',json={**settings,'api_key':'test-key-not-real'}).status_code==200
                    assert admin.get('/api/status').json()['api_configured']
                    assert admin.put('/api/settings',json={**settings,'api_key':''}).status_code==200
                    assert not admin.get('/api/status').json()['api_configured']
                    assert admin.delete('/api/devices/'+paired['device_id']).status_code==200
                    try:
                        for _ in range(5): ws.recv(timeout=5)
                    except ConnectionClosedError as closed:
                        assert closed.rcvd.code==4401
                assert admin.delete('/api/cache').status_code==200
                started=time.monotonic()
                cli_check=admin.post('/api/cli/check',timeout=25)
                assert cli_check.status_code==400 and 'không hỗ trợ' in cli_check.json()['detail']
                assert time.monotonic()-started<25 and not admin.get('/api/status').json()['cli']['checked']
                cli_login=admin.post('/api/cli/login')
                assert cli_login.status_code==400 and 'không hỗ trợ' in cli_login.json()['detail']
                assert admin.put('/api/settings',json={**settings,'provider':'cli'}).status_code==200
                cli_off=admin.get('/api/status').json()
                assert cli_off['settings']['provider']=='cli'
                assert cli_off['backend_mode']=='cache_only'
                assert cli_off['provider_configured'] is False
                denied=admin.post('/api/translate',json={**request,'request_id':'unsupported-cli',
                    'blocks':[{'id':'1','text':'A new public test sentence','box':{'x':0,'y':0,'width':1,'height':1}}]})
                assert denied.status_code==400 and 'không hỗ trợ' in denied.json()['detail']
                assert admin.get('/api/status').json()['usage']['requests']==0
                # Persist the explicit opt-in in isolated test data without invoking the CLI.
                assert admin.put('/api/settings',json={**settings,'provider':'cli','personal_cli_enabled':True}).status_code==200
                cli_on=admin.get('/api/status').json()
                assert cli_on['settings']['personal_cli_enabled'] is True
                assert cli_on['cli']['experimental_enabled'] is True
                assert cli_on['cli']['supported_backend'] is False
                assert cli_on['backend_mode']=='cli_experimental'
                assert cli_on['provider_configured']==cli_on['translation_available']
                assert cli_on['usage']['requests']==0
                assert admin.put('/api/settings',json={**cli_on['settings'],'personal_cli_enabled':False}).status_code==200
                disabled=admin.get('/api/status').json()
                assert disabled['backend_mode']=='cache_only' and disabled['cli']['experimental_enabled'] is False
                assert disabled['usage']['requests']==0
                assert admin.post('/api/shutdown').status_code==200
        process.wait(timeout=15)
        assert process.returncode==0
        # An unrelated process occupying a port must produce a clear failure, not a successful no-op.
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1',0)); occupied.listen()
            conflict_port=occupied.getsockname()[1]
            failed=subprocess.run([str(exe),'--data-dir',str(DATA/'port-conflict'),'--port','8965','--admin-port',str(conflict_port),'--no-browser'],creationflags=0x08000000,timeout=15)
            assert failed.returncode!=0
            assert 'RuntimeError' in (DATA/'port-conflict/server.log').read_text(encoding='utf-8')
        print('Packaged companion: personal CLI defaults off, explicit opt-in persists without AI calls, default-off miss blocked, single instance, occupied port, HTTPS, certificate identity, QR, WSS, glossary import/edit, DPAPI, revocation, cache and shutdown PASS')
    finally:
        if process.poll() is None:
            process.terminate(); process.wait(timeout=10)


if __name__=='__main__': main()
