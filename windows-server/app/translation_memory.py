"""Opt-in exact Translation Memory, with bounded asynchronous SQLite writes.

New AI output is always draft. Only explicit verified/user-approved entries are
eligible for reuse, in the exact language/model/profile/glossary/context scope.
No fuzzy punctuation, negation, name, pronoun or whitespace-boundary matching.
The queue drops draft/hit updates under load, never silently drops an approval.
Call close() before Store.close() to drain accepted writes on clean shutdown.
An interrupted process can lose queued drafts; video checkpoints are separate.
"""

import asyncio
from dataclasses import dataclass, field
import hashlib
import json
import time

from .storage import normalize


QUALITY = {'draft': 0, 'verified': 1, 'user_approved': 2}


def memory_hash(namespace, text, context_scope='global'):
    return hashlib.sha256(json.dumps([namespace, context_scope, normalize(text)],
                                    ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


@dataclass(frozen=True)
class MemoryEntry:
    namespace: str
    original_text: str
    translation: str
    source_language: str
    profile: str
    mode: str
    provider: str
    model: str
    glossary_version: str
    context_scope: str = 'global'
    source_type: str = 'LIVE'
    project_id: str = ''
    quality: str = 'draft'
    context_information: dict = field(default_factory=dict)
    confidence_metadata: dict = field(default_factory=dict)

    def record(self):
        if self.quality not in QUALITY or self.source_type not in ('LIVE', 'VIDEO'):
            raise ValueError('Invalid translation memory quality/source')
        if self.source_language not in ('en', 'ja', 'zh', 'ko'):
            raise ValueError('Unsupported source language')
        original, translated = self.original_text.strip(), self.translation.strip()
        if not original or len(original) > 2000 or not translated or len(translated) > 12000:
            raise ValueError('Invalid translation memory text')
        if not self.context_scope or len(self.context_scope) > 160 or len(self.project_id) > 100:
            raise ValueError('Invalid translation memory scope')
        for metadata in (self.context_information, self.confidence_metadata):
            if not isinstance(metadata, dict) or len(json.dumps(metadata, ensure_ascii=False)) > 4096:
                raise ValueError('Invalid translation memory metadata')
        normalized = normalize(original)
        record = dict(self.__dict__, original_text=original, translation=translated,
                      normalized_text=normalized,
                      content_hash=memory_hash(self.namespace, normalized, self.context_scope))
        record['context_information'] = json.dumps(self.context_information, ensure_ascii=False)
        record['confidence_metadata'] = json.dumps(self.confidence_metadata, ensure_ascii=False)
        return record


class TranslationMemoryManager:
    def __init__(self, store, *, queue_limit=512):
        self.store = store
        self._queue = asyncio.Queue(maxsize=queue_limit)
        self._worker = None
        self._closed = False
        self._epoch = 0
        self._project_epochs = {}
        self.metrics = dict(accepted=0, written=0, dropped=0, failed=0, hits=0)

    def _start(self):
        if self._worker is None:
            self._worker = asyncio.create_task(self._run(), name='translation-memory-writer')

    def _enqueue(self, kind, payload):
        if self._closed:
            return False
        self._start()
        project = payload.get('project_id', '')
        try:
            self._queue.put_nowait((kind, payload, self._epoch, self._project_epochs.get(project, 0)))
        except asyncio.QueueFull:
            self.metrics['dropped'] += 1
            return False
        self.metrics['accepted'] += 1
        return True

    def enqueue_draft(self, entry):
        if entry.quality != 'draft':
            raise ValueError('AI/background writes must remain draft')
        return self._enqueue('put', entry.record())

    async def put_verified(self, entry):
        """Explicit validation/user action; callers must opt in before calling."""
        if entry.quality not in ('verified', 'user_approved'):
            raise ValueError('Expected verified or approved entry')
        if self._closed:
            raise RuntimeError('Translation memory is closed')
        await self.flush()
        await asyncio.to_thread(self._write, 'approve' if entry.quality == 'user_approved' else 'put', entry.record())

    @staticmethod
    def _public(row):
        result = dict(row)
        result.pop('namespace', None)
        for name in ('context_information', 'confidence_metadata'):
            result[name] = json.loads(result[name])
        return result

    def lookup(self, namespace, text, *, context_scope='global'):
        key = memory_hash(namespace, text, context_scope)
        with self.store.lock:
            row = self.store.db.execute("SELECT * FROM translation_memory WHERE content_hash=? "
                "AND quality IN ('verified','user_approved')", (key,)).fetchone()
        if row is None:
            return None
        self.metrics['hits'] += 1
        self._enqueue('hit', dict(content_hash=key, project_id=row['project_id']))
        return self._public(row)

    def list_entries(self, *, source=None, quality=None, project_id=None, search='', limit=100, offset=0):
        if quality is not None and quality not in QUALITY:
            raise ValueError('Invalid quality')
        clauses, values = [], []
        for key, value in [('source_language', source), ('quality', quality), ('project_id', project_id)]:
            if value is not None:
                clauses.append(key + '=?')
                values.append(value)
        if search:
            # User text is a literal substring, not a LIKE wildcard expression.
            clauses.append('(instr(original_text,?)>0 OR instr(translation,?)>0)')
            values.extend([search[:500], search[:500]])
        where = ' WHERE ' + ' AND '.join(clauses) if clauses else ''
        with self.store.lock:
            rows = self.store.db.execute('SELECT * FROM translation_memory' + where +
                ' ORDER BY updated_at DESC LIMIT ? OFFSET ?', (*values, max(1, min(500, limit)), max(0, offset))).fetchall()
        return [self._public(row) for row in rows]

    async def update_entry(self, content_hash, translation, *, quality='user_approved'):
        if quality not in ('verified', 'user_approved'):
            raise ValueError('Only explicit validation or approval is allowed')
        translated = translation.strip()
        if not translated or len(translated) > 12000:
            raise ValueError('Invalid translation')
        await self.flush()
        def update():
            with self.store.lock, self.store.db:
                row = self.store.db.execute('SELECT * FROM translation_memory WHERE content_hash=?', (content_hash,)).fetchone()
                if row is None:
                    raise KeyError(content_hash)
                # A validation job cannot downgrade a prior human approval.
                if row['quality'] == 'user_approved' and quality != 'user_approved':
                    raise ValueError('User-approved entry cannot be overwritten by automatic validation')
                self.store.db.execute('UPDATE translation_memory SET translation=?, quality=?, updated_at=? WHERE content_hash=?',
                                      (translated, quality, time.time(), content_hash))
                return self._public(self.store.db.execute('SELECT * FROM translation_memory WHERE content_hash=?', (content_hash,)).fetchone())
        return await asyncio.to_thread(update)

    async def delete_entry(self, content_hash):
        # Drain accepted writes before deletion so no already queued draft can
        # resurrect the entry after the user's delete action.
        await self.flush()
        def delete():
            with self.store.lock, self.store.db:
                self.store.db.execute('DELETE FROM translation_memory WHERE content_hash=?', (content_hash,))
        await asyncio.to_thread(delete)

    async def delete_project(self, project_id):
        if not project_id or len(project_id) > 100:
            raise ValueError('A specific project id is required')
        self._project_epochs[project_id] = self._project_epochs.get(project_id, 0) + 1
        await self.flush()
        def delete():
            with self.store.lock, self.store.db:
                cursor = self.store.db.execute('DELETE FROM translation_memory WHERE project_id=?', (project_id,))
                return cursor.rowcount
        return await asyncio.to_thread(delete)

    async def clear(self):
        self._epoch += 1
        await self.flush()
        def clear():
            with self.store.lock, self.store.db:
                self.store.db.execute('DELETE FROM translation_memory')
        await asyncio.to_thread(clear)

    def stats(self):
        with self.store.lock:
            rows = self.store.db.execute('SELECT quality,count(*) AS count FROM translation_memory GROUP BY quality').fetchall()
        counts = {quality: 0 for quality in QUALITY}
        counts.update({row['quality']: row['count'] for row in rows})
        return dict(total=sum(counts.values()), quality=counts, queued=self._queue.qsize(), **self.metrics)

    def _write(self, kind, payload):
        with self.store.lock, self.store.db:
            if kind == 'hit':
                self.store.db.execute('UPDATE translation_memory SET hit_count=hit_count+1 WHERE content_hash=?', (payload['content_hash'],))
                return
            old = self.store.db.execute('SELECT quality FROM translation_memory WHERE content_hash=?', (payload['content_hash'],)).fetchone()
            if old is not None and kind == 'approve':
                # This path is reachable only through the explicit user-action
                # API. Preserve original provenance while accepting a later
                # human correction of an already approved translation.
                self.store.db.execute('UPDATE translation_memory SET translation=?,quality=?,updated_at=? WHERE content_hash=?',
                    (payload['translation'], 'user_approved', time.time(), payload['content_hash']))
                return
            # Preserve the first source/project provenance at an equal quality.
            # A later AI draft from live capture must not take ownership of a
            # video's stored entry, or undo a previous draft edit silently.
            if old is not None and QUALITY[old['quality']] >= QUALITY[payload['quality']]:
                return
            now = time.time()
            data = dict(payload, created_at=now, updated_at=now, hit_count=0)
            columns = tuple(data)
            update = ','.join(f'{name}=excluded.{name}' for name in columns if name not in ('content_hash', 'created_at', 'hit_count'))
            self.store.db.execute('INSERT INTO translation_memory (' + ','.join(columns) + ') VALUES (' +
                ','.join('?' for _ in columns) + ') ON CONFLICT(content_hash) DO UPDATE SET ' + update, tuple(data.values()))

    async def _run(self):
        while True:
            item = await self._queue.get()
            try:
                if item is None:
                    return
                kind, payload, epoch, project_epoch = item
                if epoch != self._epoch or project_epoch != self._project_epochs.get(payload.get('project_id', ''), 0):
                    continue
                await asyncio.to_thread(self._write, kind, payload)
                self.metrics['written'] += 1
            except Exception:
                # Status contains counters only: no raw source/translation or
                # SQLite error text is sent to logs or the dashboard.
                self.metrics['failed'] += 1
            finally:
                self._queue.task_done()

    async def flush(self):
        await self._queue.join()

    async def close(self):
        if self._closed:
            return
        self._closed = True
        await self.flush()
        if self._worker is not None:
            await self._queue.put(None)
            await self._worker
