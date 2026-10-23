import asyncio
from dataclasses import replace

import pytest

from app.manager import TranslationManager
from app.models import TranslateRequest
from app.provider import TranslationError
from app.storage import Store
from app.translation_memory import MemoryEntry, TranslationMemoryManager


def entry(**changes):
    defaults = dict(namespace='v2|api|zh|game|fast|flash|glossary|', original_text='我不会走。',
        translation='Tôi sẽ không đi.', source_language='zh', profile='game', mode='fast',
        provider='api', model='flash', glossary_version='glossary')
    return MemoryEntry(**(defaults | changes))


@pytest.fixture
def store(tmp_path):
    result = Store(tmp_path)
    yield result
    result.close()


async def test_draft_not_reused_then_approval_reused_without_ai_overwrite(store):
    memory = TranslationMemoryManager(store)
    draft = entry()
    assert memory.enqueue_draft(draft)
    await memory.flush()
    assert memory.lookup(draft.namespace, draft.original_text) is None
    row = memory.list_entries()[0]
    approved = await memory.update_entry(row['content_hash'], 'Tôi không đi đâu.')
    assert approved['quality'] == 'user_approved'
    assert memory.enqueue_draft(replace(draft, translation='Tôi sẽ đi.'))
    await memory.put_verified(replace(draft, translation='Tôi sẽ đi.', quality='verified'))
    reused = memory.lookup(draft.namespace, draft.original_text)
    assert reused['translation'] == 'Tôi không đi đâu.'
    with pytest.raises(ValueError):
        await memory.update_entry(row['content_hash'], 'AI sửa', quality='verified')
    await memory.flush()
    assert memory.list_entries()[0]['hit_count'] == 1
    await memory.close()


async def test_exact_language_glossary_context_and_negation_boundaries(store):
    memory = TranslationMemoryManager(store)
    approved = entry(quality='verified', context_scope='project:one', project_id='one')
    await memory.put_verified(approved)
    assert memory.lookup(approved.namespace, '我不会走。', context_scope='project:one')
    for text in ('我会走。', '我不会走', '他不会走。'):
        assert memory.lookup(approved.namespace, text, context_scope='project:one') is None
    assert memory.lookup(approved.namespace, approved.original_text) is None
    assert memory.lookup(approved.namespace, approved.original_text, context_scope='project:two') is None
    assert memory.lookup(approved.namespace.replace('|zh|', '|ja|'), approved.original_text, context_scope='project:one') is None
    assert memory.lookup(approved.namespace + 'new-glossary', approved.original_text, context_scope='project:one') is None
    await memory.close()


async def test_nfkc_and_whitespace_only_normalization_preserves_word_boundaries(store):
    memory = TranslationMemoryManager(store)
    approved = entry(original_text=' Ａ  cat\n sleeps ', source_language='en', quality='user_approved')
    await memory.put_verified(approved)
    assert memory.lookup(approved.namespace, 'A cat sleeps')['translation']
    assert memory.lookup(approved.namespace, 'Acatsleeps') is None
    await memory.close()


async def test_queue_bounded_drop_draft_and_clean_shutdown_flush(store):
    memory = TranslationMemoryManager(store, queue_limit=1)
    assert memory.enqueue_draft(entry())
    assert not memory.enqueue_draft(entry(original_text='新句子'))
    assert memory.metrics['dropped'] == 1
    await memory.close()
    assert memory.stats()['total'] == 1
    assert not memory.enqueue_draft(entry(original_text='关闭'))


async def test_delete_project_drops_queued_writes_and_keeps_other_project(store):
    memory = TranslationMemoryManager(store)
    await memory.put_verified(entry(project_id='other', quality='verified', original_text='其他'))
    memory.enqueue_draft(entry(project_id='private'))
    await memory.delete_project('private')
    assert not memory.list_entries(project_id='private')
    assert len(memory.list_entries(project_id='other')) == 1
    with pytest.raises(ValueError):
        await memory.delete_project('')
    await memory.close()


