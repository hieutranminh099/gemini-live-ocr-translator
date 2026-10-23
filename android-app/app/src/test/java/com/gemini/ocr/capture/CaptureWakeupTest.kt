package com.gemini.ocr.capture

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CaptureWakeupTest {
    @Test fun manyHintsCoalesceIntoTheLatestSingleCapture() = runBlocking {
        val wakeup = CaptureWakeup()
        repeat(1000) { wakeup.request(CaptureWakeup.Reason.SETTLED_SOURCE) }
        wakeup.request(CaptureWakeup.Reason.RESPONSE)
        assertEquals(CaptureWakeup.Reason.RESPONSE, withTimeout(1000) { wakeup.awaitNext(5000) })
        val second = launch(start = CoroutineStart.UNDISPATCHED) { wakeup.awaitNext(5000); fail("Consumed hints must not remain queued") }
        assertTrue(second.isActive)
        second.cancelAndJoin()
    }

    @Test fun hintWakesTheExistingPeriodicWait() = runBlocking {
        val wakeup = CaptureWakeup()
        var observed: CaptureWakeup.Reason? = null
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { observed = wakeup.awaitNext(5000) }
        assertTrue(waiting.isActive)
        wakeup.request(CaptureWakeup.Reason.RESPONSE)
        withTimeout(1000) { waiting.join() }
        assertEquals(CaptureWakeup.Reason.RESPONSE, observed)
    }

    @Test fun cancellingAWaitDoesNotPoisonFutureCapture() = runBlocking {
        val wakeup = CaptureWakeup()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { wakeup.awaitNext(5000) }
        cancelled.cancelAndJoin()
        wakeup.request(CaptureWakeup.Reason.EXPLICIT_FRAME)
        assertEquals(CaptureWakeup.Reason.EXPLICIT_FRAME, withTimeout(1000) { wakeup.awaitNext(5000) })
    }

    @Test fun resetDropsTheOldSessionsPendingHint() = runBlocking {
        val wakeup = CaptureWakeup()
        wakeup.request(CaptureWakeup.Reason.RESPONSE)
        wakeup.reset()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { wakeup.awaitNext(5000); fail("Old session woke capture") }
        assertTrue(waiting.isActive)
        waiting.cancelAndJoin()
    }

    @Test fun noHintRetainsThePeriodicCaptureDeadline() = runBlocking {
        assertNull(withTimeout(1000) { CaptureWakeup().awaitNext(20) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroPeriodCannotCreateABusyLoop() = runBlocking { CaptureWakeup().awaitNext(0); Unit }
}
