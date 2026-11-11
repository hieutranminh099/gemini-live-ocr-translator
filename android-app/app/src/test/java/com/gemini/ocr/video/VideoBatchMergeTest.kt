package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchMergeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun part(durationUs: Long = 1_000_001L, bytes: Long = 500L): MergePart = MergePart(
        MergeTrackSignature("video/avc", listOf(listOf(1.toByte(), 2.toByte())), width = 1920, height = 1080,
            rotation = 90, frameRateMilli = 29_970),
        MergeTrackSignature("audio/mp4a-latm", listOf(listOf(3.toByte())), sampleRate = 48_000, channels = 2),
        durationUs, bytes
    )

    @Test fun offsetsRetainMicrosecondsAndConfirmedOrderWithoutFrameRounding() {
        val plan = planConcatenation(listOf(part(), part(2_000_003L, 700), part(900_007L, 300)))
        assertEquals(listOf(0L, 1_000_001L, 3_000_004L), plan.offsetsUs)
        assertEquals(3_900_011L, plan.durationUs)
        assertEquals(1500L, plan.sizeBytes)
    }

    @Test fun compatibleCodecBuffersCompareByValueAndSilentPartsRemainSilent() {
        val original = part()
        assertEquals(2_000_002L, planConcatenation(listOf(original, part())).durationUs)
        assertEquals(2_000_002L, planConcatenation(listOf(original.copy(audio = null), part().copy(audio = null))).durationUs)
    }

    @Test fun canonicalMediaDurationKeepsTheSameSharedBoundaryForBothStreams() {
        // Generated fixture: video lasts 12s, AAC lasts 11.989333s. The canonical timeline
        // selects the complete media boundary, not the first/audio track's shorter duration.
        val fixture = File("src/androidTest/assets/stability-source.mp4")
        assertEquals(12_000_000L, canonicalMergeDurationUs(fixture))
        val first = part(4_032_000L) // Encoded 4s picture with a 32ms AAC tail.
        val plan = planConcatenation(listOf(first, first, first))
        assertEquals(listOf(0L, 4_032_000L, 8_064_000L), plan.offsetsUs)
        assertEquals(12_096_000L, plan.durationUs)
    }

    @Test fun incompatiblePictureTimingCodecOrAudioCannotBeStreamCopied() {
        val original = part()
        val incompatible = listOf(
            original.copy(video = original.video.copy(width = 1080, height = 1920)),
            original.copy(video = original.video.copy(rotation = 0)),
            original.copy(video = original.video.copy(frameRateMilli = 30_000)),
            original.copy(video = original.video.copy(codecData = listOf(listOf(9.toByte())))),
            original.copy(video = original.video.copy(attributes = mapOf("color-transfer" to 6))),
            original.copy(audio = original.audio!!.copy(sampleRate = 44_100)),
            original.copy(audio = original.audio!!.copy(channels = 1)),
            original.copy(audio = original.audio!!.copy(codecData = listOf(listOf(8.toByte())))),
            original.copy(audio = null)
        )
        incompatible.forEach { candidate ->
            val failure = assertThrows(IllegalArgumentException::class.java) { planConcatenation(listOf(original, candidate)) }
            assertTrue(failure.message!!.contains("Phần 2"))
        }
    }

    @Test fun unknownFormatsAndDurationOverflowFailBeforeAnyOutput() {
        val original = part()
        listOf(original.copy(video = original.video.copy(frameRateMilli = 0)),
            original.copy(video = original.video.copy(mime = "video/hevc")),
            original.copy(video = original.video.copy(codecData = emptyList())),
            original.copy(durationUs = 0), original.copy(sizeBytes = 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { planConcatenation(listOf(it)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            planConcatenation(listOf(part(Long.MAX_VALUE), part()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            planConcatenation(listOf(part(bytes = Long.MAX_VALUE), part()))
        }
    }

    @Test fun liveAndPausePreflightNeverReadInputsOrLeavePartialFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(temporary.root, "joined.mp4")
        val missing = File(temporary.root, "missing.mp4")
        val previous = SessionState.running.value
        var gateCalls = 0
        try {
            SessionState.running.value = true
            assertThrows(VideoExporter.LivePreemptedException::class.java) {
                runBlocking { VideoExporter.concatenate(context, listOf(missing), output, gate = { gateCalls++ }) }
            }
            assertEquals(0, gateCalls)
            SessionState.running.value = false
            assertThrows(CancellationException::class.java) {
                runBlocking { VideoExporter.concatenate(context, listOf(missing), output, gate = { throw CancellationException("pause") }) }
            }
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { SessionState.running.value = previous }
    }

    @Test fun anExistingOutputAndSourceAreNeverOverwritten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = temporary.newFile("joined.mp4").apply { writeText("existing") }
        val input = temporary.newFile("part.mp4").apply { writeText("source") }
        val previous = SessionState.running.value
        try {
            SessionState.running.value = false
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { VideoExporter.concatenate(context, listOf(input), output) }
            }
            assertEquals("existing", output.readText())
            assertEquals("source", input.readText())
            assertEquals(2, temporary.root.listFiles()!!.size)
        } finally { SessionState.running.value = previous }
    }
}
