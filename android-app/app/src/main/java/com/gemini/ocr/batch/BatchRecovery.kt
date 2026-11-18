package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage

internal object BatchRecovery {
    const val INTERVAL_MS = 60_000L
    const val MAX_ATTEMPTS = 5
    private const val MAX_AUTOMATIC_WAIT_MS = 5 * 60_000L
    private val retryable = setOf(
        "API_NETWORK", "API_TIMEOUT", "API_RATE_LIMIT",
        "API_SERVICE_408", "API_SERVICE_500", "API_SERVICE_502", "API_SERVICE_503", "API_SERVICE_504",
        "TEXT_INCOMPLETE_RESPONSE", "TEXT_TRUNCATED"
    )
    private val sharedManual = setOf("API_AUTH", "API_QUOTA_EXHAUSTED")
    private val polishResponseFailures = setOf(
        "POLISH_MISSING_IDS", "POLISH_TRUNCATED", "POLISH_INVALID_REVIEW_FLAGS", "TEXT_INVALID_RESPONSE"
    )

    fun canRetryPolishResponse(code: String) = code in polishResponseFailures

    fun canRetry(item: BatchItem) = item.errorCode in retryable || item.stage == BatchPipelineStage.POLISH &&
        (canRetryPolishResponse(item.errorCode) || item.errorCode == "POLISH_IDLE_TIMEOUT")

    private fun retryableItems(batch: BatchProject): List<BatchItem> {
        if (!batch.autoRetryEnabled || batch.state !in setOf("FAILED", "PAUSED")) return emptyList()
        val failed = batch.items.filter { it.stageStatus == "NEEDS_REVIEW" }
        if (failed.any { it.errorCode in sharedManual }) return emptyList()
        return failed.filter(::canRetry)
    }

    fun exhausted(batch: BatchProject) = batch.autoRetryAttempts >= MAX_ATTEMPTS && retryableItems(batch).isNotEmpty()

    fun nextAttemptAt(batch: BatchProject, nowMs: Long): Long? {
        if (batch.autoRetryAttempts >= MAX_ATTEMPTS) return null
        val candidates = retryableItems(batch)
        if (candidates.isEmpty()) return null
        // An in-flight timeout has already spent one minute without a result.
        val wait = if (candidates.all { it.errorCode == "POLISH_IDLE_TIMEOUT" }) 0L else INTERVAL_MS
        val deadline = maxOf(nowMs + wait, batch.retryAfterMs)
        return deadline.takeIf { it - nowMs <= MAX_AUTOMATIC_WAIT_MS }
    }
}
