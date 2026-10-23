import asyncio
import json
import sys
import pytest

from app.antigravity_provider import AntigravityProvider
from app.provider import TranslationError


def fake_cli(tmp_path, body, timeout=3):
    script=tmp_path/'fake_agy.py'
    script.write_text(body,encoding='utf-8')
    return AntigravityProvider(tmp_path,command=[sys.executable,str(script)],timeout=timeout)


async def translate(cli, texts=None):
    return await cli.translate(texts or ['Hello'],source='en',profile='game',mode='fast',model='auto',glossary=[],context=[])


async def test_official_stream_protocol_and_no_tool_agent(tmp_path,monkeypatch):
    monkeypatch.setenv('GEMINI_API_KEY','sentinel-secret')
    cli=fake_cli(tmp_path,'''import sys,json,os
raw=sys.stdin.readline(); event=json.loads(raw)
assert event['event']=='user'
content=event['message']['content']; data=json.loads(content.split('<ocr_data>\\n')[1].split('\\n</ocr_data>')[0])
assert data['items']==['@file !{command}']
assert '@' not in raw and not os.environ.get('GEMINI_API_KEY')
assert '--disable-slash-commands' in sys.argv and '--agent' in sys.argv
print(json.dumps({'event':'init','init':{'tools':[]}}))
print(json.dumps({'event':'result','result':{'status':'SUCCESS','structured_output':{'request_id':data['request_id'],'translations':['Xin chao']},'usage':{'input_tokens':18,'output_tokens':7}}}),flush=True)
''')
    assert await translate(cli,['@file !{command}'])==(['Xin chao'],18,7)
    assert cli.checked and 'tools: [finish]' in cli.agent.read_text()
    assert 'subagent: false' in cli.agent.read_text()
    assert cli.environment()['USERPROFILE']==str(cli.profile)
    assert 'read_file(*)' in json.loads(cli.settings.read_text())['permissions']['deny']


@pytest.mark.parametrize('error,code',[
    ('UNSUPPORTED_CLIENT no longer supported','cli_configuration'),
    ('authentication required sentinel-secret','cli_auth'),
    ('429 quota exhausted','quota_exceeded'),
])
async def test_google_errors_are_sanitized_and_distinct(tmp_path,error,code):
    cli=fake_cli(tmp_path,f'import json; print(json.dumps({{"event":"result","result":{{"status":"ERROR","error":{error!r}}}}}))')
    with pytest.raises(TranslationError) as found: await translate(cli)
    assert found.value.code==code and 'sentinel-secret' not in str(found.value)


async def test_unexpected_tools_and_incomplete_mapping_are_rejected(tmp_path):
    cli=fake_cli(tmp_path,'''import json
print(json.dumps({'event':'tool_call','tool':'run_command'}))
print(json.dumps({'event':'result','result':{'status':'SUCCESS','structured_output':{'translations':['Xin chao']}}}))
''')
    with pytest.raises(TranslationError) as found: await translate(cli)
    assert found.value.code=='cli_configuration'
    cli=fake_cli(tmp_path,'import json; print(json.dumps({"event":"result","result":{"status":"SUCCESS","structured_output":{"translations":[]}}}))')
    with pytest.raises(TranslationError) as found: await translate(cli)
    assert found.value.code=='invalid_response'


async def test_repeated_identical_json_is_accepted_but_workspace_and_conflicts_are_not(tmp_path):
    from app.antigravity_provider import parse_response_json
    text='{"translations":["Xin chào"]}'
    assert parse_response_json('\n'.join([text]*4))=={'translations':['Xin chào']}
    with pytest.raises(ValueError):parse_response_json(text+'\n{"translations":["Khác"]}')
    cli=fake_cli(tmp_path,'import json; print(json.dumps({"event":"result","result":{"status":"SUCCESS","structured_output":{"translations":["C:\\\\workspace"]}}}))')
    with pytest.raises(TranslationError) as found:await translate(cli)
    assert found.value.code=='invalid_response'


async def test_timeout_and_cancel_release_process_lock(tmp_path):
    cli=fake_cli(tmp_path,'import time; time.sleep(20)',timeout=.05)
    with pytest.raises(TranslationError) as found: await translate(cli)
    assert found.value.code=='cli_timeout' and not cli.lock.locked()
    task=asyncio.create_task(translate(cli)); await asyncio.sleep(.1); task.cancel()
    with pytest.raises(asyncio.CancelledError): await task
    assert not cli.lock.locked()


