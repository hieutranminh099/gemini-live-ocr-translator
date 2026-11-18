package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchRecoveryTest {
    private val now = 1_000_000L
    private fun failed(code: String) = BatchItem("fixture", "Episode", state = "NEEDS_REVIEW",
        stageStatus = "NEEDS_REVIEW", errorCode = code)
    private fun project(vararg codes: String) = BatchProject("fixture", "Batch", BatchKind.SERIES,
        items = codes.mapIndexed { index, code -> failed(code).copy(projectId = "video-$index") }, state = "FAILED")

    @Test fun deadlineRespectsProviderAndRejectsLongCooldown() {
        val batch = project("API_RATE_LIMIT")
        assertEquals(now + 60_000, BatchRecovery.nextAttemptAt(batch, now))
        assertEquals(now + 100_000, BatchRecovery.nextAttemptAt(batch.copy(retryAfterMs = now + 100_000), now))
        assertNull(BatchRecovery.nextAttemptAt(batch.copy(retryAfterMs = now + 310_000), now))
    }

    @Test fun fiveAutomaticAttemptsUseOneMinuteIntervalsAndHonorProviderDeadline() {
        val batch = project("API_SERVICE_503")
        repeat(5) { attempt ->
            assertEquals(now + 60_000, BatchRecovery.nextAttemptAt(batch.copy(autoRetryAttempts = attempt), now))
        }
        assertEquals(now + 270_000, BatchRecovery.nextAttemptAt(
            batch.copy(autoRetryAttempts = 4, retryAfterMs = now + 270_000), now))
        assertNull(BatchRecovery.nextAttemptAt(batch.copy(autoRetryAttempts = 5), now))
        assertNull(BatchRecovery.nextAttemptAt(batch.copy(autoRetryAttempts = 4, retryAfterMs = now + 310_000), now))
        assertTrue(BatchRecovery.exhausted(batch.copy(autoRetryAttempts = 5)))
    }

    @Test fun polishRecoveryDistinguishesIncompleteResponsesFromInFlightInactivity() {
        for (code in listOf("POLISH_MISSING_IDS", "POLISH_TRUNCATED", "POLISH_INVALID_REVIEW_FLAGS", "TEXT_INVALID_RESPONSE", "POLISH_IDLE_TIMEOUT")) {
            val batch = project(code)
            val polish = batch.copy(items = batch.items.map { it.copy(stage = BatchPipelineStage.POLISH) })
            assertNull(BatchRecovery.nextAttemptAt(batch, now))
            assertEquals(if (code == "POLISH_IDLE_TIMEOUT") now else now + 60_000, BatchRecovery.nextAttemptAt(polish, now))
            assertEquals(now + 100_000, BatchRecovery.nextAttemptAt(polish.copy(retryAfterMs = now + 100_000), now))
        }
    }

    @Test fun noAutomaticRetryForUnclassifiedManualOrExhaustedFailures() {
        for (code in listOf("RESPONSE_NEEDS_REVIEW", "TEXT_BLOCKED", "TEXT_INVALID_RESPONSE", "ROI_INVALID",
            "API_AUTH", "API_QUOTA_EXHAUSTED", "STORAGE_FULL", "SOURCE_PERMISSION")) {
            assertNull(code, BatchRecovery.nextAttemptAt(project(code), now))
        }
        assertNull(BatchRecovery.nextAttemptAt(project("API_NETWORK", "API_AUTH"), now))
        assertNull(BatchRecovery.nextAttemptAt(project("API_NETWORK").copy(autoRetryEnabled = false), now))
        assertNull(BatchRecovery.nextAttemptAt(project("API_NETWORK").copy(autoRetryAttempts = 5), now))
    }

    @Test fun legacyDefaultsOnAndInterruptedWaitKeepsProviderDeadline() {
        val original = project("API_RATE_LIMIT").copy(state = "RETRY_WAIT", retryAfterMs = now + 100_000,
            autoRetryAtMs = now + 100_000, autoRetryAttempts = 2)
        val restored = BatchProject.from(original.json())
        assertEquals(original, restored)
        val paused = BatchPlan.interrupted(restored)
        assertEquals("PAUSED", paused.state)
        assertEquals(0L, paused.autoRetryAtMs)
        assertEquals(original.retryAfterMs, paused.retryAfterMs)
        assertEquals(original.items, paused.items)
        val legacy = original.json().apply {
            remove("autoRetryEnabled"); remove("autoRetryAtMs"); remove("autoRetryAttempts")
            getJSONArray("items").getJSONObject(0).remove("errorCode")
        }
        assertTrue(BatchProject.from(legacy).autoRetryEnabled)
        assertEquals("", BatchProject.from(legacy).items.single().errorCode)
    }
}
