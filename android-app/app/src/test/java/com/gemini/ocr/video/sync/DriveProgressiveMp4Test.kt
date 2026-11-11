@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video.sync

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Actual Media3 container parsing and byte seeking on the generated SmartSourceLibraryNativeTest asset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DriveProgressiveMp4Test {
    @Test(timeout = 30_000L)
    fun extractorReadsVideoAndReopensItsSeekPointUsingOnlyBoundedRanges() {
        val fixture = File("src/androidTest/assets/stability-source.mp4").readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("d24dd173d25de9ff592538328cd8992440f96b311030188b35ff86e1067e4720", sha)
        val media = DriveMovieMedia("synthetic-mp4", fixture.size.toLong(), sha)
        val chunkBytes = 8 * 1024
        val ranges = CopyOnWriteArrayList<LongRange>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer fixture-token") return MockResponse().setResponseCode(401)
                    val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(request.getHeader("Range").orEmpty())
                        ?: return MockResponse().setResponseCode(400)
                    val first = match.groupValues[1].toLong()
                    val last = match.groupValues[2].toLong()
                    if (first !in 0..last || last >= fixture.size || last - first + 1 > chunkBytes)
                        return MockResponse().setResponseCode(416)
                    ranges.add(first..last)
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${fixture.size}")
                        .setBody(Buffer().write(fixture.copyOfRange(first.toInt(), last.toInt() + 1)))
                }
            }
            server.start()
            val source = DriveRangeDataSource.Factory(OkHttpClient(), server.url("/drive/v3/"), media,
                token = { "fixture-token" }, accountGuard = {}, chunkBytes = chunkBytes).createDataSource()
            val extractor = Mp4Extractor()
            try {
                fun openAt(position: Long): DefaultExtractorInput {
                    source.close()
                    val remaining = source.open(DataSpec.Builder().setUri(media.uri).setPosition(position).build())
                    assertEquals(media.size - position, remaining)
                    return DefaultExtractorInput(source, position, position + remaining)
                }
                var input = openAt(0)
                assertTrue(extractor.sniff(input))
                input.resetPeekPosition()
                var seekMap: SeekMap? = null
                var videoFormat: Format? = null
                val sampleTimes = mutableListOf<Long>()
                extractor.init(object : ExtractorOutput {
                    override fun endTracks() = Unit
                    override fun seekMap(map: SeekMap) { seekMap = map }
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type != C.TRACK_TYPE_VIDEO) return DummyTrackOutput()
                        return object : TrackOutput by DummyTrackOutput() {
                            override fun format(format: Format) { videoFormat = format }
                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                                cryptoData: TrackOutput.CryptoData?) { sampleTimes += timeUs }
                        }
                    }
                })
                val position = PositionHolder()
                fun readUntil(witness: () -> Boolean) {
                    repeat(500) {
                        if (witness()) return
                        when (extractor.read(input, position)) {
                            Extractor.RESULT_SEEK -> input = openAt(position.position)
                            Extractor.RESULT_END_OF_INPUT -> fail("MP4 ended before the expected video sample")
                        }
                    }
                    assertTrue("MP4 never produced the expected video sample", witness())
                }
                readUntil { seekMap != null && sampleTimes.isNotEmpty() }
                val map = requireNotNull(seekMap)
                assertTrue(map.isSeekable)
                assertEquals(12_000_000L, map.durationUs)
                assertTrue(videoFormat?.sampleMimeType?.startsWith("video/") == true)
                assertTrue("startup read less than the whole fixture", ranges.sumOf { it.last - it.first + 1 } < media.size)

                val seek = map.getSeekPoints(8_000_000L).first
                assertTrue(seek.position in 1 until media.size)
                val firstSeekRequest = ranges.size
                extractor.seek(seek.position, seek.timeUs)
                input = openAt(seek.position)
                sampleTimes.clear()
                readUntil { sampleTimes.any { it >= seek.timeUs + 500_000 } }
                assertEquals(seek.position, ranges[firstSeekRequest].first)
                assertEquals(seek.timeUs, sampleTimes.first())
                assertTrue(ranges.all { it.last - it.first + 1 <= chunkBytes })
            } finally {
                extractor.release()
                source.close()
            }
        }
    }
}
