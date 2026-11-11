package com.paddle.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedOcrOwnerTest {
    private fun assertReportedFailure(original: IllegalStateException, reported: Throwable?) {
        // Coroutine stacktrace recovery may copy the exception and retain the original as cause.
        assertTrue(reported is IllegalStateException)
        assertEquals(original.message, reported?.message)
        assertTrue(generateSequence(reported) { it.cause }.take(8).any { it === original })
    }

    @Test fun cancellationWaitsForNativeCompletionAndPublishesNoStaleOutputWhileReleaseWaits() = runBlocking {
        val finishNative = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val published = mutableListOf<String>()
        val owner = SerializedOcrOwner<String, String>(
            recognizeNative = { frame -> events += "start:$frame"; finishNative.await(); events += "finish:$frame"; "result:$frame" },
            releaseNative = { events += "release" },
        )
        val active = launch(start = CoroutineStart.UNDISPATCHED) { published += owner.recognize("old-generation") }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.recognize("new-generation") } }
        active.cancel()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        try {
            assertEquals(listOf("start:old-generation"), events)
            assertFalse(active.isCompleted)
            assertFalse(close.isCompleted)
        } finally { finishNative.complete(Unit) }
        active.join(); close.join()
        assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
        assertTrue(published.isEmpty())
        assertEquals(listOf("start:old-generation", "finish:old-generation", "release"), events)
    }

    @Test fun concurrentRequestsAreSequentialAndKeepTheirOwnResults() = runBlocking {
        val finishFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var inFlight = 0; var maximum = 0
        val owner = SerializedOcrOwner<String, String>(
            recognizeNative = { frame ->
                inFlight++; maximum = maxOf(maximum, inFlight); events += "start:$frame"
                if (frame == "first") finishFirst.await()
                events += "finish:$frame"; inFlight--; "result:$frame"
            }, releaseNative = { assertEquals(0, inFlight); events += "release" },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { owner.recognize("first") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { owner.recognize("second") }
        try { assertEquals(listOf("start:first"), events) } finally { finishFirst.complete(Unit) }
        assertEquals("result:first", first.await()); assertEquals("result:second", second.await())
        owner.release()
        assertEquals(1, maximum)
        assertEquals(listOf("start:first", "finish:first", "start:second", "finish:second", "release"), events)
    }

    @Test fun cancellingQueuedCallerNeverEntersNativeEngine() = runBlocking {
        val finish = CompletableDeferred<Unit>(); val frames = mutableListOf<String>()
        val owner = SerializedOcrOwner<String, String>(
            recognizeNative = { frames += it; finish.await(); it }, releaseNative = {},
        )
        val active = async(start = CoroutineStart.UNDISPATCHED) { owner.recognize("active") }
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { owner.recognize("cancelled-waiter") }
        try { queued.cancelAndJoin(); assertEquals(listOf("active"), frames) } finally { finish.complete(Unit) }
        assertEquals("active", active.await()); owner.release()
        assertEquals(listOf("active"), frames)
    }

    @Test fun cancellingReleaseCallerStillWaitsAndClosesOnce() = runBlocking {
        val finish = CompletableDeferred<Unit>(); var released = 0
        val owner = SerializedOcrOwner<String, String>(recognizeNative = { finish.await(); it }, releaseNative = { released++ })
        val active = async(start = CoroutineStart.UNDISPATCHED) { owner.recognize("active") }
        val close = launch(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        close.cancel()
        try { assertEquals(0, released); assertFalse(close.isCompleted) } finally { finish.complete(Unit) }
        active.await(); close.join(); owner.release()
        assertEquals(1, released)
        assertTrue(runCatching { owner.recognize("after-close") }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun nativeFailureDoesNotLeaveMutexLockedAndReleaseIsIdempotent() = runBlocking {
        var released = 0
        val failure = IllegalStateException("native failure")
        val owner = SerializedOcrOwner<String, String>(recognizeNative = { throw failure }, releaseNative = { released++ })
        assertReportedFailure(failure, runCatching { owner.recognize("first") }.exceptionOrNull())
        owner.release(); owner.release()
        assertEquals(1, released)
    }

    @Test fun releaseFailureStillBlocksFutureUseAndNeverRetriesAClosedNativeHandle() = runBlocking {
        var released = 0
        val failure = IllegalStateException("release failure")
        val owner = SerializedOcrOwner<String, String>(recognizeNative = { it }, releaseNative = { released++; throw failure })
        assertReportedFailure(failure, runCatching { owner.release() }.exceptionOrNull())
        owner.release()
        assertEquals(1, released)
        assertTrue(runCatching { owner.recognize("after-failed-release") }.exceptionOrNull() is IllegalStateException)
    }
}
