package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureCadenceTest {
    @Test fun turboSubtitleCadenceMatchesThe65msProducerInsteadOfTheOld100msTrace() {
        // With interval=150 and OCR=20, the previous source_changed trace claimed 100 ms.
        // The producer already waited 65 ms: tracing it must not silently slow capture down.
        val actual = period(interval = 150, turbo = true, ocrMs = 20)
        assertEquals(65L, actual)
        assertNotEquals(100L, actual)
    }

    @Test fun ordinarySubtitlesRetainThe100msCap() {
        assertEquals(100L, period(interval = 150, turbo = false))
        assertEquals(100L, period(interval = 500, turbo = false))
    }

    @Test fun shorterConfiguredIntervalsRemainBetweenTheFloorAndCap() {
        assertEquals(55L, period(interval = 55, turbo = true))
        assertEquals(80L, period(interval = 80, turbo = false))
        assertEquals(50L, period(interval = 1, turbo = true))
        assertEquals(75L, period(interval = 1, turbo = false))
    }

    @Test fun hintedCaptureMinimumSpacingIsIndependentOfThePeriodicCap() {
        assertEquals(50L, CaptureCadence.minSpacingMs(turbo = true))
        assertEquals(75L, CaptureCadence.minSpacingMs(turbo = false))
    }

    @Test fun standaloneTurboAlsoSpeedsUpFullscreenWithoutSubtitleFocus() {
        assertEquals(65L, period(subtitleFocus = false, standaloneActive = true, turbo = true))
        assertEquals(150L, period(subtitleFocus = false, standaloneActive = true, turbo = false))
    }

    @Test fun connectedFullscreenWithoutSubtitleFocusRetainsItsConfiguredInterval() {
        assertEquals(500L, period(interval = 500, subtitleFocus = false, standaloneActive = false, turbo = true))
        assertEquals(500L, period(interval = 500, subtitleFocus = false, standaloneActive = false, turbo = false))
    }

    @Test fun oneFrameModeDoesNotInheritTheAutomaticSubtitleOrStandaloneCap() {
        for (turbo in listOf(false, true)) {
            for (subtitleFocus in listOf(false, true)) {
                assertEquals(500L, period(interval = 500, subtitleFocus = subtitleFocus,
                    standaloneActive = true, turbo = turbo, oneFrame = true))
            }
        }
    }

    @Test fun slowOcrBacksOffAllCaptureModesUsingExistingIntegerRounding() {
        for (turbo in listOf(false, true)) {
            for (oneFrame in listOf(false, true)) {
                for (subtitleFocus in listOf(false, true)) {
                    assertEquals(601L, period(interval = 150, subtitleFocus = subtitleFocus,
                        standaloneActive = true, turbo = turbo, oneFrame = oneFrame, ocrMs = 401))
                }
            }
        }
        // A deliberately longer user interval is still respected when OCR is quicker.
        assertEquals(1000L, period(interval = 1000, subtitleFocus = false,
            standaloneActive = false, ocrMs = 401))
    }

    private fun period(
        interval: Long = 150,
        subtitleFocus: Boolean = true,
        standaloneActive: Boolean = false,
        turbo: Boolean = true,
        oneFrame: Boolean = false,
        ocrMs: Long = 20,
    ): Long = CaptureCadence.periodMs(interval, subtitleFocus, standaloneActive, turbo, oneFrame, ocrMs)
}
