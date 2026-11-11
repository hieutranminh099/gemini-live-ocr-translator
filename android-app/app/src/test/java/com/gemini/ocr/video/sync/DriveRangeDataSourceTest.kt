@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video.sync

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Repeatable private HTTP fixtures; no Google login or personal video is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DriveRangeDataSourceTest {
    private lateinit var server: MockWebServer
    private val bytes = ByteArray(64) { it.toByte() }
    private val media = DriveMovieMedia("fixture-file", bytes.size.toLong(), "a".repeat(64))
    private val acquired = mutableListOf<String?>()
    private val waits = mutableListOf<Long>()
    private var account = "owner"
    @Volatile private var respond: ((RecordedRequest) -> MockResponse)? = null

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = respond?.invoke(request) ?: range(request)
        }
        server.start()
    }
    @After fun stop() { server.shutdown() }
    private fun range(request: RecordedRequest): MockResponse {
        val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(request.getHeader("Range").orEmpty())!!
        val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
        return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            .setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
    }
    private fun source(chunk: Int = 8): DataSource = DriveRangeDataSource.Factory(OkHttpClient(), server.url("/drive/v3/"), media,
        token = { rejected -> acquired += rejected; if (rejected == null) "expired-fixture-token" else "fresh-fixture-token" },
        accountGuard = { if (account != "owner") throw IOException("unsafe-provider-message") },
        chunkBytes = chunk, pause = { waits += it }).createDataSource()
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) = DataSpec.Builder().setUri(media.uri)
        .setPosition(position).apply { if (length != C.LENGTH_UNSET.toLong()) setLength(length) }.build()
    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(5)
        while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    private fun failure(expected: DrivePlaybackFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (e: DrivePlaybackException) {
            assertEquals(expected, e.failure); assertNull(e.cause)
            assertFalse(e.message.orEmpty().contains("fixture-token"))
            assertFalse(e.message.orEmpty().contains("unsafe-provider-message"))
        }
    }

    @Test fun boundedRangesDeliverExactBytesAndSeekWithoutDownloadingRemainder() {
        val reader = source()
        assertEquals(19L, reader.open(spec(3, 19)))
        assertArrayEquals(bytes.copyOfRange(3, 22), readAll(reader)); reader.close()
        assertEquals(3, server.requestCount)
        val requests = (1..3).map { server.takeRequest() }
        assertEquals(listOf("bytes=3-10", "bytes=11-18", "bytes=19-21"), requests.map { it.getHeader("Range") })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer expired-fixture-token" })
        assertTrue(requests.all { !it.path.orEmpty().contains("token") && it.requestUrl!!.queryParameter("alt") == "media" })
        assertEquals(listOf<String?>(null), acquired)
        assertEquals(4L, reader.open(spec(60)))
        assertArrayEquals(bytes.copyOfRange(60, 64), readAll(reader)); reader.close()
        assertEquals("bytes=60-63", server.takeRequest().getHeader("Range"))
        assertEquals(listOf<String?>(null), acquired)
    }

    @Test fun openingAtEndReturnsEofAndOutOfBoundsSeekCannotRequestNetwork() {
        val reader = source()
        assertEquals(0L, reader.open(spec(64))); assertEquals(-1, reader.read(ByteArray(1), 0, 1)); reader.close()
        failure(DrivePlaybackFailure.RANGE) { reader.open(spec(65)) }
        assertEquals(0, server.requestCount); assertTrue(acquired.isEmpty())
    }

    @Test fun rejectedTokenIsRefreshedExactlyOnceAndNeverSentInAUrl() {
        respond = { request -> if (request.getHeader("Authorization") == "Bearer expired-fixture-token")
            MockResponse().setResponseCode(401).setBody("unsafe-provider-message") else range(request) }
        val reader = source(); reader.open(spec(8, 8))
        assertArrayEquals(bytes.copyOfRange(8, 16), readAll(reader)); reader.close()
        assertEquals(listOf(null, "expired-fixture-token"), acquired)
        assertEquals(2, server.requestCount)
        repeat(2) { assertFalse(server.takeRequest().path.orEmpty().contains("token")) }
    }

    @Test fun repeatedUnauthorizedStopsAfterOneRefreshWithASafeError() {
        respond = { MockResponse().setResponseCode(401).setBody("expired-fixture-token unsafe-provider-message") }
        failure(DrivePlaybackFailure.AUTH) { source().open(spec()) }
        assertEquals(2, server.requestCount); assertEquals(2, acquired.size); assertTrue(waits.isEmpty())
    }

    @Test fun forbiddenMissingAndUnsatisfiableRangesAreNeverRetried() {
        for ((code, kind) in listOf(403 to DrivePlaybackFailure.FORBIDDEN, 404 to DrivePlaybackFailure.MISSING,
            416 to DrivePlaybackFailure.RANGE)) {
            val before = server.requestCount
            respond = { MockResponse().setResponseCode(code).setBody("unsafe-provider-message") }
            failure(kind) { source().open(spec()) }
            assertEquals(before + 1, server.requestCount)
        }
        assertTrue(waits.isEmpty())
    }

    @Test fun fullResponseWrongRangeWrongSizeAndOversizedBodyAreRejected() {
        val invalid = listOf(
            { MockResponse().setBody(Buffer().write(bytes)) },
            { MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-8/64").setBody(Buffer().write(bytes.copyOf(8))) },
            { MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-7/65").setBody(Buffer().write(bytes.copyOf(8))) },
            { MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-7/64").setBody(Buffer().write(bytes.copyOf(9))) }
        )
        for (response in invalid) {
            val before = server.requestCount; respond = { response() }
            failure(DrivePlaybackFailure.RANGE) { source().open(spec()) }
            assertEquals(before + 1, server.requestCount)
        }
        assertTrue(waits.isEmpty())
    }

    @Test fun quotaBackoffHonorsRetryAfterAndStopsAfterThreeAttempts() {
        respond = { MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("unsafe-provider-message") }
        failure(DrivePlaybackFailure.QUOTA) { source().open(spec()) }
        assertEquals(3, server.requestCount); assertEquals(listOf(3000L, 3000L), waits)
    }

    @Test fun quotaNeverRetriesBeforeTheServersLongerWait() {
        for ((index, retryAfter) in listOf("999999", "Fri, 01 Jan 2100 00:00:00 GMT").withIndex()) {
            respond = { MockResponse().setResponseCode(429).setHeader("Retry-After", retryAfter) }
            failure(DrivePlaybackFailure.QUOTA) { source().open(spec()) }
            assertEquals(index + 1, server.requestCount)
        }
        assertTrue(waits.isEmpty())
    }

    @Test fun transientQuotaCanRecoverWithoutRepeatingDeliveredBytes() {
        var failures = 0
        respond = { request -> if (failures++ < 2) MockResponse().setResponseCode(429) else range(request) }
        val reader = source(); reader.open(spec(24, 8))
        assertArrayEquals(bytes.copyOfRange(24, 32), readAll(reader)); reader.close()
        assertEquals(3, server.requestCount); assertEquals(listOf(250L, 500L), waits)
    }

    @Test fun networkDisconnectUsesAFiniteRetryBudget() {
        respond = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        failure(DrivePlaybackFailure.NETWORK) { source().open(spec()) }
        assertEquals(3, server.requestCount); assertEquals(listOf(250L, 500L), waits)
    }

    @Test fun wrongAccountAndLogoutCannotDeliverEvenAlreadyBufferedBytes() {
        account = "other"
        failure(DrivePlaybackFailure.ACCOUNT) { source().open(spec()) }
        assertEquals(0, server.requestCount); assertTrue(acquired.isEmpty())
        account = "owner"
        val reader = source(); reader.open(spec())
        val output = ByteArray(8) { 99 }; account = "logged-out"
        failure(DrivePlaybackFailure.ACCOUNT) { reader.read(output, 0, output.size) }
        assertArrayEquals(ByteArray(8) { 99 }, output); assertEquals(1, server.requestCount)
        assertNull(reader.uri)
    }
}
