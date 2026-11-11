package com.gemini.ocr.batch

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real checkpointed workers with synthetic API replies and no media/network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchParallelExecutionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        SessionState.running.value = false
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        VideoSmartBatchSettings(context).groupSize = 10
        VideoSmartBatchSettings(context).economyMode = false
    }

    private fun project(prefix: String, pending: Int = 1): VideoProject {
        val cues = listOf(VideoCue("$prefix-approved", 0, 500, "已经检查", "Đã duyệt", approved = true)) +
            List(pending) { i -> VideoCue("$prefix-pending-$i", (i + 1) * 1000L,
                (i + 1) * 1000L + 700, "$prefix 第${i}句话") }
        return VideoStore(context).save(VideoProject(UUID.randomUUID().toString(), prefix,
            "file:///not-opened.mp4", (pending + 2) * 1000L, 640, 360,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
            scanMs = (pending + 2) * 1000L, cues = cues,
            pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.CLEAN, "COMPLETE")))
    }

    private fun body(ids: List<String>): String {
        val rows = JSONArray(ids.map { JSONObject().put("id", it).put("translation", "Bản Việt $it") })
        val payload = JSONObject().put("translations", rows).toString()
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", payload)))))).toString()
    }

    @Test fun independentVideosOverlapWhileEachVideosNextRequestUsesItsSavedResults() = runBlocking {
        val projects = listOf(project("one", 12), project("two", 12))
        val store = VideoStore(context)
        val videos = VideoJobs(context)
        val owner = UUID.randomUUID().toString()
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = ConcurrentHashMap<String, MutableList<List<String>>>()
        val previous = VideoTranslationClient.overrideResponseForTest
        videos.reserveBatch(owner)
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                val prefix = request.cueIds.first().substringBefore('-')
                val history = requests.computeIfAbsent(prefix) { Collections.synchronizedList(mutableListOf()) }
                history.add(request.cueIds)
                if (history.size == 1) {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    release.await()
                } else {
                    val saved = store.load(projects.single { it.title == prefix }.id)
                    val completed = saved.cues.filter { it.id in history.first() }
                    assertEquals(history.first().size, completed.size)
                    assertTrue(completed.all { it.translation == "Bản Việt ${it.id}" })
                }
                body(request.cueIds)
            }
            val tasks = projects.map { p -> async(Dispatchers.Default) {
                videos.runParallelStageForBatch(owner, p.id, BatchPipelineStage.TRANSLATE)
            } }
            withTimeout(15_000) { bothEntered.await() }
            assertEquals(projects.map { it.id }.toSet(), videos.parallelProjects.value)
            assertEquals(owner, videos.batchBusy.value)
            assertTrue(videos.projects.value.filter { it.id in videos.parallelProjects.value }
                .all { it.stage == VideoStage.TRANSLATING })
            release.complete(Unit)
            val results = withTimeout(15_000) { tasks.awaitAll() }
            assertTrue(videos.parallelProjects.value.isEmpty())
            assertEquals(owner, videos.batchBusy.value)
            for (result in results) {
                assertTrue(VideoStagedPipeline.completed(result, BatchPipelineStage.TRANSLATE))
                assertEquals(projects.single { it.id == result.id }.cues.first(), result.cues.first())
                assertTrue(result.cues.drop(1).all { it.translation == "Bản Việt ${it.id}" && !it.approved })
                assertEquals(2, requests.getValue(result.title).size)
                assertEquals(result.cues.drop(1).map { it.id }, requests.getValue(result.title).flatten())
            }
        } finally {
            release.complete(Unit)
            VideoTranslationClient.overrideResponseForTest = previous
            videos.releaseBatch(owner)
        }
    }

    @Test fun cancellingParallelLanesDrainsCheckpointsBeforeParentReservationCanBeReleased() = runBlocking {
        val projects = listOf(project("cancel-one"), project("cancel-two"))
        val store = VideoStore(context)
        val videos = VideoJobs(context)
        val owner = UUID.randomUUID().toString()
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val previous = VideoTranslationClient.overrideResponseForTest
        videos.reserveBatch(owner)
        try {
            VideoTranslationClient.overrideResponseForTest = {
                if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                awaitCancellation()
            }
            val tasks = projects.map { p -> launch(Dispatchers.Default) {
                videos.runParallelStageForBatch(owner, p.id, BatchPipelineStage.TRANSLATE)
            } }
            withTimeout(15_000) { bothEntered.await() }
            tasks.forEach { it.cancel() }
            withTimeout(15_000) { tasks.joinAll() }
            assertEquals(owner, videos.batchBusy.value)
            assertTrue(videos.parallelProjects.value.isEmpty())
            projects.forEach { original ->
                val saved = store.load(original.id)
                assertEquals(original.cues, saved.cues)
                assertEquals(VideoStage.PAUSED, saved.stage)
                assertEquals("PAUSED", saved.pipeline.task(BatchPipelineStage.TRANSLATE).status)
            }
            assertThrows(IllegalStateException::class.java) { videos.start(projects.first().id) }
        } finally {
            VideoTranslationClient.overrideResponseForTest = previous
            videos.releaseBatch(owner)
        }
        assertNull(videos.batchBusy.value)
    }

    @Test fun batchRam8ModeCompletesBothItemsWithoutLosingConcurrentStageUpdates() = runBlocking {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem = 16L * 1024 * 1024 * 1024
            totalMem = availMem
            lowMemory = false
        })
        assertEquals(2, BatchParallelism.workerLimit(context, BatchExecutionMode.RAM_8_GB))
        val projects = listOf(project("batch-one"), project("batch-two"))
        val store = BatchStore(context)
        val batch = store.save(store.create("Parallel fixture", BatchKind.SERIES).copy(
            executionMode = BatchExecutionMode.RAM_8_GB, delivery = BatchDelivery.SOURCE_LIBRARY,
            autoRetryEnabled = false, items = projects.mapIndexed { index, p ->
                BatchItem(p.id, "EP${index + 1}.mp4", episode = (index + 1).toString(), durationMs = p.durationMs)
            }))
        val videos = VideoJobs(context)
        val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                release.await()
                body(request.cueIds)
            }
            jobs.start(batch.id)
            jobs.execute(batch.id) { done.complete(Unit) }
            withTimeout(15_000) { bothEntered.await() }
            assertEquals(2, videos.parallelProjects.value.size)
            release.complete(Unit)
            withTimeout(15_000) { done.await() }
            val result = store.load(batch.id)
            assertEquals(result.message, "REVIEW", result.state)
            assertEquals(listOf("DONE", "DONE"), result.items.map { it.state })
            assertEquals(listOf("COMPLETE", "COMPLETE"), result.items.map { it.stageStatus })
            assertTrue(videos.parallelProjects.value.isEmpty())
            assertNull(videos.batchBusy.value)
            assertEquals(2, entered.get())
        } finally {
            release.complete(Unit)
            jobs.cancel()
            withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }
}
