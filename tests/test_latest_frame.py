import asyncio
from pathlib import Path
from fastapi.testclient import TestClient
import httpx
import pytest
from app.manager import TranslationManager
from app.provider import GeminiProvider, TranslationError
from app.server import Runtime, device_app
from app.storage import Store
from test_system import FakeProvider, frame


class SlowFirst(FakeProvider):
    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        await asyncio.sleep(1 if texts[0]=='old' else .01)
        return ['VI: '+t for t in texts], 10, 5


def test_new_frame_cancels_previous_result(tmp_path):
    p = SlowFirst(); r = Runtime(tmp_path,p)
    _, token = r.store.pair('Test Android')
    with TestClient(device_app(r)) as client:
        with client.websocket_connect('/v1/stream', headers={'Authorization':'Bearer '+token}) as ws:
            ws.receive_json()
            old = frame(('old',)); old.request_id='old'
            new = frame(('new',)); new.request_id='new'
            ws.send_json(old.model_dump()); ws.send_json(new.model_dump())
            result = ws.receive_json()
            assert result['request_id'] == 'new' and result['blocks'][0]['text'] == 'VI: new'
            ws.send_json({'type':'ping'})
            assert ws.receive_json()['type'] == 'pong'
    assert r.store.cache_size() == 1
    r.store.close()


async def test_missing_key_does_not_consume_daily_limit(tmp_path):
    s = Store(tmp_path); p = GeminiProvider(lambda:'',httpx.MockTransport(lambda request:httpx.Response(500)))
    m = TranslationManager(s,p)
    with pytest.raises(TranslationError) as error:
        await m.translate(frame(), 'd')
    assert error.value.code == 'no_api_key' and s.usage()['requests'] == 0
    await p.close(); s.close()
