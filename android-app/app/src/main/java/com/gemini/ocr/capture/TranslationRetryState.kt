package com.gemini.ocr.capture

/** Configuration failures need a configuration change, not an endless timer for every OCR frame. */
class TranslationRetryState(private val capacity: Int = 256) {
    data class Failure(val code: String, val message: String, val retryAt: Long?) {
        val automaticRetry: Boolean get() = retryAt != null
        fun status(now: Long): String = if (retryAt == null)
            "$message\nKhông tự thử lại câu này khi cấu hình chưa đổi."
        else "$message\nThử lại sau ${((retryAt - now).coerceAtLeast(0) + 999) / 1000} giây."

        fun feedback(now: Long): String = if (code == "cli_configuration" &&
            message.startsWith("Google không hỗ trợ dùng Antigravity CLI"))
            "CLI AI Pro không hỗ trợ dịch câu mới.\nChỉ dùng cache • Mở app xem chi tiết."
        else status(now)
    }

    init { require(capacity > 0) }
    private val configurationFailures = linkedMapOf<String, Failure>()
    private var transient: Failure? = null

    fun failed(key: String, code: String, message: String, now: Long, retryAfterMs: Long? = null): Failure {
        val fallback = when (code) {
            "no_api_key", "provider_configuration", "cli_auth", "cli_login_active", "cli_missing",
            "cli_configuration", "cli_platform" -> null
            "rate_limited", "quota_exceeded" -> 15000L
            "daily_limit" -> 60000L
            else -> 5000L
        }
        // Positive server RetryInfo takes precedence over our fallback. A malformed value
        // cannot create an unbounded wait or turn an authentication error into a retry loop.
        val delay = fallback?.let { retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(900000L) ?: it }
        val failure = Failure(code, message, delay?.let { now + it })
        if (delay == null) {
            configurationFailures.remove(key)
            configurationFailures[key] = failure
            while (configurationFailures.size > capacity) configurationFailures.remove(configurationFailures.keys.first())
        } else {
            // Concurrent/obsolete requests can fail out of order. They must never shorten
            // the provider-wide cooldown already promised by a previous response.
            transient?.takeIf { it.retryAt!! > failure.retryAt!! && now < it.retryAt }?.let { return it }
            transient = failure
        }
        return failure
    }

    /** Check RAM cache first. Configuration failures are per source; provider cooldown is global. */
    fun waiting(key: String, now: Long): Failure? = configurationFailures[key]
        ?: transient?.takeIf { now < it.retryAt!! }

    fun succeeded(key: String) { configurationFailures.remove(key) }
    fun clearConfigurationFailures() { configurationFailures.clear() }
    fun clear() { configurationFailures.clear(); transient = null }
}
