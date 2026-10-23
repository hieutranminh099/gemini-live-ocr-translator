import asyncio
from contextlib import suppress
import io
import json
import os
from pathlib import Path
import secrets
import socket
import time
from urllib.parse import urlencode

from fastapi import Depends, FastAPI, Header, HTTPException, Response, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import ValidationError
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .manager import TranslationManager
from .models import GlossaryEntry, GlossaryImport, PairRequest, Settings, TranslateRequest
from . import __version__
from .provider import GeminiProvider, ProviderRouter, TranslationError
from .antigravity_provider import AntigravityProvider
from .security import Pairing, ensure_certificate, protect_secret, unprotect_secret
from .storage import Store, normalize
from .subtitle_jobs import SubtitleJobService
from .subtitle_routes import register_subtitle_routes
from .video_jobs import VideoJobService
from .video_routes import register_video_routes


def local_addresses():
    found = set()
    try:
        found.update(a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))  # Select a route; sends no packet.
            found.add(s.getsockname()[0])
    except OSError:
        pass
    return sorted(a for a in found if not a.startswith(('127.', '169.254.'))) or ['127.0.0.1']


class Runtime:
    def __init__(self, directory: Path, provider=None):
        self.store = Store(directory)
        self.cert, self.key, self.pin = ensure_certificate(directory)
        self.admin_token = secrets.token_urlsafe(32)
        self.pairing = Pairing()
        self.cli = AntigravityProvider(directory,
            personal_enabled_getter=lambda: self.store.get('settings', {}).get('personal_cli_enabled', False))
        self.provider = provider or ProviderRouter(lambda: self.store.get('settings', {}), GeminiProvider(self.api_key), self.cli)
        self.manager = TranslationManager(self.store, self.provider)
        self.subtitle_jobs = SubtitleJobService(self.manager)
        self.video_jobs = VideoJobService(directory / 'video_projects', self.manager)
        self.sockets = {}
        self.stop = asyncio.Event()
        self.started = time.time()
        self.port = 8765
        self.manager.event('Máy chủ sẵn sàng. Chờ ghép cặp điện thoại.')

    def api_key(self):
        if os.environ.get('GEMINI_API_KEY'):
            return os.environ['GEMINI_API_KEY']
        encrypted = self.store.get('api_key')
        if encrypted:
            try:
                return unprotect_secret(encrypted)
            except (OSError, ValueError):
                return ''
        return ''

    def provider_state(self):
        settings = self.manager.settings()
        experimental = settings.personal_cli_enabled
        mode = ('cli_experimental' if experimental else 'cache_only') if settings.provider == 'cli' else 'api'
        return dict(provider=settings.provider, backend_mode=mode,
                    translation_available=bool(getattr(self.provider, 'configured', True)),
                    experimental_cli_enabled=experimental)

    def invalidate(self):
        self.manager.invalidate()
        if hasattr(self.cli, 'reset_sessions'):
            asyncio.create_task(self.cli.reset_sessions())
        async def notify(ws):
            with suppress(RuntimeError, WebSocketDisconnect):
                await ws.send_json(dict(type='invalidate', cache_enabled=self.manager.settings().cache_enabled, revision=self.manager.revision,
                                        **self.provider_state()))
        for ws in list(self.sockets.values()):
            asyncio.create_task(notify(ws))


