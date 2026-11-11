package com.gemini.ocr.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class HttpTransportTimingTest {
    @Test fun deterministicPhasesAddExactlyWithoutDoubleCountingTlsOrConnectionSetup() {
        var now = 0L
        val timing = HttpTransportTiming { now }
        val request = Request.Builder().url("http://127.0.0.1/synthetic").build()
        val call = OkHttpClient().newCall(request)
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), 80)
        val socket = Socket()
        val connection = object : Connection {
            override fun route(): Route = error("Address must never be inspected")
            override fun socket() = socket
            override fun handshake(): Handshake? = null
            override fun protocol() = Protocol.HTTP_2
        }
        val listener = timing.listener
        listener.callStart(call)
        now = 2; listener.dnsStart(call, "must-not-be-retained")
        now = 7; listener.dnsEnd(call, "must-not-be-retained", emptyList())
        listener.connectStart(call, address, Proxy.NO_PROXY)
        now = 14; listener.secureConnectStart(call)
        now = 25; listener.secureConnectEnd(call, null)
        now = 28; listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2)
        now = 30; listener.connectionAcquired(call, connection)
        now = 33; listener.requestHeadersStart(call)
        now = 40; listener.requestBodyEnd(call, 2)
        listener.responseHeadersStart(call)
        now = 70; listener.responseHeadersEnd(call, Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK").build())
        now = 76; listener.callEnd(call)
        val metrics = timing.snapshot()
        assertEquals(5L, metrics.dnsMs)
        assertEquals(10L, metrics.tcpMs)
        assertEquals(11L, metrics.tlsMs)
        assertEquals(4L, metrics.connectionOtherMs)
        assertEquals(30L, metrics.waitHeadersMs)
        assertEquals(metrics.totalMs, listOf(metrics.connectionMs, metrics.prepareMs, metrics.uploadMs,
            metrics.waitHeadersMs, metrics.bodyMs).sumOf { requireNotNull(it) })
        assertEquals(false, metrics.connectionReused)
        assertFalse(metrics.toString().contains("must-not-be-retained"))
        socket.close()
    }

    @Test fun realLoopbackDistinguishesPooledConnectionWithoutReadingCredentialsOrAddresses() {
        val server = MockWebServer()
        val timings = mutableListOf<HttpTransportTiming>()
        val client = OkHttpClient.Builder().eventListenerFactory {
            HttpTransportTiming().also { timings.add(it) }.listener
        }.build()
        try {
            server.start(InetAddress.getLoopbackAddress(), 0)
            repeat(2) { server.enqueue(MockResponse().setBody("synthetic response")) }
            repeat(2) {
                val request = Request.Builder().url(server.url("/local-only"))
                    .post("{}".toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response -> response.body!!.string() }
            }
            val first = timings[0].snapshot()
            val second = timings[1].snapshot()
            assertEquals(false, first.connectionReused)
            assertEquals(true, second.connectionReused)
            assertEquals(0L, second.dnsMs)
            assertEquals(0L, second.tcpMs)
            assertEquals(0L, second.tlsMs)
            assertNotNull(second.waitHeadersMs)
            assertEquals(second.totalMs, listOf(second.connectionMs, second.prepareMs, second.uploadMs,
                second.waitHeadersMs, second.bodyMs).sumOf { requireNotNull(it) })
        } finally {
            server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
