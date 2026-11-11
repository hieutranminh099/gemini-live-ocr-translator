package com.gemini.ocr.video

import android.content.Context
import org.json.JSONObject
import java.io.File

internal class VideoQuotaWaitException(message: String, val nextRetryAt: Long) : IllegalStateException(message)

/** Numeric measurements only. No source text, subtitles, URI, prompts, keys or response bodies. */
internal class VideoBatchTelemetry(context: Context, id: String) {
    private val path = File(VideoStore(context).folder(id), "smart-batch-metrics.json")
    private val values = VideoMetricFile.read(path).apply {
        if (!has("schema")) put("schema", 1)
        if (!has("responses")) put("responses", diagnosticSum(optLong("responses_with_token_usage"), optLong("responses_without_token_usage")))
    }
    @Synchronized fun add(name: String, amount: Long = 1) {
        require(amount >= 0)
        set(name, diagnosticSum(values.optLong(name).coerceAtLeast(0), amount))
    }
    @Synchronized fun set(name: String, value: Long) {
        require(METRIC_NAME.matches(name) && value >= 0)
        values.put(name, value)
    }
    @Synchronized fun maximum(name: String, value: Long) {
        set(name, maxOf(values.optLong(name), value))
    }
    @Synchronized fun dispatch(cues: Int, repair: Boolean) {
        require(cues >= 0)
        add("requests"); add("requested_cues", cues.toLong()); if (repair) add("repair_requests")
        set("last_request_cues", cues.toLong())
        set("last_request_at_ms", System.currentTimeMillis())
        set("active_retry_at_ms", 0)
        values.remove("last_retry_kind"); values.remove("last_retry_attempt"); values.remove("last_retry_deadline_ms")
        flush()
    }

    /** A scheduler may report the same wait repeatedly. Count a distinct wait, never a poll. */
    @Synchronized fun retry(code: String, attempt: Int, deadlineMs: Long) {
        val kind = diagnosticKind(code)
        val savedAttempt = attempt.coerceAtLeast(0).toLong()
        val deadline = deadlineMs.coerceAtLeast(0)
        if (values.optLong("last_retry_kind", -1) == kind.first &&
            values.optLong("last_retry_attempt", -1) == savedAttempt &&
            values.optLong("last_retry_deadline_ms", -1) == deadline) return
        add("retry_waits"); add("retry_cause_" + kind.second)
        set("last_retry_kind", kind.first); set("last_retry_attempt", savedAttempt)
        set("last_retry_deadline_ms", deadline); set("active_retry_at_ms", deadline)
        flush()
    }

    /** A terminal logical request failure is separate from its physical dispatches and waits. */
    @Synchronized fun failure(code: String) {
        add("failed_requests"); add("failure_cause_" + diagnosticKind(code).second)
        flush()
    }

    @Synchronized fun responseFailure(reason: Int) {
        set("response_failure_reason", VideoResponseFailureReason.fromId(reason).id.toLong())
        set("response_failure_at_ms", System.currentTimeMillis())
        flush()
    }

    @Synchronized fun capacity(limit: Int, ceiling: Int) {
        require(limit in 1..8 && ceiling in limit..8)
        set("request_capacity", limit.toLong()); set("request_capacity_ceiling", ceiling.toLong())
        set("request_capacity_at_ms", System.currentTimeMillis())
        // The dispatch/retry checkpoint flushes this sample with the associated event.
    }
    @Synchronized fun requireQuotaReady() {
        val deadline=values.optLong("retry_after_ms")
        val remaining=deadline-System.currentTimeMillis()
        if(remaining>0) throw VideoQuotaWaitException("Đạt giới hạn lượt gọi Gemini API (429). Chờ ${(remaining+999)/1000} giây; checkpoint vẫn được giữ.", deadline)
    }
    @Synchronized fun quotaFailure() {
        values.put("retry_after_ms",System.currentTimeMillis()+61_000L)
        flush()
    }
    @Synchronized fun response(result: VideoTranslationClient.Result) {
        add("responses")
        add("accepted_cues", result.accepted.size.toLong()); add("missing_cues", result.missingIds.size.toLong())
        if (result.missingIds.isNotEmpty()) add("partial_responses")
        if (result.accepted.isEmpty() && result.missingIds.isNotEmpty()) add("empty_responses")
        if (result.truncated) add("truncated_responses")
        set("active_retry_at_ms", 0)
        add("request_wall_ms", result.elapsedMs); add("quota_wait_ms", result.quotaWaitMs)
        val tokens = result.tokens
        if (tokens != null) {
            add("responses_with_token_usage")
            tokens.inputTokens?.let { add("input_tokens", it.toLong()) }
            tokens.outputTokens?.let { add("output_tokens", it.toLong()) }
            tokens.thinkingTokens?.let { add("thinking_tokens", it.toLong()) }
            tokens.cachedInputTokens?.let { add("cached_input_tokens", it.toLong()) }
        } else add("responses_without_token_usage")
        flush()
    }
    @Synchronized fun flush() {
        values.put("updated_ms", System.currentTimeMillis())
        VideoMetricFile.write(path, values)
    }
    private companion object {
        val METRIC_NAME = Regex("[a-z_]{1,50}")
        fun diagnosticKind(code: String): Pair<Long, String> = when {
            code == "API_RATE_LIMIT" -> 1L to "rate_limit"
            code.matches(Regex("API_SERVICE_[1-5][0-9]{2}")) -> 2L to "service"
            code == "API_NETWORK" || code == "API_TIMEOUT" -> 3L to "network"
            code == "API_AUTH" -> 4L to "authorization"
            code == "API_QUOTA_EXHAUSTED" -> 5L to "quota"
            code.matches(Regex("TEXT_[A-Z_]{2,40}")) -> 6L to "response"
            else -> 7L to "other"
        }
    }
}
