package com.gemini.ocr.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All cloud output is supplied by a fake; no credentials or user database is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeferredTranslationTest {
    private val source = "下周三下午四点在东门的蓝色招牌下面等我"

    @Before fun setup() {
        PhoneTranslationCache.clear(null)
        StandaloneTranslator.overrideOnDeviceTranslator = null
        StandaloneTranslator.overrideGeminiTranslator = null
    }

    @After fun cleanup() {
        StandaloneTranslator.overrideGeminiTranslator = null
        StandaloneTranslator.overrideOnDeviceTranslator = null
        PhoneTranslationCache.clear(null)
    }

    @Test fun deferredResultDoesNotChangeContextOrCachesUntilItsOwnerAcceptsIt() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf("Đợi tôi dưới biển hiệu xanh ở cổng đông.") }
        val scope = StandaloneTranslator.cacheNamespace("manga", "", emptyList())
        val result = StandaloneTranslator.translateStandaloneMode(null, listOf(source),
            StandaloneTranslator.MODE_HYBRID_BANK_GEMINI, "unused-fake-key", deferSideEffects = true,
            cacheNamespace = scope, contextItems = emptyList(), allowContextFreeBank = false)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertNull(StandaloneTranslator.lookupDirectLru(source, scope))
        assertNull(SelfLearningDatabase.lookup(null, source, incrementFrequency = false))
        StandaloneTranslator.commitAcceptedOutcome(listOf(source), result, scope)
        assertEquals(source, StandaloneTranslator.getRecentContextSnapshot().single().first)
        assertEquals(result.translations.single(), StandaloneTranslator.lookupDirectLru(source, scope))
        assertNull(StandaloneTranslator.lookupDirectLru(source))
        StandaloneTranslator.persistAcceptedOutcome(null, listOf(source), result,
            StandaloneTranslator.MODE_HYBRID_BANK_GEMINI, allowContextFreeBank = false)
        assertNull(SelfLearningDatabase.lookup(null, source, incrementFrequency = false))
    }

    @Test fun rejectedOrCancelledWorkCannotContaminateTheNextRequestsContext() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> awaitCancellation() }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            StandaloneTranslator.translateStandaloneMode(null, listOf(source),
                StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true)
        }
        result.cancelAndJoin()
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertNull(StandaloneTranslator.lookupDirectLru(source))
        assertNull(SelfLearningDatabase.lookup(null, source, incrementFrequency = false))
    }

    @Test fun sameSourceInDifferentContextOrGlossaryUsesDifferentDirectCacheEntries() = runBlocking {
        val text = "你先走吧"
        val contextA = listOf("前面很危险" to "Phía trước nguy hiểm lắm.")
        val contextB = listOf("我不想再见到你" to "Tôi không muốn gặp anh nữa.")
        val scopeA = StandaloneTranslator.cacheNamespace("manga", "", contextA)
        val scopeB = StandaloneTranslator.cacheNamespace("manga", "", contextB)
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ ->
            calls++; listOf(if (calls == 1) "Anh đi trước đi." else "Anh cứ đi đi.")
        }
        suspend fun translate(scope: String, history: List<Pair<String, String>>) =
            StandaloneTranslator.translateStandaloneMode(null, listOf(text),
                StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true,
                cacheNamespace = scope, contextItems = history)
        val first = translate(scopeA, contextA)
        StandaloneTranslator.commitAcceptedOutcome(listOf(text), first, scopeA)
        val second = translate(scopeB, contextB)
        StandaloneTranslator.commitAcceptedOutcome(listOf(text), second, scopeB)
        assertEquals(2, calls)
        assertNotEquals(first.translations, second.translations)
        assertEquals(first.translations, translate(scopeA, contextA).translations)
        assertEquals(2, calls)
        assertNotEquals(scopeA, StandaloneTranslator.cacheNamespace("manga", "你=em", contextA))
        assertNotEquals(scopeA, StandaloneTranslator.cacheNamespace("technical", "", contextA))
    }

    @Test fun contextualHybridCannotBypassGeminiWithAnUnscopedLearnedDialogue() = runBlocking {
        SelfLearningDatabase.learnOrUpsert(null, source, "Bản cũ sai ngữ cảnh")
        var called = false
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> called = true; listOf("Bản mới đúng ngữ cảnh") }
        val result = StandaloneTranslator.translateStandaloneMode(null, listOf(source),
            StandaloneTranslator.MODE_HYBRID_BANK_GEMINI, "unused", deferSideEffects = true,
            contextItems = listOf("请不要迟到" to "Đừng đến muộn."), allowContextFreeBank = false)
        assertTrue(called)
        assertEquals(listOf("Bản mới đúng ngữ cảnh"), result.translations)
        assertFalse(result.fromBank)
    }

    @Test fun explicitGlossaryRemainsAValidContextualShortcut() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> fail("Exact glossary must avoid a cloud request"); emptyList() }
        val result = StandaloneTranslator.translateStandaloneMode(null, listOf("师父"),
            StandaloneTranslator.MODE_HYBRID_BANK_GEMINI, "unused", glossaryRaw = "师父=Sư phụ",
            deferSideEffects = true, allowContextFreeBank = false)
        assertEquals(listOf("Sư phụ"), result.translations)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
    }

    @Test fun namespaceSeparatesGlossaryAndHistoryAndCanonicalizesGlossaryOrder() {
        assertNotEquals(StandaloneTranslator.cacheNamespace("manga", "甲=乙", emptyList()),
            StandaloneTranslator.cacheNamespace("manga", "", listOf("甲" to "乙")))
        assertEquals(StandaloneTranslator.cacheNamespace("manga", "甲=乙\n丙=丁", emptyList()),
            StandaloneTranslator.cacheNamespace("manga", "丙=丁\n甲=乙", emptyList()))
        assertNotEquals(StandaloneTranslator.cacheNamespace("manga", "", listOf("ab" to "c")),
            StandaloneTranslator.cacheNamespace("manga", "", listOf("a" to "bc")))
    }

    @Test fun malformedFakeBatchIsRejectedBeforeAnySideEffects() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> emptyList() }
        try {
            StandaloneTranslator.translateStandaloneMode(null, listOf(source),
                StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true)
            fail("Wrong output count was accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertEquals(0, StandaloneTranslator.directLruSize())
    }
}
