package com.gemini.ocr.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Prevent replay of a quota-bounded POST, including HTTP 503 Retry-After: 0 follow-ups.
 * Pair with a client that disables connection retries and redirects. Do not reuse a Call.
 */
internal fun singleAttemptBody(body: RequestBody): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = body.contentType()
    override fun contentLength(): Long = body.contentLength()
    override fun isDuplex(): Boolean = body.isDuplex()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
