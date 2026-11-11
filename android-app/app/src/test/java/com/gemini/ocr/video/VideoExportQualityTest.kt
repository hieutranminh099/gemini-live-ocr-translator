package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoExportQualityTest {
    @Test fun bitrateRetainsHighDetailSourceHintRatherThanAlwaysUsingDefaultMediumMotionBudget() {
        assertEquals(8_000_000, videoQualityBitrate(1280, 720, 30f, 8_000_000))
        assertEquals(17_418_240, videoQualityBitrate(1920, 1080, 30f, 3_000_000))
    }

    @Test fun budgetScalesForSourceFpsWithoutChangingFrameRateAndUnknownMetadataIsBounded() {
        assertEquals(2 * videoQualityBitrate(1280, 720, 30f, 0), videoQualityBitrate(1280, 720, 60f, 0))
        assertEquals(videoQualityBitrate(1280, 720, 30f, 0), videoQualityBitrate(1280, 720, Float.NaN, -1))
        assertEquals(100_000_000, videoQualityBitrate(7680, 4320, 120f, Int.MAX_VALUE))
        assertEquals(1_000_000, videoQualityBitrate(320, 180, 24f, 0))
    }
}
