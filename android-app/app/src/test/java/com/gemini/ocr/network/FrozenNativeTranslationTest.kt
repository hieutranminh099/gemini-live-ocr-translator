package com.gemini.ocr.network

import com.gemini.ocr.BuildConfig
import com.gemini.ocr.PerformanceTranslationSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrozenNativeTranslationTest {
    @Test fun isolatedNativeProviderReceivesFrozenContextAndCannotShareGeminisCacheNamespace() = runBlocking {
        val source = "你先走吧。"
        val frozen = listOf("老师，这条路我很熟悉。" to "Thưa thầy, em rất quen đường này.")
        StandaloneTranslator.clearDirectCache()
        val cloudNamespace = StandaloneTranslator.cacheNamespace("manga", "", frozen)
        StandaloneTranslator.overrideMt60TranslatorForVerification = { _, supplied, _ ->
            assertEquals(frozen, supplied)
            assertNotEquals(StandaloneTranslator.getRecentContextSnapshot(), supplied)
            listOf("Thầy đi trước đi.")
        }
        try {
            StandaloneTranslator.recordContextPair("朋友", "Bạn cũ")
            val result = runCatching { StandaloneTranslator.translateStandaloneMode(null, listOf(source),
                StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true,
                contextItems = frozen, allowContextFreeBank = false) }
            if (BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench") {
                val native = result.getOrThrow()
                assertEquals(PerformanceTranslationSource.ON_DEVICE, native.telemetrySource)
                assertEquals(StandaloneTranslator.VERIFICATION_MT60_MODEL, native.modelUsed)
                assertEquals(listOf("Thầy đi trước đi."), native.translations)
                assertNotEquals(cloudNamespace, StandaloneTranslator.cacheNamespace("manga", "", frozen))
                assertFalse(native.engineLabel.contains("Gemini"))
                assertNull(StandaloneTranslator.lookupDirectLru(source, cloudNamespace))
                assertEquals(listOf("朋友" to "Bạn cũ"), StandaloneTranslator.getRecentContextSnapshot())
            } else {
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(cloudNamespace, StandaloneTranslator.cacheNamespace("manga", "", frozen))
            }
        } finally {
            StandaloneTranslator.overrideMt60TranslatorForVerification = null
            StandaloneTranslator.verificationMt60ContextMode = StandaloneTranslator.VerificationMt60ContextMode.RAW_FORCED_PREFIX
            StandaloneTranslator.clearDirectCache()
        }
        Unit
    }

    @Test fun researchContextProtocolsHaveDifferentModelAndCacheIdentities() = runBlocking {
        StandaloneTranslator.overrideMt60TranslatorForVerification = { _, _, _ -> listOf("Đi trước đi.") }
        try {
            val raw = StandaloneTranslator.cacheNamespace("manga", "", emptyList())
            StandaloneTranslator.verificationMt60ContextMode = StandaloneTranslator.VerificationMt60ContextMode.SOURCE_ONLY
            val isolated = BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench"
            if (isolated) {
                assertNotEquals(raw, StandaloneTranslator.cacheNamespace("manga", "", emptyList()))
                val result = StandaloneTranslator.translateStandaloneMode(null, listOf("先走吧。"),
                    StandaloneTranslator.MODE_GEMINI_DIRECT, "unused", deferSideEffects = true,
                    contextItems = emptyList(), allowContextFreeBank = false)
                assertEquals("${StandaloneTranslator.VERIFICATION_MT60_MODEL}:source-only-v1", result.modelUsed)
            } else assertEquals(raw, StandaloneTranslator.cacheNamespace("manga", "", emptyList()))
        } finally {
            StandaloneTranslator.overrideMt60TranslatorForVerification = null
            StandaloneTranslator.verificationMt60ContextMode = StandaloneTranslator.VerificationMt60ContextMode.RAW_FORCED_PREFIX
            StandaloneTranslator.clearDirectCache()
        }
        Unit
    }
}
