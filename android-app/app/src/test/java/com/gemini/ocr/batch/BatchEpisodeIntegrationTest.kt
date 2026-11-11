package com.gemini.ocr.batch

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real BatchJobs + VideoJobs + AtomicFile checkpoints. Synthetic media/text responses only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [com.gemini.ocr.movie.MovieSourceLibraryTest.WindowsFileUris::class])
class BatchEpisodeIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val videoStore get() = VideoStore(context)
    private val batchStore get() = BatchStore(context)
    private val previousNetworkGuard = StandaloneTranslator.beforeNetworkCallForTest

    @Before fun setup() {
        SessionState.running.value = false
        StandaloneTranslator.beforeNetworkCallForTest = { throw AssertionError("Unexpected network in a synthetic batch test") }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        VideoSmartBatchSettings(context).groupSize = 10
        VideoSmartBatchSettings(context).economyMode = false
        shadowOf(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .setMemoryInfo(ActivityManager.MemoryInfo().apply {
                availMem = 16L * 1024 * 1024 * 1024; totalMem = availMem; lowMemory = false
            })
    }

    @After fun restoreNetworkGuard() { StandaloneTranslator.beforeNetworkCallForTest = previousNetworkGuard }

    private fun project(index: Int, pending: Int = 1): VideoProject {
        val id = UUID.randomUUID().toString()
        val protected = VideoCue("$id-user", 10, 700, "原来的名字", "Tên tôi đã sửa",
            model = "user-edited")
        val cues = listOf(protected) + List(pending) { i ->
            VideoCue("$id-$i", 1000L + i * 1000, 1800L + i * 1000, "这是第${i}句话")
        }
        val duration = (pending + 2) * 1000L
        return videoStore.save(VideoProject(id, "Episode $index", "file:///never-decoded.mp4", duration, 640, 360,
            sourceMode = VideoSourceMode.SIDECAR, translationMode = VideoTranslationMode.GEMINI,
            scanMs = duration, cues = cues, stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING,
            pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.CLEAN, "COMPLETE")))
    }

    private fun batch(projects: List<VideoProject>, mode: BatchExecutionMode = BatchExecutionMode.SEQUENTIAL): BatchProject {
        val seed = batchStore.create("Ten episodes", BatchKind.SERIES)
        return batchStore.save(seed.copy(delivery = BatchDelivery.SOURCE_LIBRARY, autoRetryEnabled = false,
            executionMode = mode, items = projects.mapIndexed { i, p ->
                BatchItem(p.id, "EP${i + 1}.mp4", episode = (i + 1).toString(), durationMs = p.durationMs)
            }))
    }

    private fun reply(ids: List<String>): String {
        val text = JSONObject().put("translations", JSONArray(ids.map {
            JSONObject().put("id", it).put("translation", "Bản dịch hợp lệ")
        })).toString()
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(10)
    }

    @Test fun tenEpisodesFirstFinishesWhileSecondExtractorIsBlockedAndOtherEightAreUnextracted() = runBlocking {
        val extractionEntered = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val projects = (1..10).map { index ->
            val seed = project(index)
            val file = File(videoStore.folder(seed.id), "fixture.srt").apply {
                writeText("1\n00:00:00,100 --> 00:00:01,500\n这是第${index}句话\n\n", Charsets.UTF_8)
            }
            val uri = if (index == 2) Uri.parse("content://batch-fixture/second.srt") else Uri.fromFile(file)
            if (index == 2) {
                shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { object : ByteArrayInputStream(file.readBytes()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        extractionEntered.countDown()
                        check(releaseExtraction.await(15, TimeUnit.SECONDS)) { "First episode never finished" }
                        return super.read(b, off, len)
                    }
                } }
            }
            videoStore.save(seed.copy(cues = emptyList(), scanMs = 0, pipeline = VideoPipelineCheckpoint(),
                stage = VideoStage.READY, resumeStage = VideoStage.EXTRACTING,
                subtitleUri = uri.toString(), subtitleName = "fixture.srt"))
        }
        val b = batch(projects, BatchExecutionMode.RAM_8_GB)
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                if (requests.isEmpty()) {
                    assertTrue("Extractor must run while the first API response is pending",
                        extractionEntered.await(10, TimeUnit.SECONDS))
                }
                // Imported SRT IDs are per project; the request context and workers own their timeline.
                requests += request.cueIds
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            awaitCondition {
                VideoStagedPipeline.completed(videoStore.load(projects.first().id), BatchPipelineStage.FINISH) &&
                    batchStore.load(b.id).items.first().state == "DONE"
            }
            assertTrue(projects.drop(1).any { !VideoStagedPipeline.extracted(videoStore.load(it.id)) })
            assertEquals("DONE", batchStore.load(b.id).items.first().state)
            val first = videoStore.load(projects.first().id)
            assertEquals(100L, first.cues.single().startMs)
            assertEquals(1500L, first.cues.single().endMs)
            assertTrue(videoStore.exportFile(first.id, "srt").readText().contains("00:00:00,100 --> 00:00:01,500"))
            releaseExtraction.countDown()
            withTimeout(20_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertTrue(batchStore.load(b.id).items.all { it.state == "DONE" })
            assertEquals(10, requests.size)
        } finally {
            releaseExtraction.countDown(); jobs.cancel()
            if (!done.isCompleted && jobs.busy.value != null) withTimeout(20_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun pauseResumeSendsOnlyMissingGroupsAndKeepsUserEditsAndTimestamps() = runBlocking { interruptAndResume(false) }
    @Test fun cancelDrainsWorkersBeforeReservationIsReleasedAndCanResumeMissingWork() = runBlocking { interruptAndResume(true) }

    private suspend fun interruptAndResume(cancel: Boolean) {
        val originals = (1..10).map { project(it, if (it == 1) 12 else 1) }
        val b = batch(originals)
        val videos = VideoJobs(context)
        val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<List<String>>())
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                requests += request.cueIds
                if (requests.size == 2) { secondRequest.complete(Unit); awaitCancellation() }
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(15_000) { secondRequest.await() }
            if (cancel) { jobs.cancel(); jobs.pause() } else jobs.pause() // Service destruction must not downgrade Cancel.
            assertEquals(if (cancel) "CANCELLED" else "PAUSED", batchStore.load(b.id).state)
            withTimeout(15_000) { done.await() }
            assertEquals(if (cancel) "CANCELLED" else "PAUSED", batchStore.load(b.id).state)
            assertNull(videos.batchBusy.value)
            assertTrue(videos.parallelProjects.value.isEmpty())
            assertTrue(batchStore.load(b.id).items.none { it.stageStatus == "RUNNING" })
            val accepted = videoStore.load(originals.first().id).cues.filter { it.translation.isNotBlank() }.map { it.id }.toSet()
            assertTrue(accepted.size > 1)
            requests.clear()
            VideoTranslationClient.overrideResponseForTest = { request ->
                assertTrue(request.cueIds.none { it in accepted })
                requests += request.cueIds
                reply(request.cueIds)
            }
            val resumed = BatchJobs(context, VideoJobs(context))
            val resumedDone = CompletableDeferred<Unit>()
            try {
                resumed.start(b.id); resumed.execute(b.id) { resumedDone.complete(Unit) }
                withTimeout(20_000) { resumedDone.await() }
                assertEquals("REVIEW", batchStore.load(b.id).state)
                assertEquals(requests.flatten().size, requests.flatten().distinct().size)
                originals.forEach { original ->
                    val saved = videoStore.load(original.id)
                    assertEquals(original.cues.first(), saved.cues.first())
                    assertEquals(original.cues.map { it.startMs to it.endMs }, saved.cues.map { it.startMs to it.endMs })
                }
            } finally { resumed.cancel(); if (resumed.busy.value != null) withTimeout(15_000) { resumedDone.await() } }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun redeliveredActiveBatchUsesDurableAcceptedRowsAndIgnoresDuplicateServiceDispatch() = runBlocking {
        val original = project(1, 4)
        val saved = videoStore.save(original.copy(cues = original.cues.mapIndexed { index, cue ->
            if (index == 1) cue.copy(translation = "Đã lưu trước khi tiến trình dừng") else cue
        }, pipeline = original.pipeline.record(BatchPipelineStage.TRANSLATE, "RUNNING", 2)))
        val b = batchStore.save(batch(listOf(saved)).copy(state = "RUNNING", items = listOf(
            BatchItem(saved.id, "EP1.mp4", state = "PENDING", stage = BatchPipelineStage.TRANSLATE, stageStatus = "RUNNING"))))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                calls.incrementAndGet()
                assertTrue(request.cueIds.none { id -> saved.cues.any { it.id == id && it.translation.isNotBlank() } })
                entered.complete(Unit); release.await(); reply(request.cueIds)
            }
            jobs.execute(b.id) { done.complete(Unit) } // Fresh process: no in-memory start/reservation.
            withTimeout(15_000) { entered.await() }
            jobs.execute(b.id) { fail("Duplicate service intent must not acquire another worker") }
            release.complete(Unit)
            withTimeout(15_000) { done.await() }
            assertEquals(1, calls.get())
            assertEquals(saved.cues.first(), videoStore.load(saved.id).cues.first())
            val accepted = saved.cues[1]
            assertEquals(accepted.copy(draftVi = accepted.translation), videoStore.load(saved.id).cues[1])
            assertEquals("REVIEW", batchStore.load(b.id).state)
        } finally {
            release.complete(Unit); jobs.cancel()
            if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun staleServiceIntentDoesNotRestartUserPausedCancelledOrFinishedWork() {
        val p = project(1)
        val b = batch(listOf(p))
        for (state in listOf("PAUSED", "CANCELLED", "REVIEW")) {
            batchStore.save(b.copy(state = state))
            val jobs = BatchJobs(context, VideoJobs(context))
            var finished = false
            jobs.execute(b.id) { finished = true }
            assertTrue(finished)
            assertNull(jobs.busy.value)
            assertEquals(state, batchStore.load(b.id).state)
            assertEquals(p.cues, videoStore.load(p.id).cues)
        }
    }

    @Test fun responseLostBeforeDurableSaveRemainsPendingAndIsRetriedFromTheLastCheckpoint() = runBlocking {
        val original = project(1, 3)
        val b = batch(listOf(original))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val previous = VideoTranslationClient.overrideResponseForTest
        val requested = mutableListOf<List<String>>()
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                requested += request.cueIds
                reply(request.cueIds) // Synthetic response exists, but no durable result has been committed.
                throw CancellationException("Process stopped before committing the response")
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(15_000) { done.await() }
            val checkpoint = videoStore.load(original.id)
            assertEquals(original.cues, checkpoint.cues)
            assertNotEquals("COMPLETE", checkpoint.pipeline.task(BatchPipelineStage.TRANSLATE).status)
            // Model a process killed before its batch-finally block: only the durable active marker survives.
            batchStore.save(batchStore.load(b.id).copy(state = "RUNNING"))
            val recovered = BatchJobs(context, VideoJobs(context))
            val recoveredDone = CompletableDeferred<Unit>()
            VideoTranslationClient.overrideResponseForTest = { request -> requested += request.cueIds; reply(request.cueIds) }
            try {
                recovered.execute(b.id) { recoveredDone.complete(Unit) }
                withTimeout(15_000) { recoveredDone.await() }
                assertEquals(requested[0], requested[1]) // Ambiguous, uncommitted request must be repeated safely.
                assertEquals(original.cues.first(), videoStore.load(original.id).cues.first())
                assertEquals("REVIEW", batchStore.load(b.id).state)
            } finally { recovered.cancel(); if (recovered.busy.value != null) withTimeout(15_000) { recoveredDone.await() } }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun transientNetworkFailureRetriesPendingIdsWithoutLosingTheOtherEpisodes() = runBlocking {
        val originals = (1..10).map { project(it) }
        val b = batchStore.save(batch(originals).copy(autoRetryEnabled = true))
        var waits = 0
        val jobs = BatchJobs(context, VideoJobs(context), waitBeforeRetry = { waits++ })
        val done = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                if (calls.incrementAndGet() == 1) throw VideoApiException("API_NETWORK", transient = true)
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(20_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertEquals(1, waits)
            assertEquals(11, calls.get())
            originals.forEach { assertEquals(it.cues.first(), videoStore.load(it.id).cues.first()) }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun playbackBlocksNewExtractionAndResumesAfterTheRecoveryHold() = runBlocking {
        val p = project(1)
        val file = File(videoStore.folder(p.id), "source.srt").apply {
            writeText("1\n00:00:00,100 --> 00:00:01,500\n你好\n\n")
        }
        val pending = videoStore.save(p.copy(cues = emptyList(), scanMs = 0, pipeline = VideoPipelineCheckpoint(),
            subtitleUri = Uri.fromFile(file).toString(), subtitleName = "source.srt"))
        val b = batch(listOf(pending))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val owner = Any()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            BatchPlaybackPriority.current.setPlaying(owner, true)
            VideoTranslationClient.overrideResponseForTest = { calls.incrementAndGet(); reply(it.cueIds) }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            delay(150)
            assertFalse(done.isCompleted)
            assertTrue(videoStore.load(p.id).cues.isEmpty())
            assertEquals(0, calls.get())
            BatchPlaybackPriority.current.setPlaying(owner, false)
            withTimeout(15_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertEquals(1, calls.get())
        } finally {
            BatchPlaybackPriority.current.setPlaying(owner, false); jobs.cancel()
            if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            withTimeout(3_000) { while (BatchPlaybackPriority.current.restricted()) delay(20) }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }
}
