package com.gemini.ocr.asr

import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoSourceSelection
import org.junit.Assert.*
import org.junit.Test

class AsrPcmRingTest {
    @Test fun absolutePositionsSurviveWrapAndCopiesDoNotAliasDecoderInput() {
        val ring = AsrPcmRing(6)
        val input = floatArrayOf(0f, 1f, 2f, 3f)
        ring.append(input)
        input.fill(99f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), ring.copy(1, 4), 0f)
        ring.append(floatArrayOf(4f, 5f, 6f, 7f))
        assertEquals(8L, ring.totalSamples)
        assertEquals(2L, ring.firstSample)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f), ring.copy(2, 8), 0f)
        val retained = ring.copy(3, 6)
        ring.append(floatArrayOf(8f, 9f, 10f, 11f, 12f, 13f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), retained, 0f)
    }

    @Test fun anExpiredSpeechWindowCannotSilentlyReadUnrelatedNewAudio() {
        val ring = AsrPcmRing(3)
        ring.append(FloatArray(12) { it.toFloat() })
        assertEquals(9L, ring.firstSample)
        try { ring.copy(0, 3); fail("Expired audio must be rejected") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(floatArrayOf(9f, 10f, 11f), ring.copy(9, 12), 0f)
        ring.clear()
        try { ring.append(floatArrayOf(1f)); fail("Released audio must not be reused") } catch (_: IllegalStateException) { }
    }

    @Test fun speechWindowUsesAbsoluteSamplesWithoutChangingOrRoundingCuePositions() {
        val window = AsrSpeechWindow(16000L * 3600 + 17L, FloatArray(777))
        assertEquals(57600794L, window.endSample)
    }

    @Test fun paddedContextOverlapDoesNotMergeSeparateIdenticalUtterances() {
        val first = AsrSpeechWindow(0, FloatArray(28800), coreStartSample = 8000, coreEndSample = 24000)
        val second = AsrSpeechWindow(25600, FloatArray(28800), coreStartSample = 30400, coreEndSample = 49600)
        assertTrue("Context overlaps", first.endSample > second.startSample)
        assertTrue("Speech itself has a real400ms gap", first.coreEndSample < second.coreStartSample)
        assertFalse(first.isContinuation)
        assertFalse(second.isContinuation)
        val old = VideoCue("first", first.coreStartSample / 16, first.coreEndSample / 16, "谢谢", origin = "ASR")
        val next = VideoCue("second", second.coreStartSample / 16, second.coreEndSample / 16, "谢谢", origin = "ASR")
        val merged = VideoSourceSelection.merge(listOf(old), next)
        assertEquals(listOf(old, next), merged.first)
        assertFalse(merged.second)
    }
}
