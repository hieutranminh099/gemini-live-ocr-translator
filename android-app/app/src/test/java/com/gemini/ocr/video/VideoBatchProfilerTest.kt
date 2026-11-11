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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchProfilerTest {
    @Test fun stageTimingAndCueSnapshotSurviveReopenWithoutPersistingPrivateText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val project = VideoProject(id, "private-fixture-title", "file:///private-fixture.mp4", 5000, 640, 360,
            sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID, scanMs = 5000,
            cues = listOf(
                VideoCue("one", 0, 1000, "private-fixture-source", "private-fixture-approved", approved = true),
                VideoCue("two", 1000, 2000, "private-fixture-pending"),
                VideoCue("three", 2000, 3000, "private-fixture-flagged", "private-fixture-translation",
                    refinementState = "done", qualityFlags = listOf("TRANSLATION_NEEDS_REVIEW")),
                VideoCue("four", 3000, 4000, "private-fixture-asr", "private-fixture-translation",
                    refinementState = "done", qualityFlags = listOf("ASR_UNAVAILABLE")),
                VideoCue("five", 4000, 5000, "private-fixture-reviewed", "private-fixture-approved",
                    approved = true, qualityFlags = listOf("LOW_OCR_CONFIDENCE"))
            ),
            ocrMetrics = JSONObject().put("decoded_frames", 120).put("ocr_native_calls", 12)
                .put("recognition_requests", 10).put("exact_pixel_reused_observations", 25)
                .put("unrecognized_text", "private-fixture-ignored"))
        val file = File(VideoStore(context).folder(id), "smart-batch-metrics.json")
        val telemetry = VideoBatchTelemetry(context, id)
        telemetry.add("checkpoint_writes", 9L)
        telemetry.add("retry_after_ms", 1_900_000_000_000L)
        var wall = 100L
        var cpu = 10L
        val clock = { VideoBatchProfiler.Reading(wall, cpu) }
        val profiler = VideoBatchProfiler(telemetry, VideoProfileStage.EXTRACT, clock)
        fun advance(wallMs: Long, cpuMs: Long) { wall += wallMs; cpu += cpuMs }

        advance(40, 12); profiler.checkpoint(VideoProfileStage.CLEAN, 320)
        advance(10, 2); profiler.checkpoint(VideoProfileStage.TRANSLATE, 512)
        advance(15, 7); profiler.checkpoint(VideoProfileStage.LIVE_WAIT, 100)
        advance(200, 80); profiler.checkpoint(VideoProfileStage.TRANSLATE, 256)
        advance(5, 3); profiler.checkpoint(VideoProfileStage.POLISH, 512)
        advance(6, 4); profiler.checkpoint(VideoProfileStage.FINISH, 512)
        advance(8, 4); profiler.checkpoint(VideoProfileStage.EXPORT, 400)
        advance(16, 10); profiler.checkpoint(pssKiB = 480)
        profiler.finish(project)
        telemetry.flush()
        val first = JSONObject(file.readText())
        for ((key, value) in mapOf(
            "extract_wall_ms" to 40L, "clean_wall_ms" to 10L, "translate_wall_ms" to 20L,
            "polish_wall_ms" to 6L, "finish_wall_ms" to 8L, "export_wall_ms" to 16L,
            "live_wait_wall_ms" to 200L, "worker_wall_ms" to 300L,
            "translate_process_cpu_ms" to 10L, "live_wait_process_cpu_ms" to 80L,
            "worker_process_cpu_ms" to 122L, "peak_pss_kib" to 512L,
            "extract_peak_pss_kib" to 320L, "translate_peak_pss_kib" to 512L,
            "live_wait_peak_pss_kib" to 256L, "export_peak_pss_kib" to 480L,
            "source_cues" to 5L, "translated_cues" to 4L, "completed_cues" to 4L,
            "pending_cues" to 1L, "approved_cues" to 2L, "needs_review_cues" to 2L,
            "decoded_frames" to 120L, "ocr_native_calls" to 12L, "recognition_requests" to 10L,
            "exact_pixel_reused_observations" to 25L
        )) assertEquals(key, value, first.getLong(key))

        val reopened = VideoBatchTelemetry(context, id)
        val resumed = VideoBatchProfiler(reopened, VideoProfileStage.TRANSLATE, clock)
        advance(20, 5)
        resumed.checkpoint(pssKiB = 128)
        resumed.finish(project.copy(sourceMode = VideoSourceMode.SIDECAR, cues = project.cues.map { cue ->
            if (cue.id == "two") cue.copy(translation = "private-fixture-resumed", refinementState = "done") else cue
        }))
        reopened.flush()
        val text = file.readText()
        val second = JSONObject(text)
        assertEquals(320L, second.getLong("worker_wall_ms"))
        assertEquals(40L, second.getLong("translate_wall_ms"))
        assertEquals(127L, second.getLong("worker_process_cpu_ms"))
        assertEquals(512L, second.getLong("peak_pss_kib"))
        assertEquals(5L, second.getLong("translated_cues"))
        assertEquals(0L, second.getLong("pending_cues"))
        assertEquals(1L, second.getLong("needs_review_cues"))
        assertEquals(0L, second.getLong("decoded_frames"))
        assertEquals(0L, second.getLong("exact_pixel_reused_observations"))
        assertEquals(9L, second.getLong("checkpoint_writes"))
        assertEquals(1_900_000_000_000L, second.getLong("retry_after_ms"))
        assertTrue(second.keys().asSequence().all { second.get(it) is Number })
        assertFalse(text.contains("private-fixture"))
    }
}
