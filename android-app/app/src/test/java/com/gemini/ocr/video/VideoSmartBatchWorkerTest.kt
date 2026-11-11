package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoSmartBatchWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun configuredSyntheticKey() {
        // Robolectric has no Android Keystore. Exercise the pre-existing unreadable-ciphertext
        // fallback seam in this synthetic application; never request or read a real API key.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        assertEquals("fixture-no-network", AppSettings(context).effectiveStandaloneApiKey())
    }
    private fun body(ids: List<String>) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", JSONObject()
            .put("translations", JSONArray(ids.map { JSONObject().put("id", it).put("translation", "Bản Việt $it") })).toString())))))).toString()
    @Test fun partialResponseIsCheckpointedAndOnlyMissingIdsAreRetried() = runBlocking {
        SessionState.running.value = false
        configuredSyntheticKey()
        val store = VideoStore(context)
        val cues = List(4) { VideoCue("cue-$it", it * 1000L, it * 1000L + 900, "第${it}句") }
            .mapIndexed { i, c -> if (i == 0) c.copy(translation = "Tôi đã duyệt", approved = true) else c }
        val project = store.save(VideoProject(UUID.randomUUID().toString(), "Fixture", "file:///not-read.mp4", 5000, 640, 360,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            cues = cues, scanMs = 5000, stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING))
        val requests = mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest = { batch ->
            requests += batch.cueIds
            if (requests.size == 1) body(listOf("cue-3", "cue-1")) else {
                assertEquals(listOf("cue-2"), batch.cueIds)
                val saved = store.load(project.id)
                assertEquals("Bản Việt cue-1", saved.cues[1].translation)
                assertEquals("Bản Việt cue-3", saved.cues[3].translation)
                body(batch.cueIds)
            }
        }
        try {
            val jobs = VideoJobs(context); val finished = CompletableDeferred<Unit>()
            jobs.execute(project.id, false) { finished.complete(Unit) }
            withTimeout(15_000) { finished.await() }
            val result = store.load(project.id)
            assertEquals(result.message, VideoStage.REVIEW, result.stage)
            assertEquals(cues[0], result.cues[0]); assertEquals(2, requests.size)
            assertEquals(2, result.cloudCalls); assertTrue(result.cues.drop(1).all { it.translation.isNotBlank() && !it.approved })
        } finally { VideoTranslationClient.overrideResponseForTest = null }
    }
    @Test fun missingIdStillMissingAfterOneRetryFailsWithoutLosingAcceptedRows() = runBlocking {
        SessionState.running.value = false
        configuredSyntheticKey()
        val store = VideoStore(context)
        val p = store.save(VideoProject(UUID.randomUUID().toString(), "Fixture", "file:///not-read.mp4", 5000, 640, 360,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            cues = List(2) { VideoCue("cue-$it", it * 1000L, it * 1000L + 900, "第${it}句") },
            scanMs = 5000, stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING))
        var calls = 0
        VideoTranslationClient.overrideResponseForTest = { batch -> calls++; body(if (calls == 1) listOf(batch.cueIds.first()) else emptyList()) }
        try {
            val jobs = VideoJobs(context); val finished = CompletableDeferred<Unit>()
            jobs.execute(p.id, false) { finished.complete(Unit) }
            withTimeout(15_000) { finished.await() }
            val result = store.load(p.id)
            assertEquals(2, calls); assertEquals(VideoStage.FAILED, result.stage)
            assertTrue(result.cues.first().translation.isNotBlank())
            assertTrue(result.cues.last().translation.isBlank()); assertTrue(result.cues.last().error.isNotBlank())
            assertEquals(listOf("cue-1"), VideoTranslationPlan.nextCloudBatch(result.cues, false)!!.cueIds)
        } finally { VideoTranslationClient.overrideResponseForTest = null }
    }
    @Test fun quotaFailureIsNotRetriedAndCooldownSurvivesWorkerReconstruction() = runBlocking {
        SessionState.running.value = false
        configuredSyntheticKey()
        val store = VideoStore(context)
        val p = store.save(VideoProject(UUID.randomUUID().toString(), "Quota fixture", "file:///not-read.mp4", 5000, 640, 360,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            cues = listOf(VideoCue("cue-0", 0, 1000, "你好")), scanMs = 5000,
            stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING))
        var calls = 0
        VideoTranslationClient.overrideResponseForTest = { calls++; throw IOException("Đạt giới hạn lượt gọi Gemini API (429).") }
        suspend fun runWorker() {
            val complete = CompletableDeferred<Unit>()
            VideoJobs(context).execute(p.id, false) { complete.complete(Unit) }
            withTimeout(15_000) { complete.await() }
        }
        try {
            runWorker()
            assertEquals(1, calls); assertEquals(VideoStage.FAILED, store.load(p.id).stage)
            val metrics = JSONObject(File(store.folder(p.id), "smart-batch-metrics.json").readText())
            assertTrue(metrics.getLong("retry_after_ms") > System.currentTimeMillis())
            try { VideoBatchTelemetry(context, p.id).requireQuotaReady(); fail("persisted quota wait must block") }
            catch (_: IllegalStateException) { }
            runWorker()
            assertEquals("reconstructed worker must not dispatch during persisted quota wait", 1, calls)
            assertEquals(metrics.getLong("retry_after_ms"), JSONObject(File(store.folder(p.id), "smart-batch-metrics.json").readText()).getLong("retry_after_ms"))
            assertEquals(1, store.load(p.id).cloudCalls)
            assertTrue(store.load(p.id).cues.single().translation.isBlank())
        } finally { VideoTranslationClient.overrideResponseForTest = null }
    }

    @Test fun blockedGroupRemainsManualWhenLaterGroupGetsTransientCooldown() = runBlocking {
        SessionState.running.value = false
        configuredSyntheticKey()
        val settings = VideoSmartBatchSettings(context)
        val originalGroupSize = settings.groupSize
        settings.groupSize = 10
        val store = VideoStore(context)
        val approved = VideoCue("approved", 0, 900, "已确认", "Câu đã duyệt", approved = true)
        val accepted = VideoCue("accepted", 1000, 1900, "已经翻译", "Câu đã dịch", draftVi = "Câu đã dịch")
        val remaining = (2..12).map { VideoCue("cue-$it", it * 1000L, it * 1000L + 900, "第${it}句") }
        val project = store.save(VideoProject(UUID.randomUUID().toString(), "Mixed Gemini recovery", "file:///not-read.mp4",
            14_000, 640, 360, sourceMode = VideoSourceMode.SIDECAR, translationMode = VideoTranslationMode.GEMINI,
            cues = listOf(approved, accepted) + remaining, scanMs = 14_000, stage = VideoStage.PAUSED,
            resumeStage = VideoStage.TRANSLATING))
        val deadline = System.currentTimeMillis() + 120_000
        val requests = mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest = { batch ->
            requests += batch.cueIds
            if (requests.size == 1) JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")).toString()
            else throw VideoApiException("API_SERVICE_503", transient = true, nextRetryAt = deadline)
        }
        val jobs = VideoJobs(context)
        val owner = UUID.randomUUID().toString()
        jobs.reserveBatch(owner)
        try {
            val result = withTimeout(15_000) { jobs.runStageForBatch(owner, project.id, BatchPipelineStage.TRANSLATE) }
            val saved = store.load(project.id)
            assertEquals(listOf(remaining.take(10).map { it.id }, listOf(remaining.last().id)), requests)
            assertEquals(VideoStage.FAILED, result.stage)
            assertEquals(2, saved.cloudCalls)
            val checkpoint = saved.pipeline.task(BatchPipelineStage.TRANSLATE)
            assertEquals("NEEDS_REVIEW", checkpoint.status)
            assertEquals("TEXT_BLOCKED", checkpoint.errorCode)
            assertEquals(deadline, checkpoint.nextRetryAt)
            assertEquals(listOf(approved, accepted), saved.cues.take(2))
            assertTrue(saved.cues.drop(2).all { it.translation.isBlank() })
            assertTrue(saved.cues[2].error.contains("TEXT_BLOCKED"))
            assertTrue(saved.cues.last().error.contains("API_SERVICE_503"))
        } finally {
            jobs.releaseBatch(owner)
            VideoTranslationClient.overrideResponseForTest = null
            settings.groupSize = originalGroupSize
        }
    }

    @Test fun livePriorityAndPauseKeepEveryPendingCueWithoutDispatching() = runBlocking {
        SessionState.running.value = true
        configuredSyntheticKey()
        val store = VideoStore(context)
        val cues = listOf(VideoCue("cue-0", 0, 1000, "你好"))
        val p = store.save(VideoProject(UUID.randomUUID().toString(), "Live fixture", "file:///not-read.mp4", 5000, 640, 360,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            cues = cues, scanMs = 5000, stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING))
        var calls = 0
        VideoTranslationClient.overrideResponseForTest = { batch -> calls++; body(batch.cueIds) }
        try {
            val jobs = VideoJobs(context); val paused = CompletableDeferred<Unit>()
            jobs.execute(p.id, false) { paused.complete(Unit) }
            withTimeout(5000) { while (store.load(p.id).stage != VideoStage.WAITING_LIVE) delay(10) }
            assertEquals(0, calls)
            jobs.pause(); withTimeout(5000) { paused.await() }
            assertEquals(VideoStage.PAUSED, store.load(p.id).stage); assertEquals(cues, store.load(p.id).cues)
            SessionState.running.value = false
            val resumed = CompletableDeferred<Unit>()
            VideoJobs(context).execute(p.id, false) { resumed.complete(Unit) }
            withTimeout(10_000) { resumed.await() }
            assertEquals(1, calls); assertEquals(VideoStage.REVIEW, store.load(p.id).stage)
        } finally { SessionState.running.value = false; VideoTranslationClient.overrideResponseForTest = null }
    }
}
