package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class OcrFrameFingerprintTest {
    private val full = OcrFrameFingerprint(1, 123L, 100, 400, 1500, 800)
    private val narrow = OcrFrameFingerprint(2, 456L, 100, 480, 1500, 550)

    @Test fun newerDifferentPixelsInIdenticalRegionStillDiscardOldOcr() {
        assertTrue(full.supersededByPixelChange(full.copy(frameId = 2, crc = 999)))
        assertTrue(narrow.supersededByPixelChange(narrow.copy(frameId = 3, crc = 999)))
    }

    @Test fun periodicFullRegionReacquisitionSurvivesNewerNarrowCapture() {
        // Identical source text produces different CRCs when image dimensions differ.
        // Discarding the full OCR result here prevents observing relocated/second source rows.
        assertFalse(full.supersededByPixelChange(narrow))
        assertFalse(narrow.supersededByPixelChange(full.copy(frameId = 3)))
    }

    @Test fun translatedRectangleIsNotComparableEvenWithIdenticalDimensions() {
        assertFalse(narrow.supersededByPixelChange(narrow.copy(frameId = 3, crc = 999, top = 550, bottom = 620)))
        assertFalse(narrow.supersededByPixelChange(narrow.copy(frameId = 3, crc = 999, left = 200, right = 1600)))
    }

    @Test fun samePixelsOlderFrameAndMissingSnapshotDoNotDiscard() {
        assertFalse(full.supersededByPixelChange(full.copy(frameId = 2)))
        assertFalse(full.supersededByPixelChange(full.copy(crc = 999)))
        assertFalse(full.supersededByPixelChange(full.copy(frameId = 0, crc = 999)))
        assertFalse(full.supersededByPixelChange(null))
    }
}
