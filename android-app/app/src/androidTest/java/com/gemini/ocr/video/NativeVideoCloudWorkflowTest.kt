package com.gemini.ocr.video

import android.net.Uri
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

/** Two-request opt-in on six public subtitle cues, through the production video service. */
@RunWith(AndroidJUnit4::class)
class NativeVideoCloudWorkflowTest {
    @Test fun explicitGeminiModeTranslatesSixPublicCuesAsDraftWithTwoCallLimit(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase=args.getString("videoPhase")
        val resuming=phase=="gemini-resume"
        val requestBudget=if(resuming)1 else 2
        assumeTrue(args.getString("executeVideo") == "true" && phase in setOf("gemini","gemini-resume"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.DEBUG)
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        require(!BuildConfig.VERIFICATION_OFFLINE_NETWORK) { "Use the normal benchmark APK for this explicitly selected cloud test." }
        require(!SessionState.running.value)
        val base = File(context.getExternalFilesDir(null), "verification")
        val source = File(base, "video-source-modern.mp4")
        val subtitles = File(base, "video-source-modern.srt")
        require(source.isFile && subtitles.isFile) { "Push the public MP4 and six-cue SRT fixtures first." }
        val output = File(base, "video-cloud").apply { mkdirs() }
        val prior=if(resuming)JSONObject(File(output,"report.json").readText(Charsets.UTF_8)) else null
        val reportFile = File(output, if(resuming)"resume-report.json" else "report.json")
        val report = JSONObject().put("completed", false).put("phase", phase)
            .put("request_budget", requestBudget).put("source_kind", "six manually annotated cues from the public recorded clip")
            .put("translation_quality", "unscored: inspect actual source/translation rows")
        val attempted = AtomicInteger()
        val accepted = AtomicInteger()
        val previousHook = StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest = {
            val number = attempted.incrementAndGet()
            check(number <= requestBudget) { "Public video Gemini verification exceeded its explicit request budget." }
            accepted.incrementAndGet()
        }
        val store = VideoStore(context)
        val jobs = VideoJobs.get(context)
        var createdId = ""
        val started = SystemClock.elapsedRealtime()
        fun snapshot(project: VideoProject? = null) {
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("dispatch_hook_attempts", attempted.get()).put("allowed_dispatches", accepted.get())
            project?.let {
                // Deliberately exclude message/error fields and provider response bodies.
                report.put("project_id", it.id).put("stage", it.stage.name).put("cloud_calls", it.cloudCalls)
                    .put("job_elapsed_ms", it.elapsedMs).put("job_max_sampled_pss_kib", it.maxPssKiB)
                    .put("cues", JSONArray(it.cues.map { cue -> JSONObject().put("id", cue.id)
                        .put("start_ms", cue.startMs).put("end_ms", cue.endMs).put("source", cue.source)
                        .put("translation", cue.translation).put("model", cue.model).put("approved", cue.approved) }))
            }
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        try {
            assertNull(jobs.busy.value)
            val originalHash = sha256(source)
            val selected=if(resuming){
                store.load(checkNotNull(prior).getString("project_id")).also{p->
                    require(p.uri==Uri.fromFile(source).toString() && p.translationMode==VideoTranslationMode.GEMINI)
                    require(p.cues.size==6 && p.cues.count{it.translation.isBlank()} in 1..4)
                    report.put("preserved_completed_cues",p.cues.count{it.translation.isNotBlank()})
                }
            }else{
                val created = jobs.create(Uri.fromFile(source), "Verification · Gemini public six cues")
                jobs.selectSubtitle(created.id, Uri.fromFile(subtitles), subtitles.name)
                store.load(created.id)
            }
            createdId = selected.id
            val fixtureCues = VideoSubtitles.readSidecar(context, selected)
            assertEquals("This bounded run authorizes exactly the prepared six public cues", 6, fixtureCues.size)
            require(fixtureCues.sumOf { it.source.length } <= 2000) { "Unexpected fixture size; refusing cloud processing." }
            val project = jobs.save(selected.copy(translationMode = VideoTranslationMode.GEMINI))
            report.put("source_sha256", originalHash)
            snapshot(project)
            jobs.start(project.id)
            val complete = withTimeout(120000) {
                var current = store.load(project.id)
                while (current.stage != VideoStage.REVIEW || jobs.busy.value != null) {
                    // A provider error stops immediately: no rerun, retry or alternative provider.
                    check(current.stage != VideoStage.FAILED) { "Production video Gemini job failed; no automatic retry was attempted." }
                    delay(200)
                    current = store.load(project.id)
                    snapshot(current)
                }
                current
            }
            assertEquals(6, complete.cues.size)
            assertTrue(complete.cues.all { it.translation.isNotBlank() && !it.approved })
            assertTrue(accepted.get() in 1..requestBudget)
            assertTrue(attempted.get() <= requestBudget)
            if(!resuming)assertTrue(complete.cloudCalls in 1..2)
            assertEquals(originalHash, sha256(source))
            val srt = File(output, "public-six-cues-gemini.srt")
            srt.writeText(VideoTimeline.srt(complete.cues), Charsets.UTF_8)
            report.put("completed", true).put("source_unchanged", true).put("translated_srt", srt.absolutePath)
            snapshot(complete)
        } catch (failure: Throwable) {
            // Never copy provider messages, keys, request URLs or response bodies into this report.
            report.put("failure_type", failure.javaClass.name).put("failure", "Bounded cloud workflow did not complete; no retry was started.")
            if (createdId.isNotEmpty()) runCatching { snapshot(store.load(createdId)) }
            throw AssertionError("Bounded video Gemini verification failed (${failure.javaClass.simpleName}); see sanitized report.")
        } finally {
            if (jobs.busy.value == createdId) {
                jobs.pause()
                val finishBy = SystemClock.elapsedRealtime() + 15000
                while (jobs.busy.value == createdId && SystemClock.elapsedRealtime() < finishBy) delay(100)
            }
            StandaloneTranslator.beforeNetworkCallForTest = previousHook
            snapshot()
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
