"""Invoke the unmodified official CLI. OAuth credentials never enter this process."""
import asyncio
from contextlib import suppress
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from .provider import TranslationError, translation_data, translation_system, validate_translations

CLI_VERSION = '0.65.0'


class GeminiCliProvider:
    def __init__(self, directory, *, command=None, timeout=45):
        self.directory = Path(directory).resolve() / 'google-cli'
        self.home = self.directory / 'profile'
        self.workspace = self.directory / 'workspace'
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.home.mkdir(parents=True, exist_ok=True)
        self.timeout = timeout
        self.lock = asyncio.Lock()
        self.login_process = None
        self.checked = False
        self.message = 'Đăng nhập Google, sau đó nhấn Kiểm tra tài khoản.'
        self.command = command if command is not None else self.find_command()
        self.prepare()
        self.clean_transcripts()

    @staticmethod
    def find_command():
        if getattr(sys, 'frozen', False):
            base = Path(sys._MEIPASS) / 'google-runtime'
            node = base / 'node.exe'
            entry = base / 'node_modules/@google/gemini-cli/bundle/gemini.js'
        else:
            root = Path(__file__).resolve().parents[2]
            node = Path(shutil.which('node') or '')
            entry = root / '.tools/gemini-cli/node_modules/@google/gemini-cli/bundle/gemini.js'
        return [str(node), str(entry)] if node.is_file() and entry.is_file() else []

    @property
    def configured(self):
        return bool(self.command)

    def configuration_error(self):
        return TranslationError('cli_missing', 'Bản Windows thiếu Gemini CLI. Hãy cài lại bản Windows mới nhất.')

    def prepare(self):
        self.policy = self.directory / 'deny-tools.toml'
        self.policy.write_text('[[rule]]\ntoolName = "*"\ndecision = "deny"\npriority = 999\n', encoding='utf-8')
        self.system = self.directory / 'translator.md'
        self.system.write_text(translation_system(), encoding='utf-8')
        self.settings = self.directory / 'settings.json'
        # A private CLI profile prevents user extensions, hooks, MCP and project instructions
        # from being inherited. These are documented settings of the stock CLI.
        config = {
            'security': {'auth': {'selectedType': 'oauth-personal', 'enforcedType': 'oauth-personal'},
                         'disableYoloMode': True, 'disableAlwaysAllow': True},
            'tools': {'core': [], 'discoveryCommand': None, 'callCommand': None},
            'admin': {'mcp': {'enabled': False}, 'extensions': {'enabled': False}, 'skills': {'enabled': False}},
            'hooksConfig': {'enabled': False}, 'skills': {'enabled': False},
            'context': {'fileName': [], 'includeDirectoryTree': False},
            'general': {'enableAutoUpdate': False, 'checkpointing': {'enabled': False}, 'maxAttempts': 1},
            'telemetry': {'enabled': False, 'logPrompts': False},
            'privacy': {'usageStatisticsEnabled': False},
            'advanced': {'ignoreLocalEnv': True}, 'model': {'maxSessionTurns': 1},
            'experimental': {'autoMemory': False, 'enableAgents': False},
        }
        self.settings.write_text(json.dumps(config), encoding='utf-8')

    def environment(self, *, interactive=False):
        # Start from an allowlist: no API keys, endpoint overrides, Node preload scripts,
        # existing Gemini config or third-party plugin settings are inherited.
        allowed = ('SYSTEMROOT', 'WINDIR', 'PATH', 'PATHEXT', 'TEMP', 'TMP', 'USERPROFILE',
                   'LOCALAPPDATA', 'APPDATA', 'COMSPEC', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
                   'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY')
        env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
        env.update(GEMINI_CLI_HOME=str(self.home), GEMINI_CLI_SYSTEM_SETTINGS_PATH=str(self.settings),
                   GEMINI_SYSTEM_MD=str(self.system), GEMINI_TELEMETRY_ENABLED='false',
                   GEMINI_TELEMETRY_LOG_PROMPTS='false', GEMINI_CLI_NO_RELAUNCH='1')
        if not interactive:
            env.update(NO_BROWSER='1', NO_COLOR='1', CI='1')
        return env

    def arguments(self):
        return [*self.command, '--extensions', 'none', '--admin-policy', str(self.policy), '--skip-trust']

    def status(self):
        active = self.login_process is not None and self.login_process.poll() is None
        return dict(installed=self.configured, version=CLI_VERSION, checked=self.checked,
                    login_active=active, message=self.message)

    def login(self):
        if not self.configured:
            raise self.configuration_error()
        if self.login_process is not None and self.login_process.poll() is None:
            return
        if self.lock.locked():
            raise TranslationError('cli_busy', 'Hãy tạm dừng dịch trước khi đăng nhập lại.')
        if os.name != 'nt':
            raise TranslationError('cli_platform', 'Mở Gemini CLI trên Windows để đăng nhập.')
        self.checked = False
        # This console is intentionally visible: the human signs in with Google in the
        # official CLI. No password, browser cookie or OAuth token is read by this app.
        self.login_process = subprocess.Popen(self.arguments(), cwd=self.workspace,
            env=self.environment(interactive=True), creationflags=subprocess.CREATE_NEW_CONSOLE)
        self.message = 'Hoàn tất đăng nhập trong cửa sổ Gemini CLI, thoát bằng /quit rồi nhấn Kiểm tra tài khoản.'

    def clean_transcripts(self):
        # Only ephemeral files belonging to our isolated CLI profile. Never touch auth files.
        ephemeral = (self.home / '.gemini/tmp').resolve()
        if ephemeral.is_relative_to(self.home.resolve()) and ephemeral.is_dir():
            shutil.rmtree(ephemeral, ignore_errors=True)

    def failure(self, output):
        message = output.lower()
        if any(t in message for t in ('unsupported_client', 'no longer supported', 'ineligibletier')):
            return TranslationError('cli_configuration', 'Google đã dừng Gemini CLI cho tài khoản cá nhân. Cập nhật Windows sang bản dùng Antigravity CLI chính thức.')
        if any(t in message for t in ('429', 'quota', 'resource_exhausted', 'rate limit', 'capacity')):
            return TranslationError('quota_exceeded', 'Tài khoản Gemini CLI đang bị giới hạn. Chờ hạn mức phục hồi hoặc thử mô hình khác.')
        if any(t in message for t in ('auth', 'login', 'log in', 'sign in', 'credential', '401', 'invalid_grant', 'oauth')):
            self.checked = False
            self.message = 'Cần đăng nhập lại Google trong Cài đặt AI trên Windows.'
            return TranslationError('cli_auth', self.message)
        if any(t in message for t in ('model', '404', 'not found', '403', 'permission')):
            return TranslationError('cli_configuration', 'Tài khoản chưa truy cập được mô hình CLI này. Chọn auto trong Cài đặt AI hoặc kiểm tra quyền Google AI Pro.')
        return TranslationError('cli_unavailable', 'Gemini CLI chưa trả lời. Kiểm tra Internet và đăng nhập Google trên Windows.')

    async def translate(self, texts, *, source, profile, mode, model, glossary, context):
        if not self.configured:
            raise self.configuration_error()
        if self.login_process is not None and self.login_process.poll() is None:
            raise TranslationError('cli_login_active', 'Hãy hoàn tất đăng nhập rồi thoát Gemini CLI bằng /quit trước khi dịch.')
        data = translation_data(texts, source, profile, mode, glossary, context)
        # Screen content is passed on stdin, never interpolated into commands or argv.
        # Avoid the CLI's @file / !shell prompt syntax before it reaches the model.
        # These are equivalent JSON string escapes; translation still sees the original text.
        payload = json.dumps(data, ensure_ascii=False).replace('@', r'\u0040').replace('!', r'\u0021').encode('utf-8')
        args = [*self.arguments(), '--output-format', 'json', '--prompt',
                'Translate the JSON data on stdin. Return only {"translations":["..."]}.']
        if model != 'auto':
            args += ['--model', model]
        async with self.lock:
            process = None
            try:
                process = await asyncio.create_subprocess_exec(*args, cwd=self.workspace,
                    env=self.environment(), stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                stdout, stderr = await asyncio.wait_for(process.communicate(payload), self.timeout)
                if len(stdout) > 1024 * 1024:
                    raise TranslationError('invalid_response', 'Gemini CLI trả lời quá dài. Hãy thu hẹp vùng dịch.')
                try:
                    body = json.loads(stdout)
                except (ValueError, UnicodeError):
                    raise self.failure(stderr.decode('utf-8', errors='replace')) from None
                if process.returncode or body.get('error'):
                    raise self.failure(json.dumps(body.get('error', {})) + stderr.decode('utf-8', errors='replace'))
                response = body.get('response', '').strip()
                if response.startswith('```json\n') and response.endswith('```'):
                    response = response[8:-3].strip()
                elif response.startswith('```\n') and response.endswith('```'):
                    response = response[4:-3].strip()
                translations = validate_translations(json.loads(response)['translations'], len(texts))
                models = body.get('stats', {}).get('models', {}).values()
                tokens = [m.get('tokens', {}) for m in models]
                self.checked = True
                self.message = 'Gemini CLI đã dịch thành công bằng tài khoản Google đã đăng nhập.'
                return translations, sum(t.get('prompt', t.get('input', 0)) for t in tokens), sum(t.get('candidates', 0) for t in tokens)
            except asyncio.TimeoutError:
                raise TranslationError('cli_timeout', 'Gemini CLI trả lời lâu hơn 45 giây. Chờ rồi thử lại hoặc chọn mô hình nhanh hơn.') from None
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                raise TranslationError('invalid_response', 'Gemini CLI chưa trả đủ bản dịch. Kiểm tra tài khoản rồi thử lại.') from None
            finally:
                if process and process.returncode is None:
                    with suppress(ProcessLookupError):
                        process.kill()
                    await process.wait()
                self.clean_transcripts()

    async def close(self):
        if self.login_process is not None and self.login_process.poll() is None:
            self.login_process.terminate()
        self.clean_transcripts()