def device_app(runtime: Runtime):
    app = FastAPI(title='Gemini OCR Device API', docs_url=None, redoc_url=None, openapi_url=None)

    @app.get('/health')
    async def health():
        return dict(status='ok', protocol=1)

    @app.post('/v1/pair')
    async def pair(body: PairRequest):
        if not runtime.pairing.consume(body.code):
            raise HTTPException(403, 'Mã không đúng, hết hạn hoặc đã vượt 8 lần thử. Tạo mã mới trên máy tính.')
        ident, token = runtime.store.pair(body.name)
        runtime.manager.event('Đã ghép cặp một thiết bị mới.')
        return dict(device_id=ident, token=token, protocol=1)

    @app.websocket('/v1/stream')
    async def stream(ws: WebSocket):
        authorization = ws.headers.get('authorization', '')
        token = authorization.removeprefix('Bearer ')
        device = runtime.store.authenticate(token) if authorization.startswith('Bearer ') else None
        if not device:
            await ws.close(code=4401)
            return
        ident = device['id']
        if len(runtime.sockets) >= 8 and ident not in runtime.sockets:
            # After acceptance a client can distinguish capacity from an authentication rejection.
            await ws.accept()
            await ws.close(code=4429)
            return
        if ident in runtime.sockets:
            await runtime.sockets[ident].close(code=4001, reason='New connection')
        await ws.accept()
        runtime.sockets[ident] = ws
        live_owner = f'{ident}:{id(ws)}'
        runtime.manager.resources.set_live_active(live_owner, True)
        runtime.manager.event('Điện thoại đã kết nối kênh dịch.')
        active = None
        active_latest = False
        pending = None
        wanted = None
        api_active = {}
        api_pending = None
        api_sequence = 0
        send_lock = asyncio.Lock()

        async def send(data, only=None):
            async with send_lock:
                if only is None or only():
                    await ws.send_json(data)

        async def process(request, latest=False, queued_at=None, *, api=False, cache_only=False):
            def current():
                return not latest or wanted is request
            try:
                if cache_only:
                    result = await runtime.manager.translate_cached(request, ident, queued_at=queued_at)
                else:
                    options = {'is_current': current} if api else {}
                    result = await runtime.manager.translate(request, ident, queued_at=queued_at, **options)
                if result is None:
                    return False
                await send(result, only=current)
                return True
            except TranslationError as error:
                runtime.manager.metrics['errors'] += 1
                runtime.manager.event(str(error), 'error')
                payload = dict(type='error', request_id=request.request_id, region_id=request.region_id,
                               screen_generation=request.screen_generation, code=error.code, message=str(error))
                if getattr(error, 'timings_ms', None) is not None:
                    payload['timings_ms'] = error.timings_ms
                if getattr(error, 'diagnostics', None) is not None:
                    payload['diagnostics'] = error.diagnostics
                if getattr(error, 'retry_after_ms', None) is not None:
                    payload['retry_after_ms'] = error.retry_after_ms
                await send(payload, only=current)
                return True
            except asyncio.CancelledError:
                raise
            except Exception:
                runtime.manager.metrics['errors'] += 1
                runtime.manager.event('Lỗi nội bộ khi dịch; nội dung màn hình không được ghi nhật ký.', 'error')
                await send(dict(type='error', request_id=request.request_id, region_id=request.region_id,
                                screen_generation=request.screen_generation, code='internal_error', message='Máy chủ gặp lỗi. Hãy thử lại.'), only=current)
                return True

        async def drain_latest():
            # A running official CLI turn is drained to retain its warm process.
            # Only one waiting request is retained and only the newest may reply.
            nonlocal pending
            while pending is not None:
                request, queued_at = pending
                pending = None
                await process(request, latest=True, queued_at=queued_at)

        def fingerprint(request):
            # Correlation ids and boxes may change while the same subtitle is
            # still visible. They must not start a second identical API call.
            return (runtime.manager.revision, request.source, request.profile,
                    request.mode, tuple(normalize(block.text) for block in request.blocks))

        async def api_worker(request, queued_at, ordinal):
            # API requests do not need to serialize behind an obsolete CLI turn.
            # Keep at most one obsolete cache filler beside the newest frame.
            # A third distinct frame preempts the oldest obsolete HTTP worker.
            nonlocal api_pending
            task = asyncio.current_task()
            try:
                while True:
                    api_active[task] = (fingerprint(request), ordinal)
                    await process(request, latest=True, queued_at=queued_at, api=True)
                    if api_pending is None:
                        break
                    next_request, next_queued_at, next_ordinal = api_pending
                    next_key = fingerprint(next_request)
                    if any(key == next_key for other, (key, _) in api_active.items() if other is not task):
                        # The other worker will fill the cache before handling
                        # the identical latest request with its new correlation.
                        break
                    api_pending = None
                    request, queued_at, ordinal = next_request, next_queued_at, next_ordinal
            except (WebSocketDisconnect, RuntimeError):
                # The receive loop handles connection cleanup. A concurrently
                # closed socket must not leave an unobserved worker exception.
                pass
            finally:
                api_active.pop(task, None)

        async def cancel_api():
            nonlocal api_pending
            api_pending = None
            tasks = list(api_active)
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            api_active.clear()

        try:
            await send(dict(type='hello', protocol=1, cache_enabled=runtime.manager.settings().cache_enabled, revision=runtime.manager.revision,
                            **runtime.provider_state(), capabilities=['latest_pending', 'api_latest_parallel_v1', 'correlation_v1', 'timings_v1','capture_state_v1']))
            invalid = 0
            while True:
                raw = await ws.receive_text()
                if len(raw.encode()) > 65536:
                    await ws.close(code=1009)
                    break
                try:
                    data = json.loads(raw)
                    if not isinstance(data, dict):
                        raise ValueError()
                    if data.get('type') == 'ping':
                        await send(dict(type='pong'))
                        continue
                    if data.get('type') == 'capture_state':
                        if not isinstance(data.get('active'),bool):raise ValueError()
                        runtime.manager.resources.set_live_active(live_owner,data['active'])
                        continue
                    if data.get('type') == 'cancel':
                        requested_id = data.get('request_id')
                        if requested_id is not None and wanted is not None and requested_id != wanted.request_id:
                            continue
                        wanted = None
                        pending = None
                        await cancel_api()
                        if data.get('preserve_session') is True and active_latest:
                            continue
                        if active:
                            active.cancel()
                            with suppress(asyncio.CancelledError):
                                await active
                            active = None
                        continue
                    request = TranslateRequest.model_validate(data)
                except (ValidationError, ValueError, TypeError):
                    invalid += 1
                    await send(dict(type='error', code='invalid_request', message='Gói dữ liệu không hợp lệ.'))
                    if invalid >= 5:
                        await ws.close(code=1008)
                        break
                    continue
                if not runtime.store.authenticate(token):
                    await ws.close(code=4401)
                    break
                if request.latest_only:
                    if runtime.manager.settings().provider == 'api':
                        if active is not None and not active.done():
                            active.cancel()
                            with suppress(asyncio.CancelledError):
                                await active
                        active = None
                        active_latest = False
                        pending = None
                        wanted = request
                        queued_at = time.monotonic()
                        api_sequence += 1
                        api_pending = None
                        if await process(request, latest=True, queued_at=queued_at, cache_only=True):
                            continue
                        key = fingerprint(request)
                        same_active = any(active_key == key for active_key, _ in api_active.values())
                        if len(api_active) >= 2 and not same_active:
                            # Neither active request can display a useful result
                            # now. Free the oldest slot so the current subtitle
                            # cannot queue behind two stalled obsolete calls.
                            # Cancelling HTTP does not guarantee provider-side
                            # cancellation or a refund; quota remains reserved.
                            oldest = min(api_active, key=lambda task: api_active[task][1])
                            runtime.manager.metrics['stale_cancels'] += 1
                            oldest.cancel()
                            await asyncio.gather(oldest, return_exceptions=True)
                        if len(api_active) < 2 and not same_active:
                            task = asyncio.create_task(api_worker(request, queued_at, api_sequence))
                            api_active[task] = (key, api_sequence)
                        else:
                            api_pending = (request, queued_at, api_sequence)
                        continue
                    await cancel_api()
                    if active is not None and not active.done() and not active_latest:
                        active.cancel()
                        with suppress(asyncio.CancelledError):
                            await active
                    wanted = request
                    pending = (request, time.monotonic())
                    if active is None or active.done():
                        active = asyncio.create_task(drain_latest())
                    active_latest = True
                else:
                    wanted = None
                    pending = None
                    await cancel_api()
                    if active:
                        active.cancel()
                        with suppress(asyncio.CancelledError):
                            await active
                    active_latest = False
                    active = asyncio.create_task(process(request))
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            runtime.manager.resources.set_live_active(live_owner, False)
            await cancel_api()
            if active:
                active.cancel()
                with suppress(asyncio.CancelledError, RuntimeError):
                    await active
            if runtime.sockets.get(ident) is ws:
                runtime.sockets.pop(ident, None)
                runtime.manager.contexts = {k: v for k, v in runtime.manager.contexts.items() if k[0] != ident}
                await runtime.cli.release_device(ident)

    return app


