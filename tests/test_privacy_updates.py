import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.models import GlossaryImport, Settings
from app.provider import TranslationError
from app.server import Runtime, admin_app, device_app
from app.storage import Store
from app.manager import TranslationManager
from test_system import FakeProvider, frame


class WaitingProvider(FakeProvider):
    def __init__(self):
        super().__init__()
        self.started = asyncio.Event()
        self.release = asyncio.Event()
    async def translate(self, texts, **kwargs):
        self.calls.append((texts,kwargs)); self.started.set()
        await self.release.wait()
        return ['VI: '+t for t in texts],25,12


@pytest.mark.parametrize('operation',['clear_cache','disable_cache','disable_context','edit_glossary'])
async def test_privacy_action_wins_over_inflight_result(tmp_path,operation):
    s=Store(tmp_path); p=WaitingProvider(); m=TranslationManager(s,p)
    task=asyncio.create_task(m.translate(frame(), 'device'))
    await p.started.wait()
    if operation=='clear_cache': s.clear_cache()
    if operation=='disable_cache': s.set('settings',Settings(cache_enabled=False).model_dump(exclude={'api_key'}))
    if operation=='disable_context': s.set('settings',Settings(context_enabled=False).model_dump(exclude={'api_key'}))
    if operation=='edit_glossary': s.put_glossary('Hero','Dũng sĩ','game')
    m.invalidate(); p.release.set()
    with pytest.raises(TranslationError) as error: await task
    assert error.value.code=='configuration_changed'
    assert s.cache_size()==0 and not m.contexts
    assert s.usage()['input_tokens']==25  # Provider usage remains counted even though content is discarded.
    s.close()


async def test_queued_old_configuration_never_reaches_provider(tmp_path):
    s=Store(tmp_path); p=FakeProvider(); m=TranslationManager(s,p)
    await m.semaphore.acquire(); await m.semaphore.acquire()
    task=asyncio.create_task(m.translate(frame(), 'device'))
    await asyncio.sleep(0)
    m.invalidate(); m.semaphore.release(); m.semaphore.release()
    with pytest.raises(TranslationError) as error: await task
    assert error.value.code=='configuration_changed' and p.calls==[] and s.usage()['requests']==0
    s.close()


async def test_tokens_stay_with_request_day_at_utc_midnight(tmp_path):
    s=Store(tmp_path); p=WaitingProvider(); m=TranslationManager(s,p)
    s.day=lambda:'2026-10-22'
    task=asyncio.create_task(m.translate(frame(), 'device'))
    await p.started.wait()
    s.day=lambda:'2026-10-23'; p.release.set()
    await task
    previous=s.db.execute('SELECT requests,input_tokens FROM usage WHERE day=?',('2026-10-22',)).fetchone()
    assert tuple(previous)==(1,25) and s.usage()['input_tokens']==0
    s.close()


def test_glossary_import_is_atomic_and_edit_does_not_leave_old_entry(tmp_path):
    r=Runtime(tmp_path,FakeProvider())
    with TestClient(admin_app(r,Path(__file__).resolve().parents[1]/'windows-server/static')) as client:
        h={'Authorization':'Bearer '+r.admin_token}
        imported=client.post('/api/glossary/import',headers=h,json={'entries':[
            {'source':'Hero','target':'Dũng sĩ','profile':'game'},
            {'source':'Quest','target':'Nhiệm vụ','profile':'game'}]})
        assert imported.status_code==200
        entries=client.get('/api/glossary',headers=h).json()
        hero=next(e for e in entries if e['source']=='Hero')
        assert client.put('/api/glossary/'+str(hero['id']),headers=h,json={'source':'Protagonist','target':'Nhân vật chính','profile':'game'}).status_code==200
        changed=client.get('/api/glossary',headers=h).json()
        assert len(changed)==2 and 'Hero' not in [e['source'] for e in changed]
        assert client.post('/api/glossary/import',headers=h,json={'entries':[
            {'source':'A','target':'Một'},{'source':'Ａ','target':'Hai'}]}).status_code==422
        assert len(client.get('/api/glossary',headers=h).json())==2
        assert client.put('/api/glossary/'+str(hero['id']),headers=h,json={'source':'Quest','target':'Trùng','profile':'game'}).status_code==409
    r.store.close()


def test_glossary_import_limit_rolls_back_every_entry(tmp_path):
    s=Store(tmp_path)
    initial=GlossaryImport(entries=[{'source':str(i),'target':'v'} for i in range(499)])
    s.import_glossary(initial.entries)
    changes=GlossaryImport(entries=[{'source':'0','target':'changed'},{'source':'new-a','target':'v'},{'source':'new-b','target':'v'}])
    with pytest.raises(ValueError): s.import_glossary(changes.entries)
    assert len(s.glossary())==499 and next(e for e in s.glossary() if e['source']=='0')['target']=='v'
    s.close()


def test_capacity_rejection_does_not_look_like_authentication_failure(tmp_path):
    r=Runtime(tmp_path,FakeProvider())
    _,token=r.store.pair('Busy test')
    r.sockets={str(i):object() for i in range(8)}
    with TestClient(device_app(r)) as client:
        with pytest.raises(WebSocketDisconnect) as error:
            with client.websocket_connect('/v1/stream',headers={'Authorization':'Bearer '+token}) as ws:
                ws.receive_json()
        assert error.value.code==4429
        assert r.store.authenticate(token) is not None
        r.sockets.clear()
        with client.websocket_connect('/v1/stream',headers={'Authorization':'Bearer '+token}) as ws:
            assert ws.receive_json()['type']=='hello'
    r.store.close()
