package com.gemini.ocr.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoAdaptiveAdmissionTest {
    @Test fun outageReductionSurvivesStageScopesAndRecoversGraduallyWithinTheBatchCeiling() = runBlocking {
        val now = AtomicLong(1_000L)
        VideoApiRecovery.withAdaptiveBatch(8) {
            assertEquals(VideoRequestCapacity(8, 8), VideoApiRecovery.currentCapacity())
            VideoApiRecovery.withParallelRequests(8, now::get) {
                var requests = 0
                val failed = runCatching {
                    VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}, jitter = { 0 },
                        sleep = { now.addAndGet(it) }) {
                        requests++
                        throw VideoApiException("API_SERVICE_503", transient = true)
                    }
                }
                assertEquals("API_SERVICE_503", (failed.exceptionOrNull() as VideoApiException).code)
                assertEquals(3, requests)
            }
            assertEquals(VideoRequestCapacity(1, 8), VideoApiRecovery.currentCapacity())
            VideoApiRecovery.withParallelRequests(2, now::get) {
                assertEquals(VideoRequestCapacity(1, 2), VideoApiRecovery.currentCapacity())
            }
            VideoApiRecovery.withParallelRequests(8, now::get) {
                repeat(3) { VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) { "valid" } }
                assertEquals(1, VideoApiRecovery.currentCapacity()!!.limit)
                VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) { "valid" }
                assertEquals(2, VideoApiRecovery.currentCapacity()!!.limit)
                repeat(32) { VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) { "valid" } }
                assertEquals(VideoRequestCapacity(8, 8), VideoApiRecovery.currentCapacity())
            }
            VideoApiRecovery.withParallelRequests(2, now::get) {
                assertEquals(VideoRequestCapacity(2, 2), VideoApiRecovery.currentCapacity())
            }
            assertEquals(VideoRequestCapacity(8, 8), VideoApiRecovery.currentCapacity())
        }
        assertNull(VideoApiRecovery.currentCapacity())
        VideoApiRecovery.withAdaptiveBatch(8) {
            assertEquals(VideoRequestCapacity(8, 8), VideoApiRecovery.currentCapacity())
        }
    }

    @Test fun reducedCapacityDrainsAdmittedWorkAndOldSuccessesCannotUndoAnOutage() = runBlocking {
        withTimeout(10_000) {
            VideoApiRecovery.withAdaptiveBatch(4) {
                coroutineScope {
                    val entered = List(4) { CompletableDeferred<Unit>() }
                    val release = List(4) { CompletableDeferred<Unit>() }
                    val initial = List(4) { index ->
                        async {
                            runCatching {
                                VideoApiRecovery.withAccountAdmission {
                                    entered[index].complete(Unit)
                                    release[index].await()
                                    if (index == 0) throw VideoApiException("API_SERVICE_503", transient = true)
                                }
                            }
                        }
                    }
                    entered.forEach { it.await() }
                    val nextEntered = CompletableDeferred<Unit>()
                    val next = async {
                        VideoApiRecovery.withAccountAdmission { nextEntered.complete(Unit) }
                    }
                    yield()
                    assertFalse(nextEntered.isCompleted)
                    release[0].complete(Unit)
                    assertEquals("API_SERVICE_503", (initial[0].await().exceptionOrNull() as VideoApiException).code)
                    assertEquals(2, VideoApiRecovery.currentCapacity()!!.limit)
                    assertFalse(nextEntered.isCompleted)
                    release[1].complete(Unit); initial[1].await(); yield()
                    assertFalse("Two old requests must drain before a new one is admitted", nextEntered.isCompleted)
                    release[2].complete(Unit); initial[2].await()
                    nextEntered.await(); next.await()
                    release[3].complete(Unit); initial[3].await()
                    assertEquals(2, VideoApiRecovery.currentCapacity()!!.limit)
                    repeat(3) { VideoApiRecovery.withAccountAdmission {} }
                    assertEquals(3, VideoApiRecovery.currentCapacity()!!.limit)
                }
            }
        }
    }

    @Test fun cancellationWhileWaitingDoesNotConsumeCapacityAndOtherRequestsRemainUsable() = runBlocking {
        withTimeout(10_000) {
            VideoApiRecovery.withAdaptiveBatch(1) {
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val holder = launch {
                        VideoApiRecovery.withAccountAdmission { entered.complete(Unit); awaitCancellation() }
                    }
                    entered.await()
                    var cancelledCalls = 0
                    val waiter = launch { VideoApiRecovery.withAccountAdmission { cancelledCalls++ } }
                    yield(); waiter.cancelAndJoin(); holder.cancelAndJoin()
                    assertEquals(0, cancelledCalls)
                    assertEquals("ready", VideoApiRecovery.withAccountAdmission { "ready" })
                    assertEquals(VideoRequestCapacity(1, 1), VideoApiRecovery.currentCapacity())
                }
            }
        }
    }

    @Test fun quotaAuthAndInvalidContentDoNotChangeAdmissionOrLeakAcrossBatchExecutions() = runBlocking {
        VideoApiRecovery.withAdaptiveBatch(4) {
            for (code in listOf("API_AUTH", "API_QUOTA_EXHAUSTED", "TEXT_INVALID_RESPONSE", "TEXT_BLOCKED")) {
                val failed = runCatching { VideoApiRecovery.withAccountAdmission { throw VideoApiException(code) } }
                assertEquals(code, (failed.exceptionOrNull() as VideoApiException).code)
                assertEquals(VideoRequestCapacity(4, 4), VideoApiRecovery.currentCapacity())
            }
        }
    }
}