async def test_clear_and_delete_entry_do_not_resurrect_accepted_queue(store):
    memory = TranslationMemoryManager(store)
    draft = entry()
    memory.enqueue_draft(draft)
    await memory.flush()
    key = memory.list_entries()[0]['content_hash']
    memory.enqueue_draft(draft)
    await memory.delete_entry(key)
    assert memory.stats()['total'] == 0
    memory.enqueue_draft(draft)
    await memory.clear()
    assert memory.stats()['total'] == 0
    await memory.close()


async def test_search_literal_wildcards_and_filters(store):
    memory = TranslationMemoryManager(store)
    await memory.put_verified(entry(original_text='100%_safe', quality='verified'))
    await memory.put_verified(entry(original_text='普通文本', quality='user_approved', project_id='p'))
    assert len(memory.list_entries(search='%_')) == 1
    assert len(memory.list_entries(project_id='p', quality='user_approved', source='zh')) == 1
    assert not memory.list_entries(source='ja')
    await memory.close()


async def test_explicit_reapproval_can_edit_but_ai_cannot_take_project_ownership(store):
    memory = TranslationMemoryManager(store)
    await memory.put_verified(entry(quality='user_approved', project_id='first'))
    await memory.put_verified(entry(quality='user_approved', project_id='second', translation='Bản sửa do người dùng.'))
    row = memory.list_entries()[0]
    assert row['translation'] == 'Bản sửa do người dùng.' and row['project_id'] == 'first'
    memory.enqueue_draft(entry(translation='AI ghi đè.', project_id='second'))
    await memory.flush()
    assert memory.list_entries()[0]['translation'] == 'Bản sửa do người dùng.'
    await memory.close()


@pytest.mark.parametrize('changes', [dict(quality='excellent'), dict(source_type='PRIVATE'),
    dict(source_language='vi'), dict(original_text=' '), dict(translation=''),
    dict(context_scope=''), dict(context_information={'huge': 'x' * 5000})])
def test_invalid_record_rejected(changes):
    with pytest.raises(ValueError):
        entry(**changes).record()


class Api:
    configured = True

    def __init__(self):
        self.calls = []
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.block = False
        self.error = None

    async def translate(self, texts, **kwargs):
        self.calls.append((texts, kwargs))
        self.started.set()
        if self.block:
            await self.release.wait()
        if self.error:
            raise self.error
        return ['VI:' + text for text in texts], 7, 5


def request(text='你好', ident='r'):
    return TranslateRequest.model_validate(dict(request_id=ident, source='zh', mode='fast',
        blocks=[dict(id='b', text=text, box=dict(x=0, y=0, width=1, height=1))]))


async def test_manager_memory_default_off_keeps_originals_out_of_store(store):
    api = Api()
    manager = TranslationManager(store, api)
    await manager.translate(request(), 'live')
    await manager.memory.flush()
    assert manager.memory.stats()['total'] == 0
    assert store.cache_size() == 1
    await manager.close()


async def test_manager_optin_draft_then_approved_reused_over_old_hash_cache(store):
    store.set('settings', dict(memory_enabled=True))
    api = Api()
    manager = TranslationManager(store, api)
    await manager.translate(request(), 'live')
    await manager.memory.flush()
    row = manager.memory.list_entries()[0]
    assert row['original_text'] == '你好' and row['quality'] == 'draft'
    await manager.memory.update_entry(row['content_hash'], 'Xin chào bạn.')
    result = await manager.translate(request(), 'live')
    assert result['blocks'][0]['text'] == 'Xin chào bạn.'
    assert result['blocks'][0]['cached'] and len(api.calls) == 1
    await manager.close()


async def test_manager_private_video_no_hash_cache_memory_context_or_session(store):
    store.set('settings', dict(memory_enabled=True, context_enabled=True))
    api = Api()
    manager = TranslationManager(store, api)
    await manager.translate(request(), 'video', priority='video', source_type='VIDEO',
        project_id='private', context_scope='project:private', allow_cache=False,
        persist_memory=False, allow_context=False)
    await manager.memory.flush()
    assert store.cache_size() == 0 and manager.memory.stats()['total'] == 0
    assert not manager.contexts
    assert api.calls[0][1]['context'] == [] and api.calls[0][1]['session_key'] is None
    await manager.close()


