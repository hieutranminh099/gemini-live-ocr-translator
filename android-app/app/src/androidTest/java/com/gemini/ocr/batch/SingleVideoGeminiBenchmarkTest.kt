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
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.flow.first
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
        try {
            ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { activity ->
                val start = SystemClock.elapsedRealtime()
                val batch = jobs.create("Synthetic single video", BatchKind.SERIES)
                val item = jobs.add(batch.id, listOf(Uri.fromFile(input))).items.single()
                jobs.configure(batch.id, VideoTranslationMode.GEMINI, VideoSourceMode.OCR)
                jobs.applySharedRoi(batch.id, listOf(0f, .65f, 1f, .97f))
                val ready = SystemClock.elapsedRealtime()
                activity.onActivity { jobs.start(batch.id) }
                withTimeout(480_000L) { jobs.busy.first { it == null } }
                val review = SystemClock.elapsedRealtime()
                val result = BatchStore(context).load(batch.id)
                assertEquals("Batch did not reach review. Stage errors: ${result.items.map { it.errorCode }}", "REVIEW", result.state)
                val sources = jobs.validatedSources(batch.id, approveAll = true)
                val library = MovieLibrary.get(context)
                library.confirmSources(batch.id, result.title, sources)
                val end = SystemClock.elapsedRealtime()
                val movie = library.movies.value.single { it.id == batch.id }
                val saved = movie.episodes.single().source!!.cues
                val project = VideoStore(context).load(item.projectId)
                assertTrue("Expected actual OCR output from the synthetic fixture", saved.size >= 36)
                assertTrue(saved.all { it.translation.isNotBlank() && it.approved })
                assertTrue("Expected real Gemini requests", project.cloudCalls > 0)
                val sourceTimeline = saved.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.source}" }
                val digest = MessageDigest.getInstance("SHA-256").digest(sourceTimeline.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                File(context.filesDir, "benchmark-result.json").writeText(JSONObject()
                    .put("total_ms", end - start).put("import_ms", ready - start)
                    .put("processing_ms", review - ready).put("save_ms", end - review)
                    .put("cue_count", saved.size).put("cloud_calls", project.cloudCalls)
                    .put("timeline_sha256", digest).toString())
            }
        } finally {
            if (jobs.busy.value != null) jobs.cancel()
            settings.standaloneApiKey = ""
        }
    }
}
