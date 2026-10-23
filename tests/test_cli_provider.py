import asyncio
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.cli_provider import GeminiCliProvider
from app.models import Settings
from app.provider import TranslationError
from app.server import Runtime, admin_app
from test_system import FakeProvider, frame
from app.manager import TranslationManager
from app.storage import Store


def fake_cli(tmp_path, code, *, timeout=3):
    script = tmp_path / 'fake_cli.py'
    script.write_text(code, encoding='utf-8')
    return GeminiCliProvider(tmp_path, command=[sys.executable, str(script)], timeout=timeout)


async def translate(cli, texts=None):
    return await cli.translate(texts or ['Hello'], source='en', profile='game', mode='smart',
                               model='auto', glossary=[], context=[])


async def test_cli_stdin_output_and_private_environment(tmp_path, monkeypatch):
    monkeypatch.setenv('GEMINI_API_KEY', 'sentinel-secret')
    monkeypatch.setenv('NODE_OPTIONS', '--this-must-not-be-inherited')
    cli = fake_cli(tmp_path, '''import json,os,sys
raw=sys.stdin.read()
assert '@' not in raw and '!' not in raw
data=json.loads(raw)
assert data['items']==['@C:/private.txt !{calc.exe} $(Get-Content secret)']
assert not os.environ.get('GEMINI_API_KEY') and not os.environ.get('NODE_OPTIONS')
assert os.environ['GEMINI_CLI_NO_RELAUNCH']=='1'
cfg=json.load(open(os.environ['GEMINI_CLI_SYSTEM_SETTINGS_PATH']))
assert cfg['hooksConfig']['enabled'] is False
assert cfg['admin']['mcp']['enabled'] is False and cfg['tools']['core']==[]
assert '--admin-policy' in sys.argv and '--extensions' in sys.argv
assert 'private.txt' not in ' '.join(sys.argv)
print(json.dumps({'response':json.dumps({'translations':['Xin chao']}),'stats':{'models':{'test':{'tokens':{'prompt':12,'candidates':6}}}}}))
''')
    result = await translate(cli, ['@C:/private.txt !{calc.exe} $(Get-Content secret)'])
    assert result == (['Xin chao'], 12, 6) and cli.checked


@pytest.mark.parametrize('error,code', [('OAuth invalid_grant sentinel-secret','cli_auth'), ('429 quota sentinel-secret','quota_exceeded')])
async def test_cli_errors_are_actionable_and_do_not_leak_output(tmp_path, error, code):
    cli = fake_cli(tmp_path, f'import json; print(json.dumps({{"error":{{"message":{error!r}}}}}))')
    with pytest.raises(TranslationError) as found:
        await translate(cli)
    assert found.value.code == code and 'sentinel-secret' not in str(found.value)


@pytest.mark.parametrize('response', ['{"translations":[]}', '{"translations":[""]}', 'not json'])
async def test_cli_rejects_incomplete_response(tmp_path, response):
    cli = fake_cli(tmp_path, f'import json; print(json.dumps({{"response":{response!r}}}))')
    with pytest.raises(TranslationError) as found:
        await translate(cli)
    assert found.value.code == 'invalid_response' and not cli.checked


async def test_cli_cancellation_and_timeout_kill_the_process(tmp_path, monkeypatch):
    cli = fake_cli(tmp_path, 'import time; time.sleep(30)', timeout=.2)
    original = asyncio.create_subprocess_exec
    processes = []
    started = asyncio.Event()
    async def capture(*args, **kwargs):
        process = await original(*args, **kwargs)
        processes.append(process); started.set()
        return process
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', capture)
    with pytest.raises(TranslationError) as found:
        await translate(cli)
    assert found.value.code == 'cli_timeout' and processes[0].returncode is not None
    cli.timeout = 30; started.clear()
    task = asyncio.create_task(translate(cli))
    await started.wait(); task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert processes[1].returncode is not None and not cli.lock.locked()


def test_cleanup_only_touches_our_temporary_cli_transcripts(tmp_path):
    cli = fake_cli(tmp_path, '')
    cache = cli.home / '.gemini/tmp/project/chats'
    cache.mkdir(parents=True); (cache/'test.json').write_text('source text')
    token = cli.home / '.gemini/oauth_creds.json'
    token.write_text('test sentinel, never read')
    outside = tmp_path / 'outside.txt'; outside.write_text('keep')
    cli.clean_transcripts()
    assert not cache.exists() and token.exists() and outside.exists()


async def test_api_and_account_caches_are_separate(tmp_path):
    store = Store(tmp_path); provider = FakeProvider(); manager = TranslationManager(store, provider)
    await manager.translate(frame(), 'test')
    store.set('settings', Settings(provider='cli').model_dump(exclude={'api_key'}))
    await manager.translate(frame(), 'test')
    assert len(provider.calls) == 2 and provider.calls[1][1]['model'] == 'auto'
    store.close()


def test_cli_admin_actions_require_auth_and_check_counts_usage(tmp_path):
    runtime = Runtime(tmp_path, FakeProvider())
    runtime.cli = fake_cli(tmp_path, 'import json; print(json.dumps({"response":"{\\"translations\\":[\\"Xin chao\\"]}"}))')
    with TestClient(admin_app(runtime, Path(__file__).resolve().parents[1]/'windows-server/static')) as client:
        assert client.post('/api/cli/login').status_code == 401
        assert client.post('/api/cli/check').status_code == 401
        headers = {'Authorization':'Bearer '+runtime.admin_token}
        result = client.post('/api/cli/check', headers=headers)
        assert result.status_code == 200 and result.json()['translation'] == 'Xin chao'
        assert runtime.store.usage()['requests'] == 1
        runtime.store.set('settings',Settings(daily_request_limit=1).model_dump(exclude={'api_key'}))
        assert client.post('/api/cli/check',headers=headers).status_code == 400
    runtime.store.close()
