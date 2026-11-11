package com.gemini.ocr.capture

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A whole request deadline also bounds quota waits and slow trickling HTTP responses. */
internal object LiveRequestPolicy {
    const val DEADLINE_MS=20_000L
    class Deadline:IOException("Gemini chưa hoàn tất trong 20 giây. Đã hủy yêu cầu và giữ khả năng dịch câu tiếp theo.")
    suspend fun <T> request(timeoutMs:Long=DEADLINE_MS,block:suspend()->T):T = try {
        withTimeout(timeoutMs){block()}
    }catch(_:TimeoutCancellationException){currentCoroutineContext().ensureActive();throw Deadline()}
    data class Failure(val code:String,val delayMs:Long?=null)
    fun failure(error:Exception):Failure {
        val text=error.message.orEmpty()
        return when {
            error is Deadline || error is InterruptedIOException -> Failure("request_timeout",5000)
            "(429)" in text -> Failure("rate_limited",61_000)
            Regex("\\((400|401|403)\\)").containsMatchIn(text) -> Failure("provider_configuration")
            text.contains("Chưa có khóa") || text.contains("Chưa nhập khóa") -> Failure("no_api_key")
            else -> Failure("provider_error",5000)
        }
    }
}
