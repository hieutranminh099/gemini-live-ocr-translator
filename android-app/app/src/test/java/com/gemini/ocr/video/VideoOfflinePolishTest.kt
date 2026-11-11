package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoOfflinePolishTest {
    @Test fun oldProjectsRemainOfflineAndUnknownPolicyNeverEnablesCloud() {
        val legacy = project(VideoPolishPolicy.NONE)
        assertFalse(legacy.json().has("offlinePolishPolicy"))
        assertEquals(VideoPolishPolicy.NONE, VideoProject.from(legacy.json()).offlinePolishPolicy)
        assertEquals(VideoPolishPolicy.NONE, VideoProject.from(legacy.json().put("offlinePolishPolicy", "future")).offlinePolishPolicy)
        val enabled = legacy.withOfflinePolishPolicy(VideoPolishPolicy.ALL)
        assertEquals(VideoPolishPolicy.ALL, VideoProject.from(enabled.json()).offlinePolishPolicy)
        assertEquals(VideoPolishPolicy.NONE, VideoProject.from(enabled.json().put("translationMode", "HYBRID")).offlinePolishPolicy)
    }

    @Test fun flaggedUsesTextAndContextOnlyAfterTheOfflineDraftAndKeepsApproved() = runBlocking {
        val original = project(VideoPolishPolicy.FLAGGED)
        withFixture(original) { store, jobs, owner ->
            var requests = 0
            VideoPolishClient.overrideResponseForTest = { batch ->
                requests++
                assertEquals(listOf("flagged"), batch.cueIds)
                assertEquals(listOf("Chào bạn."), batch.offlineDrafts)
                val context = JSONObject(batch.videoContext)
                assertEquals("Nhân vật là bạn học", context.getString("filmContext"))
                assertTrue(context.getJSONArray("previous").length() > 0)
                assertTrue(context.getJSONArray("following").length() > 0)
                response(batch.cueIds)
            }
            for (stage in BatchPipelineStage.entries.take(3)) {
                val saved = jobs.runStageForBatch(owner, original.id, stage)
                assertEquals(original.pipeline.task(stage), saved.pipeline.task(stage))
            }
            assertEquals(0, requests)
            val polished = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertTrue(VideoStagedPipeline.completed(polished, BatchPipelineStage.POLISH))
            assertEquals(1, requests)
            assertEquals(original.cues[0], polished.cues[0])
            assertEquals(original.cues[2], polished.cues[2])
            assertEquals("Xin chào.", polished.cues[1].polishedVi)
            assertEquals("Chào bạn.", polished.cues[1].draftVi)
            assertEquals(original.cues.map(::timing), store.load(original.id).cues.map(::timing))
        }
    }

    @Test fun restartSendsOnlyUnresolvedIdsAfterAValidPartialEditorialResponse() = runBlocking {
        val original = project(VideoPolishPolicy.ALL)
        withFixture(original) { store, jobs, owner ->
            val requests = mutableListOf<List<String>>()
            VideoPolishClient.overrideResponseForTest = { batch ->
                requests += batch.cueIds
                response(if (requests.size == 1) batch.cueIds.take(1) else batch.cueIds)
            }
            val partial = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertEquals("NEEDS_REVIEW", partial.pipeline.task(BatchPipelineStage.POLISH).status)
            assertEquals("Xin chào.", partial.cues[1].polishedVi)
            assertEquals(original.cues[2].translation, partial.cues[2].translation)
            assertTrue("POLISH_PENDING" in partial.cues[2].qualityFlags)
            assertEquals(2L, partial.pipeline.task(BatchPipelineStage.POLISH).lastCheckpoint)
            val completed = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertEquals(listOf(listOf("flagged", "clean"), listOf("clean")), requests)
            assertEquals(partial.cues[1], completed.cues[1])
            assertEquals(original.cues[0], completed.cues[0])
            assertEquals(original.cues.map(::timing), completed.cues.map(::timing))
            assertFalse("POLISH_PENDING" in completed.cues[2].qualityFlags)
            assertEquals("COMPLETE", store.load(original.id).pipeline.task(BatchPipelineStage.POLISH).status)
        }
    }

    @Test fun quotaFailureAllowsOfflineFinishAndRetainsCooldownWhenReenabled() = runBlocking {
        val original = project(VideoPolishPolicy.ALL)
        withFixture(original) { store, jobs, owner ->
            val retryAt = System.currentTimeMillis() + 60_000
            var requests = 0
            VideoPolishClient.overrideResponseForTest = {
                requests++
                throw VideoApiException("API_RATE_LIMIT", transient = true, nextRetryAt = retryAt, detail = "Chờ hạn mức.")
            }
            val limited = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertEquals(retryAt, limited.pipeline.task(BatchPipelineStage.POLISH).nextRetryAt)
            assertEquals(1, requests)
            assertEquals(original.cues.map { it.translation }, limited.cues.map { it.translation })
            store.save(limited.withOfflinePolishPolicy(VideoPolishPolicy.NONE))
            val offline = withTimeout(5_000) {
                jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
                jobs.runStageForBatch(owner, original.id, BatchPipelineStage.FINISH)
            }
            assertTrue(VideoStagedPipeline.completed(offline, BatchPipelineStage.FINISH))
            assertEquals(original.cues[0], offline.cues[0])
            assertEquals(original.cues.map { it.translation }, offline.cues.map { it.translation })
            for (stage in BatchPipelineStage.entries.take(3)) assertEquals(original.pipeline.task(stage), offline.pipeline.task(stage))
            assertTrue(store.exportFile(original.id, "srt").readText().contains("Chào bạn."))
            store.save(offline.withOfflinePolishPolicy(VideoPolishPolicy.ALL))
            val waiting = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertEquals("API_RATE_LIMIT", waiting.pipeline.task(BatchPipelineStage.POLISH).errorCode)
            assertEquals(1, requests)
        }
    }

    @Test fun localDraftCacheSeparatesDifferentNeighborsAndKeepsDistinctOccurrences() {
        val sources = listOf("甲", "你好", "乙", "丁", "你好", "戊", "甲", "你好", "乙")
        val cues = sources.mapIndexed { i, source -> VideoCue("$i", i * 1000L, i * 1000L + 900, source) }
        val cache = VideoOfflineDraftCache(limit = 1)
        cache.remember(cues, 1, "Xin chào.")
        assertNull(cache.lookup(cues, 4))
        assertEquals("Xin chào.", cache.lookup(cues, 7))
        assertNotEquals(timing(cues[1]), timing(cues[7]))
        cache.remember(cues, 4, "Chào ngài.")
        assertNull(cache.lookup(cues, 7))
    }

    @Test fun exhaustedNetworkFailureDoesNotDispatchEveryRemainingPolishGroup() = runBlocking {
        val rows = List(80) { i -> VideoCue("cue-$i", i * 100L, i * 100L + 90, "你好", "Chào bạn.") }
        val original = project(VideoPolishPolicy.ALL).copy(durationMs = 8_000, scanMs = 8_000, cues = rows)
        withFixture(original) { _, jobs, owner ->
            var requests = 0
            VideoPolishClient.overrideResponseForTest = { batch ->
                requests++
                assertTrue(batch.cueIds.size < rows.size)
                throw VideoApiException("API_NETWORK", transient = true, detail = "Kết nối bị gián đoạn.")
            }
            val interrupted = jobs.runStageForBatch(owner, original.id, BatchPipelineStage.POLISH)
            assertEquals(1, requests)
            assertEquals("API_NETWORK", interrupted.pipeline.task(BatchPipelineStage.POLISH).errorCode)
            assertEquals(rows.map { it.translation }, interrupted.cues.map { it.translation })
            assertEquals(rows.map(::timing), interrupted.cues.map(::timing))
        }
    }

    private fun project(policy: VideoPolishPolicy): VideoProject {
        val rows = listOf(
            VideoCue("approved", 100, 900, "早上好", "Chào buổi sáng.", approved = true),
            VideoCue("flagged", 1100, 1900, "你好", "Chào bạn.", draftVi = "Chào bạn.", qualityFlags = listOf("LOW_OCR_CONFIDENCE")),
            VideoCue("clean", 2100, 2900, "你好朋友", "Chào bạn nhé.", draftVi = "Chào bạn nhé.")
        )
        val pipeline = VideoPipelineCheckpoint(contextProfile = "Nhân vật là bạn học")
            .record(BatchPipelineStage.EXTRACT, "COMPLETE", 3000)
            .record(BatchPipelineStage.CLEAN, "COMPLETE", 3)
            .record(BatchPipelineStage.TRANSLATE, "COMPLETE", 3)
        return VideoProject(UUID.randomUUID().toString(), "Synthetic offline draft", "file:///fixture.mp4", 3000, 320, 180,
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, cues = rows, scanMs = 3000,
            pipeline = pipeline, offlinePolishPolicy = policy)
    }

    private suspend fun withFixture(original: VideoProject, block: suspend (VideoStore, VideoJobs, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        store.save(original)
        val jobs = VideoJobs(context)
        val owner = UUID.randomUUID().toString()
        val previousLive = SessionState.running.value
        val previousNetworkHook = StandaloneTranslator.beforeNetworkCallForTest
        val previousResponse = VideoPolishClient.overrideResponseForTest
        SessionState.running.value = false
        StandaloneTranslator.beforeNetworkCallForTest = { error("Unexpected external request in test") }
        jobs.reserveBatch(owner)
        try { withTimeout(15_000) { block(store, jobs, owner) } }
        finally {
            jobs.releaseBatch(owner)
            SessionState.running.value = previousLive
            StandaloneTranslator.beforeNetworkCallForTest = previousNetworkHook
            VideoPolishClient.overrideResponseForTest = previousResponse
        }
    }

    private fun timing(cue: VideoCue) = Triple(cue.id, cue.startMs, cue.endMs)

    private fun response(ids: List<String>): String {
        val rows = JSONArray(ids.map { JSONObject().put("id", it).put("translation", "Xin chào.").put("needsReview", false) })
        val content = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", JSONObject().put("translations", rows).toString())))
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP").put("content", content))).toString()
    }
}
