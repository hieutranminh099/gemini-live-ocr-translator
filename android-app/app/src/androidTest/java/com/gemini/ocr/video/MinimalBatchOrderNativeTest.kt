package com.gemini.ocr.video

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchOrderAnalysis
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** At most one real Gemini decision from four local OCR samples of two generated clips. */
@RunWith(AndroidJUnit4::class)
class MinimalBatchOrderNativeTest {
    @Test(timeout = 150_000L)
    fun syntheticChronologyIsPreviewedBeforeOneExplicitOrderApplication(): Unit = runBlocking {
        assumeTrue("Explicit bounded order-analysis opt-in required",
            InstrumentationRegistry.getArguments().getString("executeMinimalBatchOrder") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr" && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value)
        require(StandaloneTranslator.beforeNetworkCallForTest == null && StandaloneTranslator.overrideGeminiTranslator == null)
        val jobs = BatchJobs.get(context)
        val videoJobs = VideoJobs.get(context)
        require(jobs.busy.value == null && videoJobs.busy.value == null && videoJobs.batchBusy.value == null)
        val folder = File(checkNotNull(context.getExternalFilesDir(null)), "stability-fixtures").apply { mkdirs() }
        val output = File(folder, "order-minimal-${UUID.randomUUID()}.json")
        val report = JSONObject().put("schema", 1).put("completed", false).put("maximumNetworkAttempts", 1)
            .put("automaticRetries", 0).put("scope", "Two generated three-second clips; local ML Kit OCR then one optional Gemini chronology decision; no video upload or translation")
        val keyConfigured = AppSettings(context).effectiveStandaloneApiKey().isNotBlank()
        report.put("keyConfigured", keyConfigured)
        if (!keyConfigured) {
            report.put("status", "BLOCKED_NO_CONFIGURED_KEY")
            output.writeText(report.toString(2))
            assumeTrue("No configured Gemini key; no request was made", false)
        }
        val sources = listOf(File(folder, "clip-a.mp4"), File(folder, "clip-b.mp4"))
        sources.forEach { source ->
            require(source.isFile && source.length() in 1..5_000_000L)
            require(VideoDecoder.metadata(context, Uri.fromFile(source)).durationMs in 2_500L..3_500L)
        }
        val sourceHashes = sources.associate { it.name to sha256(it) }
        val allowed = AtomicInteger()
        val blocked = AtomicInteger()
        val started = SystemClock.elapsedRealtime()
        var maxPss = 0
        fun sample() { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); maxPss = maxOf(maxPss, info.totalPss) }
        try {
            sample()
            val created = jobs.create("Synthetic chronology decision audit", BatchKind.SERIES)
            val imported = jobs.add(created.id, sources.map(Uri::fromFile))
            assertEquals(listOf("clip-a.mp4", "clip-b.mp4"), imported.items.map { it.name })
            val videos = VideoStore(context)
            imported.items.forEach { item ->
                val project = videos.load(item.projectId)
                assertTrue(project.cues.isEmpty())
                assertEquals(created.id, File(videos.folder(project.id), "batch-owner").readText())
                videoJobs.save(project.copy(roi = listOf(0f, .68f, 1f, .99f)))
            }
            val batch = BatchStore(context).load(created.id)
            val names = batch.items.associate { it.projectId to it.name }
            report.put("batchId", batch.id).put("inputOrder", JSONArray(batch.items.map { it.name }))
                .put("sourceHashes", JSONObject(sourceHashes)).put("maximumLocalSamplesPerVideo", 2)
                .put("automaticCloudBackupBlocked", true)
            output.writeText(report.toString(2))
            StandaloneTranslator.beforeNetworkCallForTest = {
                synchronized(allowed) {
                    if (allowed.get() >= 1) {
                        blocked.incrementAndGet()
                        throw IllegalStateException("Minimal chronology request limit reached; no further request sent")
                    }
                    allowed.incrementAndGet()
                }
            }
            val analysisStarted = SystemClock.elapsedRealtime()
            val suggestion = withTimeout(110_000L) {
                BatchOrderAnalysis(context).analyze(batch, allowCloud = true, gate = { sample() })
            }
            report.put("analysisElapsedMs", SystemClock.elapsedRealtime() - analysisStarted)
                .put("confidence", suggestion.confidence).put("method", suggestion.method)
                .put("summary", suggestion.summary).put("previewOrder", JSONArray(suggestion.projectIds.map { names[it] ?: "UNEXPECTED_ID" }))
                .put("reasons", JSONObject(suggestion.reasons.mapKeys { names[it.key] ?: "UNEXPECTED_ID" }))
            assertEquals(batch.items.map { it.projectId }.toSet(), suggestion.projectIds.toSet())
            assertEquals(2, suggestion.projectIds.size)
            assertEquals("Analysis must preserve the stored order before explicit confirmation", batch.items.map { it.projectId },
                BatchStore(context).load(batch.id).items.map { it.projectId })
            assertEquals(0, blocked.get())
            sources.forEach { assertEquals(sourceHashes.getValue(it.name), sha256(it)) }
            if (suggestion.confidence == "UNKNOWN") {
                assertEquals(batch.items.map { it.projectId }, suggestion.projectIds)
                report.put("status", "INSUFFICIENT_EVIDENCE").put("accuracyEvaluated", false)
                    .put("inputPreserved", true).put("sourcePreserved", true).put("explicitApplyCount", 0)
                assumeTrue("Sparse local OCR/Gemini evidence did not establish chronology; accuracy not evaluated", false)
            }
            assertTrue(suggestion.confidence in setOf("TENTATIVE", "LOW"))
            assertEquals("Ambiguous filenames must use the real Gemini analysis", "GEMINI", suggestion.method)
            assertEquals(1, allowed.get())
            assertEquals("Yesterday purchase precedes today's gift in this synthetic fixture", listOf("clip-b.mp4", "clip-a.mp4"),
                suggestion.projectIds.map { names.getValue(it) })
            // The checked preview is explicitly confirmed once; analysis itself never applies it.
            val confirmed = jobs.applyOrder(batch, suggestion.projectIds)
            assertEquals(suggestion.projectIds, confirmed.items.map { it.projectId })
            assertEquals(batch.items.associate { it.projectId to it.episode }, confirmed.items.associate { it.projectId to it.episode })
            sources.forEach { assertEquals(sourceHashes.getValue(it.name), sha256(it)) }
            report.put("status", "PASS").put("accuracyEvaluated", true).put("completed", true)
                .put("explicitApplyCount", 1).put("sourcePreserved", true).put("groupAssignmentsPreserved", true)
            sample()
        } catch (error: org.junit.AssumptionViolatedException) {
            throw error
        } catch (error: Throwable) {
            report.put("status", "FAIL").put("failureType", error.javaClass.simpleName)
                .put("diagnostic", Regex("\\[ORDER_[A-Z_0-9]+\\]").findAll(error.message.orEmpty()).map { it.value }.joinToString(" "))
            throw error
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            sample()
            report.put("allowedNetworkAttempts", allowed.get()).put("blockedExtraAttempts", blocked.get())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("sampledPeakPssKiB", maxPss)
            output.writeText(report.toString(2))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(32 * 1024); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
