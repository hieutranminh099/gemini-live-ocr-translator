package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class SubtitleDebounceTest {
    @Test fun ordinaryDialogueSettlesForNinetyMilliseconds() {
        val gate = SubtitleDebounce()
        listOf(100L, 900L, 1700L, 2500L).forEach {
            gate.changed(it, false)
            assertEquals(90L, gate.remainingMs(it))
            assertEquals(1L, gate.remainingMs(it + 89))
            assertEquals(0L, gate.remainingMs(it + 90))
        }
    }

    @Test fun rapidOcrChangesCoalesceUntilCurrentSentenceSettles() {
        val gate = SubtitleDebounce()
        listOf(100L, 200L, 300L).forEach { gate.changed(it, false) }
        assertEquals(180L, gate.remainingMs(300))
        assertEquals(1L, gate.remainingMs(479))
        assertEquals(0L, gate.remainingMs(480))
        gate.changed(400, false)
        assertEquals(180L, gate.remainingMs(400))
        assertEquals(0L, gate.remainingMs(580))
    }

    @Test fun emptyAndResetCannotHoldNewSentenceBehindOldJitter() {
        val gate = SubtitleDebounce()
        repeat(4) { gate.changed(it * 100L, false) }
        gate.changed(450, true)
        assertEquals(0L, gate.remainingMs(450))
        gate.changed(500, false)
        assertEquals(90L, gate.remainingMs(500))
        gate.reset()
        assertEquals(0L, gate.remainingMs(500))
        gate.changed(600, false)
        assertEquals(90L, gate.remainingMs(600))
    }

    @Test fun jitterHistoryExpiresBeforeLaterDialogue() {
        val gate = SubtitleDebounce()
        listOf(100L, 200L, 300L).forEach { gate.changed(it, false) }
        gate.changed(1401, false)
        assertEquals(90L, gate.remainingMs(1401))
        assertEquals(0L, gate.remainingMs(1491))
    }

    @Test fun repeatedObservationsDoNotExtendTheDeadline() {
        val gate = SubtitleDebounce()
        gate.changed(100, false)
        // The service only calls changed() when the source signature changes.
        listOf(120L, 140L, 160L, 180L).forEach { assertTrue(gate.remainingMs(it) > 0) }
        assertEquals(0L, gate.remainingMs(190))
        assertEquals(0L, gate.remainingMs(300))
    }

    @Test fun everyPartialAtOneHundredMillisecondCadenceWaitsForLatestSource() {
        val gate = SubtitleDebounce()
        // Distinct OCR fragments on successive frames cannot each trigger a cloud request.
        listOf(100L, 200L, 300L, 400L, 500L).forEach {
            gate.changed(it, false)
            assertTrue(gate.remainingMs(it) > 0)
        }
        assertEquals(80L, gate.remainingMs(600))
        assertEquals(0L, gate.remainingMs(700))
    }

    @Test fun clockRollbackStartsAnOrdinaryNewSource() {
        val gate = SubtitleDebounce()
        listOf(100L, 200L, 300L).forEach { gate.changed(it, false) }
        gate.changed(10, false)
        assertEquals(90L, gate.remainingMs(10))
        assertEquals(0L, gate.remainingMs(100))
    }

    @Test fun unchangedSourceAlwaysHasABoundedWait() {
        val gate = SubtitleDebounce()
        repeat(1000) { gate.changed(it.toLong(), false) }
        assertEquals(180L, gate.remainingMs(999))
        assertEquals(0L, gate.remainingMs(1179))
        assertEquals(0L, gate.remainingMs(Long.MAX_VALUE))
    }

    @Test fun noSourceHasNoDelay() {
        val gate = SubtitleDebounce()
        assertEquals(0L, gate.remainingMs(0))
        gate.changed(100, true)
        assertEquals(0L, gate.remainingMs(100))
    }
}
