package com.gemini.ocr.mtprobe

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

class Mt60NativeOwnerTest {
    @Test fun cancellationWaitsForTheCurrentNativeStepThenStopsBeforeTheNextAndNeverPublishes() = runBlocking {
        val finishNative = CompletableDeferred<Unit>(); val events = mutableListOf<String>(); val published = mutableListOf<String>()
        val owner = Mt60NativeOwner<String, String>(
            executeNative = { source, active ->
                try {
                    events += "native-start:$source"; finishNative.await(); events += "native-finish:$source"
                    active(); events += "next-token"; "translation:$source"
                } finally { events += "cleanup:$source" }
            }, closeNative = { events += "release" },
        )
        val work = launch(start = CoroutineStart.UNDISPATCHED) { published += owner.execute("old-source") }
        work.cancel()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        try {
            assertFalse(work.isCompleted); assertFalse(closing.isCompleted)
            assertEquals(listOf("native-start:old-source"), events)
        } finally { finishNative.complete(Unit) }
        work.join(); closing.join()
        assertTrue(published.isEmpty())
        assertEquals(listOf("native-start:old-source", "native-finish:old-source", "cleanup:old-source", "release"), events)
    }
    @Test fun cancelledOrClosingQueuedRequestsCannotEnterNative() = runBlocking {
        val finish = CompletableDeferred<Unit>(); val entered = mutableListOf<String>()
        val owner = Mt60NativeOwner<String, String>({ input, _ -> entered += input; finish.await(); input }, {})
        val first = async(start = CoroutineStart.UNDISPATCHED) { owner.execute("first") }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { owner.execute("cancelled") }
        cancelled.cancelAndJoin()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.execute("closing") } }
        val close = launch(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        finish.complete(Unit); assertEquals("first", first.await()); close.join()
        assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("first"), entered)
    }
    @Test fun concurrentRequestsKeepOneNativeOwnerAndTheirOwnResults() = runBlocking {
        val finish = CompletableDeferred<Unit>(); var active = 0; var maximum = 0
        val owner = Mt60NativeOwner<String, String>({ input, _ ->
            active++; maximum = maxOf(maximum, active)
            if (input == "first") finish.await()
            active--; "translation:$input"
        }, {})
        val first = async(start = CoroutineStart.UNDISPATCHED) { owner.execute("first") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { owner.execute("second") }
        finish.complete(Unit)
        assertEquals("translation:first", first.await()); assertEquals("translation:second", second.await())
        assertEquals(1, maximum); owner.release()
    }
    @Test fun cancelledReleaseStillWaitsAndClosesOnce() = runBlocking {
        val finish = CompletableDeferred<Unit>(); var closes = 0
        val owner = Mt60NativeOwner<String, String>({ input, _ -> finish.await(); input }, { closes++ })
        val first = async(start = CoroutineStart.UNDISPATCHED) { owner.execute("first") }
        val close = launch(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        close.cancel()
        try { assertEquals(0, closes); assertFalse(close.isCompleted) } finally { finish.complete(Unit) }
        first.await(); close.join(); owner.release(); assertEquals(1, closes)
        assertTrue(runCatching { owner.execute("after-close") }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun nativeFailureDoesNotLeakTheMutexAndFailedReleaseStillBlocksUse() = runBlocking {
        var closes = 0
        val owner = Mt60NativeOwner<String, String>({ _, _ -> error("native failure") }, { closes++; error("release failure") })
        assertTrue(runCatching { owner.execute("first") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { owner.release() }.exceptionOrNull() is IllegalStateException)
        owner.release(); assertEquals(1, closes)
        assertTrue(runCatching { owner.execute("after-failure") }.exceptionOrNull() is IllegalStateException)
    }
}
