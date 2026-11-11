package com.gemini.ocr.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Admission/cooldown waits are synthetic; no credentials or provider calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoRequestDeadlineTest {
    @Test fun stagedAdmissionCanOutlastTheOldWallDeadlineAndStillReturnItsResult() = runBlocking {
        for (fullDialogue in listOf(false, true)) {
            var dispatched = false
            val result = withTimeout(5_000) {
                VideoRequestDeadline.run(fullDialogue, staged = true, economy = false, timeoutMs = 1) {
                    // Represents quota/admission or a Live reservation before a physical POST.
                    delay(30)
                    dispatched = true
                    "accepted-result"
                }
            }
            assertTrue(dispatched)
            assertEquals("accepted-result", result)
        }
    }

    @Test fun cancellingAStagedAdmissionWaitDrainsCleanupWithoutDispatching() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val admission = CompletableDeferred<Unit>()
        var cleanedUp = false
        var dispatched = false
        val request = async {
            VideoRequestDeadline.run(fullDialogue = true, staged = true, economy = false, timeoutMs = 1) {
                try {
                    entered.complete(Unit)
                    admission.await()
                    dispatched = true
                } finally { cleanedUp = true }
            }
        }
        withTimeout(5_000) { entered.await() }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(cleanedUp)
        assertFalse(dispatched)
    }

    @Test fun singleVideoAndFullDialogueEconomyKeepTheirBoundedWallDeadline() = runBlocking {
        for ((fullDialogue, staged, economy) in listOf(
            Triple(false, false, false), Triple(true, false, false), Triple(true, true, true)
        )) {
            var entered = false
            var cancelled = false
            try {
                VideoRequestDeadline.run(fullDialogue, staged, economy, timeoutMs = 20) {
                    entered = true
                    try { awaitCancellation() } finally { cancelled = true }
                }
                fail("The legacy request deadline must expire")
            } catch (_: TimeoutCancellationException) { }
            assertTrue(entered)
            assertTrue(cancelled)
        }
    }

    @Test fun stagedSmallGroupEconomyKeepsItsExistingCancellableWaitContract() = runBlocking {
        assertEquals("existing-economy-result", withTimeout(5_000) {
            VideoRequestDeadline.run(fullDialogue = false, staged = true, economy = true, timeoutMs = 1) {
                delay(30)
                "existing-economy-result"
            }
        })
    }

    @Test fun providerFailureAndItsSavedDeadlineAreNotReclassifiedAsTimeout() = runBlocking {
        for (code in listOf("API_AUTH", "API_QUOTA_EXHAUSTED", "API_RATE_LIMIT", "API_SERVICE_503")) {
            val expected = VideoApiException(code, retryAfterMs = 600_000, nextRetryAt = 1_000_000)
            try {
                VideoRequestDeadline.run(fullDialogue = true, staged = true, economy = false, timeoutMs = 1) {
                    delay(30)
                    throw expected
                }
                fail("The provider failure must propagate")
            } catch (actual: VideoApiException) {
                assertSame(expected, actual)
                assertEquals(600_000L, actual.retryAfterMs)
                assertEquals(1_000_000L, actual.nextRetryAt)
            }
        }
    }

    @Test fun fullDialoguePhysicalHttpAttemptStillHasATransportDeadline() {
        val client = VideoTranslationClient.requestClient(fullDialogue = true)
        assertEquals(240_000, client.callTimeoutMillis)
        assertEquals(240_000, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
    }
}
