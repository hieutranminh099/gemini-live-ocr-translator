package com.gemini.ocr.video

import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.awaitBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.random.Random

/** Sanitized, machine-readable failure. Never retain provider bodies, URLs or keys in a cause. */
internal class VideoApiException(
    val code: String,
    val transient: Boolean = false,
    val retryAfterMs: Long = 0,
    val splitAllowed: Boolean = false,
    val nextRetryAt: Long = 0,
    detail: String = "Tiến độ đã được lưu.",
    val responseReason: VideoResponseFailureReason? = null
) : IOException("[$code] $detail")

/** Default requests are serial. An explicit batch scope can admit independent video requests. */
internal object VideoApiRecovery {
    private val lane = Mutex()
    private fun batchAdmission(limit: Int) = VideoAdaptiveAdmission(limit) {
        if (com.gemini.ocr.batch.BatchPlaybackPriority.current.restricted()) 1 else limit
    }

    /** One execution retains reduced admission across automatic retry passes and stage scopes. */
    internal suspend fun <T> withAdaptiveBatch(limit: Int, block: suspend () -> T): T =
        withContext(AdaptiveBatch(batchAdmission(limit))) { block() }

    private class AdaptiveBatch(val admission: VideoAdaptiveAdmission) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AdaptiveBatch>
    }

    internal suspend fun currentCapacity(): VideoRequestCapacity? =
        currentCoroutineContext()[ParallelRequests]?.capacity()
            ?: currentCoroutineContext()[AdaptiveBatch]?.admission?.snapshot()

    /** Multi-key requests keep independent key health while sharing only batch admission. */
    internal suspend fun <T> withAccountAdmission(block: suspend () -> T): T {
        val gate = currentCoroutineContext()[ParallelRequests]?.admission ?: currentCoroutineContext()[AdaptiveBatch]?.admission
        return if (gate == null) block() else gate.execute(block)
    }

    internal suspend fun <T> withParallelRequests(
        limit: Int,
        clockMs: () -> Long = { System.currentTimeMillis() },
        block: suspend () -> T
    ): T {
        require(limit in 1..8)
        val admission = currentCoroutineContext()[AdaptiveBatch]?.admission ?: batchAdmission(limit)
        return withContext(ParallelRequests(limit, clockMs, admission)) { block() }
    }

    private class ParallelAbort(val failure: VideoApiException) : Exception()

    private class ParallelRequests(private val limit: Int, private val clockMs: () -> Long,
                                   val admission: VideoAdaptiveAdmission) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ParallelRequests>
        private val slots = Semaphore(limit)
        private val state = Any()
        private var retryAt = 0L
        private var retryCode = ""
        private var terminal: VideoApiException? = null

        fun capacity(): VideoRequestCapacity = admission.snapshot().let {
            VideoRequestCapacity(minOf(it.limit, limit), minOf(it.ceiling, limit))
        }

        private suspend fun awaitReady(
            attempt: Int,
            onRetry: suspend (Int, Long, String) -> Unit,
            sleep: suspend (Long) -> Unit
        ) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val pending = synchronized(state) {
                    terminal?.let { throw ParallelAbort(it) }
                    retryAt to retryCode
                }
                val remaining = pending.first - clockMs()
                if (remaining <= 0) return
                onRetry(maxOf(1, attempt), pending.first, pending.second)
                sleep(remaining)
            }
        }

        suspend fun <T> run(
            beforeDispatch: suspend () -> Unit,
            onRetry: suspend (Int, Long, String) -> Unit,
            sleep: suspend (Long) -> Unit,
            jitter: () -> Long,
            dispatchTurn: suspend () -> Unit,
            operation: suspend () -> T
        ): T {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var failureDeadline = 0L
                try {
                    return slots.withPermit { admission.withPermit { admitted ->
                        try {
                            awaitReady(attempt, onRetry, sleep)
                            dispatchTurn()
                            awaitReady(attempt, onRetry, sleep)
                            beforeDispatch()
                            awaitReady(attempt, onRetry, sleep)
                            currentCoroutineContext().ensureActive()
                            operation().also { admission.succeeded(admitted) }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            val failure = when (error) {
                                is VideoApiException -> error
                                is IOException -> networkFailure(error)
                                else -> throw error
                            }
                            admission.failed(failure)
                            // Publish failures while owning the slot, before queued requests enter.
                            synchronized(state) {
                                if (failure.code in setOf("API_AUTH", "API_QUOTA_EXHAUSTED") ||
                                    (failure.transient && attempt >= 2)) {
                                    terminal = if (failure.transient) VideoApiException(failure.code, true,
                                        failure.retryAfterMs, failure.splitAllowed,
                                        maxOf(retryAt, failure.nextRetryAt, clockMs() + failure.retryAfterMs),
                                        "Dịch vụ vẫn chưa sẵn sàng; tiến độ và thời điểm tiếp tục đã lưu.")
                                    else failure
                                    throw ParallelAbort(requireNotNull(terminal))
                                }
                                if (failure.transient) {
                                    val wait = maxOf(failure.retryAfterMs,
                                        (1_000L shl attempt) + jitter().coerceIn(0, 250))
                                    retryAt = maxOf(retryAt, failure.nextRetryAt, clockMs() + wait)
                                    retryCode = failure.code
                                    failureDeadline = retryAt
                                }
                            }
                            throw failure
                        }
                    } }
                } catch (cancel: CancellationException) { throw cancel }
                catch (abort: ParallelAbort) { throw abort.failure }
                catch (failure: VideoApiException) {
                    if (!failure.transient) throw failure
                    attempt++
                    onRetry(attempt, failureDeadline, failure.code)
                    val remaining = failureDeadline - clockMs()
                    if (remaining > 0) sleep(remaining)
                }
            }
        }
    }

    suspend fun <T> run(
        beforeDispatch: suspend () -> Unit,
        onRetry: suspend (Int, Long, String) -> Unit = { _, _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
        jitter: () -> Long = { Random.nextLong(251) },
        dispatchTurn: suspend () -> Unit = { StandaloneTranslator.awaitVideoDispatchTurn() },
        operation: suspend () -> T
    ): T {
        currentCoroutineContext()[ParallelRequests]?.let {
            return it.run(beforeDispatch, onRetry, sleep, jitter, dispatchTurn, operation)
        }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return lane.withLock { withAccountAdmission {
                    dispatchTurn()
                    currentCoroutineContext().ensureActive()
                    beforeDispatch()
                    currentCoroutineContext().ensureActive()
                    operation()
                } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val failure = when (error) {
                    is VideoApiException -> error
                    is IOException -> networkFailure(error)
                    else -> throw error
                }
                if (!failure.transient) throw failure
                if (attempt >= 2) {
                    if (failure.retryAfterMs > 0) throw VideoApiException(failure.code, true,
                        failure.retryAfterMs, failure.splitAllowed,
                        nextRetryAt = System.currentTimeMillis() + failure.retryAfterMs,
                        detail = "Dịch vụ vẫn đang yêu cầu chờ; tiến độ và thời điểm tiếp tục đã lưu.")
                    throw failure
                }
                val wait = maxOf(failure.retryAfterMs, (1_000L shl attempt) + jitter().coerceIn(0, 250))
                attempt++
                onRetry(attempt, System.currentTimeMillis() + wait, failure.code)
                sleep(wait)
            }
        }
    }

    suspend fun exchange(client: OkHttpClient, request: Request): String {
        var retryAfter: String? = null
        val observed = client.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { retryAfter = it.header("Retry-After") }
        }.build()
        val response = try { observed.newCall(request).awaitBody() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: IOException) { throw networkFailure(error) }
        if (!response.isSuccessful) throw httpFailure(response.code, response.body, retryAfter)
        currentCoroutineContext().ensureActive()
        return response.body
    }

    fun networkFailure(error: IOException): VideoApiException = VideoApiException(
        if (error is InterruptedIOException) "API_TIMEOUT" else "API_NETWORK", true,
        detail = "Kết nối dịch vụ bị gián đoạn; tiến độ đã lưu.")

    fun httpFailure(status: Int, body: String = "", retryAfter: String? = null): VideoApiException {
        val wait = maxOf(retryAfterMs(retryAfter), if (status == 429 || status == 503) rpcRetryDelayMs(body) else 0)
        if (status == 400 && invalidApiKey(body)) return VideoApiException("API_AUTH",
            detail = "Dịch vụ không chấp nhận khóa API (HTTP 400). Kiểm tra khóa và quyền API; tiến độ đã lưu.")
        return when (status) {
            401, 403 -> VideoApiException("API_AUTH", detail = "Dịch vụ chưa cho phép truy cập (HTTP $status). Kiểm tra khóa và quyền API.")
            429 -> if (quotaExhausted(body)) VideoApiException("API_QUOTA_EXHAUSTED",
                detail = "Đã hết hạn mức dịch vụ (HTTP 429); không tự gửi lại. Tiến độ đã lưu.")
            else VideoApiException("API_RATE_LIMIT", true, wait,
                detail = "Dịch vụ đang giới hạn lượt gọi (429). Tiến độ đã lưu.")
            408, 500, 502, 503, 504 -> VideoApiException("API_SERVICE_$status", true, wait,
                detail = "Dịch vụ tạm thời chưa phản hồi (HTTP $status); tiến độ đã lưu.")
            400, 413 -> VideoApiException("API_REQUEST_$status", splitAllowed = true,
                detail = "Dịch vụ chưa chấp nhận nhóm dữ liệu (HTTP $status); tiến độ đã lưu.")
            else -> VideoApiException("API_HTTP_$status", detail = "Dịch vụ chưa xử lý được yêu cầu (HTTP $status); tiến độ đã lưu.")
        }
    }

    /** Only the provider's typed ErrorInfo reason identifies this authentication failure. */
    private fun invalidApiKey(body: String): Boolean = runCatching {
        if (body.length > 128 * 1024) return@runCatching false
        val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details") ?: return@runCatching false
        (0 until details.length()).any { index ->
            val detail = details.optJSONObject(index) ?: return@any false
            detail.optString("@type") == "type.googleapis.com/google.rpc.ErrorInfo" &&
                detail.optString("reason") == "API_KEY_INVALID"
        }
    }.getOrDefault(false)

    internal fun retryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long {
        if (value.isNullOrBlank()) return 0
        value.trim().toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1_000 }
        return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - nowMs).coerceIn(0, 86_400_000) }.getOrDefault(0)
    }

    internal fun quotaExhausted(body: String): Boolean = runCatching {
        if (body.length > 128 * 1024) return@runCatching false
        val error = JSONObject(body).optJSONObject("error") ?: return@runCatching false
        val details = error.optJSONArray("details")
        var shortWindow = false
        if (details != null) for (i in 0 until details.length()) {
            val detail = details.optJSONObject(i) ?: continue
            if (detail.optString("@type") != "type.googleapis.com/google.rpc.QuotaFailure") continue
            val violations = detail.optJSONArray("violations") ?: continue
            for (j in 0 until violations.length()) {
                val row = violations.optJSONObject(j) ?: continue
                val metric = (row.optString("quotaMetric") + row.optString("quotaId")).lowercase()
                if ("perday" in metric || "per_day" in metric || "daily" in metric || row.optString("quotaValue") == "0") return@runCatching true
                if (Regex("per_?(?:[0-9]+_?)?(?:minutes?|seconds?)").containsMatchIn(metric)) shortWindow = true
            }
        }
        // Gemini also mentions plan/billing in its generic RPM error text.
        if (shortWindow) return@runCatching false
        val message = error.optString("message").lowercase()
        // A generic plan/billing advisory also accompanies rolling request/spend limits.
        // Only explicit daily/disabled evidence justifies the terminal quota branch.
        val daily = Regex("\\b(?:daily|per[ -]day)\\b").containsMatchIn(message)
        val exhausted = Regex("\\b(?:exceeded|exhausted|depleted|reached)\\b").containsMatchIn(message)
        (daily && exhausted) || Regex("\\blimit:\\s*0(?:\\s|[,;)]|$)").containsMatchIn(message)
    }.getOrDefault(false)

    private fun rpcRetryDelayMs(body: String): Long = runCatching {
        if (body.length > 128 * 1024) return@runCatching 0L
        val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details") ?: return@runCatching 0L
        (0 until details.length()).maxOfOrNull { index ->
            val detail = details.optJSONObject(index)
            if (detail?.optString("@type") != "type.googleapis.com/google.rpc.RetryInfo") return@maxOfOrNull 0L
            val duration = detail.optString("retryDelay")
            if (!duration.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,9})?s"))) return@maxOfOrNull 0L
            ceil(duration.dropLast(1).toDouble().coerceAtMost(86_400.0) * 1_000).toLong()
        } ?: 0L
    }.getOrDefault(0L)

    /** Only strip a complete outer Markdown fence. Never fish JSON out of prose or infer missing IDs. */
    internal fun jsonText(value: String): String {
        val text = value.trim()
        if (!text.startsWith("```")) return text
        val lines = text.lines()
        require(lines.size >= 3 && lines.first().lowercase() in setOf("```json", "```") && lines.last() == "```")
        return lines.subList(1, lines.size - 1).joinToString("\n").trim()
    }
}
