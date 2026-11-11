package com.gemini.ocr.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GeminiDispatchLimiterTest {
    @Test fun sixteenthRequestWaitsForTheConservativeMinuteWindow() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = GeminiDispatchLimiter(clockMs = { now }, sleepMs = { waits.add(it); now += it })
        repeat(15) { assertEquals(0L, limiter.awaitTurn()) }
        var notified = 0L
        assertEquals(61_000L, limiter.awaitTurn { notified = it })
        assertEquals(listOf(61_000L), waits)
        assertEquals(61_000L, notified)
    }
    @Test fun cancelledQueuedSubtitleConsumesNoNewSlot() = runBlocking {
        var now = 0L
        val sleeping = CompletableDeferred<Unit>()
        val limiter = GeminiDispatchLimiter(1, 1000, { now }, { sleeping.complete(Unit); awaitCancellation() })
        limiter.awaitTurn()
        val obsolete = launch { limiter.awaitTurn(); fail("Queued subtitle must be cancelled") }
        withTimeout(1000) { sleeping.await() }
        obsolete.cancelAndJoin()
        now = 1000
        assertEquals(0L, withTimeout(1000) { limiter.awaitTurn() })
    }
    @Test fun multipleReservationsCannotExceedTheWindowEvenWithConcurrentCallers() = runBlocking {
        var now = 0L
        val granted = mutableListOf<Long>()
        val limiter = GeminiDispatchLimiter(3, 1000, { now }, { now += it; yield() })
        coroutineScope { repeat(20) { launch { limiter.awaitTurn(); granted.add(now) } } }
        assertEquals(20, granted.size)
        for (at in granted) assertTrue(granted.count { it <= at && it > at - 1000 } <= 3)
    }
    @Test fun elapsedReservationsExpireWithoutArtificialDelay() = runBlocking {
        var now = 0L
        val limiter = GeminiDispatchLimiter(2, 1000, { now }, { error("No wait expected") })
        limiter.awaitTurn(); limiter.awaitTurn()
        now = 1000
        assertEquals(0L, limiter.awaitTurn())
    }
}
