package com.gemini.ocr.capture

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiveRequestPolicyTest {
    @Test fun stalledRequestHasWholeDeadlineAndNextRequestCanComplete():Unit=runBlocking {
        assertThrows(LiveRequestPolicy.Deadline::class.java){runBlocking{LiveRequestPolicy.request(30){awaitCancellation()}}}
        assertEquals("next",LiveRequestPolicy.request(500){"next"})
    }
    @Test fun parentCancellationDoesNotBecomeRetryableProviderFailure():Unit=runBlocking {
        val job=launch {LiveRequestPolicy.request(5000){awaitCancellation()}}
        yield();job.cancelAndJoin();assertTrue(job.isCancelled)
    }
    @Test fun quotaAndConfigurationUseExistingScopedCooldown() {
        val retries=TranslationRetryState()
        val quota=LiveRequestPolicy.failure(IOException("Đạt giới hạn lượt gọi Gemini API (429)."))
        retries.failed("old",quota.code,"quota",1000,quota.delayMs)
        assertNotNull(retries.waiting("next",61_999));assertNull(retries.waiting("next",62_001))
        val auth=LiveRequestPolicy.failure(IOException("Khóa không hợp lệ (403)."))
        retries.failed("old",auth.code,"auth",63_000,auth.delayMs)
        assertNotNull(retries.waiting("old",9_000_000));assertNull(retries.waiting("next",9_000_000))
        assertEquals("request_timeout",LiveRequestPolicy.failure(java.net.SocketTimeoutException("timeout")).code)
    }
}
