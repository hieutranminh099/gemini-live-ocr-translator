package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Executes the real serial batch worker, with synthetic text replies and no media/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchRequestContextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        SessionState.running.value = false
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        VideoSmartBatchSettings(context).economyMode = false
        StandaloneTranslator.beforeNetworkCallForTest = { error("No network in request-context tests") }
    }

    @After fun reset() {
        VideoTranslationClient.overrideResponseForTest = null
        StandaloneTranslator.beforeNetworkCallForTest = null
        SessionState.running.value = false
    }

    @Test fun serialBatchWorkerInheritsReducedAdmissionWithoutRewritingApprovedRows() = runBlocking {
        val store = VideoStore(context)
        val rows = listOf(
            VideoCue("context-approved", 0, 800, "你好", "Bản đã duyệt", approved = true),
            VideoCue("context-pending-a", 1000, 1800, "他来了"),
            VideoCue("context-pending-b", 2000, 2800, "再见")
        )
        val pipeline = VideoPipelineCheckpoint()
            .record(BatchPipelineStage.EXTRACT, "COMPLETE", 3000)
            .record(BatchPipelineStage.CLEAN, "COMPLETE", rows.size.toLong())
        val original = store.save(VideoProject(UUID.randomUUID().toString(), "Synthetic request context",
            "file:///not-opened-context.mp4", 3000, 640, 360,
            sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID,
            cues = rows, scanMs = 3000, stage = VideoStage.FAILED,
            resumeStage = VideoStage.TRANSLATING, pipeline = pipeline))
        var callbacks = 0
        var workerCapacity: VideoRequestCapacity? = null
        var requestedIds = emptyList<String>()
        VideoTranslationClient.overrideResponseForTest = { request ->
            callbacks++
            // This runs inside VideoJobs' own worker and the client's IO context. It must
            // still see the caller's reduced gate even though no parallel lane was used.
            workerCapacity = VideoApiRecovery.currentCapacity()
            requestedIds = request.cueIds
            val translations = JSONArray(request.cueIds.map {
                JSONObject().put("id", it).put("translation", "Bản Việt $it")
            })
            JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    JSONObject().put("translations", translations).toString())))))).toString()
        }
        val jobs = VideoJobs(context)
        val batchId = UUID.randomUUID().toString()
        jobs.reserveBatch(batchId)
        try {
            VideoApiRecovery.withAdaptiveBatch(4) {
                val overload = VideoApiException("API_SERVICE_503", transient = true)
                try {
                    VideoApiRecovery.withAccountAdmission<Unit> { throw overload }
                    fail("The synthetic service failure must propagate")
                } catch (actual: VideoApiException) { assertSame(overload, actual) }
                assertEquals(VideoRequestCapacity(2, 4), VideoApiRecovery.currentCapacity())
                val result = withTimeout(30_000) {
                    jobs.runStageForBatch(batchId, original.id, BatchPipelineStage.TRANSLATE)
                }
                assertEquals(1, callbacks)
                assertEquals(VideoRequestCapacity(2, 4), workerCapacity)
                assertEquals(rows.drop(1).map { it.id }, requestedIds)
                assertTrue(VideoStagedPipeline.completed(result, BatchPipelineStage.TRANSLATE))
                assertTrue(result.cues.all { VideoTranslationPlan.isComplete(it, true) })
                assertEquals(rows.first(), result.cues.first())
                assertEquals(rows.map { listOf(it.id, it.source, it.startMs, it.endMs) },
                    result.cues.map { listOf(it.id, it.source, it.startMs, it.endMs) })
                assertNull(jobs.busy.value)
            }
            assertNull(VideoApiRecovery.currentCapacity())
        } finally {
            jobs.pause()
            jobs.releaseBatch(batchId)
        }
    }
}
