package com.gemini.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gemini.ocr.capture.PipelineTrace
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native line fixtures exercise the real filtering, recrop, bitmap ownership and return API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrObservationTest {
    private val source = "快快快进来进来"
    private fun line(confidence: Double = .2, text: String = source,
        bounds: OcrRecoveryPolicy.Bounds? = OcrRecoveryPolicy.Bounds(100, 30, 300, 60)) =
        OcrRecoveryPolicy.Line(text, confidence, bounds)

    @After fun cleanup() { PipelineTrace.observerForTest = null }

    @Test fun confidenceRejectedVisibleSubtitleRecoversOnceAndMapsItsBoxToTheOriginalImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        val seen = mutableListOf<Bitmap>(); val trace = mutableListOf<JSONObject>()
        PipelineTrace.observerForTest = { trace += JSONObject(it) }
        val processor = OcrProcessor { input ->
            seen += input
            if (seen.size == 1) listOf(line()) else {
                assertEquals(424, input.width); assertEquals(84, input.height)
                listOf(line(.8, bounds = OcrRecoveryPolicy.Bounds(12, 12, 412, 72)))
            }
        }
        val observation = processor.processObservation(bitmap, ocrPreCorrection = false, traceFrameId = 337)
        assertFalse(observation.uncertain); assertEquals(2, observation.nativePasses)
        assertEquals(source, observation.blocks.single().text)
        assertEquals(Rect(100, 30, 300, 60), observation.blocks.single().rect)
        assertEquals(2, seen.size); assertFalse(bitmap.isRecycled); assertTrue(seen.last().isRecycled)
        assertEquals(listOf(1, 2), trace.filter { it.getString("event") == "ocr_native_completed" }
            .map { it.getInt("native_pass") })
        assertTrue(trace.any { it.optString("recovery_reason") == "recovered_han" })
        assertFalse(trace.joinToString().contains(source))
        processor.close(); bitmap.recycle()
    }

    @Test fun nativeDownscaleAndRecoveryRecropComposeTheirCoordinateTransforms() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { input ->
            calls++
            if (calls == 1) {
                assertEquals(600, input.width); assertEquals(200, input.height)
                listOf(line(bounds = OcrRecoveryPolicy.Bounds(50, 20, 150, 40)))
            } else {
                assertEquals(432, input.width); assertEquals(112, input.height)
                listOf(line(.9, bounds = OcrRecoveryPolicy.Bounds(16, 16, 416, 96)))
            }
        }
        assertEquals(Rect(100, 40, 300, 80), processor.processObservation(bitmap,
            ocrPreCorrection = false).blocks.single().rect)
        assertEquals(2, calls); bitmap.recycle()
    }

    @Test fun anotherLowConfidenceResultIsUncertainAndNeverCausesAThirdPass() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; listOf(line(.1)) }
        val result = processor.processObservation(bitmap, ocrPreCorrection = false)
        assertTrue(result.blocks.isEmpty()); assertTrue(result.uncertain)
        assertEquals(2, calls); assertEquals(2, result.nativePasses)
        bitmap.recycle()
    }

    @Test fun anEmptyRecoveryOrHighConfidenceVietnameseDoesNotBecomeAChineseSource() = runBlocking {
        for (retry in listOf(emptyList(), listOf(line(.99, "Giúp tôi với")), listOf(line(Double.NaN)))) {
            val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
            var calls = 0
            val processor = OcrProcessor { if (++calls == 1) listOf(line()) else retry }
            val result = processor.processObservation(bitmap, ocrPreCorrection = false)
            assertTrue(result.uncertain); assertTrue(result.blocks.isEmpty()); assertEquals(2, calls)
            bitmap.recycle()
        }
    }

    @Test fun aTrueNativeBlankIsConfirmedWithoutRetry() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; emptyList() }
        val result = processor.processObservation(bitmap, ocrPreCorrection = false)
        assertFalse(result.uncertain); assertTrue(result.blocks.isEmpty())
        assertEquals(1, result.nativePasses); assertEquals(1, calls)
        bitmap.recycle()
    }

    @Test fun reliableNativeTextPreservesTheListApiAndDoesNotRetry() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; listOf(line(.8)) }
        assertEquals(source, processor.process(bitmap, ocrPreCorrection = false).single().text)
        assertEquals(1, calls); bitmap.recycle()
    }

    @Test fun tooLargeRecoveryStaysUncertainWithoutAllocatingAnotherBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(3000, 500, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; listOf(line(bounds = OcrRecoveryPolicy.Bounds(0, 0, 3000, 500))) }
        val result = processor.processObservation(bitmap, fastDownscale = false, ocrPreCorrection = false)
        assertTrue(result.uncertain); assertEquals("pixel_bound", result.recoveryReason)
        assertEquals(1, calls); bitmap.recycle()
    }

    @Test fun retryNativeFailureRetainsUncertaintyAndRecyclesItsTemporaryBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0; var retryBitmap: Bitmap? = null
        val processor = OcrProcessor { input ->
            if (++calls == 1) listOf(line()) else { retryBitmap = input; error("fake native failure") }
        }
        val result = processor.processObservation(bitmap, ocrPreCorrection = false)
        assertTrue(result.uncertain); assertEquals("native_failure", result.recoveryReason)
        assertEquals(2, calls); assertTrue(retryBitmap!!.isRecycled); assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test fun subtitlePolicyRecoversLowConfidenceHanEvenWhenTheHardVietnameseLineWasAccepted() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { input ->
            if (++calls == 1) listOf(line(), line(.99, "Giúp tôi với",
                OcrRecoveryPolicy.Bounds(100, 80, 300, 110))) else {
                assertEquals(424, input.width); assertEquals(84, input.height)
                listOf(line(.9, bounds = OcrRecoveryPolicy.Bounds(12, 12, 412, 72)))
            }
        }
        val result = processor.processObservation(bitmap, ocrPreCorrection = false, enforceSubtitlePolicy = true)
        assertFalse(result.uncertain); assertEquals(source, result.blocks.single().text)
        assertEquals(Rect(100, 30, 300, 60), result.blocks.single().rect)
        assertEquals(2, calls); bitmap.recycle()
    }

    @Test fun nonSubtitleListApiDoesNotExpandTheMixedLanguageRecoveryPolicy() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; listOf(line(), line(.99, "Giúp tôi với")) }
        assertEquals("Giúp tôi với", processor.process(bitmap, ocrPreCorrection = false).single().text)
        assertEquals(1, calls); bitmap.recycle()
    }

    @Test fun anAcceptedVietnameseLineAloneIsNotEvidenceForAHanRecoveryCrop() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888)
        var calls = 0
        val processor = OcrProcessor { calls++; listOf(line(.99, "Giúp tôi với")) }
        val result = processor.processObservation(bitmap, ocrPreCorrection = false, enforceSubtitlePolicy = true)
        assertFalse(result.uncertain); assertEquals("Giúp tôi với", result.blocks.single().text)
        assertEquals(1, calls); bitmap.recycle()
    }
}
