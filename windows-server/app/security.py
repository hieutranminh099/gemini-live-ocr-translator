import base64
import ctypes
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import secrets
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


def protect_secret(value: str) -> str:
    if os.name != 'nt':
        raise RuntimeError('API key storage requires Windows DPAPI. Set GEMINI_API_KEY instead.')
    class Blob(ctypes.Structure):
        _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_char))]
    raw = value.encode()
    buf = ctypes.create_string_buffer(raw)
    source = Blob(len(raw), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    target = Blob()
    if not ctypes.windll.crypt32.CryptProtectData(ctypes.byref(source), 'Gemini OCR', None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError()
    try:
        return base64.b64encode(ctypes.string_at(target.pbData, target.cbData)).decode()
    finally:
        ctypes.windll.kernel32.LocalFree(target.pbData)


def unprotect_secret(value: str) -> str:
    class Blob(ctypes.Structure):
        _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_char))]
    raw = base64.b64decode(value)
    buf = ctypes.create_string_buffer(raw)
    source = Blob(len(raw), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    target = Blob()
    if not ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(target.pbData, target.cbData).decode()
    finally:
        ctypes.windll.kernel32.LocalFree(target.pbData)


def ensure_certificate(directory: Path):
    cert_path, key_path = directory / 'server.pem', directory / 'server.key'
    if not cert_path.exists() or not key_path.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Gemini OCR Companion')])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(days=1))
                .not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .sign(key, hashes.SHA256()))
        key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    pin = cert.fingerprint(hashes.SHA256()).hex()
    return cert_path, key_path, pin


class Pairing:
    def __init__(self):
        self.code = None
        self.expires = 0
        self.attempts = 0

    def create(self):
        self.code = f'{secrets.randbelow(1000000):06}'
        self.expires = time.monotonic() + 120
        self.attempts = 0
        return self.code

    def consume(self, code):
        self.attempts += 1
        if self.attempts > 8 or time.monotonic() > self.expires or self.code is None:
            return False
        if not secrets.compare_digest(self.code, code):
            return False
        self.code = None
        return True

