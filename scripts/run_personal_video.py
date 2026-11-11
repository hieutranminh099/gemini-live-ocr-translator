"""Start the personal upload-to-result app without touching the Gemini install."""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from pathlib import Path
import socket
import sys
import webbrowser

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'windows-server'))


def main():
    parser = argparse.ArgumentParser()
    default = Path('E:/GeminiOCR-personal') if Path('E:/').exists() else Path(os.environ['LOCALAPPDATA'])/'GeminiOCR-personal'
    parser.add_argument('--data-dir', type=Path, default=default)
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    from app.security import protect_secret, unprotect_secret
    from app.personal_video import PersonalVideoRuntime, personal_video_app
    import uvicorn

    media_roots = [ROOT/'.tools/video-runtime', Path('C:/Users/AD/Documents/ChatGPT/App/.tools/video-runtime')]
    instance = args.data_dir/'window.dpapi'
    import msvcrt
    lock = (args.data_dir/'instance.lock').open('a+b')
    lock.seek(0)
    if lock.read(1) == b'':
        lock.write(b'1'); lock.flush()
    lock.seek(0)
    try:
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        # Only this application's DPAPI-protected window record, never other apps' tokens.
        if instance.exists() and not args.no_browser:
            try:
                url = json.loads(unprotect_secret(instance.read_text(encoding='utf-8')))['url']
                if url.startswith('http://127.0.0.1:'):
                    webbrowser.open(url)
            except (ValueError, OSError, KeyError):
                pass
        lock.close()
        return
    runtime = PersonalVideoRuntime(args.data_dir, media_roots=media_roots)
    if args.check:
        print(json.dumps(dict(media_ready=runtime.service.tools is not None,
            static_ready=(ROOT/'windows-server/personal-video/index.html').is_file(),
            authentication='Official Sign in with ChatGPT', data_on_e=str(args.data_dir).upper().startswith('E:'))))
        asyncio.run(runtime.close())
        lock.close()
        return
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(('127.0.0.1', args.port))
    runtime.port = sock.getsockname()[1]
    url = f'http://127.0.0.1:{runtime.port}/#access={runtime.admin_token}'
    temporary = instance.with_suffix('.tmp')
    temporary.write_text(protect_secret(json.dumps(dict(url=url))), encoding='utf-8')
    temporary.replace(instance)
    logging.basicConfig(level=logging.WARNING)
    app = personal_video_app(runtime, ROOT/'windows-server/personal-video')
    config = uvicorn.Config(app, host='127.0.0.1', port=runtime.port, access_log=False,
        log_level='warning', log_config=None, server_header=False, date_header=False)
    server = uvicorn.Server(config)
    runtime.request_shutdown = lambda: setattr(server, 'should_exit', True)

    async def serve():
        task = asyncio.create_task(server.serve(sockets=[sock]))
        while not server.started and not task.done():
            await asyncio.sleep(.05)
        if server.started and not args.no_browser:
            webbrowser.open(url)
        await task

    try:
        asyncio.run(serve())
    finally:
        sock.close()
        if instance.exists():
            instance.unlink()
        lock.close()


if __name__ == '__main__':
    main()
