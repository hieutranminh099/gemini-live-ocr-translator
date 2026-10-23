import pytest
from app.manager import TranslationManager
from app.models import Settings, TranslateRequest
from app.provider import TranslationError
from app.storage import Store


def frame(text):
    return TranslateRequest(request_id=text,source='en',profile='game',mode='fast',blocks=[
        dict(id='b0',text=text,box=dict(x=0,y=0,width=1,height=1))])


class Api:
    configured=True
    def __init__(self):self.calls=0;self.fail=False
    async def translate(self,texts,**kwargs):
        assert 'priority' not in kwargs
        self.calls+=1
        if self.fail:raise TranslationError('provider_unavailable','Synthetic API failure')
        return ['VI:'+text for text in texts],37,19


async def test_video_reserves_local_rpm_for_live_without_spending_extra_quota(tmp_path):
    store=Store(tmp_path);api=Api();manager=TranslationManager(store,api)
    store.set('settings',Settings(requests_per_minute=2,daily_request_limit=100).model_dump(exclude={'api_key'}))
    try:
        result=await manager.translate(frame('one'),'video',priority='video',source_type='VIDEO')
        assert result['diagnostics']==dict(provider_calls=1,input_tokens=37,output_tokens=19)
        with pytest.raises(TranslationError) as caught:
            await manager.translate(frame('two'),'video',priority='video',source_type='VIDEO')
        assert caught.value.code=='rate_limited' and caught.value.retry_after_ms>0
        assert store.usage()['requests']==1 and api.calls==1
        await manager.translate(frame('live'),'phone',priority='live')
        assert store.usage()['requests']==2 and api.calls==2
    finally:await manager.close();store.close()


async def test_failed_http_count_attached_to_error_and_cache_never_counts_again(tmp_path):
    store=Store(tmp_path);api=Api();manager=TranslationManager(store,api)
    try:
        await manager.translate(frame('cached'),'d')
        cached=await manager.translate(frame('cached'),'d')
        assert cached['blocks'][0]['cached'] and cached['diagnostics'].get('provider_calls',0)==0
        api.fail=True
        with pytest.raises(TranslationError) as caught:await manager.translate(frame('failed'),'d')
        assert caught.value.diagnostics=={'provider_calls':1}
        assert store.usage()['requests']==2 and api.calls==2
    finally:await manager.close();store.close()
