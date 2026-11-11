package com.gemini.ocr.batch

import org.junit.Assert.*
import org.junit.Test

class BatchPlaybackPriorityTest {
    @Test fun seeksAndRepeatedPauseEventsDoNotExtendTheRecoveryHold() {
        var now = 10L
        val priority = BatchPlaybackPriority { now }
        val player = Any()
        assertFalse(priority.restricted())
        priority.setPlaying(player, true)
        priority.setPlaying(player, true)
        priority.setPlaying(player, false)
        now += 1_500
        priority.setPlaying(player, false)
        assertTrue(priority.restricted())
        now += 501
        assertFalse(priority.restricted())
    }

    @Test fun disposingOnePlayerDoesNotReleaseAnotherPlayersPriority() {
        var now = 1L
        val priority = BatchPlaybackPriority { now }
        val first = Any(); val second = Any()
        priority.setPlaying(first, true); priority.setPlaying(second, true)
        priority.setPlaying(first, false)
        now += 10_000
        assertTrue(priority.restricted())
        priority.setPlaying(second, false)
        now += BatchPlaybackPriority.RECOVERY_HOLD_MS
        assertFalse(priority.restricted())
    }
}
