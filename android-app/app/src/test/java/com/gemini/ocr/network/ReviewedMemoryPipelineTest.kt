package com.gemini.ocr.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewedMemoryPipelineTest {
    private lateinit var memory: ContextualTranslationMemory
    private val source = "你到底想干什么？"
    private val vi = "Rốt cuộc anh muốn làm gì?"
    private val scope = ContextualTranslationMemory.Scope("fictional-a", "modern", StandaloneTranslator.FAST_MODEL,
        "glossary-v1", ContextualTranslationMemory.Scope.emptyContextFingerprint())
    private val provenance = ContextualTranslationMemory.Provenance("user://test-fixture", "owner-provided-test")
    private fun entry(text: String = source, translated: String = vi) =
        ContextualTranslationMemory.Entry(scope, text, translated, "modern", provenance, 1.0)

    @Before fun setup() {
        memory = ContextualTranslationMemory(ApplicationProvider.getApplicationContext<Context>(), databaseName = null)
        StandaloneTranslator.clearDirectCache()
        PhoneTranslationCache.clear(null)
        StandaloneTranslator.overrideGeminiTranslator = null
    }
    @After fun cleanup() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = null
        StandaloneTranslator.clearDirectCache()
        PhoneTranslationCache.clear(null)
        memory.close()
    }
    private suspend fun translate(text: String = source, activeScope: ContextualTranslationMemory.Scope = scope,
                                  correction: Boolean = true) = StandaloneTranslator.translateStandaloneMode(
        null, listOf(text), StandaloneTranslator.MODE_GEMINI_DIRECT, "unused-fake-key",
        deferSideEffects = true, cacheNamespace = "test-frozen-context", contextItems = emptyList(),
        reviewedMemory = memory, memoryScope = activeScope, applyReviewedCorrections = correction)

    @Test fun approvedExactMemoryWinsOverAnOldMachineLruWithoutCallingCloud() = runBlocking {
        memory.saveReviewed(entry())
        StandaloneTranslator.putDirectLru(source, "Old machine answer", "test-frozen-context")
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> error("Reviewed memory must avoid network") }
        val result = translate()
        assertEquals(listOf(vi), result.translations)
        assertTrue(result.fromBank)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertEquals("Old machine answer", StandaloneTranslator.lookupDirectLru(source, "test-frozen-context"))
    }
    @Test fun aiDraftAndAnotherFilmCannotBypassTheProvider() = runBlocking {
        memory.recordDraft(entry())
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> calls++; listOf("Fresh output") }
        assertEquals(listOf("Fresh output"), translate().translations)
        memory.saveReviewed(entry())
        assertEquals(listOf("Fresh output"), translate(activeScope = scope.copy(filmId = "fictional-b")).translations)
        assertEquals(2, calls)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
    }
    @Test fun correctionNeedsTwoReviewsAndNeverReplacesASubstring() = runBlocking {
        val raw = "你到底想于什么？"
        memory.saveReviewed(entry())
        memory.confirmCorrection(scope, raw, source, 1.0, provenance)
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> calls++; listOf("Unreviewed source") }
        assertEquals(listOf("Unreviewed source"), translate(raw).translations)
        memory.confirmCorrection(scope, raw, source, 1.0, provenance)
        val result = translate(raw)
        assertEquals(listOf(vi), result.translations)
        assertEquals(listOf(source), result.correctedSources)
        assertEquals(listOf("Unreviewed source"), translate("他说：$raw").translations)
        assertEquals(2, calls)
    }
    @Test fun disablingCorrectionPreservesOriginalTextSentToTheProvider() = runBlocking {
        val raw = "你到底想于什么？"
        repeat(2) { memory.confirmCorrection(scope, raw, source, 1.0, provenance) }
        memory.saveReviewed(entry())
        StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
            assertEquals(listOf(raw), texts); listOf("Raw output")
        }
        assertEquals(listOf("Raw output"), translate(raw, correction = false).translations)
    }
    @Test fun reviewedCorrectionOnAMissReachesTheProviderOnceWithDeferredSideEffects() = runBlocking {
        val raw = "你到底想于什么？"
        repeat(2) { memory.confirmCorrection(scope, raw, source, 1.0, provenance) }
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
            assertEquals(listOf(source), texts); calls++; listOf(vi)
        }
        val result = translate(raw)
        assertEquals(1, calls)
        assertEquals(listOf(source), result.correctedSources)
        assertNull(StandaloneTranslator.lookupDirectLru(raw, "test-frozen-context"))
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        StandaloneTranslator.commitAcceptedOutcome(listOf(raw), result, "test-frozen-context")
        assertEquals(vi, StandaloneTranslator.lookupDirectLru(raw, "test-frozen-context"))
        assertEquals(source, StandaloneTranslator.getRecentContextSnapshot().single().first)
    }
    @Test fun filmAndDomainArePartOfMachineCacheIdentity() {
        val base = StandaloneTranslator.cacheNamespace("manga", "", emptyList(), "film-a", "modern")
        assertNotEquals(base, StandaloneTranslator.cacheNamespace("manga", "", emptyList(), "film-b", "modern"))
        assertNotEquals(base, StandaloneTranslator.cacheNamespace("manga", "", emptyList(), "film-a", "historical"))
    }

    @Test fun partialReviewedHitKeepsItsAnswerAndSendsOnlyTheOtherLine() = runBlocking {
        val other = "请把窗户打开。"
        val raw = "你到底想于什么？"
        memory.saveReviewed(entry())
        repeat(2) { memory.confirmCorrection(scope, raw, source, 1.0, provenance) }
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
            assertEquals(listOf(other), texts); calls++; listOf("Hãy mở cửa sổ ra.")
        }
        val result = StandaloneTranslator.translateStandaloneMode(null, listOf(raw, other, source),
            StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true,
            reviewedMemory = memory, memoryScope = scope, contextItems = emptyList())
        assertEquals(1, calls)
        assertEquals(listOf(vi, "Hãy mở cửa sổ ra.", vi), result.translations)
        assertEquals(listOf(source, other, source), result.correctedSources)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertEquals(0, StandaloneTranslator.directLruSize())
    }

    @Test fun selectingReviewedMemoryCannotReuseAnUnreviewedLegacyRowOnTheFirstCue() = runBlocking {
        SelfLearningDatabase.learnOrUpsert(null, source, "Unreviewed legacy answer")
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> calls++; listOf(vi) }
        val result = StandaloneTranslator.translateStandaloneMode(null, listOf(source),
            StandaloneTranslator.MODE_HYBRID_BANK_GEMINI, "unused-fake-key", deferSideEffects = true,
            contextItems = emptyList(), allowContextFreeBank = true, reviewedMemory = memory, memoryScope = scope)
        assertEquals(listOf(vi), result.translations)
        assertEquals(1, calls)
        assertFalse(result.fromBank)
        assertNull(memory.lookup(scope, source))
        assertEquals("Unreviewed legacy answer", SelfLearningDatabase.lookup(null, source, incrementFrequency = false)?.translatedText)
    }

    @Test fun legacyOcrSubstitutionsCannotRewriteAReviewedScopeOrAnExplicitlyDisabledCorrection() = runBlocking {
        val raw = "帅兄，你来了。"
        var calls = 0
        StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
            assertEquals(listOf(raw), texts); calls++; listOf("Anh đến rồi.")
        }
        translate(raw)
        StandaloneTranslator.translateStandaloneMode(null, listOf(raw), StandaloneTranslator.MODE_GEMINI_DIRECT,
            "unused", deferSideEffects = true, applyLegacyOcrCorrection = false)
        assertEquals(2, calls)
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
    }
}
