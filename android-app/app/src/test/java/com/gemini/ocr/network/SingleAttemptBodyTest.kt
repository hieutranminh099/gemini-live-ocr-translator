package com.gemini.ocr.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class SingleAttemptBodyTest {
    @Test fun preservesRequestContentAndMetadataWhilePreventingReplay() {
        val json = "{\"source\":\"2人各拿3张票。\"}"
        val original = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val wrapped = singleAttemptBody(original)
        val buffer = Buffer()

        wrapped.writeTo(buffer)

        assertTrue(wrapped.isOneShot())
        assertEquals(original.contentType(), wrapped.contentType())
        assertEquals(original.contentLength(), wrapped.contentLength())
        assertEquals(original.isDuplex(), wrapped.isDuplex())
        assertEquals(json, buffer.readUtf8())
    }

    @Test fun retryAfterZeroCannotReplayThePostEvenWhenAnotherResponseIsAvailable() {
        val server = MockWebServer()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must not be requested"))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val request = Request.Builder().url(server.url("/synthetic-quota-test"))
                .post(singleAttemptBody("{}".toRequestBody("application/json".toMediaType())))
                .build()

            client.newCall(request).execute().use { response -> assertEquals(503, response.code) }

            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
