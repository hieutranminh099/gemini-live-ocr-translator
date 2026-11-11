package com.gemini.ocr.capture

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SubtitleFrameDifferRegressionTest {
    private val width = 160
    private val height = 80

    private fun blank() = ByteBuffer.allocateDirect(width * height * 4)

    private fun paintWhite(buffer: ByteBuffer, x: Int, y: Int) {
        val offset = (y * width + x) * 4
        repeat(3) { buffer.put(offset + it, 0xff.toByte()) }
    }

    @Test fun newGlyphBetweenSamplePointsReachesOcrImmediately() {
        val previous = blank()
        val subtitle = blank()
        // A small cross models a new Han glyph stroke between all old 16 x 8 sample points.
        for (x in 11..19) paintWhite(subtitle, x, 11)
        for (y in 7..14) paintWhite(subtitle, 13, y)
        val oldSample = SubtitleRoiCropper.sampleRgbaBuffer(previous, width, height)
        val newSample = SubtitleRoiCropper.sampleRgbaBuffer(subtitle, width, height)
        assertArrayEquals("Sparse samples miss the visible new strokes", oldSample, newSample)
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(previous, width, height, 1000))
        assertFalse("A new source must reach OCR before the 300ms fallback", differ.shouldSkipOcr(subtitle, width, height, 1065))
        assertTrue(differ.shouldSkipOcr(subtitle, width, height, 1130))
    }

    @Test fun changedSamplePointCannotDisappearInMeanDelta() {
        val previous = blank()
        val subtitle = blank()
        paintWhite(subtitle, 5, 5) // Exactly the first old sample point.
        val delta = SubtitleRoiCropper.meanPixelDelta(
            SubtitleRoiCropper.sampleRgbaBuffer(previous, width, height),
            SubtitleRoiCropper.sampleRgbaBuffer(subtitle, width, height)
        )
        assertTrue(delta > 0 && delta < SubtitleRoiCropper.DEFAULT_PIXEL_DIFF_THRESHOLD)
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(previous, width, height, 1000))
        assertFalse(differ.shouldSkipOcr(subtitle, width, height, 1065))
    }

    @Test fun unchangedFrameExpiresAtWindowBoundaryWithoutExtendingDeadline() {
        val pixels = blank()
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1000))
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1065))
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1260))
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1300))
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1301))
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1400))
    }

    @Test fun equalAreaWithDifferentShapeRequiresOcr() {
        val pixels = blank()
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1000))
        assertFalse(differ.shouldSkipOcr(pixels, height, width, 1065))
        assertTrue(differ.shouldSkipOcr(pixels, height, width, 1130))
    }

    @Test fun clockRollbackAndResetRequireFreshOcr() {
        val pixels = blank()
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1000))
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 999))
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1001))
        differ.reset()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1002))
    }

    @Test fun fingerprintLeavesCallerBufferPositionAndLimitUntouched() {
        val byteCount = width * height * 4
        val pixels = ByteBuffer.allocateDirect(byteCount + 16)
        pixels.position(7)
        pixels.limit(byteCount + 8)
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1000))
        assertEquals(7, pixels.position())
        assertEquals(byteCount + 8, pixels.limit())
        pixels.put(byteCount + 1, 0xff.toByte()) // Bytes outside the tight ROI are irrelevant.
        assertTrue(differ.shouldSkipOcr(pixels, width, height, 1065))
    }

    @Test fun truncatedBufferCannotSkipOcrOrReusePreviousFingerprint() {
        val pixels = blank()
        val differ = SubtitleFrameDiffer()
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1000))
        val truncated = pixels.duplicate().apply { limit(limit() - 1) }
        assertFalse(differ.shouldSkipOcr(truncated, width, height, 1065))
        assertFalse(differ.shouldSkipOcr(pixels, width, height, 1130))
    }
}
