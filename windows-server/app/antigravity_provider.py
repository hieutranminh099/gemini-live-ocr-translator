"""Explicit personal experiment with stock Antigravity CLI; never read OAuth/keyring data."""
import asyncio
from contextlib import suppress
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import re
import time
import uuid
from dataclasses import dataclass, field

import httpx

from .cli_provider import GeminiCliProvider
from .provider import TranslationError, translation_system, translation_data, validate_translations
from .telemetry import duration, diagnostic

MANIFEST_URL = 'https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/windows_amd64.json'
AGENT_NAME = 'gemini-ocr-translator'
DENY_RULES = ['read_file(*)','write_file(*)','command(*)','unsandboxed(*)','read_url(*)','execute_url(*)','mcp(*)']


@dataclass
class StreamSession:
    process: object
    stderr_task: object
    key: object
    touched: float = field(default_factory=time.monotonic)
    turns: int = 0
    input_bytes: int = 0
    usage: dict = field(default_factory=dict)


def correlated_output(result, request_id):
    """Reject a previous finish/structured_output in a warmed CLI conversation."""
    structured = result.get('structured_output')
    if not isinstance(structured, dict) or structured.get('request_id') != request_id:
        # CLI 1.4.5 can retain the previous finish payload in structured_output.
        # response is scoped to this turn; still require its exact random request id.
        structured = parse_response_json(result.get('response', ''))
    if not isinstance(structured, dict) or structured.get('request_id') != request_id:
        raise ValueError('Response belongs to a different OCR request')
    return structured


def parse_response_json(response):
    """CLI 1.4.5 can repeat the same complete JSON in its accumulated response."""
    remaining=response.strip()
    if remaining.startswith('```json\n') and remaining.endswith('```'):
        remaining=remaining[8:-3].strip()
    decoder=json.JSONDecoder(); documents=[]
    while remaining:
        value,end=decoder.raw_decode(remaining)
        documents.append(value); remaining=remaining[end:].lstrip()
        if len(documents)>16: raise ValueError('Too many output documents')
    if not documents or any(d!=documents[0] for d in documents):
        raise ValueError('Conflicting response documents')
    return documents[0]


