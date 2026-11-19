package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchDiagnosticsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun newId() = UUID.randomUUID().toString()
    private fun file(id: String) = File(VideoStore(context).folder(id), "smart-batch-metrics.json")

    @Test fun physicalDispatchesWaitsAndPartialResponsesStayDistinctAcrossResumeWithoutPrivateData() {
        val id = newId()
        val metrics = VideoBatchTelemetry(context, id)
        val deadline = 1_900_000_000_000L
        metrics.capacity(2, 4)
        metrics.dispatch(8, false)
        metrics.retry("API_SERVICE_503", 1, deadline)
        metrics.retry("API_SERVICE_503", 1, deadline)
        val resumed = VideoBatchTelemetry(context, id)
        resumed.retry("API_SERVICE_503", 1, deadline)
        resumed.dispatch(8, false)
        resumed.retry("API_SERVICE_503", 1, deadline) // A fresh dispatch starts a new wait episode.
        resumed.response(VideoTranslationClient.Result(mapOf("private-id-a" to "private-translation"),
            listOf("private-id-b"), "private-model", 100, truncated = true))
        resumed.dispatch(1, true)
        resumed.response(VideoTranslationClient.Result(emptyMap(), listOf("private-id-b"), "private-model", 50))
        resumed.retry("API_RATE_LIMIT", 1, deadline + 1000)
        resumed.failure("API_AUTH")
        resumed.failure("private-key-and-response-body")

        val snapshot = VideoBatchDiagnostics.read(context, listOf(id, id))
        assertEquals(1, snapshot.requestedProjects)
        assertEquals(1, snapshot.measuredProjects)
        assertEquals(3L, snapshot.requests)
        assertEquals(2L, snapshot.responses)
        assertEquals(2L, snapshot.partialResponses)
        assertEquals(1L, snapshot.emptyResponses)
        assertEquals(1L, snapshot.truncatedResponses)
        assertEquals(1L, snapshot.acceptedCues)
        assertEquals(2L, snapshot.missingCues)
        assertEquals(3L, snapshot.retryWaits)
        assertEquals(2L, snapshot.retryCauses.service)
        assertEquals(1L, snapshot.retryCauses.rateLimit)
        assertEquals(2L, snapshot.failedRequests)
        assertEquals(1L, snapshot.failureCauses.authorization)
        assertEquals(1L, snapshot.failureCauses.other)
        assertEquals(1L, snapshot.lastRequestCues)
        assertEquals(2L, snapshot.requestCapacity)
        assertEquals(4L, snapshot.requestCapacityCeiling)
        assertEquals(deadline + 1000, snapshot.retryAtMs)
        val text = file(id).readText()
        val json = JSONObject(text)
        assertTrue(json.keys().asSequence().all { json.get(it) is Number })
        assertFalse(text.contains("private"))
    }

    @Test fun oldCountersMigrateAndAggregateWithoutInterpretingStringsOrUnrelatedFields() {
        val oldId = newId()
        file(oldId).writeText(JSONObject().put("requests", 10).put("responses_with_token_usage", 3)
            .put("responses_without_token_usage", 2).put("accepted_cues", 20).put("partial_responses", 4)
            .put("failed_requests", 1).put("last_request_cues", 5).put("last_request_at_ms", 10)
            .put("api_key", "private-secret").put("prompt", "private-dialogue").toString())
        val legacy = VideoBatchDiagnostics.read(context, listOf(oldId))
        assertEquals(5L, legacy.responses)
        assertEquals(1L, legacy.failedRequests)
        val resumed = VideoBatchTelemetry(context, oldId)
        resumed.response(VideoTranslationClient.Result(mapOf("id" to "Việt"), emptyList(), "fixture", 10))
        resumed.flush()
        val newId = newId()
        file(newId).writeText(JSONObject().put("requests", 2).put("responses", 2)
            .put("last_request_cues", 6).put("last_request_at_ms", Long.MAX_VALUE)
            .put("request_capacity", 1).put("request_capacity_ceiling", 4).put("request_capacity_at_ms", 30)
            .put("missing_cues", "wrong-type").put("empty_responses", -1).put("failed_requests", 5.5)
            .put("failure_cause_service", 2).put("unknown_private_field", "private-data").toString())
        val aggregate = VideoBatchDiagnostics.read(context, listOf(oldId, newId))
        assertEquals(12L, aggregate.requests)
        assertEquals(8L, aggregate.responses)
        assertEquals(21L, aggregate.acceptedCues)
        assertEquals(0L, aggregate.missingCues)
        assertEquals(0L, aggregate.emptyResponses)
        assertEquals(1L, aggregate.failedRequests)
        assertEquals(2L, aggregate.failureCauses.service)
        assertEquals(6L, aggregate.lastRequestCues)
        assertEquals(1L, aggregate.requestCapacity)
        assertFalse(file(oldId).readText().contains("private"))
        assertFalse(aggregate.toString().contains("private"))
    }

    @Test fun ordinaryResponsesShareDispatchOrExitWriteWhileRetryDeadlinePersistsImmediately() {
        val id = newId()
        val metrics = VideoBatchTelemetry(context, id)
        val response = VideoTranslationClient.Result(mapOf("one" to "Xin chào"), emptyList(), "fixture", 10)
        metrics.dispatch(1, false)
        metrics.response(response)
        assertEquals(1L, VideoBatchDiagnostics.read(context, listOf(id)).requests)
        assertEquals(0L, VideoBatchDiagnostics.read(context, listOf(id)).responses)

        metrics.dispatch(1, false)
        assertEquals(1L, VideoBatchDiagnostics.read(context, listOf(id)).responses)
        val deadline = 1_900_000_000_000L
        metrics.retry("API_SERVICE_503", 1, deadline)
        assertEquals(deadline, VideoBatchDiagnostics.read(context, listOf(id)).retryAtMs)

        metrics.dispatch(1, false)
        metrics.response(response)
        metrics.flush()
        val final = VideoBatchDiagnostics.read(context, listOf(id))
        assertEquals(0L, final.retryAtMs)
        assertEquals(3L, final.requests)
        assertEquals(2L, final.responses)
    }

    @Test fun malformedOversizedAndInvalidPathsAreIgnoredAndAtomicBackupCanBeRecovered() {
        val backupId = newId()
        val path = file(backupId)
        path.writeText("{\"requests\":4}")
        assertTrue(path.renameTo(File(path.path + ".bak")))
        val malformedId = newId()
        file(malformedId).writeText("not JSON and private response")
        val oversizedId = newId()
        file(oversizedId).writeText("x".repeat(16 * 1024 + 1))
        val missingId = newId()
        val noFolder = File(context.filesDir, "video-projects/$missingId")
        val result = VideoBatchDiagnostics.read(context, listOf(backupId, malformedId, oversizedId, missingId, "../private"))
        assertEquals(5, result.requestedProjects)
        assertEquals(1, result.measuredProjects)
        assertEquals(4L, result.requests)
        assertFalse(noFolder.exists())
        assertTrue(path.isFile)
    }

    @Test fun countersSaturateAndReaderBoundsTheNumberOfProjectFiles() {
        val id = newId()
        val metrics = VideoBatchTelemetry(context, id)
        metrics.set("requests", Long.MAX_VALUE)
        metrics.add("requests")
        metrics.flush()
        assertEquals(Long.MAX_VALUE, VideoBatchDiagnostics.read(context, listOf(id)).requests)
        val many = List(202) { newId() }
        val result = VideoBatchDiagnostics.read(context, many)
        assertEquals(202, result.requestedProjects)
        assertEquals(0, result.measuredProjects)
        assertTrue(result.limited)
        val merged = VideoBatchDiagnostics.aggregate(listOf(JSONObject().put("requests", Long.MAX_VALUE), JSONObject().put("requests", 10)), 2)
        assertEquals(Long.MAX_VALUE, merged.requests)
    }

    @Test fun phaseMetricsRemainSeparateAndLegacyFilesMayOmitThem() {
        val legacy = JSONObject().put("requests", 4).put("extract_wall_ms", 600L)
        val measured = JSONObject().put("extract_wall_ms", 400L).put("translate_wall_ms", 300L)
            .put("polish_wall_ms", 200L).put("crop_wall_ms", 30L).put("bitmap_wall_ms", 15L)
            .put("ocr_wall_ms", 75L).put("checkpoint_wall_ms", 10L).put("project_refresh_wall_ms", 5L)
            .put("translate_requests", 4L).put("polish_requests", 2L).put("exact_source_reuses", 1L)
        val snapshot = VideoBatchDiagnostics.aggregate(listOf(legacy, measured), 2)
        assertEquals(4L, snapshot.requests)
        assertEquals(VideoDiagnosticPerformance(
            extractMs = 1000L, translateMs = 300L, polishMs = 200L,
            cropMs = 30L, bitmapMs = 15L, ocrMs = 75L, checkpointMs = 10L, refreshMs = 5L,
            translateRequests = 4L, polishRequests = 2L, exactSourceReuses = 1L
        ), snapshot.performance)
    }

    @Test fun simultaneousReadersAndAtomicWritersDoNotRestoreAnInProgressBackup() {
        val path = file(newId())
        VideoMetricFile.write(path, JSONObject().put("requests", 0))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                for (i in 1..50) VideoMetricFile.write(path, JSONObject().put("requests", i))
            }
            val reader = pool.submit {
                repeat(50) { assertTrue(VideoMetricFile.read(path).getLong("requests") in 0L..50L) }
            }
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
            assertEquals(50L, VideoMetricFile.read(path).getLong("requests"))
        } finally { pool.shutdownNow() }
    }
}
