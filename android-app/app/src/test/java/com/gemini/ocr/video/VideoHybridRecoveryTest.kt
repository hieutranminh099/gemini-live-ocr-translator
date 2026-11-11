package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoHybridRecoveryTest {
    @Test fun newAndLegacyOversizedLimitsStartSmallWhileValidSavedLimitsResume() {
        for (saved in listOf(0, -1, 1465, Int.MAX_VALUE)) {
            assertEquals(16, VideoHybridRecovery(saved).targetLimit)
        }
        assertEquals(8, VideoHybridRecovery(1).targetLimit)
        assertEquals(8, VideoHybridRecovery(8).targetLimit)
        assertEquals(32, VideoHybridRecovery(32).targetLimit)
        assertEquals(64, VideoHybridRecovery(64).targetLimit)
        assertEquals(16, VideoHybridRecovery(64, "TEXT_INVALID_RESPONSE").targetLimit)
        assertEquals(8, VideoHybridRecovery(8, "TEXT_INVALID_RESPONSE").targetLimit)
    }

    @Test fun onlyConsecutiveCompleteRepliesGrowTheLimitAndNeverBeyondSixtyFour() {
        val recovery = VideoHybridRecovery()
        assertEquals(16, recovery.completeReply())
        assertEquals(16, recovery.completeReply())
        assertEquals(32, recovery.completeReply())
        assertEquals(32, recovery.completeReply())
        assertEquals(32, recovery.completeReply())
        assertEquals(64, recovery.completeReply())
        repeat(20) { assertEquals(64, recovery.completeReply()) }
    }

    @Test fun partialResponseShrinksTheActualSentSizeAndRestartsTheSuccessStreak() {
        val recovery = VideoHybridRecovery(64)
        repeat(2) { recovery.completeReply() }
        assertEquals(16, recovery.partialReply(32))
        repeat(2) { assertEquals(16, recovery.completeReply()) }
        assertEquals(32, recovery.completeReply())
        assertEquals(8, recovery.partialReply(1))
        assertEquals(64, recovery.partialReply(1465))
        assertThrows(IllegalArgumentException::class.java) { recovery.partialReply(0) }
    }

    @Test fun malformedOutputHasOnlyOneSmallerRetryEvenIfACompleteResponseIntervenes() {
        val recovery = VideoHybridRecovery()
        assertTrue(recovery.invalidReply())
        assertEquals(8, recovery.targetLimit)
        assertEquals(1, recovery.invalidReplies)
        repeat(3) { recovery.completeReply() }
        assertEquals(16, recovery.targetLimit)
        assertFalse(recovery.invalidReply())
        assertEquals(8, recovery.targetLimit)
        assertEquals(2, recovery.invalidReplies)
        assertFalse(recovery.invalidReply())
    }

    @Test fun manualResumeRetainsSmallPersistedLimitAndCreatesANewBoundedAttempt() {
        val interrupted = VideoHybridRecovery()
        assertTrue(interrupted.invalidReply())
        val resumed = VideoHybridRecovery(interrupted.targetLimit, "TEXT_INVALID_RESPONSE")
        assertEquals(8, resumed.targetLimit)
        assertEquals(0, resumed.invalidReplies)
        assertTrue(resumed.invalidReply())
        assertFalse(resumed.invalidReply())
    }
}
