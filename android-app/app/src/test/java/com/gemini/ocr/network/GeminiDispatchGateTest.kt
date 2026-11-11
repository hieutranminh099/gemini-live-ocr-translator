package com.gemini.ocr.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiDispatchGateTest {
    @Test fun deniedBudgetFailsBeforeStartingAnyHttpOperation() = runBlocking {
        class Denied : IOException("synthetic budget denied")
        var checked = 0
        var httpEvents = 0
        StandaloneTranslator.overrideGeminiTranslator = null
        StandaloneTranslator.beforeNetworkCallForTest = { checked++; throw Denied() }
        try {
            try {
                StandaloneTranslator.translateWithGemini(apiKey = "synthetic-not-a-key",
                    texts = listOf("你好"), context = null, saveToSelfLearningBank = false,
                    deferSideEffects = true, contextItems = emptyList(), httpObserver = { httpEvents++ })
                fail("The denied budget must prevent dispatch")
            } catch (_: Denied) { /* Expected; no network call was started. */ }
            assertEquals(1, checked)
            assertEquals(0, httpEvents)
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            StandaloneTranslator.overrideGeminiTranslator = null
        }
    }
}
