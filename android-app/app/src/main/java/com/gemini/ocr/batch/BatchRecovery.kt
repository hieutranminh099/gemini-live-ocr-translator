package com.gemini.ocr.batch

internal object BatchRecovery {
    const val INTERVAL_MS = 60_000L
    const val MAX_ATTEMPTS = 3
    private const val MAX_AUTOMATIC_WAIT_MS = 5 * 60_000L
    private val retryable = setOf(
        "API_NETWORK", "API_TIMEOUT", "API_RATE_LIMIT",
        "API_SERVICE_408", "API_SERVICE_500", "API_SERVICE_502", "API_SERVICE_503", "API_SERVICE_504",
        "TEXT_INCOMPLETE_RESPONSE", "TEXT_TRUNCATED"
    )
    private val sharedManual = setOf("API_AUTH", "API_QUOTA_EXHAUSTED")

    fun canRetry(code: String) = code in retryable
    fun blocksAutomaticRetry(code: String) = code in sharedManual

    fun nextAttemptAt(batch: BatchProject, nowMs: Long): Long? {
        if (!batch.autoRetryEnabled || batch.autoRetryAttempts >= MAX_ATTEMPTS ||
            batch.state !in setOf("FAILED", "PAUSED") ||
            batch.items.any { it.stageStatus == "NEEDS_REVIEW" && blocksAutomaticRetry(it.errorCode) } ||
            batch.items.none { it.stageStatus == "NEEDS_REVIEW" && canRetry(it.errorCode) }) return null
        val backoff = INTERVAL_MS * (1L shl batch.autoRetryAttempts.coerceIn(0, MAX_ATTEMPTS - 1))
        val deadline = maxOf(nowMs + backoff, batch.retryAfterMs)
        return deadline.takeIf { it - nowMs <= MAX_AUTOMATIC_WAIT_MS }
    }
}
