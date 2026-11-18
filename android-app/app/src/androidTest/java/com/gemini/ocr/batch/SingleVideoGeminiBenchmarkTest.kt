package com.gemini.ocr.batch

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SingleVideoGeminiBenchmarkTest {
    @Test fun measureImportThroughMovieLibrary() = runBlocking {
        val registry = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("singleVideoGeminiBenchmark") == "true")
        val context = registry.targetContext
        check(BuildConfig.DEBUG && context.packageName in setOf("com.gemini.ocr.perfbase", "com.gemini.ocr.perfnext"))
        check(VideoCloudSync.get(context).state.value.accountEmail.isBlank())
        val keyFile = File(context.filesDir, "benchmark-key")
        val input = File(context.filesDir, "benchmark.mp4")
        require(input.isFile && input.length() in 1..32_000_000L)
        val key = keyFile.readText().trim()
        check(keyFile.delete())
        require(key.isNotBlank()) { "Missing benchmark Gemini credential" }
        val settings = AppSettings(context)
        settings.standaloneApiKey = key
        require(settings.hasGeminiVideoKey())
        val jobs = BatchJobs.get(context)
        val videoJobs = VideoJobs.get(context)
        val trace = File(context.filesDir, "benchmark-progress.jsonl")
        fun status(event: String, fields: JSONObject = JSONObject()) {
            trace.appendText(fields.put("event", event).toString() + "\n")
        }
        try {
            status("inputs_ready")
            ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { activity ->
                status("activity_ready")
                val start = SystemClock.elapsedRealtime()
                val batch = jobs.create("Synthetic single video", BatchKind.SERIES)
                val item = jobs.add(batch.id, listOf(Uri.fromFile(input))).items.single()
                jobs.configure(batch.id, VideoTranslationMode.GEMINI, VideoSourceMode.OCR)
                jobs.applySharedRoi(batch.id, listOf(0f, .65f, 1f, .97f))
                val ready = SystemClock.elapsedRealtime()
                activity.onActivity { jobs.start(batch.id) }
                status("worker_dispatched")
                val review = coroutineScope {
                    val monitor = launch(Dispatchers.IO) {
                        var lastState = ""
                        var changedAt = SystemClock.elapsedRealtime()
                        while (jobs.busy.value != null) {
                            val savedBatch = jobs.batches.value.firstOrNull { it.id == batch.id }
                            val savedItem = savedBatch?.items?.singleOrNull()
                            val video = videoJobs.projects.value.firstOrNull { it.id == item.projectId }
                            val task = savedItem?.let { video?.pipeline?.task(it.stage) }
                            val snapshot = JSONObject()
                                .put("batch", savedBatch?.state ?: "MISSING")
                                .put("stage", savedItem?.stage?.name ?: "MISSING")
                                .put("status", savedItem?.stageStatus ?: "MISSING")
                                .put("error", savedItem?.errorCode.orEmpty())
                                .put("video_stage", video?.stage?.name ?: "MISSING")
                                .put("task_status", task?.status.orEmpty())
                                .put("task_error", task?.errorCode.orEmpty())
                                .put("scan_ms", video?.scanMs ?: 0L)
                                .put("cues", video?.cues?.size ?: 0)
                                .put("ocr_native_calls", video?.ocrMetrics?.optLong("ocr_native_calls") ?: 0L)
                                .put("ocr_wall_ms", video?.ocrMetrics?.optLong("ocr_wall_ms") ?: 0L)
                                .put("decode_wall_ms", video?.ocrMetrics?.optLong("decode_and_other_wall_ms") ?: 0L)
                                .put("cloud_calls", video?.cloudCalls ?: 0)
                                .put("attempts", savedBatch?.autoRetryAttempts ?: 0)
                                .put("retry_at_ms", maxOf(savedBatch?.autoRetryAtMs ?: 0L, task?.nextRetryAt ?: 0L))
                            val state = snapshot.toString()
                            val now = SystemClock.elapsedRealtime()
                            if (lastState != state) {
                                status("checkpoint", snapshot.put("elapsed_ms", now - start))
                                changedAt = now
                                lastState = state
                            } else if (now - changedAt >= 120_000 && snapshot.getLong("retry_at_ms") <= System.currentTimeMillis()) {
                                status("checkpoint_stalled", snapshot.put("elapsed_ms", now - start))
                                error("Benchmark checkpoint did not advance for two minutes")
                            }
                            delay(5_000)
                        }
                    }
                    try {
                        withTimeout(480_000L) { jobs.busy.first { it == null } }
                        SystemClock.elapsedRealtime()
                    } finally {
                        monitor.cancelAndJoin()
                    }
                }
                val result = BatchStore(context).load(batch.id)
                assertEquals("Batch did not reach review. Stage errors: ${result.items.map { it.errorCode }}", "REVIEW", result.state)
                val sources = jobs.validatedSources(batch.id, approveAll = true)
                status("review_complete")
                val library = MovieLibrary.get(context)
                library.confirmSources(batch.id, result.title, sources)
                val end = SystemClock.elapsedRealtime()
                val movie = library.movies.value.single { it.id == batch.id }
                val saved = movie.episodes.single().source!!.cues
                val project = VideoStore(context).load(item.projectId)
                assertTrue("Expected actual OCR output from the synthetic fixture", saved.size >= 5)
                assertTrue(saved.all { it.translation.isNotBlank() && it.approved })
                assertTrue("Expected real Gemini requests", project.cloudCalls > 0)
                val sourceTimeline = saved.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.source}" }
                val digest = MessageDigest.getInstance("SHA-256").digest(sourceTimeline.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                File(context.filesDir, "benchmark-result.json").writeText(JSONObject()
                    .put("total_ms", end - start).put("import_ms", ready - start)
                    .put("processing_ms", review - ready).put("save_ms", end - review)
                    .put("cue_count", saved.size).put("cloud_calls", project.cloudCalls)
                    .put("source_duration_ms", project.durationMs)
                    .put("ocr_native_calls", project.ocrMetrics.getLong("ocr_native_calls"))
                    .put("ocr_wall_ms", project.ocrMetrics.getLong("ocr_wall_ms"))
                    .put("decode_wall_ms", project.ocrMetrics.getLong("decode_and_other_wall_ms"))
                    .put("uniform_roi_skips", project.ocrMetrics.optLong("uniform_roi_skips"))
                    .put("timeline_sha256", digest).toString())
            }
        } finally {
            if (jobs.busy.value != null) jobs.cancel()
            settings.standaloneApiKey = ""
        }
    }
}
