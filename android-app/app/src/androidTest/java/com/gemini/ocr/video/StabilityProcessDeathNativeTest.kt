package com.gemini.ocr.video

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchDelivery
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Two explicit invocations around a caller-controlled force-stop; no data clearing or API calls. */
@RunWith(AndroidJUnit4::class)
class StabilityProcessDeathNativeTest {
    @Test(timeout = 210_000L)
    fun approvedFixtureCheckpointSurvivesARealProcessBoundary(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeStabilityProcess") == "true")
        val phase = InstrumentationRegistry.getArguments().getString("stabilityProcessPhase")
        require(phase in setOf("start", "resume"))
        val context = guard()
        val pointer = File(File(checkNotNull(context.getExternalFilesDir(null)), "movie-fixtures").apply { mkdirs() },
            "smart-process-pointer.json")
        val jobs = BatchJobs.get(context)
        val videos = VideoStore(context)
        if (phase == "start") {
            require(!pointer.exists()) { "Existing process-test evidence must be preserved before starting a new run" }
            val started = SystemClock.elapsedRealtime()
            val batch = jobs.create("Native process boundary checkpoint", BatchKind.SERIES)
            jobs.setDelivery(batch.id, BatchDelivery.MP4_ONLY)
            var readyForStop = false
            val report = JSONObject().put("schema", 1).put("phase", "start").put("startCompleted", false)
                .put("pid", Process.myPid()).put("batchId", batch.id).put("cloudCalls", 0)
                .put("scope", "Real foreground service/process boundary/export using one seeded approved fixture; no fresh OCR, translation or cloud")
            try {
                jobs.add(batch.id, listOf(Uri.parse("content://com.gemini.ocr.codexbench.test.stabilitypipe/EP01.mp4")))
                jobs.configure(batch.id, VideoTranslationMode.OFFLINE, VideoSourceMode.SIDECAR)
                val item = BatchStore(context).load(batch.id).items.single()
                val project = videos.load(item.projectId)
                val cue = VideoCue("process-approved-fixture", 1_000, 3_000, "你好", "Xin chào Việt Nam",
                    approved = true, model = "manual-process-fixture", origin = "MANUAL_TEST_FIXTURE")
                val checkpoint = videos.save(project.copy(cues = listOf(cue), scanMs = project.durationMs,
                    stage = VideoStage.REVIEW, resumeStage = VideoStage.EXPORTING, model = "manual-process-fixture"))
                report.put("projectId", project.id).put("sourceUri", project.uri)
                    .put("sourceSha256", sha256(File(checkNotNull(Uri.parse(project.uri).path))))
                    .put("cueSha256", hash(cue.json().toString().toByteArray(Charsets.UTF_8)))
                    .put("scanMs", checkpoint.scanMs).put("approvedFixtureText", true)
                write(pointer, report)
                // Hold the actual export at the normal Live-priority gate without starting capture.
                SessionState.running.value = true
                ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { scenario ->
                    scenario.onActivity { jobs.start(batch.id) }
                    await(20_000L) { videos.load(project.id).stage == VideoStage.WAITING_LIVE }
                }
                assertEquals(batch.id, jobs.busy.value)
                assertEquals(VideoStage.EXPORTING, videos.load(project.id).resumeStage)
                assertEquals("RUNNING", BatchStore(context).load(batch.id).state)
                report.put("startCompleted", true).put("waitingAt", "WAITING_LIVE_BEFORE_EXPORT")
                    .put("startElapsedMs", SystemClock.elapsedRealtime() - started)
                write(pointer, report)
                readyForStop = true
            } finally {
                if (!readyForStop) { jobs.pause(); SessionState.running.value = false; write(pointer, report) }
            }
            // Successful start deliberately leaves this owned service at the gate for force-stop.
        } else {
            require(pointer.isFile && pointer.length() in 1..64_000L) { "Run the start phase first" }
            val report = JSONObject(pointer.readText())
            require(report.getBoolean("startCompleted"))
            assertNotEquals("Resume must run in a different OS process", report.getInt("pid"), Process.myPid())
            val batchId = report.getString("batchId")
            val projectId = report.getString("projectId")
            val started = SystemClock.elapsedRealtime()
            jobs.refresh()
            VideoJobs.get(context).refresh()
            assertEquals("PAUSED", jobs.batches.value.single { it.id == batchId }.state)
            assertEquals(VideoStage.PAUSED, VideoJobs.get(context).projects.value.single { it.id == projectId }.stage)
            val before = videos.load(projectId)
            assertEquals(report.getLong("scanMs"), before.scanMs)
            assertEquals(VideoStage.EXPORTING, before.resumeStage)
            assertEquals(report.getString("sourceUri"), before.uri)
            assertEquals(report.getString("cueSha256"), hash(before.cues.single().json().toString().toByteArray(Charsets.UTF_8)))
            report.put("resumePid", Process.myPid()).put("processChanged", true).put("recoveredView", "PAUSED")
            write(pointer, report)
            try {
                ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { scenario ->
                    scenario.onActivity { jobs.start(batchId) }
                    await(180_000L) { jobs.busy.value == null && VideoJobs.get(context).busy.value == null }
                }
                val batch = BatchStore(context).load(batchId)
                assertEquals(batch.message, "REVIEW", batch.state)
                val after = videos.load(projectId)
                assertEquals(report.getString("cueSha256"), hash(after.cues.single().json().toString().toByteArray(Charsets.UTF_8)))
                assertEquals(report.getString("sourceSha256"), sha256(File(checkNotNull(Uri.parse(after.uri).path))))
                assertEquals(0, after.cloudCalls)
                assertTrue(after.cues.single().approved)
                assertEquals(1, batch.outputs.size)
                val output = File(batch.outputs.single().path)
                assertTrue(output.length() > 0)
                assertNotNull(VideoDecoder.preview(context, Uri.fromFile(output), 2_000)?.also { it.recycle() })
                report.put("resumeCompleted", true).put("finalState", batch.state).put("sourceAndCuePreserved", true)
                    .put("realExportDecoded", true).put("workerMaxPssKiB", after.maxPssKiB)
                    .put("resumeElapsedMs", SystemClock.elapsedRealtime() - started)
            } catch (error: Throwable) {
                report.put("resumeCompleted", false).put("failureType", error.javaClass.simpleName)
                throw error
            } finally { if (jobs.busy.value == batchId) jobs.pause(); write(pointer, report) }
        }
    }

    private suspend fun await(timeout: Long, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(100) }
    }
    private fun write(file: File, report: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun guard(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.VERIFICATION_OFFLINE_NETWORK && BuildConfig.GEMINI_API_KEY.isEmpty())
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value && VideoJobs.get(context).busy.value == null && BatchJobs.get(context).busy.value == null)
        require(VideoCloudSync.get(context).state.value.accountEmail.isBlank())
        return context
    }
}
