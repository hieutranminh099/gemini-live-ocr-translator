"""Loopback-only, upload-to-result personal workflow using existing video stages."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
from pathlib import Path
import secrets
import re
from types import SimpleNamespace
from urllib.parse import unquote

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .chatgpt_plan import ChatGPTError, ChatGPTPlanClient
from .resources import ResourceManager
from .storage import Store
from .translation_memory import TranslationMemoryManager
from .video_jobs import MAX_VIDEO_BYTES, VideoJobService, VideoOptions
from .video_media import VideoMediaError, discover_tools


class PersonalVideoManager:
    """Adapter to the established checkpoint/media runner, with an isolated store."""
    def __init__(self, store, client):
        self.store, self.client = store, client
        self.provider = client
        self.resources = ResourceManager(slots=1)
        self.memory = TranslationMemoryManager(store)
        self.revision = 0
        self.contexts = {}

    def settings(self):
        # The existing media runner's 'api' gate denotes an official backend.
        # This store is never shared with the Gemini/Live store or its cache.
        return SimpleNamespace(provider='api', memory_enabled=False,
            fast_model=self.client.selected_model, smart_model=self.client.selected_model,
            quality_model=self.client.selected_model)

    def require_revision(self, revision):
        if revision != self.revision:
            raise ChatGPTError('CHATGPT_CONNECTION_CHANGED', 'Kết nối thay đổi. Tiến độ được giữ để tiếp tục.')

    async def translate(self, request, device_id, **kwargs):
        async with self.resources.permit('video'):
            accepted, usage = await self.client.translate_rows(
                [dict(id=b.id, text=b.text) for b in request.blocks], request.source)
        return dict(blocks=[dict(id=b.id, text=accepted[b.id], cached=False) for b in request.blocks],
            diagnostics=dict(provider_calls=usage.get('provider_calls', 1), input_tokens=usage.get('input_tokens', 0), output_tokens=usage.get('output_tokens', 0)))


class PersonalVideoRuntime:
    def __init__(self, directory, *, client=None, media_roots=(), service_factory=VideoJobService):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.client = client or ChatGPTPlanClient(self.directory/'chatgpt')
        self.store = Store(self.directory/'project-store')
        self.manager = PersonalVideoManager(self.store, self.client)
        self.service = service_factory(self.directory/'films', self.manager, tools=discover_tools(media_roots))
        self.service.runtime_roots = list(map(Path, media_roots)) + self.service.runtime_roots
        self.admin_token = secrets.token_urlsafe(32)
        self.browser_token = secrets.token_urlsafe(32)
        self.port = 0
        self.runner = None
        self.active_id = None
        self.uploading = False
        self.lock = asyncio.Lock()

    @property
    def busy(self):
        return self.uploading or self.runner is not None and not self.runner.done()

    def require_idle(self):
        if self.busy:
            raise VideoMediaError('video_busy', 'Đang xử lý phim. Tạm dừng hoặc đợi hoàn tất trước khi đổi kết nối.')

    async def import_and_start(self, name, reader, source='zh'):
        async with self.lock:
            self.require_idle()
            if not self.client.configured:
                raise ChatGPTError('CHATGPT_PLAN_DISABLED', 'Đăng nhập và bật quyền dùng gói ChatGPT trước khi chọn phim.')
            self.uploading = True
            try:
                if not self.client.models:
                    await self.client.list_models()
                options = VideoOptions(source=source, use_memory=False, private=True, save_memory=False)
                job = await self.service.import_upload(name, reader)
                await self.service.start(job['id'], options.model_dump())
                self.active_id = job['id']
                self.runner = asyncio.create_task(self._finish(job['id']), name='personal-film')
                return self.service.list_jobs()
            finally:
                self.uploading = False

    async def _finish(self, ident):
        try:
            await self.service.task
            if self.service._job(ident)['status'] != 'ready':
                return
            await self.service.export(ident, dict(format='srt'))
            await self.service.export(ident, dict(format='mp4', quality='balanced'))
            await self.service.task
        except asyncio.CancelledError:
            job = self.service._job(ident)
            job.update(status='interrupted' if self.service.closed else 'cancelled',
                error='Đã lưu tiến độ. Có thể tiếp tục phần chưa hoàn tất.')
            await self.service._save(job)
            raise
        except (VideoMediaError, ChatGPTError) as error:
            job = self.service._job(ident)
            job.update(status='failed', error=str(error), error_code=error.code)
            await self.service._save(job)
        except Exception:
            job = self.service._job(ident)
            job.update(status='failed', error='Chưa xuất được kết quả. Nguồn và tiến độ đã lưu được giữ.', error_code='PERSONAL_EXPORT_FAILED')
            await self.service._save(job)
        finally:
            if self.active_id == ident:
                self.active_id = None

    async def resume(self, ident):
        async with self.lock:
            self.require_idle()
            job = self.service._job(ident)
            outputs = job.get('outputs', {})
            if job['status'] == 'completed' and all(kind in outputs and
                    (self.service._path(ident)/outputs[kind]).is_file() for kind in ('srt', 'mp4')):
                return  # A completed film must not consume AI or render space again.
            if not self.client.configured:
                raise ChatGPTError('CHATGPT_PLAN_DISABLED', 'Kết nối ChatGPT cần được cấp quyền trước khi tiếp tục.')
            if not self.client.models:
                await self.client.list_models()
            await self.service.action(ident, 'resume')
            self.active_id = ident
            self.runner = asyncio.create_task(self._finish(ident), name='personal-film')

    async def pause(self, ident):
        # Cancel cooperatively to persist the existing checkpoint and admit a later resume.
        async with self.lock:
            self.service._job(ident)
            if self.active_id != ident:
                raise VideoMediaError('video_not_active', 'Phim này không phải phim đang xử lý. Hãy làm mới danh sách.')
            await self.service.action(ident, 'cancel')
            if self.runner and not self.runner.done():
                self.runner.cancel()
                await asyncio.gather(self.runner, return_exceptions=True)

    async def close(self):
        await self.service.close()
        if self.runner and not self.runner.done():
            self.runner.cancel()
            await asyncio.gather(self.runner, return_exceptions=True)
        await self.manager.memory.flush()
        await self.client.close()
        self.store.close()


def personal_video_app(runtime, static_path):
    @asynccontextmanager
    async def lifespan(app):
        yield
        await runtime.close()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=['127.0.0.1', 'localhost', 'testserver'])

    @app.middleware('http')
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers.update({'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
            'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"})
        return response

    cookie_name = 'personal_video_'+str(runtime.port)

    def admin(request: Request, authorization: str = Header(default='')):
        header_valid = secrets.compare_digest(authorization.encode('utf-8'), ('Bearer '+runtime.admin_token).encode('utf-8'))
        cookie_valid = secrets.compare_digest(request.cookies.get(cookie_name, '').encode('utf-8'), runtime.browser_token.encode('utf-8'))
        if not (header_valid or cookie_valid):
            raise HTTPException(401, 'Mở app bằng lối tắt Dịch phim cá nhân trên máy.')
        origin = request.headers.get('origin')
        expected_origin = str(request.base_url).rstrip('/')
        if origin is not None and origin != expected_origin or (
                not header_valid and request.method not in {'GET', 'HEAD', 'OPTIONS'} and origin != expected_origin):
            raise HTTPException(403, 'Yêu cầu phải xuất phát từ cửa sổ Dịch phim cá nhân.')

    @app.exception_handler(ChatGPTError)
    @app.exception_handler(VideoMediaError)
    async def failure(request, error):
        from fastapi.responses import JSONResponse
        return JSONResponse(status_code=409 if error.code == 'video_busy' else 400,
            content=dict(error=str(error), code=error.code))

    @app.get('/')
    async def index():
        return FileResponse(Path(static_path)/'index.html')

    @app.post('/api/session', dependencies=[Depends(admin)])
    async def session(request: Request):
        # Exchange the launcher capability for an HTTP-only, same-site browser session.
        # OAuth credentials remain server-side; reloading this page needs no new sign-in.
        from fastapi.responses import JSONResponse
        response = JSONResponse(dict(ok=True))
        response.set_cookie(cookie_name, runtime.browser_token, httponly=True, samesite='strict', path='/api/')
        return response

    @app.get('/auth/callback')
    async def callback(request: Request):
        try:
            if any(len(request.query_params.getlist(key)) != 1 for key in ('state', 'code', 'client_id', 'error') if key in request.query_params):
                raise ChatGPTError('CHATGPT_CALLBACK_INVALID', 'Lượt đăng nhập không hợp lệ.')
            await runtime.client.finish_sign_in(dict(request.query_params))
            runtime.client.last_error = ''
            message = 'Đã kết nối tài khoản. Quay lại cửa sổ Dịch phim cá nhân để chọn phim.'
            if not runtime.client.configured:
                message = 'Đã đăng nhập nhưng chưa được cấp quyền dùng gói ChatGPT. Quay lại app để kiểm tra.'
            return HTMLResponse('<!doctype html><html lang="vi"><meta charset="utf-8"><title>Kết nối ChatGPT</title><body><h1>'+message+'</h1></body></html>')
        except ChatGPTError as error:
            runtime.client.last_error = str(error)
            # Never reflect callback parameters or provider diagnostic text into HTML.
            return HTMLResponse('<!doctype html><html lang="vi"><meta charset="utf-8"><title>Kết nối chưa hoàn tất</title><body><h1>Chưa kết nối được. Quay lại app để xem nguyên nhân và thử lại.</h1></body></html>', status_code=400)

    @app.get('/api/state', dependencies=[Depends(admin)])
    async def state():
        return dict(account=runtime.client.public_state(), jobs=runtime.service.list_jobs(), busy=bool(runtime.busy), active_id=runtime.active_id,
            media_ready=runtime.service.tools is not None, data_location=str(runtime.directory))

    @app.post('/api/account/login', dependencies=[Depends(admin)])
    async def login():
        async with runtime.lock:
            runtime.require_idle()
            return dict(url=runtime.client.begin_sign_in(f'http://127.0.0.1:{runtime.port}/auth/callback'))

    @app.post('/api/account/models', dependencies=[Depends(admin)])
    async def models():
        async with runtime.lock:
            runtime.require_idle()
            try:
                await runtime.client.list_models()
                runtime.client.last_error = ''
            except ChatGPTError as error:
                runtime.client.last_error = str(error)
                raise
        return runtime.client.public_state()

    @app.post('/api/account/model', dependencies=[Depends(admin)])
    async def model(request: Request):
        async with runtime.lock:
            runtime.require_idle()
            data = await request.json()
            runtime.client.select_model(data.get('model'))
        return runtime.client.public_state()

    @app.post('/api/account/logout', dependencies=[Depends(admin)])
    async def logout():
        async with runtime.lock:
            runtime.require_idle()
            revoked = await runtime.client.disconnect()
        return dict(revoked=revoked)

    @app.post('/api/films', dependencies=[Depends(admin)])
    async def upload(request: Request, source: str = 'zh', x_video_name: str = Header(default='')):
        if source not in {'zh', 'ja', 'ko', 'en'}:
            raise VideoMediaError('invalid_source', 'Ngôn ngữ nguồn chưa được hỗ trợ.')
        try:
            length = int(request.headers.get('content-length', '0'))
        except ValueError:
            raise VideoMediaError('upload_limit', 'Dung lượng tệp chưa hợp lệ.') from None
        if length < 0 or length > MAX_VIDEO_BYTES:
            raise VideoMediaError('upload_limit', 'Tệp phim vượt dung lượng cho phép.')
        stream = request.stream().__aiter__()
        carry = b''
        async def reader(size):
            nonlocal carry
            while not carry:
                carry = await anext(stream, b'')
                if not carry:
                    return b''
            result, carry = carry[:size], carry[size:]
            return result
        # Raw streaming avoids spooling a whole film into the Windows C: temp folder.
        return await runtime.import_and_start(unquote(x_video_name)[:300], reader, source)

    @app.post('/api/films/{ident}/resume', dependencies=[Depends(admin)])
    async def resume(ident: str):
        await runtime.resume(ident)
        return dict(ok=True)

    @app.post('/api/films/{ident}/pause', dependencies=[Depends(admin)])
    async def pause(ident: str):
        await runtime.pause(ident)
        return dict(ok=True)

    @app.post('/api/close', dependencies=[Depends(admin)])
    async def close():
        if hasattr(runtime, 'request_shutdown'):
            runtime.request_shutdown()
        return dict(ok=True)

    @app.get('/api/video/files/{ident}/{kind}')
    async def download(ident: str, kind: str, token: str = ''):
        if kind not in {'mp4', 'srt'}:
            raise HTTPException(404)
        path = runtime.service.file(ident, kind, token)
        source_name = Path(runtime.service._job(ident)['name']).stem
        title = re.sub(r'[\x00-\x1f\x7f<>:"/\\|?*]', '_', source_name).strip(' .')[:150] or 'Phim'
        return FileResponse(path, filename=title+'.vi.'+kind, content_disposition_type='attachment')

    app.mount('/assets', StaticFiles(directory=static_path), name='personal-assets')
    return app
