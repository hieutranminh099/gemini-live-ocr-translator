package com.gemini.ocr.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class CancellableHttpCallTest {
    private class ManualCall : Call {
        lateinit var callback: Callback
        var cancellation: (() -> Unit)? = null
        private val cancelled = AtomicBoolean(false)
        private var executed = false
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/test").build()
        override fun execute(): Response = error("Blocking execute must never be used")
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun cancel() { cancelled.set(true); cancellation?.invoke() }
        override fun isCanceled(): Boolean = cancelled.get()
        override fun isExecuted(): Boolean = executed
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ManualCall()
        fun respond(body: ResponseBody, code: Int = 200) = callback.onResponse(this,
            Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build())
    }

    private class TrackingBody(private val readAction: (() -> Unit)? = null) : ResponseBody() {
        var closed = false
        private var emitted = false
        private val data = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (emitted) return -1
                readAction?.invoke()
                emitted = true
                sink.writeUtf8("body")
                return 4
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 4
        override fun source(): BufferedSource = data
    }

    @Test fun consumesAndClosesTheBodyBeforeReturningItsValue() = runBlocking {
        val call = ManualCall(); val body = TrackingBody()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
        call.respond(body)
        val result = pending.await()
        assertEquals(200, result.code); assertEquals("body", result.body)
        assertTrue(result.isSuccessful); assertTrue(body.closed)
        assertFalse(call.isCanceled())
    }

    @Test fun cancellationBeforeHeadersCancelsCallAndClosesAnyLateResponse() = runBlocking {
        val call = ManualCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
        pending.cancelAndJoin()
        assertTrue(call.isCanceled())
        val body = TrackingBody()
        call.respond(body)
        assertTrue(body.closed)
        assertTrue(pending.isCancelled)
    }

    @Test fun cancellationDuringBodyReadStillCancelsTheCall() = runBlocking {
        val call = ManualCall()
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        call.cancellation = { released.countDown() }
        val body = TrackingBody {
            entered.countDown()
            check(released.await(3, TimeUnit.SECONDS)) { "Cancellation did not release the body reader" }
            if (call.isCanceled()) throw IOException("cancelled body")
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
        val callback = thread(name = "fake-http-body") { call.respond(body) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertFalse("Headers alone must not finish awaitBody", pending.isCompleted)
        pending.cancelAndJoin()
        callback.join(3000)
        assertFalse(callback.isAlive)
        assertTrue(call.isCanceled()); assertTrue(body.closed)
    }

    @Test fun cancellationAfterTheCallbackBeforeDispatchCannotLeakTheResponse() = runBlocking {
        val call = ManualCall(); val body = TrackingBody()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
        call.respond(body)
        pending.cancelAndJoin()
        assertTrue(body.closed)
        assertTrue(pending.isCancelled)
    }

    @Test fun errorsPropagateWithoutBecomingAnEmptySuccess() = runBlocking {
        val call = ManualCall()
        // A supervised child lets the test inspect the original exception without cancelling runBlocking.
        kotlinx.coroutines.supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
            val failure = IOException("fake transport failed")
            call.callback.onFailure(call, failure)
            try { pending.await(); fail("Transport failure was swallowed") }
            catch (error: IOException) {
                // Coroutine stack recovery may copy IOException while preserving its cause.
                assertEquals(failure.message, error.message)
                assertTrue(error === failure || error.cause === failure)
            }
        }
    }

    @Test fun bodyReadFailureClosesTheResponse() = runBlocking {
        val call = ManualCall(); val body = TrackingBody { throw IOException("body failed") }
        kotlinx.coroutines.supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody() }
            call.respond(body)
            try { pending.await(); fail("Body failure was swallowed") }
            catch (error: IOException) { assertEquals("body failed", error.message) }
        }
        assertTrue(body.closed)
    }

    @Test fun realLoopbackOkHttpPreservesStatusAndBodyForExistingErrorPolicy() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("fake quota response"))
            server.start()
            val response = client.newCall(Request.Builder().url(server.url("/test")).build()).awaitBody()
            assertEquals(429, response.code)
            assertFalse(response.isSuccessful)
            assertEquals("fake quota response", response.body)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun deterministicMetricsSeparateHeadersAndBodyAndAreAttachedToTheResponse() = runBlocking {
        val now = AtomicLong(100)
        val events = mutableListOf<HttpCallMetrics>()
        val call = ManualCall()
        val body = TrackingBody {
            // Status and time to headers must be visible before any body is consumed.
            assertEquals(listOf("headers_received"), events.map { it.phase })
            assertEquals(429, events.single().statusCode)
            assertEquals(40L, events.single().headersMs)
            assertNull(events.single().bodyMs)
            now.set(175)
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody(events::add, now::get) }
        now.set(140)
        call.respond(body, 429)
        val response = pending.await()
        assertEquals(listOf("headers_received", "complete"), events.map { it.phase })
        assertEquals(HttpCallMetrics(75, 40, 35, 429, "complete"), response.metrics)
        assertEquals(events.last(), response.metrics)
        assertEquals("body", response.body)
        assertTrue(body.closed)
    }

    @Test fun bodyTimeoutRetainsStatusAndItsPhaseWithoutAnyExceptionMessage() = runBlocking {
        val now = AtomicLong(0)
        val events = mutableListOf<HttpCallMetrics>()
        val call = ManualCall()
        val body = TrackingBody {
            now.set(12_000)
            throw SocketTimeoutException("private diagnostic text must never enter metrics")
        }
        kotlinx.coroutines.supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody(events::add, now::get) }
            now.set(100)
            call.respond(body, 429)
            try { pending.await(); fail("Body failure was swallowed") }
            catch (_: SocketTimeoutException) { }
        }
        assertEquals(listOf("headers_received", "body_read"), events.map { it.phase })
        assertEquals(HttpCallMetrics(12_000, 100, 11_900, 429, "body_read", "SocketTimeoutException"), events.last())
        assertFalse(events.toString().contains("private diagnostic"))
        assertTrue(body.closed)
    }

    @Test fun failureWithoutHeadersHasNoInventedStatusOrBodyDuration() = runBlocking {
        val now = AtomicLong(100)
        val events = mutableListOf<HttpCallMetrics>()
        val call = ManualCall()
        kotlinx.coroutines.supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody(events::add, now::get) }
            now.set(6_100)
            call.callback.onFailure(call, SocketTimeoutException("sensitive transport text"))
            try { pending.await(); fail("Transport failure was swallowed") }
            catch (_: SocketTimeoutException) { }
        }
        assertEquals(listOf(HttpCallMetrics(6_000, null, null, null, "before_headers", "SocketTimeoutException")), events)
    }

    @Test fun observerFailureCannotChangeSuccessfulHttpOutcome() = runBlocking {
        val call = ManualCall(); val body = TrackingBody()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            call.awaitBody(observer = { throw AssertionError("broken observer") }, clockMs = { 0L })
        }
        call.respond(body)
        val response = pending.await()
        assertEquals("body", response.body)
        assertEquals(HttpCallMetrics(0, 0, 0, 200, "complete"), response.metrics)
        assertTrue(body.closed)
    }

    @Test fun observerFailureCannotReplaceTheOriginalTransportFailure() = runBlocking {
        val call = ManualCall()
        kotlinx.coroutines.supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                call.awaitBody(observer = { throw IllegalStateException("broken observer") }, clockMs = { 0L })
            }
            call.callback.onFailure(call, IOException("original transport failure"))
            try { pending.await(); fail("Transport failure was swallowed") }
            catch (error: IOException) { assertEquals("original transport failure", error.message) }
        }
    }

    @Test fun cancelledCallReportsOneTerminalEventAndClosesALateResponse() = runBlocking {
        val now = AtomicLong(100)
        val events = mutableListOf<HttpCallMetrics>()
        val call = ManualCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody(events::add, now::get) }
        now.set(150)
        pending.cancelAndJoin()
        // OkHttp may still invoke a callback after cancellation; it must not replace the terminal metric.
        call.callback.onFailure(call, IOException("cancelled"))
        val body = TrackingBody()
        call.respond(body)
        assertEquals(listOf(HttpCallMetrics(50, null, null, null, "cancelled_before_headers", "CancellationException")), events)
        assertTrue(call.isCanceled())
        assertTrue(body.closed)
    }

    @Test fun cancellationAtHeadersPreservesStatusAndSkipsBodyConsumption() = runBlocking {
        val now = AtomicLong(100)
        val events = mutableListOf<HttpCallMetrics>()
        val call = ManualCall()
        val body = TrackingBody { fail("A cancelled response must not be consumed") }
        lateinit var pending: kotlinx.coroutines.Deferred<BufferedHttpResponse>
        pending = async(start = CoroutineStart.UNDISPATCHED) {
            call.awaitBody(observer = {
                events.add(it)
                if (it.phase == "headers_received") { now.set(155); pending.cancel() }
            }, clockMs = now::get)
        }
        now.set(140)
        call.respond(body, 503)
        pending.cancelAndJoin()
        assertEquals(listOf("headers_received", "cancelled_body_read"), events.map { it.phase })
        assertEquals(HttpCallMetrics(55, 40, 15, 503, "cancelled_body_read", "CancellationException"), events.last())
        assertTrue(call.isCanceled())
        assertTrue(body.closed)
    }
}
