package com.gemini.ocr.video

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two separate, explicitly selected instrumentation runs. The controller force-stops the disposable
 * app between service-start and service-resume; this test does not emulate process death in memory.
 * It exercises the production foreground service and its persisted OCR/translation checkpoint.
 */
@RunWith(AndroidJUnit4::class)
class VideoServiceResumeNativeTest {
    @Test fun actualForegroundServiceResumesItsPersistedPublicVideoAfterProcessRestart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("videoPhase")
        val localOnly=args.getString("allowConnectedForLocalValidation")=="true"
        assumeTrue(args.getString("executeVideo") == "true" && phase in setOf("service-start", "service-resume"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        require(!SessionState.running.value) { "Start each phase in a fresh process with no Live capture." }
        val root = File(context.getExternalFilesDir(null), "verification")
        val folder = File(root, if(localOnly)"video-service-local" else "video-service").apply { mkdirs() }
        val pointer = File(folder, "project-id.txt")
        val reportFile = File(folder, "$phase.json")
        val report = JSONObject().put("phase", phase).put("completed", false)
            .put("network_verification_mode",if(args.getString("networkPermissionDeniedExpected")=="true")"APP_INTERNET_PERMISSION_DENIED; physical airplane mode NOT VERIFIED" else "DEVICE_NETWORK_DISCONNECTED")
            .put("physical_offline_acceptance",if(localOnly)"NOT VERIFIED: connected local-engine/service validation only" else "requires blocked Internet")
            .put("fixture_kind", "180-second loop of the controller's recorded public clip")
            .put("translation_quality", "unscored: review the recorded actual source/translation rows")
        val start = SystemClock.elapsedRealtime()
        var maxPss = 0L
        val networkAttempts = AtomicInteger()
        val previousNetworkHook = StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest = { networkAttempts.incrementAndGet(); error("Cloud dispatch forbidden in offline service test.") }
        val store = VideoStore(context)
        val jobs = VideoJobs.get(context)
        var leftWaitingForController = false
        fun networkSnapshot(): JSONObject {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
            return JSONObject().put("internet_networks", caps.count { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
                .put("validated_networks", caps.count { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) })
        }
        suspend fun offline() { if(!localOnly)VideoOfflineNetworkWitness.verify(context) }
        fun persist(project: VideoProject? = null) {
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            maxPss = maxOf(maxPss, memory.totalPss.toLong())
            report.put("test_elapsed_ms", SystemClock.elapsedRealtime() - start).put("max_sampled_pss_kib", maxPss)
                .put("gemini_dispatch_attempts", networkAttempts.get())
            project?.let { report.put("last_project_state", it.json()) }
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        try {
            offline()
            assertTrue("Prepare offline models before disconnecting networking", VideoOfflineTranslator.ready())
            assertNull(jobs.busy.value)
            if (phase == "service-start") {
                val source = File(args.getString("videoServicePath") ?: File(root, "video-source-modern-180s.mp4").absolutePath)
                require(source.isFile) { "Push the public 180s fixture before service-start." }
                val metadata = VideoDecoder.metadata(context, Uri.fromFile(source))
                require(metadata.durationMs in 175000L..185000L) { "Expected a 180-second video fixture." }
                val created = jobs.create(Uri.fromFile(source), "Verification · service OCR 180s")
                val project = jobs.save(created.copy(sourceMode = VideoSourceMode.OCR,
                    translationMode = VideoTranslationMode.OFFLINE, roi = listOf(.06f, .76f, .94f, .86f)))
                pointer.writeText(project.id, Charsets.UTF_8)
                report.put("project_id", project.id).put("source_sha256", sha256(source))
                    .put("source", source.absolutePath).put("process_id", android.os.Process.myPid())
                    .put("network_before", networkSnapshot())
                persist(project)
                jobs.start(project.id)
                val checkpoint = withTimeout(240000) {
                    var current = store.load(project.id)
                    while (current.scanMs < 3000) {
                        offline()
                        require(current.stage != VideoStage.FAILED) { current.message }
                        delay(300)
                        current = store.load(project.id)
                        persist(current)
                    }
                    current
                }
                assertTrue(checkpoint.scanMs < project.durationMs)
                // Only the scheduler gate is set. No capture, screen projection, or real Live OCR
                // session is started. The controller force-stops this process after the report.
                SessionState.running.value = true
                val waiting = withTimeout(30000) {
                    var current = store.load(project.id)
                    while (current.stage != VideoStage.WAITING_LIVE) {
                        require(current.stage != VideoStage.FAILED) { current.message }
                        delay(100)
                        current = store.load(project.id)
                    }
                    current
                }
                assertEquals(VideoStage.EXTRACTING, waiting.resumeStage)
                assertEquals(0, waiting.cloudCalls)
                assertEquals(0, networkAttempts.get())
                report.put("checkpoint_scan_ms", waiting.scanMs).put("checkpoint_cues", waiting.cues.size)
                    .put("checkpoint_carry", waiting.carry).put("artificial_live_scheduler_gate", true)
                    .put("controller_next_step", "force-stop the target app, then run service-resume with networking still disabled")
                    .put("completed", true)
                persist(waiting)
                leftWaitingForController = true
            } else {
                require(pointer.isFile) { "Run service-start first and keep its private/external data." }
                val id = pointer.readText(Charsets.UTF_8).trim()
                val checkpoint = store.load(id)
                val previous = JSONObject(File(folder, "service-start.json").readText(Charsets.UTF_8))
                assertTrue("The controller must restart the process", previous.getInt("process_id") != android.os.Process.myPid())
                assertTrue("The prior process must leave a durable paused/waiting checkpoint",
                    checkpoint.stage in setOf(VideoStage.WAITING_LIVE, VideoStage.PAUSED))
                assertTrue(checkpoint.scanMs >= 3000 && checkpoint.scanMs < checkpoint.durationMs)
                assertTrue("The extraction checkpoint must remain incomplete", checkpoint.progress < 100)
                report.put("project_id", id).put("process_id", android.os.Process.myPid())
                    .put("prior_process_id", previous.getInt("process_id")).put("resume_scan_ms", checkpoint.scanMs)
                    .put("checkpoint_before_resume", checkpoint.json()).put("network_before", networkSnapshot())
                persist(checkpoint)
                jobs.start(id)
                val complete = withTimeout(600000) {
                    var current = store.load(id)
                    while (current.stage != VideoStage.REVIEW || jobs.busy.value != null) {
                        offline()
                        require(current.stage != VideoStage.FAILED) { current.message }
                        delay(500)
                        current = store.load(id)
                        persist(current)
                    }
                    current
                }
                assertTrue(complete.cues.isNotEmpty())
                assertTrue(complete.cues.all { it.translation.isNotBlank() && !it.approved })
                assertEquals(complete.durationMs, complete.scanMs)
                assertEquals(0, complete.cloudCalls)
                assertEquals(0, networkAttempts.get())
                VideoTimeline.validate(complete.cues, complete.durationMs)
                val repeated = complete.cues.groupBy { VideoTimeline.identity(it.source) }.values.filter { it.size > 1 }
                assertTrue("Repeated source occurrences in the loop must remain separate timeline cues", repeated.isNotEmpty())
                repeated.forEach { occurrences ->
                    assertEquals(occurrences.size, occurrences.map { it.id }.distinct().size)
                    occurrences.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
                }
                val source = File(Uri.parse(complete.uri).path!!)
                assertEquals(previous.getString("source_sha256"), sha256(source))
                val translated = File(folder, "service-180s-translated.srt")
                translated.writeText(VideoTimeline.srt(complete.cues), Charsets.UTF_8)
                val throughput = complete.durationMs.toDouble() / complete.elapsedMs.coerceAtLeast(1)
                report.put("completed", true).put("process_restart_verified", true)
                    .put("timeline_cues", JSONArray(complete.cues.map { it.json() }))
                    .put("repeated_source_groups", repeated.size).put("processing_elapsed_ms", complete.elapsedMs)
                    .put("realtime_factor", throughput).put("recorded_job_max_pss_kib", complete.maxPssKiB)
                    .put("translated_srt", translated.absolutePath).put("source_unchanged", true)
                    .put("network_after", networkSnapshot())
                persist(complete)
            }
        } catch (failure: Throwable) {
            report.put("error", failure.message ?: failure.javaClass.simpleName).put("error_type", failure.javaClass.name)
            throw failure
        } finally {
            persist()
            if (!leftWaitingForController) StandaloneTranslator.beforeNetworkCallForTest = previousNetworkHook
            // service-start intentionally leaves the synthetic gate true until controller force-stop.
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
