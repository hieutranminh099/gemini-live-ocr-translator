package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/** Exercises actual batch and VideoJobs orchestration offline; no provider/model/media export is dispatched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchOrchestrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun project(ready: Boolean, exported: Boolean = false): VideoProject {
        val store = VideoStore(context); val id = UUID.randomUUID().toString()
        val output = File(store.folder(id), "existing.mp4").also { if (exported) it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        return store.save(VideoProject(id, "Video", "file:///not-dispatched.mp4", 4000, 720, 1280,
            translationMode = VideoTranslationMode.GEMINI, sourceMode = VideoSourceMode.SIDECAR,
            cues = if (ready) listOf(VideoCue("cue", 0, 1000, "你好", "Xin chào", true)) else emptyList(),
            stage = if (ready) VideoStage.REVIEW else VideoStage.READY,
            resumeStage = if (ready) VideoStage.TRANSLATING else VideoStage.EXTRACTING,
            scanMs = if (ready) 4000 else 0, exportedMp4 = if (exported) output.absolutePath else ""))
    }
    private fun batch(projects: List<VideoProject>): BatchProject {
        val store = BatchStore(context); val b = store.create("Test", BatchKind.SERIES)
        projects.forEach { File(VideoStore(context).folder(it.id), "batch-owner").writeText(b.id) }
        return store.save(b.copy(items = projects.mapIndexed { i, p -> BatchItem(p.id, "EP${i + 1}.mp4", episode = (i + 1).toString(), durationMs = p.durationMs) }))
    }
    @Test fun oneFailedVideoDoesNotCancelFollowingVideosOrDestroyCompletedFilesAndTimeline() = runBlocking {
        SessionState.running.value = false
        val failed = project(false); val complete = project(true, true); val following = project(false)
        val before = VideoStore(context).load(complete.id).json().toString()
        val bytes = File(complete.exportedMp4).readBytes()
        AppSettings(context).standaloneApiKey = ""
        val b = batch(listOf(failed, complete, following)); val videos = VideoJobs(context); val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>()
        jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
        withTimeout(15_000) { done.await() }
        val result = BatchStore(context).load(b.id)
        assertEquals(listOf("NEEDS_REVIEW", "DONE", "NEEDS_REVIEW"), result.items.map { it.state })
        assertEquals(before, VideoStore(context).load(complete.id).json().toString())
        assertArrayEquals(bytes, File(complete.exportedMp4).readBytes())
        assertEquals(0, VideoStore(context).load(failed.id).cloudCalls)
        // Extraction now precedes API configuration/translation for every input.
        assertTrue(result.items.first().error, result.items.first().error.contains("Chọn tệp SRT/ASS/VTT trước"))
        assertEquals(BatchPipelineStage.EXTRACT, result.items.first().stage)
        assertEquals("NEEDS_REVIEW", result.items.first().stageStatus)
        assertEquals(0, VideoStore(context).load(following.id).cloudCalls)
        assertEquals("PENDING", VideoStore(context).load(failed.id).pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals("FAILED", result.state) // Dummy completed file is deliberately not a playable movie.
        assertNull(jobs.busy.value); assertNull(videos.batchBusy.value)
    }
    @Test fun pauseBeforeServiceStartsRetainsCheckpointAndReleasesReservation() = runBlocking {
        val p = project(true); val before = VideoStore(context).load(p.id).json().toString()
        val b = batch(listOf(p)); val videos = VideoJobs(context); val jobs = BatchJobs(context, videos); val done = CompletableDeferred<Unit>()
        jobs.start(b.id); jobs.pause(); jobs.execute(b.id) { done.complete(Unit) }
        withTimeout(10_000) { done.await() }
        assertEquals("PAUSED", BatchStore(context).load(b.id).state)
        assertEquals(before, VideoStore(context).load(p.id).json().toString())
        assertNull(videos.batchBusy.value)
    }
    @Test fun cancellingWhileWaitingForLiveReleasesVideoWorkerBeforeBatchReservation() = runBlocking {
        val p = project(true); val b = batch(listOf(p)); val videos = VideoJobs(context); val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>(); SessionState.running.value = true
        try {
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(10_000) {
                while (VideoStore(context).load(p.id).stage != VideoStage.WAITING_LIVE) {
                    if (done.isCompleted) fail("Worker exited before Live wait: ${BatchStore(context).load(b.id).message}; ${VideoStore(context).load(p.id).message}")
                    delay(10)
                }
            }
            jobs.cancel(); withTimeout(10_000) { done.await() }
            val saved = VideoStore(context).load(p.id)
            assertEquals(p.cues, saved.cues); assertEquals(VideoStage.PAUSED, saved.stage)
            assertEquals("CANCELLED", BatchStore(context).load(b.id).state)
            assertNull(videos.busy.value); assertNull(videos.batchBusy.value)
            assertFalse(File(VideoStore(context).folder(p.id), "translated.mp4").exists())
        } finally { SessionState.running.value = false; jobs.cancel() }
    }
    @Test fun batchReservationBlocksSingleVideoStartAndWritesUntilReleased() = runBlocking {
        val p = project(true); val jobs = VideoJobs(context); val owner = UUID.randomUUID().toString()
        jobs.reserveBatch(owner)
        try {
            assertThrows(IllegalStateException::class.java) { jobs.start(p.id) }
            assertThrows(IllegalStateException::class.java) { runBlocking { jobs.save(p.copy(glossary = "你=bạn")) } }
            assertEquals(p.cues, VideoStore(context).load(p.id).cues)
        } finally { jobs.releaseBatch(owner) }
        assertNotNull(jobs.save(p.copy(glossary = "你=bạn")))
    }
    @Test fun sharedQuotaFailurePausesQueuePersistsCooldownAndDoesNotDispatchNextVideoAfterConfigOrRestart() = runBlocking {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val encrypted = prefs.getString("standalone_api_key", null); val fallback = prefs.getString("standalone_api_key_plain_fallback", null)
        val previous = StandaloneTranslator.overrideGeminiTranslator
        val complete = project(true, true); val completedBytes = File(complete.exportedMp4).readBytes()
        val store = VideoStore(context)
        fun pending(source: String) = store.save(project(true).copy(cues = listOf(VideoCue("new", 0, 1000, source)),
            stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING))
        val first = pending("我有两本书"); val next = pending("我没有两本书")
        val b = BatchStore(context).save(batch(listOf(complete, first, next)).copy(autoRetryEnabled = false))
        val videos = VideoJobs(context); val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>(); var calls = 0; SessionState.running.value = false
        try {
            prefs.edit().putString("standalone_api_key", "synthetic-invalid-ciphertext")
                .putString("standalone_api_key_plain_fallback", "synthetic-test-only").commit()
            StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> calls++; throw IOException("Đạt giới hạn lượt gọi Gemini API (429).") }
            val before = System.currentTimeMillis()
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }; withTimeout(15_000) { done.await() }
            val paused = BatchStore(context).load(b.id)
            val diagnostic = "Batch ${paused.state}/${paused.currentStage}: ${paused.message}; " +
                listOf(complete, first, next).joinToString(" | ") { original ->
                    val p = store.load(original.id)
                    "${original.id}: ${p.stage}/${p.resumeStage}, scan=${p.scanMs}/${p.durationMs}, " +
                        "cues=${p.cues.size}, ${p.pipeline.json()}, message=${p.message}"
                }
            assertEquals(diagnostic, 1, calls); assertEquals(diagnostic, "PAUSED", paused.state)
            // The earlier completed episode remains DONE; shared quota stops later API dispatches.
            assertEquals(listOf("DONE", "NEEDS_REVIEW", "PENDING"), paused.items.map { it.state })
            assertEquals(BatchPipelineStage.TRANSLATE, paused.currentStage)
            assertEquals("COMPLETE", paused.items.first().stageStatus)
            assertEquals("NEEDS_REVIEW", paused.items[1].stageStatus)
            assertEquals("API_RATE_LIMIT", store.load(first.id).pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
            assertEquals("COMPLETE", store.load(first.id).pipeline.task(BatchPipelineStage.CLEAN).status)
            assertEquals(next.cues.map { Triple(it.id, it.startMs, it.endMs) },
                store.load(next.id).cues.map { Triple(it.id, it.startMs, it.endMs) })
            assertTrue(paused.retryAfterMs >= before + 61_000L)
            assertEquals(0, store.load(next.id).cloudCalls); assertArrayEquals(completedBytes, File(complete.exportedMp4).readBytes())
            assertNull(videos.batchBusy.value); assertNull(videos.busy.value)
            assertEquals(paused.retryAfterMs, jobs.configure(b.id, VideoTranslationMode.GEMINI, VideoSourceMode.SIDECAR).retryAfterMs)
            val reopened = BatchJobs(context, VideoJobs(context))
            assertThrows(IllegalStateException::class.java) { reopened.start(b.id) }
            assertEquals(1, calls)
            assertEquals(paused.retryAfterMs, BatchStore(context).load(b.id).retryAfterMs)
            assertEquals(401, BatchJobs.sharedGeminiFailure("Khóa Gemini API hoặc mô hình không hợp lệ (401)."))
            assertEquals(403, BatchJobs.sharedGeminiFailure("Gemini chưa cho phép nhận chữ. Kiểm tra khóa và quyền API trong Cài đặt."))
            assertNull(BatchJobs.sharedGeminiFailure("Video có lỗi đọc (429)."))
        } finally {
            StandaloneTranslator.overrideGeminiTranslator = previous
            prefs.edit().apply {
                if (encrypted == null) remove("standalone_api_key") else putString("standalone_api_key", encrypted)
                if (fallback == null) remove("standalone_api_key_plain_fallback") else putString("standalone_api_key_plain_fallback", fallback)
            }.commit()
            jobs.cancel()
        }
    }

    @Test fun partialBatchAutomaticallyRetriesOnlyMissingIdsAndKeepsApprovedAndOtherErrors() = runBlocking {
        verifyPartialRetry(pauseWhileWaiting = false)
    }

    @Test fun pausingDuringAutomaticWaitKeepsAcceptedRowsAndStopsFurtherCalls() = runBlocking {
        verifyPartialRetry(pauseWhileWaiting = true)
    }

    private suspend fun verifyPartialRetry(pauseWhileWaiting: Boolean) {
        SessionState.running.value = false
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        val store = VideoStore(context)
        val seed = project(true)
        val approved = seed.cues.single()
        val pending = store.save(seed.copy(pipeline = seed.pipeline.record(BatchPipelineStage.CLEAN, "COMPLETE"),
            cues = listOf(approved, VideoCue("valid", 1100, 2000, "他来了"),
            VideoCue("missing", 2100, 3000, "我没走"))))
        val missingSidecar = project(false)
        val batchStore = BatchStore(context)
        val b = batchStore.save(batch(listOf(pending, missingSidecar)).copy(delivery = BatchDelivery.SOURCE_LIBRARY))
        val waiting = CompletableDeferred<Long>()
        val proceed = CompletableDeferred<Unit>()
        val videos = VideoJobs(context)
        val jobs = BatchJobs(context, videos, waitBeforeRetry = { millis ->
            waiting.complete(millis)
            proceed.await()
        })
        val calls = mutableListOf<List<String>>()
        val previous = VideoTranslationClient.overrideResponseForTest
        val done = CompletableDeferred<Unit>()
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                calls += request.cueIds
                val ids = when (calls.size) {
                    1 -> listOf("valid")
                    2 -> emptyList()
                    else -> request.cueIds
                }
                val rows = JSONArray(ids.map { JSONObject().put("id", it).put("translation", "Đã dịch $it") })
                JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP").put(
                    "content", JSONObject().put("parts", JSONArray().put(JSONObject().put(
                        "text", JSONObject().put("translations", rows).toString())))))).toString()
            }
            jobs.start(b.id)
            jobs.execute(b.id) { done.complete(Unit) }
            assertEquals(BatchRecovery.INTERVAL_MS, withTimeout(15_000) { waiting.await() })
            val queued = batchStore.load(b.id)
            assertEquals("RETRY_WAIT", queued.state)
            assertEquals("TEXT_INCOMPLETE_RESPONSE", queued.items.first().errorCode)
            assertEquals(approved, store.load(pending.id).cues.first())
            assertEquals("Đã dịch valid", store.load(pending.id).cues[1].translation)
            assertEquals(listOf(listOf("valid", "missing"), listOf("missing")), calls.toList())
            val failedCheckpoint = store.load(missingSidecar.id).pipeline.task(BatchPipelineStage.EXTRACT)
            if (pauseWhileWaiting) jobs.pause() else proceed.complete(Unit)
            withTimeout(15_000) { done.await() }
            val result = batchStore.load(b.id)
            assertEquals(0L, result.autoRetryAtMs)
            assertNull(videos.batchBusy.value)
            assertEquals(failedCheckpoint, store.load(missingSidecar.id).pipeline.task(BatchPipelineStage.EXTRACT))
            val finalCues = store.load(pending.id).cues
            assertEquals(approved, finalCues.first())
            if (pauseWhileWaiting) {
                assertEquals("PAUSED", result.state)
                assertEquals(2, calls.size)
                assertTrue(finalCues.last().translation.isBlank())
            } else {
                assertEquals(listOf("missing"), calls[2])
                assertEquals(3, calls.size)
                assertEquals("DONE", result.items.first().state)
                assertEquals("Đã dịch missing", finalCues.last().translation)
                assertFalse(finalCues.last().qualityFlags.contains("TRANSLATION_NEEDS_REVIEW"))
            }
        } finally {
            jobs.cancel()
            VideoTranslationClient.overrideResponseForTest = previous
            preferences.edit().remove("standalone_api_key").remove("standalone_api_key_plain_fallback").commit()
        }
    }
}
