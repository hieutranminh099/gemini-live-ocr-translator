package com.paddle.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPreprocessProfileTest {
    private val v4 = OcrPreprocessProfile.RAPIDOCR_V4
    private val tiny = OcrPreprocessProfile.PP_OCR_V6_TINY
    private fun crop(width: Int, height: Int) = OcrPreprocessProfile.CropSize(width, height)

    @Test fun defaultConfigurationKeepsFrozenTinyProfileAndBgrDetectorInput() {
        val config = PaddleOCRConfig()
        assertEquals(tiny, config.modelProfile)
        assertEquals("BGR", config.detImgMode)
        assertEquals(listOf(.485, .456, .406), tiny.detMean)
        assertEquals(listOf(.229, .224, .225), tiny.detStd)
        assertTrue(tiny.recognitionUsesRgb)
    }

    @Test fun v4DetectorNormalizesEachBgrChannelToMinusOneThroughOne() {
        assertEquals(listOf(.5, .5, .5), v4.detMean)
        assertEquals(listOf(.5, .5, .5), v4.detStd)
        for (channel in 0..2) {
            assertEquals(-1.0, v4.normalizedDetectorSample(channel, 0), 1e-12)
            assertEquals(1.0, v4.normalizedDetectorSample(channel, 255), 1e-12)
            assertEquals(1.0 / 255, v4.normalizedDetectorSample(channel, 128), 1e-12)
        }
        assertFalse(v4.recognitionUsesRgb)
    }

    @Test fun tinyNormalizationRetainsItsExistingDistinctChannelConstants() {
        assertEquals(2.2489082969432315, tiny.normalizedDetectorSample(0, 255), 1e-12)
        assertEquals(-2.0357142857142856, tiny.normalizedDetectorSample(1, 0), 1e-12)
        assertEquals(-1.8044444444444445, tiny.normalizedDetectorSample(2, 0), 1e-12)
    }

    @Test fun unsupportedNormalizationArgumentsFailBeforeIndexing() {
        assertThrows(IllegalArgumentException::class.java) { v4.normalizedDetectorSample(3, 0) }
        assertThrows(IllegalArgumentException::class.java) { v4.normalizedDetectorSample(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { v4.normalizedDetectorSample(0, 256) }
    }

    @Test fun v4PadsShortSingleLineTo320WhileTinyKeepsNaturalWidth() {
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(96), 320), v4.recognitionPlan(listOf(crop(100, 50))))
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(96), 96), tiny.recognitionPlan(listOf(crop(100, 50))))
    }

    @Test fun v4PerBatchWidthMatchesRapidFloorWhileEachResizeUsesCeilThenClamp() {
        val crops = listOf(crop(401, 50), crop(100, 50))
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(384, 96), 384), v4.recognitionPlan(crops))
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(385, 96), 385), tiny.recognitionPlan(crops))
    }

    @Test fun v4BatchPlanPreservesInputOrderEvenWithDifferentAspectRatios() {
        val crops = listOf(crop(100, 50), crop(401, 50), crop(200, 50))
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(96, 384, 192), 384), v4.recognitionPlan(crops))
    }

    @Test fun v4Exact320BoundaryAndLongTextDoNotAddUnrequestedPadding() {
        assertEquals(320, v4.recognitionPlan(listOf(crop(320, 48))).tensorWidth)
        assertEquals(640, v4.recognitionPlan(listOf(crop(640, 48))).tensorWidth)
        assertEquals(3200, v4.recognitionPlan(listOf(crop(3200, 48))).tensorWidth)
    }

    @Test fun tinyRetainsItsWidthClampButV4FailsClosedInsteadOfChangingItsContract() {
        assertEquals(OcrPreprocessProfile.RecognitionPlan(listOf(3200), 3200), tiny.recognitionPlan(listOf(crop(4000, 48))))
        assertThrows(IllegalArgumentException::class.java) { v4.recognitionPlan(listOf(crop(4000, 48))) }
        assertThrows(IllegalArgumentException::class.java) { v4.recognitionPlan(listOf(crop(Int.MAX_VALUE, 1))) }
    }

    @Test fun invalidGeometryAndEmptyBatchFailBeforeCreatingNativeMats() {
        assertThrows(IllegalArgumentException::class.java) { crop(0, 50) }
        assertThrows(IllegalArgumentException::class.java) { crop(50, -1) }
        assertThrows(IllegalArgumentException::class.java) { tiny.recognitionPlan(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { tiny.recognitionPlan(listOf(crop(10, 10)), 0) }
    }

    @Test fun v4CannotBeAccidentallyConfiguredWithAnRgbDetector() {
        assertThrows(IllegalArgumentException::class.java) {
            PaddleOCRConfig(detImgMode = "RGB", modelProfile = v4)
        }
        assertEquals(v4, PaddleOCRConfig(detImgMode = "BGR", modelProfile = v4).modelProfile)
    }
}
