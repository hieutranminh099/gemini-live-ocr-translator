package com.gemini.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackProgressTest {
    @Test fun launchPrerollDoesNotSeekAgainOrRelaxTheObservedStartGate() {
        org.junit.Assert.assertFalse(YouTubePlaybackProgress.needsComparisonSeek(924, 936))
        org.junit.Assert.assertFalse(YouTubePlaybackProgress.needsComparisonSeek(935, 936))
        org.junit.Assert.assertTrue(YouTubePlaybackProgress.needsComparisonSeek(940, 936))
        org.junit.Assert.assertTrue(YouTubePlaybackProgress.needsComparisonSeek(923, 936))
        assertNull(YouTubePlaybackProgress.verifiedObservationStart(930, 935, 936))
        assertEquals(937L, YouTubePlaybackProgress.verifiedObservationStart(935, 937, 936))
        try {
            YouTubePlaybackProgress.verifiedObservationStart(935, 940, 936)
            org.junit.Assert.fail("Pre-roll must not widen the original comparison gate")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun vietnameseClockExcludesTheTotalDuration() {
        assertEquals(960L, YouTubePlaybackProgress.elapsedSeconds("Đã phát 16 phút 0 giây trên 1 giờ 23 phút 5 giây"))
        assertEquals(961L, YouTubePlaybackProgress.elapsedSeconds("Đã phát 16 phút 1 giây trên 1 giờ 23 phút 5 giây"))
    }
    @Test fun englishClockSupportsHourBoundaries() {
        assertEquals(3601L, YouTubePlaybackProgress.elapsedSeconds("Played 1 hour 1 second of 2 hours"))
        assertEquals(59L, YouTubePlaybackProgress.elapsedSeconds("Played 59 seconds of 1 minute"))
    }
    @Test fun missingOrUnrecognizedClockDoesNotCountAsProgress() {
        assertNull(YouTubePlaybackProgress.elapsedSeconds(null))
        assertNull(YouTubePlaybackProgress.elapsedSeconds("Trình phát video"))
        assertNull(YouTubePlaybackProgress.elapsedSeconds("Đã phát trên 1 giờ"))
    }
    @Test fun disappearingControlsAreRetriedWithoutInventingClockProgress() {
        assertNull(YouTubePlaybackProgress.latestOrNull<String> { throw androidx.test.uiautomator.StaleObjectException() })
        assertEquals("fresh clock", YouTubePlaybackProgress.latestOrNull { "fresh clock" })
        try {
            YouTubePlaybackProgress.latestOrNull<String> { throw IllegalStateException("unrelated failure") }
            org.junit.Assert.fail("Only a stale accessibility snapshot may be retried")
        } catch (_: IllegalStateException) { }
    }
    @Test fun seekingUsesTheObservedDurationAndRejectsAnOutOfVideoTarget() {
        val description = "Đã phát 27 phút 28 giây trên 1 giờ 23 phút 5 giây"
        assertEquals(1648L, YouTubePlaybackProgress.elapsedSeconds(description))
        assertEquals(4985L, YouTubePlaybackProgress.durationSeconds(description))
        assertEquals(936.0 / 4985.0, YouTubePlaybackProgress.seekFraction(936, 4985), 0.000001)
        assertNull(YouTubePlaybackProgress.durationSeconds("Trình phát video"))
        try { YouTubePlaybackProgress.seekFraction(5000, 4985); org.junit.Assert.fail("Invalid seek") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun prerollWaitsForTheActualClockToCrossTheRequestedSegment() {
        assertEquals(924, YouTubePlaybackProgress.seekLeadInSeconds(936))
        assertEquals(0, YouTubePlaybackProgress.seekLeadInSeconds(5))
        assertNull(YouTubePlaybackProgress.verifiedObservationStart(924, 925, 936))
        assertNull(YouTubePlaybackProgress.verifiedObservationStart(936, 936, 936))
        assertEquals(937L, YouTubePlaybackProgress.verifiedObservationStart(936, 937, 936))
    }
    @Test fun successfulSeekInjectionCannotAuthorizeADifferentPlayingSegment() {
        try {
            YouTubePlaybackProgress.verifiedObservationStart(992, 993, 936)
            org.junit.Assert.fail("A different actual segment must not count as the requested comparison")
        } catch (_: IllegalArgumentException) { }
    }
}
