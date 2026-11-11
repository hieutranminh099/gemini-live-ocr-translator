package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.*
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Real staged workers and checkpoints with synthetic Gemini replies; no source media or API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoPartialResponseRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: VideoStore get() = VideoStore(context)

    @Before fun setup() {
        SessionState.running.value = false
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        VideoSmartBatchSettings(context).economyMode = false
        StandaloneTranslator.beforeNetworkCallForTest = { error("No network in partial-response tests") }
    }

    @After fun reset() {
        VideoTranslationClient.overrideResponseForTest = null
        StandaloneTranslator.beforeNetworkCallForTest = null
    }

    private fun project(count: Int, prefix: String = "cue"): VideoProject {
        val rows = List(count) { i ->
            VideoCue("$prefix-$i", i * 1000L, i * 1000L + 800, "你好",
                translation = if (i == 0) "Bản đã duyệt" else "", approved = i == 0)
        }
        val pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "COMPLETE", count * 1000L)
            .record(BatchPipelineStage.CLEAN, "COMPLETE", count.toLong())
        return store.save(VideoProject(UUID.randomUUID().toString(), "Synthetic response fixture",
            "file:///not-opened.mp4", count * 1000L, 640, 360,
            sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID,
            cues = rows, scanMs = count * 1000L, stage = VideoStage.FAILED,
            resumeStage = VideoStage.TRANSLATING, pipeline = pipeline))
    }

    private fun body(ids: List<String>, reason: String = "STOP"): String {
        val text = JSONObject().put("translations", JSONArray(ids.map {
            JSONObject().put("id", it).put("translation", "Bản Việt $it")
        })).toString()
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", reason)
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()
    }

    private suspend fun run(project: VideoProject): VideoProject {
        val jobs = VideoJobs(context)
        val done = CompletableDeferred<Unit>()
        jobs.execute(project.id, false, BatchPipelineStage.TRANSLATE) { done.complete(Unit) }
        withTimeout(30_000) { done.await() }
        assertNull(jobs.busy.value)
        return store.load(project.id)
    }

    @Test fun largeTimelineUsesSmallGroupsAndKeepsAcceptedRows() = runBlocking {
        val original = project(740)
        val batches = BatchStore(context)
        val batch = batches.save(batches.create("Synthetic batch", BatchKind.SERIES).copy(
            items = listOf(BatchItem(original.id, "EP01.mp4", durationMs = original.durationMs)),
            translationMode = VideoTranslationMode.HYBRID, sourceMode = VideoSourceMode.OCR,
            delivery = BatchDelivery.SOURCE_LIBRARY, autoRetryEnabled = false))
        val sent = mutableListOf<List<String>>()
        val accepted = mutableSetOf<String>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sent += request.cueIds
            assertEquals(740, JSONObject(request.videoContext).getJSONArray("dialogue").length())
            assertFalse(request.cueIds.contains(original.cues.first().id))
            assertTrue(request.cueIds.none { it in accepted })
            if (sent.size > 1) {
                assertTrue(request.cueIds.size <= 64)
                val saved = store.load(original.id)
                assertTrue(saved.cues.filter { it.id in accepted }.all { VideoTranslationPlan.isComplete(it, true) })
                assertTrue(saved.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit > 0)
            }
            val returned = if (sent.size == 1) request.cueIds.dropLast(5) else request.cueIds
            accepted += returned
            body(returned.reversed())
        }
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        jobs.start(batch.id)
        jobs.execute(batch.id) { done.complete(Unit) }
        try {
            withTimeout(30_000) { done.await() }
        } finally { jobs.cancel() }
        val result = store.load(original.id)
        assertEquals("REVIEW", batches.load(batch.id).state)
        assertEquals("DONE", batches.load(batch.id).items.single().state)
        assertEquals(16, sent.first().size)
        assertTrue(sent.all { it.size <= 64 })
        assertEquals(739, accepted.size)
        assertTrue(result.cues.all { VideoTranslationPlan.isComplete(it, true) })
        assertEquals(original.cues.first(), result.cues.first())
        assertEquals(original.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) },
            result.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) })
        assertEquals(0, batches.load(batch.id).autoRetryAttempts)
    }

    @Test fun oldIncompleteCheckpointResumesWithSmallTargetsAndKeepsTheFullDialogue() = runBlocking {
        val original = project(389)
        val finished = original.cues.mapIndexed { i, cue ->
            if (i in 1..182) cue.copy(translation = "Bản Việt ${cue.id}", draftVi = "Bản Việt ${cue.id}",
                refinementState = "done") else cue
        }
        val saved = store.save(original.copy(cues = finished,
            pipeline = original.pipeline.record(BatchPipelineStage.TRANSLATE, "NEEDS_REVIEW", 183, "TEXT_INCOMPLETE_RESPONSE")))
        assertFalse(saved.pipeline.task(BatchPipelineStage.TRANSLATE).json().has("targetCueLimit"))
        val sent = mutableListOf<String>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            assertTrue(request.cueIds.size <= 64)
            assertEquals(389, JSONObject(request.videoContext).getJSONArray("dialogue").length())
            assertTrue(request.cueIds.none { id -> finished.first { it.id == id }.refinementState == "done" })
            sent += request.cueIds
            body(request.cueIds)
        }
        val result = run(saved)
        assertEquals(206, sent.size)
        assertTrue(result.cues.all { VideoTranslationPlan.isComplete(it, true) })
        assertEquals(finished.take(183), result.cues.take(183))
        assertEquals("COMPLETE", result.pipeline.task(BatchPipelineStage.TRANSLATE).status)
    }

    @Test fun truncatedReplyKeepsCompleteIdsAndAutomaticallyContinuesOnlyTheMissingPart() = runBlocking {
        val original = project(40)
        val sent = mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sent += request.cueIds
            if (sent.size == 1) body(request.cueIds.take(10), "MAX_TOKENS") else {
                assertTrue(request.cueIds.none { it in sent.first().take(10) })
                assertTrue(request.cueIds.size <= 19)
                body(request.cueIds)
            }
        }
        val result = run(original)
        assertEquals(listOf(16, 8, 8, 8, 5), sent.map { it.size })
        assertEquals("COMPLETE", result.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals(original.cues.first(), result.cues.first())
        assertTrue(result.cues.all { VideoTranslationPlan.isComplete(it, true) })
    }

    @Test fun pauseDuringRecoveryPersistsTheSmallerLimitAndAcceptedRowsForANewWorker() = runBlocking {
        val original = project(20)
        val entered = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val sent = mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sent += request.cueIds
            if (sent.size == 1) body(request.cueIds.take(6)) else {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val jobs = VideoJobs(context)
        jobs.execute(original.id, false, BatchPipelineStage.TRANSLATE) { done.complete(Unit) }
        try {
            withTimeout(30_000) { entered.await() }
        } finally {
            jobs.pause()
            withTimeout(30_000) { done.await() }
        }
        val paused = store.load(original.id)
        val limit = paused.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit
        assertEquals(VideoStage.PAUSED, paused.stage)
        assertEquals(8, limit)
        assertEquals(limit, VideoProject.from(paused.json()).pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit)
        VideoTranslationClient.overrideResponseForTest = { request ->
            assertTrue(request.cueIds.size <= limit)
            assertTrue(request.cueIds.none { it in sent.first().take(6) })
            body(request.cueIds)
        }
        val result = run(paused)
        assertEquals("COMPLETE", result.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals(paused.cues.take(7), result.cues.take(7))
    }

    @Test fun savedOneCueLimitGrowsAfterValidRepliesWithoutReplayingCompletedOrApprovedRows() = runBlocking {
        val original = project(260)
        val task = original.pipeline.task(BatchPipelineStage.TRANSLATE).copy(targetCueLimit = 1)
        val saved = store.save(original.copy(pipeline = original.pipeline.copy(stages = original.pipeline.stages +
            (BatchPipelineStage.TRANSLATE to task))))
        val sizes = mutableListOf<Int>()
        val accepted = mutableSetOf<String>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sizes += request.cueIds.size
            assertEquals(260, JSONObject(request.videoContext).getJSONArray("dialogue").length())
            assertTrue(request.cueIds.none { it in accepted || it == original.cues.first().id })
            if (sizes.size > 1) {
                val checkpoint = store.load(original.id)
                assertEquals(minOf(64, 8 shl ((sizes.size - 1) / 3)),
                    checkpoint.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit)
                assertTrue(checkpoint.cues.filter { it.id in accepted }.all { VideoTranslationPlan.isComplete(it, true) })
            }
            accepted += request.cueIds
            body(request.cueIds)
        }
        val result = run(saved)
        assertEquals(listOf(8, 8, 8, 16, 16, 16, 32, 32, 32, 64, 27), sizes)
        assertEquals(64, result.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit)
        assertEquals("COMPLETE", result.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertTrue(result.cues.all { VideoTranslationPlan.isComplete(it, true) })
        assertEquals(original.cues.first(), result.cues.first())
        assertEquals(original.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) },
            result.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) })
    }

    @Test fun threeConsecutiveEmptyRepliesStopWithAnExplicitErrorInsteadOfLooping() = runBlocking {
        val original = project(200)
        val sizes = mutableListOf<Int>()
        VideoTranslationClient.overrideResponseForTest = { request -> sizes += request.cueIds.size; body(emptyList()) }
        val result = run(original)
        assertEquals(listOf(16, 8, 8), sizes)
        assertEquals("TEXT_EMPTY_RESPONSE", result.pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
        assertEquals(VideoStage.FAILED, result.stage)
        assertEquals(original.cues.first(), result.cues.first())
        assertTrue(result.cues.drop(1).all { it.translation.isBlank() && !it.approved })
    }

    @Test fun legacyInvalidReplyResumesSmallAndCompletesAfterOneSafeRetry() = runBlocking {
        val original = project(100)
        val task = original.pipeline.task(BatchPipelineStage.TRANSLATE).copy(
            status = "NEEDS_REVIEW", errorCode = "TEXT_INVALID_RESPONSE", targetCueLimit = 1465)
        val saved = store.save(original.copy(progress = 100, pipeline = original.pipeline.copy(
            stages = original.pipeline.stages + (BatchPipelineStage.TRANSLATE to task))))
        val sizes = mutableListOf<Int>()
        val accepted = mutableSetOf<String>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sizes += request.cueIds.size
            assertEquals(100, JSONObject(request.videoContext).getJSONArray("dialogue").length())
            assertTrue(request.cueIds.none { it in accepted || it == original.cues.first().id })
            if (sizes.size == 1) body(listOf("unknown-id")) else {
                accepted += request.cueIds
                body(request.cueIds)
            }
        }
        val result = run(saved)
        assertEquals(listOf(16, 8), sizes.take(2))
        assertTrue(sizes.all { it <= 64 })
        assertEquals(99, accepted.size)
        assertEquals("COMPLETE", result.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals(100, result.progress)
        assertEquals(original.cues.first(), result.cues.first())
        val diagnostics = VideoBatchDiagnostics.read(context, listOf(original.id))
        assertEquals(VideoResponseFailureReason.UNKNOWN_ID.id, diagnostics.responseFailureReason)
    }

    @Test fun repeatedMalformedRepliesStopAfterTwoWithoutFalseHundredOrChangedSource() = runBlocking {
        val original = project(100)
        val sizes = mutableListOf<Int>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            sizes += request.cueIds.size
            body(listOf(request.cueIds.first(), request.cueIds.first()))
        }
        val result = run(original.copy(progress = 100).let(store::save))
        assertEquals(listOf(16, 8), sizes)
        assertEquals("TEXT_INVALID_RESPONSE", result.pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
        assertEquals(1, result.progress) // One existing Approved row, zero new accepted translations.
        assertEquals(original.cues.first(), result.cues.first())
        assertTrue(result.cues.drop(1).all { it.translation.isBlank() && !it.approved })
        assertEquals(original.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) },
            result.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) })
    }

    @Test fun quotaStopsImmediatelyAndAmbiguousIdsHaveOnlyOneBoundedRetry() = runBlocking {
        for (code in listOf("API_QUOTA_EXHAUSTED", "API_RATE_LIMIT", "TEXT_INVALID_RESPONSE")) {
            val original = project(20)
            var calls = 0
            VideoTranslationClient.overrideResponseForTest = { request ->
                calls++
                if (code == "TEXT_INVALID_RESPONSE") body(listOf(request.cueIds[0], request.cueIds[0]))
                else throw VideoApiException(code)
            }
            val result = run(original)
            assertEquals(if (code == "TEXT_INVALID_RESPONSE") 2 else 1, calls)
            assertEquals(code, result.pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
            assertEquals(if (code == "TEXT_INVALID_RESPONSE") 8 else 16, result.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit)
            assertEquals(original.cues.first(), result.cues.first())
            assertEquals(original.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) },
                result.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) })
            assertTrue(result.cues.drop(1).all { !VideoTranslationPlan.isComplete(it, true) })
        }
    }
}