@pytest.mark.parametrize(('global_enabled', 'persist_memory', 'expected_rows'), [
    (True, True, 1), (True, False, 0), (False, True, 0),
])
async def test_video_memory_save_does_not_require_lookup_optin(store, global_enabled, persist_memory, expected_rows):
    store.set('settings', dict(memory_enabled=global_enabled))
    api = Api()
    manager = TranslationManager(store, api)
    try:
        result = await manager.translate(request(), 'video', priority='video', source_type='VIDEO',
            project_id='movie', context_scope='global', use_memory=False,
            persist_memory=persist_memory, allow_cache=False, allow_context=False)
        await manager.memory.flush()
        rows = manager.memory.list_entries()
        assert len(rows) == expected_rows and len(api.calls) == 1
        assert result['blocks'][0]['cached'] is False
        assert store.cache_size() == 0
        if rows:
            assert rows[0]['quality'] == 'draft'
            assert rows[0]['source_type'] == 'VIDEO' and rows[0]['project_id'] == 'movie'
    finally:
        await manager.close()


async def test_manager_optin_video_draft_not_global_cache_or_live_reuse(store):
    store.set('settings', dict(memory_enabled=True))
    api = Api()
    manager = TranslationManager(store, api)
    await manager.translate(request(), 'video', priority='video', source_type='VIDEO',
        project_id='movie', allow_cache=False, allow_context=False)
    await manager.memory.flush()
    assert store.cache_size() == 0
    row = manager.memory.list_entries()[0]
    assert row['source_type'] == 'VIDEO' and row['project_id'] == 'movie' and row['quality'] == 'draft'
    await manager.translate(request(), 'live')
    assert len(api.calls) == 2  # Draft cannot bypass the quality gate.
    await manager.close()


async def test_singleflight_duplicate_inflight_has_one_call_and_one_quota_reservation(store):
    api = Api()
    api.block = True
    manager = TranslationManager(store, api)
    one = asyncio.create_task(manager.translate(request(ident='one'), 'same-session'))
    await api.started.wait()
    two = asyncio.create_task(manager.translate(request(ident='two'), 'same-session'))
    await asyncio.sleep(.01)
    assert len(api.calls) == 1 and store.usage()['requests'] == 1
    api.release.set()
    first, second = await asyncio.gather(one, two)
    assert first['request_id'] == 'one' and second['request_id'] == 'two'
    assert first['blocks'][0]['text'] == second['blocks'][0]['text']
    assert store.usage()['input_tokens'] == 7 and not manager._flights
    await manager.close()


async def test_singleflight_failure_propagates_and_retry_not_poisoned(store):
    api = Api()
    api.block = True
    api.error = TranslationError('provider_unavailable', 'offline')
    manager = TranslationManager(store, api)
    one = asyncio.create_task(manager.translate(request(), 'same-session'))
    await api.started.wait()
    two = asyncio.create_task(manager.translate(request(ident='two'), 'same-session'))
    await asyncio.sleep(.01)
    api.release.set()
    results = await asyncio.gather(one, two, return_exceptions=True)
    assert all(isinstance(result, TranslationError) for result in results)
    assert len(api.calls) == 1 and not manager._flights
    api.error = None
    assert (await manager.translate(request(), 'same-session'))['blocks'][0]['text']
    assert len(api.calls) == 2
    await manager.close()


async def test_cancelled_singleflight_leader_does_not_abort_current_follower(store):
    api = Api()
    api.block = True
    manager = TranslationManager(store, api)
    one = asyncio.create_task(manager.translate(request(), 'same-session'))
    await api.started.wait()
    two = asyncio.create_task(manager.translate(request(ident='two'), 'same-session'))
    await asyncio.sleep(.01)
    one.cancel()
    with pytest.raises(asyncio.CancelledError):
        await one
    api.release.set()
    assert (await asyncio.wait_for(two, 1))['request_id'] == 'two'
    assert len(api.calls) == 2 and not manager._flights
    await manager.close()