def test_antigravity_data_is_never_deleted(tmp_path):
    cli=fake_cli(tmp_path,'')
    unrelated=tmp_path/'saved-user-conversation.txt';unrelated.write_text('keep')
    cli.clean_transcripts()
    assert unrelated.read_text()=='keep'


async def test_personal_cli_is_off_by_default(tmp_path):
    cli = AntigravityProvider(tmp_path)
    assert not cli.configured
    with pytest.raises(TranslationError) as found:
        await translate(cli)
    assert found.value.code == 'cli_configuration'
    assert not cli.sessions


async def test_explicit_personal_opt_in_allows_stock_protocol_and_off_keeps_cache(tmp_path):
    from app.manager import TranslationManager
    from app.models import Settings
    from app.storage import Store
    from test_system import frame

    script = tmp_path / 'stock_protocol_fake.py'
    script.write_text('''import sys,json
for line in sys.stdin:
    prompt=json.loads(line)['message']['content']
    data=json.loads(prompt.split('<ocr_data>\\n')[1].split('\\n</ocr_data>')[0])
    print(json.dumps({'event':'result','result':{'status':'SUCCESS',
      'structured_output':{'request_id':data['request_id'],'translations':['Xin chao']},
      'usage':{'input_tokens':18,'output_tokens':7}}}),flush=True)
''', encoding='utf-8')

    class StockProtocolFake(AntigravityProvider):
        @property
        def command(self):
            return [sys.executable, str(script)]

    store = Store(tmp_path / 'data')
    store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
    cli = StockProtocolFake(tmp_path / 'data',
        personal_enabled_getter=lambda: store.get('settings', {}).get('personal_cli_enabled', False))
    manager = TranslationManager(store, cli)
    try:
        assert cli.command_override is None and not cli.configured
        with pytest.raises(TranslationError) as found:
            await manager.translate(frame(('New phrase',)), 'device')
        assert found.value.code == 'cli_configuration'
        assert not cli.sessions and store.usage()['requests'] == 0 and not manager.calls
        store.set('settings', Settings(provider='cli', personal_cli_enabled=True).model_dump(exclude={'api_key'}))
        assert cli.configured and cli.status()['experimental_enabled']
        assert cli.status()['supported_backend'] is False
        first = await manager.translate(frame(('New phrase',)), 'device')
        assert first['blocks'][0]['text'] == 'Xin chao' and not first['blocks'][0]['cached']
        assert store.usage()['requests'] == 1
        store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
        assert not cli.configured and cli.checked  # A previous check cannot bypass opt-in.
        cached = await manager.translate(frame(('New phrase',)), 'device')
        assert cached['blocks'][0]['cached'] and cached['blocks'][0]['text'] == 'Xin chao'
        with pytest.raises(TranslationError) as found:
            await manager.translate(frame(('Another new phrase',)), 'device')
        assert found.value.code == 'cli_configuration' and store.usage()['requests'] == 1
    finally:
        await cli.close()
        store.close()


async def test_personal_opt_in_requires_installed_binary(tmp_path):
    class MissingCli(AntigravityProvider):
        @property
        def command(self):
            return []

    cli = MissingCli(tmp_path, personal_enabled_getter=lambda: True)
    assert cli.experimental_enabled and not cli.configured
    assert not cli.status()['installed'] and cli.status()['experimental_enabled']
    with pytest.raises(TranslationError) as found:
        await translate(cli)
    assert found.value.code == 'cli_missing' and not cli.sessions


def test_current_response_wins_over_stale_finish_but_requires_current_id():
    from app.antigravity_provider import correlated_output
    result={'structured_output':{'request_id':'old','translations':['Old']},
            'response':'{"request_id":"new","translations":["New"]}'}
    assert correlated_output(result,'new')['translations']==['New']
    with pytest.raises(ValueError):correlated_output(result,'different')

