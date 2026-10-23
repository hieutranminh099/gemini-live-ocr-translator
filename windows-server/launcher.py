import argparse
import asyncio
import ctypes
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import socket
import sys
import time
from urllib.parse import urlsplit
import webbrowser

import httpx
import uvicorn
from app.server import Runtime, admin_app, device_app


def listener(host, port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind((host, port)); sock.listen(128); sock.setblocking(False)
        return sock
    except OSError:
        sock.close()
        raise


def existing_dashboard(path, port):
    # Verify a live companion before treating an occupied port as our own process.
    with httpx.Client(timeout=.4, trust_env=False) as client:
        for _ in range(15):
            try:
                url = path.read_text(encoding='utf-8').strip()
                parsed = urlsplit(url)
                if parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port == port and len(parsed.fragment) >= 32:
                    response = client.get(f'http://127.0.0.1:{port}/api/status', headers={'Authorization':'Bearer '+parsed.fragment})
                    if response.status_code == 200 and response.json().get('protocol') == 1:
                        return url
            except (OSError, ValueError, httpx.HTTPError):
                pass
            time.sleep(.1)
    return None


def main():
    if len(sys.argv)>1 and sys.argv[1]=='--speech-worker':
        from app.video_speech import speech_worker_main
        return speech_worker_main(sys.argv[2:])
    parser = argparse.ArgumentParser(description='Gemini Live OCR Translator — Windows Companion')
    parser.add_argument('--data-dir', type=Path)
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--admin-port', type=int, default=8766)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1 <= args.admin_port <= 65535 or args.port == args.admin_port:
        parser.error('Các cổng phải khác nhau và nằm trong khoảng 1–65535.')
    directory = args.data_dir or Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'GeminiOCR' / 'data'
    directory.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(directory / 'server.log', maxBytes=500000, backupCount=2, encoding='utf-8')
    logging.basicConfig(level=logging.WARNING, handlers=[handler], force=True)
    launch_file = directory / 'dashboard.url'
    try:
        admin_socket = listener('127.0.0.1', args.admin_port)
    except OSError:
        dashboard = existing_dashboard(launch_file, args.admin_port)
        if dashboard:
            if not args.no_browser: webbrowser.open(dashboard)
            return
        raise RuntimeError(f'Cổng quản trị {args.admin_port} đang được ứng dụng khác sử dụng.') from None
    try:
        device_socket = listener('0.0.0.0', args.port)
    except OSError:
        admin_socket.close()
        raise RuntimeError(f'Cổng thiết bị {args.port} đang được sử dụng. Hãy tắt máy chủ trùng cổng.') from None
    try:
        runtime = Runtime(directory)
    except Exception:
        device_socket.close(); admin_socket.close()
        raise
    runtime.port = args.port
    url = f'http://127.0.0.1:{args.admin_port}/#{runtime.admin_token}'
    static_path = Path(__file__).resolve().parent / 'static'
    if hasattr(sys, '_MEIPASS'):
        static_path = Path(sys._MEIPASS) / 'static'

    async def run():
        common = dict(log_config=None, log_level='warning', access_log=False, timeout_graceful_shutdown=5)
        devices = uvicorn.Server(uvicorn.Config(device_app(runtime), host='0.0.0.0', port=args.port,
            ssl_certfile=str(runtime.cert), ssl_keyfile=str(runtime.key), ws='websockets',
            ws_max_size=65536, ws_max_queue=4, **common))
        admin = uvicorn.Server(uvicorn.Config(admin_app(runtime, static_path), host='127.0.0.1', port=args.admin_port, **common))
        # Keep both sockets bound from validation through shutdown: simultaneous launches cannot race.
        tasks = [asyncio.create_task(devices.serve(sockets=[device_socket])), asyncio.create_task(admin.serve(sockets=[admin_socket]))]
        try:
            for _ in range(100):
                if all(s.started for s in (devices, admin)):
                    break
                if any(t.done() for t in tasks):
                    for task in tasks:
                        if task.done(): task.result()
                    raise RuntimeError('Máy chủ đã dừng trước khi khởi động xong.')
                await asyncio.sleep(.1)
            if not all(s.started for s in (devices,admin)):
                raise TimeoutError('Máy chủ chưa khởi động sau 10 giây.')
            pending_url = directory / 'dashboard.url.tmp'
            pending_url.write_text(url,encoding='utf-8')
            pending_url.replace(launch_file)
            if not args.no_browser:
                webbrowser.open(url)
            while not runtime.stop.is_set() and not any(t.done() for t in tasks):
                await asyncio.sleep(.25)
        finally:
            await runtime.video_jobs.close()
            await runtime.subtitle_jobs.close()
            devices.should_exit = admin.should_exit = True
            async def close_socket(ws):
                try:
                    await ws.close(code=1001)
                except Exception:
                    pass
            try:
                await asyncio.wait_for(asyncio.gather(*(close_socket(ws) for ws in list(runtime.sockets.values()))),timeout=2)
            except asyncio.TimeoutError:
                pass
            await asyncio.gather(*tasks, return_exceptions=True)
            await runtime.provider.close()
            await runtime.manager.close()
            runtime.store.close()
            if launch_file.exists() and launch_file.read_text(encoding='utf-8') == url:
                launch_file.unlink()
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
    finally:
        admin_socket.close(); device_socket.close()


if __name__ == '__main__':
    try:
        result = main()
        if isinstance(result,int):
            raise SystemExit(result)
    except Exception as error:
        logging.exception('Application startup failed')
        if '--no-browser' not in sys.argv and os.name == 'nt':
            ctypes.windll.user32.MessageBoxW(None, f'Không thể mở Gemini OCR.\n{error}\nXem server.log trong thư mục dữ liệu để biết nguyên nhân.', 'Gemini OCR', 16)
        sys.exit(1)