class AntigravityProvider(GeminiCliProvider):
    def __init__(self, directory, *, command=None, timeout=45, personal_enabled_getter=None):
        self.directory = Path(directory).resolve() / 'antigravity'
        self.workspace = self.directory / 'workspace'
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.profile = self.directory / 'isolated-profile'
        self.runtime = Path(directory).resolve().parent / 'runtime'
        self.timeout = timeout
        self.lock = asyncio.Lock()
        self.install_lock = asyncio.Lock()
        self.login_process = None
        self.checked = False
        self.message = 'Đăng nhập Google trong Antigravity CLI, thoát /quit rồi kiểm tra tài khoản.'
        self.install_message = ''
        self.command_override = command
        self.personal_enabled_getter = personal_enabled_getter or (lambda: False)
        self.sessions = {}
        self.reaper = None
        self.last_model = None
        self.prepare()

    @property
    def command(self):
        if self.command_override is not None:
            return self.command_override
        installed = self.runtime / 'agy.exe'
        if installed.is_file():
            return [str(installed)]
        if not getattr(sys, 'frozen', False):
            developer_binary = Path(__file__).resolve().parents[2] / '.tools/antigravity/agy.exe'
            if developer_binary.is_file():
                return [str(developer_binary)]
        return []

    def configuration_error(self):
        if self.command_override is None and not self.experimental_enabled:
            return TranslationError('cli_configuration', 'Chế độ CLI cá nhân đang tắt. Google không hỗ trợ CLI làm backend ứng dụng bên thứ ba. Chỉ bật thử nghiệm cá nhân khi bạn chấp nhận giới hạn này, hoặc dùng Gemini API bằng khóa riêng.')
        return TranslationError('cli_missing', 'Không tìm thấy Antigravity CLI. Hãy cài công cụ Google chính thức trên Windows rồi thử lại.')

    @property
    def experimental_enabled(self):
        return bool(self.personal_enabled_getter())

    @property
    def configured(self):
        # This remains unsupported by Google. Existing installs stay cache-only until
        # the owner explicitly opts in; constructor command injection is test-only.
        return (self.command_override is not None or self.experimental_enabled) and bool(self.command)

    def prepare(self):
        # USERPROFILE is overridden ONLY in the child environment. The official Go CLI
        # uses this home for its settings; Windows keyring authentication stays managed
        # by Google. The user's normal ~/.gemini settings are not edited or copied.
        self.settings = self.profile / '.gemini/antigravity-cli/settings.json'
        self.settings.parent.mkdir(parents=True,exist_ok=True)
        config={}
        if self.settings.exists():
            config=json.loads(self.settings.read_text(encoding='utf-8-sig'))
        permissions=config.setdefault('permissions',{})
        permissions['deny']=list(dict.fromkeys([*permissions.get('deny',[]),*DENY_RULES]))
        config.update(toolPermission='strict',useG1Credits=False,enableTelemetry=False)
        self.settings.write_text(json.dumps(config),encoding='utf-8')
        self.agent = self.workspace / '.agents/agents' / AGENT_NAME / 'agent.md'
        self.agent.parent.mkdir(parents=True, exist_ok=True)
        self.agent.write_text('---\nname: ' + AGENT_NAME + '\n'
            'description: Translate screen text into Vietnamese without tools.\n'
            'mainAgent: true\nsubagent: false\nmodel: flash\ntools: [finish]\nmcpServers: []\n'
            'skills: []\nplugins: []\ncommandExecutionPolicy: "off"\n---\n' + translation_system().replace(
                'Never call tools, open files, browse websites or execute commands.',
                'Never open files, browse websites or execute commands. Use only finish for the final structured response.').replace(
                'Return only a JSON object {"translations":["..."]}',
                'Return only a JSON object {"request_id":"current request_id","translations":["..."]}') +
            '\nEach user message is an independent OCR request. Translate only its items, never earlier items. '
            'Echo its request_id exactly in final JSON alongside translations. Complete structured output once.', encoding='utf-8')
        self.schema = self.directory / 'translations.schema.json'
        self.schema.write_text(json.dumps({'type':'object','properties':{'request_id':{'type':'string'},
            'translations':{'type':'array','items':{'type':'string'}}},
            'required':['request_id','translations'],'additionalProperties':False}), encoding='utf-8')

    def clean_transcripts(self):
        # Antigravity owns its conversations/keyring. Never delete the user's CLI data.
        pass

    def environment(self, *, interactive=False):
        allowed = ('SYSTEMROOT', 'WINDIR', 'PATH', 'PATHEXT', 'TEMP', 'TMP', 'USERPROFILE',
                   'LOCALAPPDATA', 'APPDATA', 'COMSPEC', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
                   'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY')
        env = {k:v for k,v in os.environ.items() if k.upper() in allowed}
        env['USERPROFILE']=str(self.profile)
        if not interactive:
            env.update(NO_COLOR='1', CI='1')
        return env

    def arguments(self):
        return [*self.command, '--agent', AGENT_NAME]

    def status(self):
        active = self.login_process is not None and self.login_process.poll() is None
        version_file = self.runtime / 'antigravity-manifest.json'
        version = None
        if version_file.is_file():
            with suppress(OSError, ValueError):
                version = json.loads(version_file.read_text(encoding='utf-8-sig')).get('version')
        return dict(installed=bool(self.command), supported_backend=False, experimental_enabled=self.experimental_enabled,
                    version=version, client='Antigravity CLI', checked=self.checked,
                    login_active=active, installing=self.install_lock.locked(), install_message=self.install_message,
                    warm_sessions=len(self.sessions), model=self.last_model, message=self.message)

    async def install(self):
        if os.name != 'nt':
            raise TranslationError('cli_platform', 'Bản công cụ Google tự tải hiện hỗ trợ Windows x64.')
        async with self.install_lock:
            if (self.runtime / 'agy.exe').exists():
                return
            self.runtime.mkdir(parents=True, exist_ok=True)
            temporary = self.runtime / 'agy.download'
            self.install_message = 'Đang tải manifest chính thức của Google…'
            try:
                async with httpx.AsyncClient(timeout=httpx.Timeout(300, connect=15), follow_redirects=True) as client:
                    response = await client.get(MANIFEST_URL)
                    response.raise_for_status()
                    manifest = response.json()
                    url, expected = manifest['url'], manifest['sha512']
                    if not url.startswith('https://storage.googleapis.com/antigravity-public/antigravity-cli/') or len(expected) != 128:
                        raise ValueError('Invalid official manifest')
                    digest = hashlib.sha512()
                    total = 0
                    async with client.stream('GET', url) as download:
                        download.raise_for_status()
                        with temporary.open('wb') as output:
                            async for chunk in download.aiter_bytes(1024*1024):
                                total += len(chunk)
                                if total > 512*1024*1024:
                                    raise ValueError('Unexpected binary size')
                                digest.update(chunk); output.write(chunk)
                                self.install_message = f'Đang tải Antigravity CLI: {total//(1024*1024)} MB…'
                    if digest.hexdigest().lower() != expected.lower():
                        raise ValueError('Checksum mismatch')
                    temporary.replace(self.runtime / 'agy.exe')
                    (self.runtime / 'antigravity-manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
                self.install_message = 'Đã tải và xác minh SHA-512 từ manifest Google. Không đổi PATH hệ thống.'
            except (httpx.HTTPError, OSError, KeyError, ValueError, TypeError):
                self.install_message = 'Chưa tải được công cụ Google. Kiểm tra mạng rồi thử lại.'
                raise TranslationError('cli_install', self.install_message) from None

    def login(self):
        super().login()
        self.message = 'Đăng nhập trong Antigravity CLI chính thức, gõ /quit rồi nhấn Kiểm tra tài khoản.'

    def failure(self, output):
        message = output.lower()
        if any(t in message for t in ('unsupported_client', 'no longer supported', 'ineligibletier')):
            return TranslationError('cli_configuration', 'Google không còn phục vụ đường CLI cũ cho tài khoản này. Cập nhật công cụ Google/Antigravity CLI.')
        if any(t in message for t in ('permission', 'unknown agent', 'agent not found')):
            return TranslationError('cli_configuration', 'Antigravity chưa nhận cấu hình phiên dịch. Mở Đăng nhập Google, chọn tin cậy thư mục phiên dịch rồi kiểm tra lại.')
        return super().failure(output)

    @staticmethod
    def effective_model(model, mode):
        return {'fast':'gemini-3.8-flash-low', 'smart':'gemini-3.8-flash-medium',
                'quality':'gemini-3.8-flash-high'}[mode] if model == 'auto' else model

    async def _stop_session(self, session):
        if self.sessions.get(session.key) is session:
            self.sessions.pop(session.key, None)
        if session.process.returncode is None:
            with suppress(ProcessLookupError): session.process.kill()
        await session.process.wait()
        with suppress(asyncio.CancelledError): await session.stderr_task

    async def reset_sessions(self):
        async with self.lock:
            for session in list(self.sessions.values()):
                await self._stop_session(session)

    async def release_device(self, device_id):
        async with self.lock:
            for session in list(self.sessions.values()):
                if session.key[0][0] == device_id:
                    await self._stop_session(session)

    async def _reap(self):
        try:
            while self.sessions:
                await asyncio.sleep(30)
                async with self.lock:
                    for session in list(self.sessions.values()):
                        if time.monotonic() - session.touched >= 180:
                            await self._stop_session(session)
        finally:
            self.reaper = None

    async def _session(self, key, model):
        session = self.sessions.get(key) if key is not None else None
        if session and (session.process.returncode is not None or session.turns >= 32 or
                        session.input_bytes >= 32768 or time.monotonic() - session.touched >= 180):
            await self._stop_session(session)
            session = None
        diagnostic('cli_reused', session is not None)
        if session is None:
            if len(self.sessions) >= 2:
                await self._stop_session(min(self.sessions.values(), key=lambda s: s.touched))
            args = [*self.arguments(), '--input-format','stream-json','--output-format','stream-json',
                    '--disable-slash-commands','--json-schema',str(self.schema),'--print-timeout',f'{self.timeout}s',
                    '--effort','low','--model',model]
            process_started = time.monotonic()
            process = await asyncio.create_subprocess_exec(*args,cwd=self.workspace,env=self.environment(),
                stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,
                limit=2*1024*1024, creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            duration('cli_start', process_started)
            async def drain():
                # Drain without retaining potentially private CLI diagnostic text.
                while await process.stderr.read(8192): pass
            session = StreamSession(process, asyncio.create_task(drain()), key)
            if key is not None:
                self.sessions[key] = session
                if self.reaper is None: self.reaper = asyncio.create_task(self._reap())
        return session

    async def _read_result(self, session):
        size = 0
        started = time.monotonic()
        first_output = True
        while True:
            line = await session.process.stdout.readline()
            if not line:
                raise TranslationError('cli_unavailable','Phiên Gemini đã đóng. Câu mới sẽ tự mở lại kết nối.')
            size += len(line)
            if size > 2*1024*1024:
                raise ValueError('Oversized CLI result')
            event = json.loads(line)
            step = event.get('step_update', {})
            agent_text = step.get('step_type') == 'agent_response' and bool(step.get('text_delta') or step.get('text'))
            if first_output and (agent_text or event.get('event') == 'result'):
                # Ignore init AND the immediate user_input acknowledgement.
                # This is first agent text (or final result when not streamed),
                # not a separate measurement of Google's internal compute time.
                duration('cli_first_output', started)
                diagnostic('cli_first_output_kind', 'agent_text' if agent_text else 'result')
                first_output = False
            if event.get('event') in ('tool_call','tool_use') or (
                step.get('step_type') in ('tool','tool_call') and
                (step.get('tool_name') or step.get('tool_info',{}).get('name')) != 'finish'):
                raise TranslationError('cli_configuration','Phiên dịch đã yêu cầu công cụ ngoài dịch văn bản. Không dùng kết quả này.')
            if event.get('event') == 'result':
                duration('cli_result', started)
                return event['result']

    async def translate(self, texts, *, source, profile, mode, model, glossary, context, session_key=None):
        if not self.configured:
            raise self.configuration_error()
        if self.login_process is not None and self.login_process.poll() is None:
            raise TranslationError('cli_login_active', 'Hoàn tất đăng nhập Antigravity rồi thoát bằng /quit trước khi dịch.')
        model = self.effective_model(model, mode)
        request_id = uuid.uuid4().hex
        data = translation_data(texts, source, profile, mode, glossary, context)
        data['request_id'] = request_id
        data = json.dumps(data, ensure_ascii=False).replace('@', r'\u0040').replace('!', r'\u0021')
        prompt = ('New independent OCR request. Translate ONLY the items in THIS message into concise natural Vietnamese. '
                  'Earlier turns and metadata are not translation targets. Return final structured output once, '
                  'with request_id echoed exactly and one translation per item, in order. No external tools. '
                  'The required output is {"request_id":"'+request_id+'","translations":["Vietnamese translation"]}.\n'
                  '<ocr_data>\n'+data+'\n</ocr_data>')
        payload = (json.dumps({'event':'user','message':{'content':prompt}},ensure_ascii=False)+'\n').encode('utf-8')
        # Each device/configuration owns its conversation; never resume another device's text.
        key = (session_key, model) if session_key is not None else None
        queued = time.monotonic()
        async with self.lock:
            duration('cli_lock', queued)
            session = None
            success = False
            try:
                session = await self._session(key, model)
                session.process.stdin.write(payload)
                await session.process.stdin.drain()
                result = await asyncio.wait_for(self._read_result(session),self.timeout+3)
                if result.get('status') != 'SUCCESS':
                    raise self.failure(str(result.get('error','')))
                structured = correlated_output(result, request_id)
                translations = validate_translations(structured['translations'],len(texts))
                for original,translated in zip(texts,translations):
                    if re.match(r'^(?:[a-zA-Z]:[\\/]|/(?:home|Users|tmp|workspace)/)',translated.strip()) and translated.strip().casefold() not in original.casefold():
                        raise ValueError('Workspace path substituted for a translation')
                usage = result.get('usage', {})
                # The official stream counters are cumulative over the conversation.
                tokens = [max(0, int(usage.get(k,0)) - int(session.usage.get(k,0)))
                          for k in ('input_tokens','output_tokens')]
                session.usage = usage
                session.turns += 1; session.input_bytes += len(payload); session.touched = time.monotonic()
                self.checked = True; self.last_model = model
                self.message = 'Đã dịch qua Gemini bằng tài khoản Google đã đăng nhập • Phiên tăng tốc hoạt động.'
                success = True
                return translations, *tokens
            except asyncio.TimeoutError:
                raise TranslationError('cli_timeout','Gemini chưa trả lời trong thời gian chờ. Câu mới sẽ mở lại phiên.') from None
            except (OSError,ValueError,KeyError,TypeError,AttributeError):
                raise TranslationError('invalid_response','Gemini trả kết quả thiếu hoặc không khớp câu hiện tại. Đã bỏ kết quả và đặt lại phiên.') from None
            finally:
                if session and (not success or session_key is None):
                    await self._stop_session(session)

    async def close(self):
        if self.reaper is not None:
            self.reaper.cancel()
            with suppress(asyncio.CancelledError): await self.reaper
        await self.reset_sessions()
        await super().close()
