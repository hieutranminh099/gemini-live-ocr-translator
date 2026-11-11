package com.gemini.ocr.batch

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BatchEpisodePipelineTest {
    private val episodes = (1..10).map { "episode-$it" }

    @Test fun firstTranslationStartsBeforeLaterEpisodesAreExtracted() = runBlocking {
        val extracted = mutableListOf<String>()
        val saved = mutableListOf<String>()
        BatchEpisodePipeline(2).run(episodes, {
            extracted += it
            true
        }, {
            if (it == episodes.first()) assertEquals(listOf(it), extracted)
            saved += it
        }, { _, failure -> throw failure })
        assertEquals(episodes, extracted)
        assertEquals(episodes.toSet(), saved.toSet())
        assertEquals(10, saved.size)
    }

    @Test fun firstEpisodeCanFinishWhileLaterEpisodesStillNeedExtraction() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondExtracted = CompletableDeferred<Unit>()
        val firstSaved = CompletableDeferred<Unit>()
        val extracted = mutableListOf<String>()
        val saved = mutableListOf<String>()
        withTimeout(5_000) {
            BatchEpisodePipeline(1).run(episodes, { id ->
                if (id == episodes[1]) firstStarted.await()
                if (id == episodes[2]) firstSaved.await()
                extracted += id
                if (id == episodes[1]) secondExtracted.complete(Unit)
                true
            }, { id ->
                if (id == episodes.first()) {
                    firstStarted.complete(Unit)
                    secondExtracted.await()
                    assertEquals(episodes.take(2), extracted)
                    firstSaved.complete(Unit)
                }
                saved += id
            }, { _, failure -> throw failure })
        }
        assertEquals(episodes, saved)
    }

    @Test fun nextExtractionOverlapsTranslationWithoutAWholeSeriesBarrier() = runBlocking {
        val translatingSecond = CompletableDeferred<Unit>()
        val extractedThird = CompletableDeferred<Unit>()
        val saved = Collections.synchronizedList(mutableListOf<String>())
        withTimeout(5_000) {
            BatchEpisodePipeline(1).run(episodes, { id ->
                if (id == episodes[2]) {
                    translatingSecond.await()
                    extractedThird.complete(Unit)
                }
                true
            }, { id ->
                if (id == episodes[1]) {
                    translatingSecond.complete(Unit)
                    extractedThird.await()
                }
                saved += id
            }, { _, failure -> throw failure })
        }
        assertEquals(episodes, saved)
    }

    @Test fun disablingOverlapNeverRunsExtractionWithATextWorker() = runBlocking {
        val media = AtomicInteger()
        val text = AtomicInteger()
        withContext(Dispatchers.Default) {
            BatchEpisodePipeline(3, { BatchEpisodePipeline.Admission(3, false) }).run(episodes, {
                media.incrementAndGet()
                try { assertEquals(0, text.get()); yield(); assertEquals(0, text.get()); true }
                finally { media.decrementAndGet() }
            }, {
                text.incrementAndGet()
                try { assertEquals(0, media.get()); yield(); assertEquals(0, media.get()) }
                finally { text.decrementAndGet() }
            }, { _, failure -> throw failure })
        }
        assertEquals(0, media.get())
        assertEquals(0, text.get())
    }

    @Test fun lowerCapacityAppliesToQueuedWorkWhileExistingWorkersDrain() = runBlocking {
        val lowMemory = AtomicBoolean()
        val active = AtomicInteger()
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val bothStarted = CompletableDeferred<Unit>()
        val saved = Collections.synchronizedList(mutableListOf<String>())
        val task = async(Dispatchers.Default) {
            BatchEpisodePipeline(2, { BatchEpisodePipeline.Admission(if (lowMemory.get()) 1 else 2, true) })
                .run(episodes, { true }, { id ->
                    if (id != episodes.first()) {
                        val current = active.incrementAndGet()
                        try {
                            val ordinal = started.incrementAndGet()
                            if (ordinal <= 2) {
                                if (ordinal == 2) bothStarted.complete(Unit)
                                release.await()
                            } else {
                                assertTrue(lowMemory.get())
                                assertEquals(1, current)
                                yield()
                            }
                        } finally { active.decrementAndGet() }
                    }
                    saved += id
                }, { _, failure -> throw failure })
        }
        try {
            withTimeout(5_000) { bothStarted.await() }
            lowMemory.set(true)
            release.complete(Unit)
            withTimeout(5_000) { task.await() }
            assertEquals(10, saved.size)
            assertEquals(0, active.get())
        } finally { release.complete(Unit); task.cancelAndJoin() }
    }

    @Test fun cancelWaitsForAllWorkerCheckpointFinalizers() = runBlocking {
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.Default) {
            BatchEpisodePipeline(2).run(episodes, { true }, { id ->
                if (id != episodes.first()) {
                    active.incrementAndGet()
                    try {
                        if (entered.incrementAndGet() == 2) bothStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { yield(); active.decrementAndGet() }
                    }
                }
            }, { _, failure -> throw failure })
        }
        try {
            withTimeout(5_000) { bothStarted.await() }
            withTimeout(5_000) { task.cancelAndJoin() }
            assertEquals(0, active.get())
            assertEquals(2, entered.get())
        } finally { task.cancelAndJoin() }
    }

    @Test fun failedExtractionAndTranslationDoNotStopOtherEpisodes() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val saved = Collections.synchronizedList(mutableListOf<String>())
        BatchEpisodePipeline(2).run(episodes, {
            if (it == episodes[2]) throw IOException("synthetic extraction failure")
            true
        }, {
            if (it == episodes[4]) throw IOException("synthetic text failure")
            saved += it
        }, { id, _ -> failures += id })
        assertEquals(setOf(episodes[2], episodes[4]), failures.toSet())
        assertEquals(episodes.toSet() - failures.toSet(), saved.toSet())
        assertEquals(8, saved.size)
    }

    @Test fun failureHandlerCanStopSharedAuthOrStorageFailures() = runBlocking {
        val seen = mutableListOf<String>()
        try {
            BatchEpisodePipeline(2).run(episodes, {
                seen += it
                throw IOException("synthetic shared failure")
            }, { fail("No text requests may be started") }, { _, failure -> throw failure })
            fail("Expected shared failure")
        } catch (expected: IOException) {
            assertEquals("synthetic shared failure", expected.message)
        }
        assertEquals(listOf(episodes.first()), seen)
    }

    @Test fun duplicateProjectIdsAreRejectedBeforeAnyWork() = runBlocking {
        try {
            BatchEpisodePipeline(2).run(episodes + episodes.first(), {
                fail("Duplicate input must fail before extraction"); true
            }, { fail("Duplicate input must fail before translation") }, { _, failure -> throw failure })
            fail("Expected duplicate rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun independentlyCancelledTextWorkerStopsTheWholePipeline() = runBlocking {
        val run = async {
            BatchEpisodePipeline(1).run(episodes, { true }, {
                throw CancellationException("Synthetic stage stopped before saving")
            }, { _, _ -> fail("Cancellation is not a retryable translation failure") })
        }
        try {
            try { withTimeout(5_000) { run.await() }; fail("Expected cancellation") }
            catch (timeout: TimeoutCancellationException) { throw timeout }
            catch (_: CancellationException) { assertTrue(run.isCancelled) }
        } finally { run.cancelAndJoin() }
    }

    @Test fun emptyQueueDoesNotCallAnyWorker() = runBlocking {
        BatchEpisodePipeline(1).run(emptyList(), { fail(); true }, { fail() }, { _, _ -> fail() })
    }

    @Test fun alreadyExtractedSourcesCanTranslateWhilePlaybackHoldsNewMediaWork() = runBlocking {
        val saved = mutableListOf<String>()
        withTimeout(5_000) {
            BatchEpisodePipeline(1, { BatchEpisodePipeline.Admission(1, false, extractAllowed = false) })
                .run(episodes, { true }, { saved += it }, { _, failure -> throw failure },
                    requiresExtraction = { false })
        }
        assertEquals(episodes, saved)
    }

    @Test fun playbackCanHoldTheExtractorWithoutCancellingTheTextResponseBeingSaved() = runBlocking {
        val extractAllowed = AtomicBoolean(true)
        val held = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val firstSaved = CompletableDeferred<Unit>()
        val extracted = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val task = async {
            BatchEpisodePipeline(1, {
                BatchEpisodePipeline.Admission(1, true, extractAllowed = extractAllowed.get())
            }, { held.complete(Unit); resume.await() }).run(episodes, {
                extracted += it; true
            }, { id ->
                if (id == episodes.first()) {
                    extractAllowed.set(false)
                    held.await()
                    saved += id
                    firstSaved.complete(Unit)
                } else saved += id
            }, { _, failure -> throw failure })
        }
        try {
            withTimeout(5_000) { firstSaved.await() }
            assertEquals(episodes.take(1), extracted)
            assertEquals(episodes.take(1), saved)
            extractAllowed.set(true); resume.complete(Unit)
            withTimeout(5_000) { task.await() }
            assertEquals(episodes, saved)
        } finally { resume.complete(Unit); task.cancelAndJoin() }
    }

    @Test fun suspendedAdmissionDoesNotStartAnyWorkUntilResumed() = runBlocking {
        val suspended = AtomicBoolean(true)
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val extracted = mutableListOf<String>()
        val task = async {
            BatchEpisodePipeline(1, { BatchEpisodePipeline.Admission(1, true, suspended.get()) }, {
                waiting.complete(Unit)
                resume.await()
            }).run(episodes, { extracted += it; true }, {}, { _, failure -> throw failure })
        }
        try {
            withTimeout(5_000) { waiting.await() }
            assertTrue(extracted.isEmpty())
            suspended.set(false)
            resume.complete(Unit)
            withTimeout(5_000) { task.await() }
            assertEquals(episodes, extracted)
        } finally { resume.complete(Unit); task.cancelAndJoin() }
    }
}
