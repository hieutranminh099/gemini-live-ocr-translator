package com.gemini.ocr.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Opt-in, at most two real requests for one generated four-second fixture. No retries/export. */
@RunWith(AndroidJUnit4::class)
class MinimalGeminiWorkflowNativeTest {
    @Test(timeout = 210_000L)
    fun realVisionThenTranslationKeepsSyntheticChineseAndSourceTimestamps(): Unit = runBlocking {
        assumeTrue("Explicit bounded real Gemini opt-in required",
            InstrumentationRegistry.getArguments().getString("executeMinimalGemini") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr")
        require(!BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value)
        require(StandaloneTranslator.overrideGeminiTranslator == null && StandaloneTranslator.overrideOnDeviceTranslator == null)
        require(StandaloneTranslator.beforeNetworkCallForTest == null)
        val jobs = VideoJobs.get(context)
        require(jobs.busy.value == null && jobs.batchBusy.value == null && BatchJobs.get(context).busy.value == null)
        val folder = File(checkNotNull(context.getExternalFilesDir(null)), "stability-fixtures").apply { mkdirs() }
        val reportFile = File(folder, "minimal-gemini-${UUID.randomUUID()}.json")
        val report = JSONObject().put("schema", 1).put("completed", false)
            .put("scope", "Fresh Gemini visual OCR and translation of a generated four-second fixture only; no export or Google sync")
            .put("maximumNetworkDispatches", 2).put("automaticRetries", 0)
            .put("semanticReview", "Root must review actual Vietnamese; this test does not assert exact wording")
        // Never store, print or pass the key anywhere except the existing application pipeline.
        val keyConfigured = AppSettings(context).effectiveStandaloneApiKey().isNotBlank()
        report.put("keyConfigured", keyConfigured)
        if (!keyConfigured) {
            report.put("status", "BLOCKED_NO_CONFIGURED_KEY")
            reportFile.writeText(report.toString(2))
            assumeTrue("No configured Gemini key; no request was made", false)
        }
        val source = File(folder, "chinese-4s.mp4")
        require(source.isFile && source.length() in 1..5_000_000L)
        val metadata = VideoDecoder.metadata(context, Uri.fromFile(source))
        require(metadata.durationMs in 3_500L..4_500L)
        val started = SystemClock.elapsedRealtime()
        val allowed = AtomicInteger()
        val blocked = AtomicInteger()
        var maxPss = 0
        var createdId: String? = null
        fun sample() { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); maxPss = maxOf(maxPss, info.totalPss) }
        fun safeDiagnostic(message: String): String = Regex("\\[VISION_[A-Z_]+\\]|HTTP \\d{3}|\\((?:429|401|403)\\)")
            .findAll(message).map { it.value }.joinToString(" ").take(100)
        try {
            sample()
            val created = jobs.create(Uri.fromFile(source), "Synthetic four-second Gemini audit")
            createdId = created.id
            // Keep this unconfirmed private fixture outside every legacy automatic backup path.
            File(VideoStore(context).folder(created.id), "batch-owner").writeText(UUID.randomUUID().toString())
            jobs.save(created.copy(sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.GEMINI,
                roi = listOf(0f, .70f, 1f, .99f), glossary = "", profile = "manga", visualSampleMs = 250L))
            report.put("projectId", created.id).put("durationMs", metadata.durationMs)
                .put("sourceSha256", sha256(source)).put("automaticCloudBackupBlocked", true)
            StandaloneTranslator.beforeNetworkCallForTest = {
                synchronized(allowed) {
                    if (allowed.get() >= 2) {
                        blocked.incrementAndGet()
                        throw IllegalStateException("Minimal integration request limit reached; no further request sent")
                    }
                    allowed.incrementAndGet()
                }
            }
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", created.id)).use { scenario ->
                scenario.onActivity { jobs.start(created.id) }
                withTimeout(180_000L) { while (jobs.busy.value == created.id) { sample(); delay(100) } }
            }
            val saved = VideoStore(context).load(created.id)
            val rows = JSONArray(saved.cues.map { cue -> JSONObject()
                .put("source", cue.source).put("vietnamese", cue.translation)
                .put("startMs", cue.startMs).put("endMs", cue.endMs)
                .put("approved", cue.approved).put("model", cue.model) })
            val acceptedOcr = saved.scanMs == saved.durationMs && saved.cues.isNotEmpty()
            val acceptedTranslation = saved.stage == VideoStage.REVIEW && saved.cues.isNotEmpty() && saved.cues.all { it.translation.isNotBlank() }
            report.put("stage", saved.stage.name).put("diagnostic", safeDiagnostic(saved.message))
                .put("projectAttemptCounter", saved.cloudCalls)
                .put("acceptedOcrGroups", if (acceptedOcr) 1 else 0)
                .put("acceptedTranslationGroups", if (acceptedTranslation) 1 else 0)
                .put("httpSuccessCount", JSONObject.NULL)
                .put("httpSuccessCountNote", "Not exposed by this pipeline; attempts and accepted result groups are reported separately")
                .put("cues", rows).put("ocrMetrics", saved.ocrMetrics)
                .put("extractionElapsedMs", saved.extractionElapsedMs)
                .put("translationElapsedMs", saved.translationElapsedMs).put("workerMaxPssKiB", saved.maxPssKiB)
            reportFile.writeText(report.toString(2))
            assertEquals("Worker did not finish; safe diagnostic=${safeDiagnostic(saved.message)}", VideoStage.REVIEW, saved.stage)
            assertEquals("Expected one OCR request and one translation request", 2, allowed.get())
            assertEquals("No request beyond the fixed plan may be attempted", 0, blocked.get())
            assertTrue(acceptedOcr && acceptedTranslation)
            assertEquals("Generated fixture contains exactly two Chinese subtitle occurrences", 2, saved.cues.size)
            fun han(text: String) = text.filter { it in '\u3400'..'\u9fff' }
            val expected = listOf(Triple("你好，我是小明", 500L, 1_750L), Triple("我没有两本书", 2_000L, 3_250L))
            val timing = JSONArray()
            report.put("timing", timing).put("missedKnownLines", expected.count { (text, _, _) -> saved.cues.none { han(it.source) == han(text) } })
                .put("maximumTimestampToleranceMs", 500)
            expected.forEach { (text, start, end) ->
                val matches = saved.cues.filter { han(it.source) == han(text) }
                assertEquals("Known synthetic source line must occur once: $text", 1, matches.size)
                val cue = matches.single()
                val startError = abs(cue.startMs - start)
                val endError = abs(cue.endMs - end)
                timing.put(JSONObject().put("source", text).put("startErrorMs", startError).put("endErrorMs", endError))
                assertTrue("Source onset differs by ${startError}ms", startError <= 500L)
                assertTrue("Source disappearance differs by ${endError}ms", endError <= 500L)
                assertTrue(cue.translation.isNotBlank())
                assertFalse("New AI text must remain Draft", cue.approved)
            }
            assertEquals(report.getString("sourceSha256"), sha256(source))
            report.put("sourcePreserved", true).put("completed", true).put("status", "PASS_REQUIRES_SEMANTIC_REVIEW")
        } catch (error: Throwable) {
            report.put("status", "FAIL").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            val id = createdId
            if (id != null && jobs.busy.value == id) {
                jobs.pause()
                withTimeoutOrNull(10_000L) { while (jobs.busy.value == id) delay(100) }
            }
            StandaloneTranslator.beforeNetworkCallForTest = null
            sample()
            report.put("allowedNetworkDispatches", allowed.get()).put("blockedExtraDispatches", blocked.get())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("sampledPeakPssKiB", maxPss)
            reportFile.writeText(report.toString(2))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(32 * 1024); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
