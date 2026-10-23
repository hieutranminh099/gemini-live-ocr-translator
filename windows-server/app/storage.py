import hashlib
import json
import secrets
import sqlite3
import threading
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path


def normalize(text: str) -> str:
    return ' '.join(unicodedata.normalize('NFKC', text).split())


class Store:
    def __init__(self, directory: Path):
        directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(directory / 'translator.db', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        PRAGMA secure_delete=ON;
        CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL,
          token_hash TEXT UNIQUE NOT NULL, created REAL NOT NULL, seen REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, translated TEXT NOT NULL, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS glossary (id INTEGER PRIMARY KEY, source TEXT NOT NULL,
          target TEXT NOT NULL, profile TEXT NOT NULL, UNIQUE(source,profile));
        CREATE TABLE IF NOT EXISTS usage (day TEXT PRIMARY KEY, requests INTEGER NOT NULL DEFAULT 0,
          input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS translation_memory (
          content_hash TEXT PRIMARY KEY, namespace TEXT NOT NULL,
          original_text TEXT NOT NULL, normalized_text TEXT NOT NULL, translation TEXT NOT NULL,
          source_language TEXT NOT NULL, profile TEXT NOT NULL, mode TEXT NOT NULL,
          provider TEXT NOT NULL, model TEXT NOT NULL, glossary_version TEXT NOT NULL,
          context_scope TEXT NOT NULL, context_information TEXT NOT NULL,
          source_type TEXT NOT NULL CHECK(source_type IN ('LIVE','VIDEO')),
          project_id TEXT NOT NULL, quality TEXT NOT NULL CHECK(quality IN ('draft','verified','user_approved')),
          confidence_metadata TEXT NOT NULL, created_at REAL NOT NULL, updated_at REAL NOT NULL,
          hit_count INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS memory_exact ON translation_memory(namespace,context_scope,normalized_text);
        CREATE INDEX IF NOT EXISTS memory_project ON translation_memory(project_id,updated_at);
        ''')
        self.db.commit()

    def get(self, key, default=None):
        with self.lock:
            row = self.db.execute('SELECT value FROM settings WHERE key=?', (key,)).fetchone()
            return json.loads(row[0]) if row else default

    def set(self, key, value):
        with self.lock, self.db:
            self.db.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, json.dumps(value)))

    def pair(self, name):
        token, ident = secrets.token_urlsafe(32), secrets.token_hex(12)
        now = time.time()
        with self.lock, self.db:
            self.db.execute('INSERT INTO devices VALUES (?,?,?,?,?)',
                            (ident, name, hashlib.sha256(token.encode()).hexdigest(), now, now))
        return ident, token

    def authenticate(self, token):
        digest = hashlib.sha256(token.encode()).hexdigest()
        with self.lock:
            row = self.db.execute('SELECT id,name FROM devices WHERE token_hash=?', (digest,)).fetchone()
            if row:
                with self.db:
                    self.db.execute('UPDATE devices SET seen=? WHERE id=?', (time.time(), row['id']))
                return dict(row)

    def devices(self):
        with self.lock:
            return [dict(r) for r in self.db.execute('SELECT id,name,created,seen FROM devices ORDER BY seen DESC')]

    def revoke(self, ident):
        with self.lock, self.db:
            self.db.execute('DELETE FROM devices WHERE id=?', (ident,))

    def cached(self, key):
        with self.lock:
            row = self.db.execute('SELECT translated FROM cache WHERE key=?', (key,)).fetchone()
            return row[0] if row else None

    def put_cache(self, key, translated):
        with self.lock, self.db:
            self.db.execute('INSERT OR REPLACE INTO cache VALUES (?,?,?)', (key, translated, time.time()))
            # The legacy cache keeps only hashes + translations. Original text
            # is stored exclusively by the separate opt-in Translation Memory.
            self.db.execute('DELETE FROM cache WHERE key IN (SELECT key FROM cache ORDER BY updated DESC LIMIT -1 OFFSET 20000)')

    def clear_cache(self):
        with self.lock:
            with self.db:
                self.db.execute('DELETE FROM cache')
            self.db.execute('PRAGMA wal_checkpoint(TRUNCATE)')

    def cache_size(self):
        with self.lock:
            return self.db.execute('SELECT count(*) FROM cache').fetchone()[0]

    def glossary(self, profile=None):
        with self.lock:
            if profile:
                rows = self.db.execute("SELECT * FROM glossary WHERE profile IN ('all',?) ORDER BY profile, id", (profile,))
            else:
                rows = self.db.execute('SELECT * FROM glossary ORDER BY id DESC')
            return [dict(r) for r in rows]

    def put_glossary(self, source, target, profile):
        with self.lock, self.db:
            if self.db.execute('SELECT count(*) FROM glossary').fetchone()[0] >= 500:
                existing = self.db.execute('SELECT id FROM glossary WHERE source=? AND profile=?', (source, profile)).fetchone()
                if not existing:
                    raise ValueError('Từ điển đã đạt 500 mục. Hãy xóa mục cũ trước.')
            self.db.execute('INSERT INTO glossary(source,target,profile) VALUES (?,?,?) ON CONFLICT(source,profile) DO UPDATE SET target=excluded.target', (source, target, profile))

    def delete_glossary(self, ident):
        with self.lock, self.db:
            self.db.execute('DELETE FROM glossary WHERE id=?', (ident,))

    def update_glossary(self, ident, source, target, profile):
        with self.lock, self.db:
            if not self.db.execute('SELECT id FROM glossary WHERE id=?', (ident,)).fetchone():
                raise KeyError(ident)
            try:
                self.db.execute('UPDATE glossary SET source=?,target=?,profile=? WHERE id=?', (source, target, profile, ident))
            except sqlite3.IntegrityError:
                raise ValueError('Đã có mục từ cùng nguyên văn và nội dung. Hãy sửa mục đó.') from None

    def import_glossary(self, entries):
        with self.lock, self.db:
            existing = {(r[0],r[1]) for r in self.db.execute('SELECT source,profile FROM glossary')}
            additions = {(normalize(e.source),e.profile) for e in entries} - existing
            if len(existing) + len(additions) > 500:
                raise ValueError('Tổng từ điển sau khi nhập vượt 500 mục. Chưa có dữ liệu nào được thay đổi.')
            self.db.executemany('INSERT INTO glossary(source,target,profile) VALUES (?,?,?) ON CONFLICT(source,profile) DO UPDATE SET target=excluded.target',
                                [(normalize(e.source),e.target.strip(),e.profile) for e in entries])

    @staticmethod
    def day():
        return datetime.now(timezone.utc).strftime('%Y-%m-%d')

    def usage(self):
        with self.lock:
            row = self.db.execute('SELECT * FROM usage WHERE day=?', (self.day(),)).fetchone()
            return dict(row) if row else dict(day=self.day(), requests=0, input_tokens=0, output_tokens=0)

    def reserve_call(self, limit, day=None):
        with self.lock, self.db:
            day = day or self.day()
            self.db.execute('INSERT OR IGNORE INTO usage(day) VALUES (?)', (day,))
            cur = self.db.execute('UPDATE usage SET requests=requests+1 WHERE day=? AND requests < ?', (day, limit))
            return cur.rowcount == 1

    def add_tokens(self, input_tokens, output_tokens, day=None):
        with self.lock, self.db:
            self.db.execute('UPDATE usage SET input_tokens=input_tokens+?, output_tokens=output_tokens+? WHERE day=?', (input_tokens, output_tokens, day or self.day()))

    def close(self):
        self.db.close()

