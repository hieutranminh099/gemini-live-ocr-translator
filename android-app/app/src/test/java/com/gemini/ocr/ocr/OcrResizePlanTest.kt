package com.gemini.ocr.ocr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Geometry tests exercise the same target-size plan used by Bitmap.createScaledBitmap. */
class OcrResizePlanTest {
    @Test fun shallowTrackedRowsAreNeverStretchedOrUpscaled() {
        for ((width, height) in listOf(1080 to 70, 1920 to 60, 960 to 100, 1001 to 100)) {
            assertEquals(OcrProcessor.TargetSize(width, height), OcrProcessor.computeTargetSize(width, height))
            assertEquals(1f, OcrProcessor.computeDownscaleFactor(width, height), 0f)
        }
    }

    @Test fun crossingTheOldWidthThresholdDoesNotSuddenlyEnlargeTheRowHeight() {
        for (width in 959..1002) {
            val size = OcrProcessor.computeTargetSize(width, 100)
            assertEquals(width, size.width)
            assertEquals(100, size.height)
        }
    }

    @Test fun normalTallRoiRetainsTheExisting200PixelHeightTarget() {
        assertEquals(OcrProcessor.TargetSize(600, 200), OcrProcessor.computeTargetSize(1080, 360))
        assertEquals(OcrProcessor.TargetSize(960, 200), OcrProcessor.computeTargetSize(1920, 400))
    }

    @Test fun minimumHeightConstrainsBothAxesRatherThanClampingOneAxis() {
        val size = OcrProcessor.computeTargetSize(1920, 200)
        assertEquals(OcrProcessor.TargetSize(1536, 160), size)
        assertTrue("Width is a soft target when lowering it would harm a shallow row", size.width > 960)
        assertEquals(1920f / 200, size.width.toFloat() / size.height, .001f)
    }

    @Test fun bothRoundedDimensionsComeFromTheSameScale() {
        for ((width, height) in listOf(1080 to 240, 1701 to 193, 1920 to 271, 577 to 1080, 12 to 1920)) {
            val scale = OcrProcessor.computeDownscaleFactor(width, height)
            val size = OcrProcessor.computeTargetSize(width, height)
            assertTrue(scale > 0 && scale <= 1)
            if (scale < .96f) {
                assertTrue(abs(size.width - width * scale) <= .501f)
                assertTrue(abs(size.height - height * scale) <= .501f)
            }
            assertTrue(size.width in 1..width)
            assertTrue(size.height in 1..height)
        }
    }

    @Test fun fullResolutionOptionBypassesAllDownscaling() {
        assertEquals(OcrProcessor.TargetSize(1920, 400), OcrProcessor.computeTargetSize(1920, 400, false))
        assertEquals(OcrProcessor.TargetSize(1080, 70), OcrProcessor.computeTargetSize(1080, 70, false))
    }

    @Test fun minimumWidthIsNotAppliedIndependentlyToStretchTallNarrowInputs() {
        val size = OcrProcessor.computeTargetSize(12, 1920)
        assertEquals(OcrProcessor.TargetSize(1, 200), size)
        assertTrue(size.width < 16)
    }

    @Test fun invalidPlanDimensionsAreRejectedAndFactorCompatibilityIsPreserved() {
        assertEquals(1f, OcrProcessor.computeDownscaleFactor(0, 10), 0f)
        for ((width, height) in listOf(0 to 10, 10 to 0, -1 to 20, 20 to -1)) {
            assertThrows(IllegalArgumentException::class.java) { OcrProcessor.computeTargetSize(width, height) }
        }
    }
}
