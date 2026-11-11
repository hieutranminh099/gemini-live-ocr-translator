package com.gemini.ocr

import android.graphics.Bitmap
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.ocr.OcrRecoveryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PerformancePipelineHooksTest {
    @Test fun directLruAndNativePassHooksCountCompletedOperations() = runBlocking {
        val telemetry = AndroidPerformanceTelemetry.counters
        StandaloneTranslator.clearDirectCache()
        telemetry.setEnabled(true)
        val bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        val processor = OcrProcessor { listOf(OcrRecoveryPolicy.Line("你好", .99,
            OcrRecoveryPolicy.Bounds(1, 1, 40, 30))) }
        try {
            StandaloneTranslator.putDirectLru("你好", "Xin chào", "test-only")
            assertEquals("Xin chào", StandaloneTranslator.lookupDirectLru("你好", "test-only"))
            assertNull(StandaloneTranslator.lookupDirectLru("再见", "test-only"))
            assertEquals(1, processor.process(bitmap, ocrPreCorrection = false).size)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
            telemetry.sampleIfDue()
            val sample = telemetry.snapshot.value
            assertEquals(PerformanceCacheMetric(1, 1), sample.caches[PerformanceCacheTier.DIRECT_LRU])
            assertEquals(1L, sample.frames[PerformanceFrameStage.OCR_COMPLETED]?.completed)
            assertEquals(0L, sample.frames[PerformanceFrameStage.CAPTURE_ACQUIRED]?.completed)
            assertEquals(0L, sample.frames[PerformanceFrameStage.CANVAS_DRAWN]?.completed)
        } finally {
            processor.close(); bitmap.recycle()
            StandaloneTranslator.clearDirectCache(); telemetry.setEnabled(false)
        }
        Unit
    }
}
