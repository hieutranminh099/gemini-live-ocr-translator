package com.gemini.ocr.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Whitelisted transport timings only. No request, URL, header, body or exception message. */
data class HttpCallMetrics(
    val totalMs: Long,
    val headersMs: Long?,
    val bodyMs: Long?,
    val statusCode: Int?,
    val phase: String,
    val exceptionType: String? = null,
)

/** A fully consumed response: no socket or ResponseBody escapes the callback. */
data class BufferedHttpResponse(val code: Int, val body: String, val metrics: HttpCallMetrics? = null) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Cancellation stays attached until the body finishes, not merely until headers arrive.
 * This lets an obsolete subtitle release its HTTP call instead of blocking an IO worker.
 */
suspend fun Call.awaitBody(
    observer: ((HttpCallMetrics) -> Unit)? = null,
    clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
): BufferedHttpResponse = suspendCancellableCoroutine { continuation ->
    data class Headers(val elapsedMs: Long, val code: Int)
    val started = clockMs()
    val headers = AtomicReference<Headers?>(null)
    val terminalReported = AtomicBoolean(false)

    fun notifySafely(metrics: HttpCallMetrics) {
        try { observer?.invoke(metrics) } catch (_: Throwable) { /* Telemetry cannot change an HTTP result. */ }
    }
    fun terminal(phase: String, exceptionType: String? = null): HttpCallMetrics? {
        if (!terminalReported.compareAndSet(false, true)) return null
        val elapsed = (clockMs() - started).coerceAtLeast(0)
        val received = headers.get()
        return HttpCallMetrics(elapsed, received?.elapsedMs,
            received?.let { (elapsed - it.elapsedMs).coerceAtLeast(0) }, received?.code,
            phase, exceptionType).also(::notifySafely)
    }
    continuation.invokeOnCancellation {
        terminal(if (headers.get() == null) "cancelled_before_headers" else "cancelled_body_read", "CancellationException")
        cancel()
    }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    try {
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                terminal(if (headers.get() == null) "before_headers" else "body_read", error.javaClass.simpleName)
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                val received = Headers((clockMs() - started).coerceAtLeast(0), response.code)
                headers.set(received)
                // Publish status before body consumption: a stalled error body must not hide 429.
                if (!terminalReported.get()) notifySafely(HttpCallMetrics(received.elapsedMs,
                    received.elapsedMs, null, received.code, "headers_received"))
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                val result = runCatching {
                    response.use { BufferedHttpResponse(it.code, it.body?.string().orEmpty()) }
                }
                val metrics = terminal(if (result.isSuccess) "complete" else "body_read",
                    result.exceptionOrNull()?.javaClass?.simpleName)
                continuation.resumeWith(result.map { it.copy(metrics = metrics) })
            }
        })
    } catch (error: Exception) {
        terminal(if (headers.get() == null) "before_headers" else "body_read", error.javaClass.simpleName)
        continuation.resumeWith(Result.failure(error))
    }
}