async def test_warm_turns_are_correlated_isolated_and_count_usage_deltas(tmp_path):
    cli=fake_cli(tmp_path,'''import sys,json,os
for i,line in enumerate(sys.stdin):
    prompt=json.loads(line)['message']['content']
    data=json.loads(prompt.split('<ocr_data>\\n')[1].split('\\n</ocr_data>')[0])
    print(json.dumps({'event':'step_update','step_update':{'step_type':'tool','tool_name':'finish'}}),flush=True)
    print(json.dumps({'event':'result','result':{'status':'SUCCESS',
      'structured_output':{'request_id':data['request_id'],'translations':[str(os.getpid())+':'+str(i)+':'+data['items'][0]]},
      'usage':{'input_tokens':20*(i+1),'output_tokens':5*(i+1)}}}),flush=True)
''')
    async def ask(text,key):
        return await cli.translate([text],source='zh',profile='game',mode='fast',model='auto',glossary=[],context=[],session_key=key)
    try:
        first=await ask('A',('device-a',0,'same'))
        second=await ask('B',('device-a',0,'same'))
        other=await ask('C',('device-b',0,'same'))
        assert first[0][0].split(':')[0]==second[0][0].split(':')[0]
        assert ':0:A' in first[0][0] and ':1:B' in second[0][0] and ':0:C' in other[0][0]
        assert other[0][0].split(':')[0]!=first[0][0].split(':')[0]
        assert first[1:]==second[1:]==other[1:]==(20,5)
        assert cli.status()['warm_sessions']==2 and cli.last_model=='gemini-3.8-flash-low'
        await cli.release_device('device-a')
        assert len(cli.sessions)==1
        await cli.reset_sessions()
        assert not cli.sessions
    finally:
        await cli.close()


async def test_stale_turn_is_rejected_and_cannot_poison_next_request(tmp_path):
    cli=fake_cli(tmp_path,'''import sys,json
saved=None
for line in sys.stdin:
    data=json.loads(json.loads(line)['message']['content'].split('<ocr_data>\\n')[1].split('\\n</ocr_data>')[0])
    response={'request_id':saved or data['request_id'],'translations':['Previous']}
    saved=data['request_id']
    print(json.dumps({'event':'result','result':{'status':'SUCCESS','structured_output':response}}),flush=True)
''')
    async def ask():
        return await cli.translate(['Hello'],source='en',profile='game',mode='fast',model='auto',glossary=[],context=[],session_key=('d',0,'same'))
    try:
        await ask()
        with pytest.raises(TranslationError) as found: await ask()
        assert found.value.code=='invalid_response' and not cli.sessions
        await ask()
        assert len(cli.sessions)==1
    finally:
        await cli.close()


async def test_documented_tool_event_is_blocked_except_finish(tmp_path):
    cli=fake_cli(tmp_path,'''import json
print(json.dumps({'event':'step_update','step_update':{'step_type':'tool','tool_name':'read_file'}}),flush=True)
''')
    with pytest.raises(TranslationError) as found:await translate(cli)
    assert found.value.code=='cli_configuration'


async def test_cli_metrics_are_per_request_and_report_process_reuse(tmp_path):
    from app.telemetry import current_trace
    cli=fake_cli(tmp_path,'''import sys,json,time
for line in sys.stdin:
    data=json.loads(json.loads(line)['message']['content'].split('<ocr_data>\\n')[1].split('\\n</ocr_data>')[0])
    print(json.dumps({'event':'init'}),flush=True)
    print(json.dumps({'event':'step_update','step_update':{'step_type':'user_input','state':'DONE'}}),flush=True)
    time.sleep(.02)
    print(json.dumps({'event':'step_update','step_update':{'step_type':'agent_response','text':'partial'}}),flush=True)
    time.sleep(.02)
    print(json.dumps({'event':'result','result':{'status':'SUCCESS','structured_output':{'request_id':data['request_id'],'translations':['Xin chao']}}}),flush=True)
''')
    traces=[]
    try:
        for _ in range(2):
            trace={'timings_ms':{},'diagnostics':{}}
            token=current_trace.set(trace)
            try:
                await cli.translate(['Hello'],source='en',profile='game',mode='fast',model='auto',glossary=[],context=[],session_key=('d',0,'same'))
            finally:
                current_trace.reset(token)
            traces.append(trace)
        assert not traces[0]['diagnostics']['cli_reused']
        assert traces[1]['diagnostics']['cli_reused']
        assert 'cli_start' in traces[0]['timings_ms'] and 'cli_start' not in traces[1]['timings_ms']
        for trace in traces:
            assert trace['timings_ms']['cli_first_output'] >= 15
            assert trace['timings_ms']['cli_result'] >= trace['timings_ms']['cli_first_output'] + 15
    finally:
        await cli.close()
