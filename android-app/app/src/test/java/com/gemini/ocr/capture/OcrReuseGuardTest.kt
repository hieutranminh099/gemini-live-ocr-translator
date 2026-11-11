package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class OcrReuseGuardTest {
    private val frame = OcrReuseGuard.Stamp(2, "bottom-roi", 4, "source-A", 1600, 900,
        OcrReuseGuard.Bounds(100, 600, 1500, 850), 123456, 1000, 980)

    private fun guard() = OcrReuseGuard().apply { assertTrue(recordAcceptedOcr(frame)) }

    @Test fun identicalRoiFromNewHardwareCanReuseAcceptedNativeOcr() {
        assertTrue(guard().canReuse(frame.copy(acquiredAtMs = 1100, sourceAtMs = 1080)))
    }

    @Test fun unchangedStaticHardwareCanReuseWithinTheSameGenerationWithoutAResponse() {
        assertTrue(guard().canReuse(frame.copy(acquiredAtMs = 1100)))
    }

    @Test fun newSubtitlePixelsAlwaysRequireNativeOcr() {
        assertFalse(guard().canReuse(frame.copy(crc32 = frame.crc32 + 1, acquiredAtMs = 1100, sourceAtMs = 1080)))
    }

    @Test fun equalSizedCropShiftIsNotTheSameImageCoordinates() {
        assertFalse(guard().canReuse(frame.copy(crop = OcrReuseGuard.Bounds(101, 600, 1501, 850))))
        assertFalse(guard().canReuse(frame.copy(crop = OcrReuseGuard.Bounds(100, 599, 1500, 849))))
    }

    @Test fun fullFrameDimensionChangesInvalidateEvenAnOtherwiseIdenticalCrop() {
        assertFalse(guard().canReuse(frame.copy(frameWidth = 1700)))
        assertFalse(guard().canReuse(frame.copy(frameHeight = 1000)))
    }

    @Test fun readerEpochRegionGenerationAndSourceIdentityMustAllMatch() {
        for (different in listOf(frame.copy(captureEpoch = 3), frame.copy(regionId = "other-roi"),
            frame.copy(screenGeneration = 5), frame.copy(sourceIdentity = "source-B"))) {
            assertFalse(guard().canReuse(different))
        }
    }

    @Test fun aLaterSnapshotOfOldHardwareDoesNotConfirmANetworkResponse() {
        val laterSnapshot = frame.copy(acquiredAtMs = 1500, sourceAtMs = 980)
        assertFalse(guard().canReuse(laterSnapshot, responseAtMs = 1200))
    }

    @Test fun freshIdenticalHardwareCanConfirmResponseAtItsTimestampBoundary() {
        val fresh = frame.copy(acquiredAtMs = 1250, sourceAtMs = 1200)
        assertTrue(guard().canReuse(fresh, responseAtMs = 1200))
        assertFalse(guard().canReuse(fresh, responseAtMs = 1201))
    }

    @Test fun returningToAFormerSentenceCannotReuseItsFormerOccurrence() {
        // A -> B -> A has the same text and pixels but a different screen generation.
        val returningA = frame.copy(screenGeneration = 6, acquiredAtMs = 2000, sourceAtMs = 1980)
        assertFalse(guard().canReuse(returningA))
    }

    @Test fun resetAndEmptyAcceptedOcrRemoveThePreviousSentenceReusePermission() {
        val guard = guard()
        guard.clear()
        assertFalse(guard.canReuse(frame))
        assertTrue(guard.recordAcceptedOcr(frame))
        assertTrue(guard.recordAcceptedOcr(frame.copy(sourceIdentity = "")))
        assertFalse(guard.canReuse(frame))
    }

    @Test fun knownBlankRequiresOptInAndCanNeverConfirmATranslationResponse() {
        val blank = frame.copy(sourceIdentity = "")
        val guard = OcrReuseGuard()
        assertTrue(guard.recordAcceptedOcr(blank))
        val fresh = blank.copy(acquiredAtMs = 1250, sourceAtMs = 1200)
        assertFalse(guard.canReuse(fresh))
        assertTrue(guard.canReuse(fresh, allowEmpty = true))
        assertFalse(guard.canReuse(fresh, responseAtMs = 1200, allowEmpty = true))
        assertFalse(guard.canReuse(fresh.copy(crc32 = fresh.crc32 + 1), allowEmpty = true))
    }

    @Test fun pausedSubtitleWithAnInitialEmptyRecognitionRequiresNativeRetryAt300Ms() {
        val blank = frame.copy(sourceIdentity = "")
        val guard = OcrReuseGuard()
        assertTrue(guard.recordAcceptedOcr(blank))
        // The hardware pixels remain completely unchanged while the player is paused.
        assertTrue(guard.canReuse(blank.copy(acquiredAtMs = 1299), allowEmpty = true))
        assertFalse(guard.canReuse(blank.copy(acquiredAtMs = 1300), allowEmpty = true))
        assertFalse(guard.canReuse(blank.copy(acquiredAtMs = 2000), allowEmpty = true))
        // A fresh native retry may now find the visible short Chinese subtitle.
        val recognized = blank.copy(sourceIdentity = "帮帮我", acquiredAtMs = 1300)
        assertTrue(guard.recordAcceptedOcr(recognized))
        assertTrue(guard.canReuse(recognized.copy(acquiredAtMs = 1400)))
    }

    @Test fun repeatedChecksAndNewHardwareDoNotExtendAnEmptyRecognitionDeadline() {
        val blank = frame.copy(sourceIdentity = "")
        val guard = OcrReuseGuard()
        assertTrue(guard.recordAcceptedOcr(blank))
        for (time in listOf(1050L, 1100L, 1200L, 1299L)) {
            assertTrue(guard.canReuse(blank.copy(acquiredAtMs = time, sourceAtMs = time), allowEmpty = true))
        }
        assertFalse(guard.canReuse(blank.copy(acquiredAtMs = 1300, sourceAtMs = 1300), allowEmpty = true))
        assertFalse(guard.canReuse(blank.copy(acquiredAtMs = 5000, sourceAtMs = 5000), allowEmpty = true))
    }

    @Test fun onlyANewAcceptedNativeRecognitionStartsAnotherEmptyReuseWindow() {
        val blank = frame.copy(sourceIdentity = "")
        val guard = OcrReuseGuard()
        assertTrue(guard.recordAcceptedOcr(blank))
        val retry = blank.copy(acquiredAtMs = 1300, sourceAtMs = 1280)
        assertFalse(guard.canReuse(retry, allowEmpty = true))
        assertTrue(guard.recordAcceptedOcr(retry))
        assertTrue(guard.canReuse(retry.copy(acquiredAtMs = 1599), allowEmpty = true))
        assertFalse(guard.canReuse(retry.copy(acquiredAtMs = 1600), allowEmpty = true))
    }

    @Test fun nonemptyIdenticalPixelsStillReuseAfterTheBlankRetryDeadline() {
        val guard = guard()
        val fresh = frame.copy(acquiredAtMs = 5000, sourceAtMs = 4980)
        assertTrue(guard.canReuse(fresh))
        assertTrue(guard.canReuse(fresh, responseAtMs = 4980))
        assertFalse(guard.canReuse(fresh, responseAtMs = 4981))
    }

    @Test fun malformedOrOlderHardwareCannotBeReused() {
        val guard = guard()
        for (invalid in listOf(frame.copy(sourceAtMs = 1001), frame.copy(sourceAtMs = -1),
            frame.copy(acquiredAtMs = 999), frame.copy(sourceAtMs = 979), frame.copy(crc32 = -1),
            frame.copy(crop = OcrReuseGuard.Bounds(0, 0, 0, 0)))) {
            assertFalse(guard.canReuse(invalid))
        }
        assertFalse(guard.canReuse(frame, responseAtMs = -1))
    }

    @Test fun rejectedCandidateDoesNotBecomeTheAcceptedNativeReference() {
        val guard = guard()
        val obsolete = frame.copy(captureEpoch = 1, crc32 = 999)
        assertFalse(guard.canReuse(obsolete))
        // Merely checking a discarded/queued frame must not publish it as the reference.
        assertTrue(guard.canReuse(frame.copy(acquiredAtMs = 1100, sourceAtMs = 1080)))
    }
}
