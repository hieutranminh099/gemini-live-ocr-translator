package com.gemini.ocr.mtprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Mt60ResultOwnersTest {
    private class Result(val label: String, private val events: MutableList<String>, val fail: Boolean = false) : AutoCloseable {
        var closes = 0
        override fun close() { closes++; events += "close:$label"; if (fail) error("close failure:$label") }
    }
    @Test fun initialCrossResultSurvivesCachedDecoderResultsWithoutAnUnboundedHistory() {
        val events = mutableListOf<String>(); val first = Result("first", events)
        val owners = Mt60ResultOwners<Result>(); owners.adopt(first, true)
        var previous: Result = first
        repeat(127) { step ->
            assertEquals(0, first.closes) // Cross KV is still a live input for this run.
            assertEquals(0, previous.closes) // Latest decoder KV survives until the new run returns.
            val next = Result("step$step", events)
            events += "native-finished:$step"
            owners.adopt(next, false)
            assertTrue(owners.retainedCount <= 2)
            if (previous !== first) assertEquals(1, previous.closes)
            previous = next
        }
        owners.close(); owners.close()
        assertEquals(1, first.closes); assertEquals(1, previous.closes)
    }
    @Test fun nonemptyNewCrossCacheCanReplaceBothOlderOwnersAfterTheRun() {
        val events = mutableListOf<String>()
        val first = Result("initial", events); val second = Result("cached", events); val third = Result("new-cross", events)
        val owners = Mt60ResultOwners<Result>()
        owners.adopt(first, true); owners.adopt(second, false)
        assertTrue(events.isEmpty())
        events += "native-finished"
        owners.adopt(third, true)
        assertEquals(listOf("native-finished", "close:initial", "close:cached"), events)
        assertEquals(1, owners.retainedCount); assertTrue(owners.owns(third)); assertFalse(owners.owns(first))
        owners.close(); assertEquals(1, third.closes)
    }
    @Test fun theSameOwnerIsClosedOnlyOnceAndMissingInitialCrossIsRejected() {
        val events = mutableListOf<String>(); val first = Result("first", events)
        val owners = Mt60ResultOwners<Result>()
        assertThrows(IllegalArgumentException::class.java) { owners.adopt(first, false) }
        owners.adopt(first, true)
        assertThrows(IllegalArgumentException::class.java) { owners.adopt(first, false) }
        owners.close(); owners.close(); assertEquals(1, first.closes)
    }
    @Test fun cleanupAttemptsBothOwnersEvenIfTheFirstCloseFails() {
        val events = mutableListOf<String>(); val first = Result("first", events, fail = true); val second = Result("second", events)
        val owners = Mt60ResultOwners<Result>(); owners.adopt(first, true); owners.adopt(second, false)
        assertThrows(IllegalStateException::class.java) { owners.close() }
        assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(0, owners.retainedCount)
        owners.close()
    }
    @Test fun failedOldOwnerCloseDoesNotOrphanTheNewlyAdoptedResult() {
        val events = mutableListOf<String>(); val first = Result("first", events, fail = true)
        val second = Result("second", events); val replacement = Result("replacement", events)
        val owners = Mt60ResultOwners<Result>(); owners.adopt(first, true); owners.adopt(second, false)
        assertThrows(IllegalStateException::class.java) { owners.adopt(replacement, true) }
        assertTrue(owners.owns(replacement)); assertEquals(1, first.closes); assertEquals(1, second.closes)
        owners.close(); assertEquals(1, replacement.closes)
    }
}