def admin_app(runtime: Runtime, static_path: Path):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=['127.0.0.1', 'localhost', 'testserver'])

    @app.middleware('http')
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['Cache-Control'] = 'no-store'
        response.headers['Content-Security-Policy'] = "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response

    def admin(authorization: str = Header(default='')):
        if not secrets.compare_digest(authorization, 'Bearer ' + runtime.admin_token):
            raise HTTPException(401, 'Hãy mở giao diện từ chương trình Gemini OCR trên Windows.')

    @app.get('/')
    async def index():
        return FileResponse(static_path / 'index.html')

    @app.get('/api/status', dependencies=[Depends(admin)])
    async def status():
        settings = runtime.manager.settings().model_dump(exclude={'api_key'})
        return dict(version=__version__, protocol=1, api_configured=bool(runtime.api_key()),
                    provider_configured=runtime.provider_state()['translation_available'],
                    **runtime.provider_state(),
                    cli=runtime.cli.status(),
                    addresses=local_addresses(), port=runtime.port, fingerprint=runtime.pin,
                    devices=[dict(d, connected=d['id'] in runtime.sockets) for d in runtime.store.devices()],
                    metrics=runtime.manager.metrics, cache_entries=runtime.store.cache_size(),
                    usage=runtime.store.usage(), settings=settings,
                    events=list(runtime.manager.events), uptime=int(time.time() - runtime.started))

    @app.put('/api/settings', dependencies=[Depends(admin)])
    async def settings(body: Settings):
        if body.api_key is not None:
            runtime.store.set('api_key', protect_secret(body.api_key.strip()) if body.api_key.strip() else None)
        runtime.store.set('settings', body.model_dump(exclude={'api_key'}))
        runtime.invalidate()
        if not body.cache_enabled:
            runtime.store.clear_cache()
        runtime.manager.event('Đã lưu cấu hình dịch.')
        return dict(ok=True)

    @app.post('/api/cli/login', dependencies=[Depends(admin)])
    async def cli_login():
        try:
            runtime.cli.login()
        except (TranslationError, OSError) as error:
            raise HTTPException(400, str(error) if isinstance(error, TranslationError) else 'Không mở được Gemini CLI. Hãy cài lại bản Windows mới nhất.') from None
        runtime.invalidate()
        return dict(ok=True, **runtime.cli.status())

    @app.post('/api/cli/install', dependencies=[Depends(admin)])
    async def cli_install():
        if runtime.cli.install_lock.locked():
            return dict(ok=True, **runtime.cli.status())
        async def install():
            try:
                await runtime.cli.install()
            except TranslationError as error:
                runtime.manager.event(str(error),'error')
        asyncio.create_task(install())
        return dict(ok=True, **runtime.cli.status())

    @app.post('/api/cli/check', dependencies=[Depends(admin)])
    async def cli_check():
        # A synthetic public sentence verifies actual translation, not the mere presence
        # of a cached credential. It never inspects OAuth credentials or claims a plan tier.
        if runtime.cli.lock.locked():
            raise HTTPException(409, 'Gemini CLI đang dịch. Hãy tạm dừng trước khi kiểm tra.')
        try:
            settings = runtime.manager.settings()
            if not runtime.cli.configured:
                raise runtime.cli.configuration_error()
            now = time.monotonic()
            while runtime.manager.calls and now - runtime.manager.calls[0] >= 60:
                runtime.manager.calls.popleft()
            if len(runtime.manager.calls) >= settings.requests_per_minute:
                raise TranslationError('rate_limited', 'Đạt giới hạn lượt gọi/phút đã cài. Hãy chờ rồi kiểm tra lại.')
            day = runtime.store.day()
            if not runtime.store.reserve_call(settings.daily_request_limit, day=day):
                raise TranslationError('daily_limit', 'Đã đạt giới hạn lượt gọi trong ngày (UTC).')
            runtime.manager.calls.append(now)
            model = settings.cli_model
            result, input_tokens, output_tokens = await runtime.cli.translate(['Hello, welcome!'], source='en',
                profile='game', mode='fast', model=model, glossary=[], context=[])
            runtime.store.add_tokens(input_tokens, output_tokens, day=day)
            runtime.manager.event('Đã kiểm tra dịch qua Gemini CLI thành công.')
            return dict(ok=True, translation=result[0], **runtime.cli.status())
        except TranslationError as error:
            raise HTTPException(400, str(error)) from None

    @app.post('/api/pairing', dependencies=[Depends(admin)])
    async def pairing():
        code = runtime.pairing.create()
        host = local_addresses()[0]
        uri = 'gemocr://pair?' + urlencode(dict(host=f'{host}:{runtime.port}', pin=runtime.pin, code=code))
        return dict(code=code, expires_in=120, uri=uri)

    @app.get('/api/pairing/qr', dependencies=[Depends(admin)])
    async def qr(host: str):
        import qrcode
        import qrcode.image.svg
        if host not in local_addresses() or not runtime.pairing.code or time.monotonic() > runtime.pairing.expires:
            raise HTTPException(400, 'Chọn địa chỉ Wi-Fi hợp lệ và tạo mã mới.')
        uri = 'gemocr://pair?' + urlencode(dict(host=f'{host}:{runtime.port}', pin=runtime.pin, code=runtime.pairing.code))
        image = qrcode.make(uri, image_factory=qrcode.image.svg.SvgPathImage)
        out = io.BytesIO()
        image.save(out)
        return Response(out.getvalue(), media_type='image/svg+xml')

    @app.delete('/api/devices/{ident}', dependencies=[Depends(admin)])
    async def revoke(ident: str):
        runtime.store.revoke(ident)
        if ident in runtime.sockets:
            await runtime.sockets[ident].close(code=4401)
        runtime.invalidate()
        return dict(ok=True)

    @app.get('/api/glossary', dependencies=[Depends(admin)])
    async def glossary():
        return runtime.store.glossary()

    @app.post('/api/glossary', dependencies=[Depends(admin)])
    async def add_glossary(body: GlossaryEntry):
        if not normalize(body.source) or not body.target.strip():
            raise HTTPException(400, 'Hãy nhập cả từ gốc và bản dịch.')
        try:
            runtime.store.put_glossary(normalize(body.source), body.target.strip(), body.profile)
        except ValueError as error:
            raise HTTPException(400, str(error)) from None
        runtime.invalidate()
        return dict(ok=True)

    @app.delete('/api/glossary/{ident}', dependencies=[Depends(admin)])
    async def delete_glossary(ident: int):
        runtime.store.delete_glossary(ident)
        runtime.invalidate()
        return dict(ok=True)

    @app.put('/api/glossary/{ident}', dependencies=[Depends(admin)])
    async def edit_glossary(ident: int, body: GlossaryEntry):
        try:
            runtime.store.update_glossary(ident, normalize(body.source), body.target.strip(), body.profile)
        except KeyError:
            raise HTTPException(404, 'Mục từ đã bị xóa. Hãy tải lại từ điển.') from None
        except ValueError as error:
            raise HTTPException(409, str(error)) from None
        runtime.invalidate()
        return dict(ok=True)

    @app.post('/api/glossary/import', dependencies=[Depends(admin)])
    async def import_glossary(body: GlossaryImport):
        try:
            runtime.store.import_glossary(body.entries)
        except ValueError as error:
            raise HTTPException(400, str(error)) from None
        runtime.invalidate()
        return dict(ok=True, imported=len(body.entries))

    @app.delete('/api/cache', dependencies=[Depends(admin)])
    async def clear_cache():
        runtime.store.clear_cache()
        runtime.invalidate()
        return dict(ok=True)

    @app.post('/api/translate', dependencies=[Depends(admin)])
    async def test_translation(body: TranslateRequest):
        try:
            return await runtime.manager.translate(body, 'desktop-test')
        except TranslationError as error:
            raise HTTPException(400, str(error)) from None

    @app.post('/api/shutdown', dependencies=[Depends(admin)])
    async def shutdown():
        runtime.stop.set()
        return dict(ok=True)

    register_subtitle_routes(app, runtime.subtitle_jobs, admin)
    register_video_routes(app, runtime, admin)
    app.mount('/assets', StaticFiles(directory=static_path), name='assets')
    return app

