package com.gemini.ocr

import android.graphics.PointF
import com.gemini.ocr.ocr.OcrRecoveryPolicy.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaddleOcrProbeTest {
    @Test fun rotatedQuadIsRoundedOutwardAndClippedToActualInput() {
        val points = listOf(PointF(-2.5f, 10.8f), PointF(80.1f, 8.2f), PointF(101f, 25.2f), PointF(0f, 31.2f))
        assertEquals(Bounds(0, 8, 100, 32), PaddleOcrProbe.mappedBounds(points, 100, 40))
    }
    @Test fun invalidAndEntirelyOffscreenQuadsCannotCreateSourceLines() {
        assertNull(PaddleOcrProbe.mappedBounds(List(4) { PointF(Float.NaN, 2f) }, 100, 40))
        assertNull(PaddleOcrProbe.mappedBounds(List(4) { PointF(120f, 2f) }, 100, 40))
        assertNull(PaddleOcrProbe.mappedBounds(listOf(PointF(1f, 1f)), 100, 40))
    }
}
