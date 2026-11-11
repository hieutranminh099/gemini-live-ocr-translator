package com.gemini.ocr.video

import android.content.Context
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
import kotlinx.coroutines.CancellationException
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
import kotlin.math.abs

/**
 * Explicit opt-in, disposable APK only. Prepare downloads models; offline never downloads or calls
 * Gemini. The controller disables networking between those two independent instrumentation runs.
 * Export/codec validation is covered by VideoExporter's separate device test.
 */
@RunWith(AndroidJUnit4::class)
class VideoWorkflowTest {
    private class CheckpointStop : CancellationException("Stop after a durable OCR checkpoint")
    private var requireDisconnected=true

    @Test fun realVideoTextOcrOfflineTranslationAndCheckpoint():Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeVideo") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench") { "Use the isolated verification APK." }
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        require(!SessionState.running.value)
        val phase = args.getString("videoPhase") ?: "offline"
        require(phase in setOf("prepare", "offline", "local-validation"))
        requireDisconnected=phase=="offline"
        val base = File(context.getExternalFilesDir(null), "verification")
        val runName=(args.getString("videoRun")?:"video-workflow").also{require(it.matches(Regex("[a-z0-9-]{1,60}")))}
        val output = File(base, runName).apply { mkdirs() }
        val reportFile = File(output, "$phase.json")
        val report = JSONObject().put("phase", phase).put("completed", false)
            .put("physical_network_disconnection_required",requireDisconnected)
            .put("network_verification_mode",if(args.getString("networkPermissionDeniedExpected")=="true")"APP_INTERNET_PERMISSION_DENIED; physical airplane mode NOT VERIFIED" else "DEVICE_NETWORK_DISCONNECTED")
            .put("physical_offline_acceptance",if(requireDisconnected)"strict: requires zero INTERNET networks" else "NOT VERIFIED by this phase")
            .put("translation_quality", "unscored: inspect the recorded source and actual Vietnamese output")
            .put("export_device_test", "separate VideoExport device test")
        val started = SystemClock.elapsedRealtime()
        var maxPss = 0L
        fun persist() {
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            maxPss = maxOf(maxPss, memory.totalPss.toLong())
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("max_sampled_pss_kib", maxPss)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        val attempts = AtomicInteger()
        val previousNetworkHook = StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest = {
            attempts.incrementAndGet()
            error("Gemini network calls are forbidden in the video offline test.")
        }
        try {
            withTimeout(600000) {
                report.put("network_before", networkState(context))
                if (phase == "prepare") {
                    report.put("ready_before", VideoOfflineTranslator.ready()); persist()
                    VideoOfflineTranslator().use { it.prepare() }
                    assertTrue("Both language models must be downloaded", VideoOfflineTranslator.ready())
                    report.put("ready_after", true)
                } else {
                    assertDisconnected(context)
                    assertTrue("Run videoPhase=prepare while online first", VideoOfflineTranslator.ready())
                    val source = File(args.getString("videoPath") ?: File(base, "video-source-modern.mp4").absolutePath)
                    val sidecar = File(args.getString("videoSubtitlePath") ?: File(base, "video-source-modern.srt").absolutePath)
                    val embedded = File(args.getString("videoEmbeddedPath") ?: File(base, "video-source-modern.mkv").absolutePath)
                    require(source.isFile && sidecar.isFile && embedded.isFile) { "Push source MP4, annotated SRT and embedded MKV fixtures first." }
                    val originalHash = sha256(source)
                    val metadata = VideoDecoder.metadata(context, Uri.fromFile(source))
                    report.put("source", source.absolutePath).put("source_sha256", originalHash)
                        .put("duration_ms", metadata.durationMs).put("width", metadata.width).put("height", metadata.height)
                    val store = VideoStore(context)
                    val created = store.create(Uri.fromFile(source), "Verification · recorded modern clip", metadata)
                    var textProject = store.save(created.copy(sourceMode = VideoSourceMode.SIDECAR,
                        subtitleUri = Uri.fromFile(sidecar).toString(), subtitleName = sidecar.name))
                    val textCues = VideoSubtitles.readSidecar(context, textProject)
                    assertTrue("Annotated sidecar must contain actual Chinese subtitle cues", textCues.isNotEmpty())
                    val (tracks, embeddedCues) = VideoSubtitles.embedded(context, Uri.fromFile(embedded), metadata.durationMs)
                    assertTrue("MKV text subtitle track must be found", tracks.isNotEmpty())
                    assertEquals("Embedded text should retain all sidecar occurrences", textCues.size, embeddedCues.size)
                    textCues.zip(embeddedCues).forEach { (a, b) ->
                        assertEquals(VideoTimeline.identity(a.source), VideoTimeline.identity(b.source))
                        assertTrue("Embedded start timestamp drift", abs(a.startMs - b.startMs) <= 50)
                        assertTrue("Embedded end timestamp drift", abs(a.endMs - b.endMs) <= 50)
                    }
                    report.put("sidecar_cues", JSONArray(textCues.map { it.json() }))
                        .put("embedded_tracks", JSONArray(tracks.map { JSONObject().put("id", it.id).put("language", it.language).put("mime", it.mime) }))
                        .put("embedded_cues", JSONArray(embeddedCues.map { it.json() })); persist()
                    val localStarted = SystemClock.elapsedRealtime()
                    val localCues = VideoOfflineTranslator().use { local ->
                        textCues.map { cue ->
                            assertDisconnected(context)
                            cue.copy(translation = local.translate(cue.source), approved = false, model = VideoOfflineTranslator.MODEL)
                        }
                    }
                    textProject = store.save(textProject.copy(cues = localCues, stage = VideoStage.REVIEW,
                        resumeStage = VideoStage.TRANSLATING, progress = 100, model = VideoOfflineTranslator.MODEL))
                    assertTrue(localCues.all { it.translation.isNotBlank() && !it.approved })
                    val reloadedText = VideoStore(context).load(textProject.id)
                    assertEquals(localCues, reloadedText.cues)
                    assertEquals(0, reloadedText.cloudCalls)
                    report.put("offline_translation_ms", SystemClock.elapsedRealtime() - localStarted)
                        .put("offline_translations_draft", JSONArray(localCues.map { it.json() })); persist()
                    val jobs = VideoJobs.get(context)
                    val first = reloadedText.cues.first()
                    jobs.updateCue(textProject.id, first.copy(translation = first.translation + " ", approved = false))
                    assertFalse(VideoStore(context).load(textProject.id).cues.first().approved)
                    jobs.updateCue(textProject.id, first.copy(approved = true)) // Explicit test approval action.
                    val approved = VideoStore(context).load(textProject.id)
                    assertTrue(approved.cues.first().approved)
                    assertTrue(approved.cues.drop(1).none { it.approved })
                    val translatedSrt = File(output, "recorded-modern-translated.srt")
                    translatedSrt.writeText(VideoTimeline.srt(VideoStore(context).load(textProject.id).cues),Charsets.UTF_8)
                    assertTrue(translatedSrt.readText(Charsets.UTF_8).contains(first.translation))
                    report.put("text_project_id", textProject.id).put("translated_srt", translatedSrt.absolutePath)
                        .put("explicit_approval_verified", true); persist()

                    val ocrStarted = SystemClock.elapsedRealtime()
                    var ocrProject = store.create(Uri.fromFile(source), "Verification · OCR resume", metadata)
                    ocrProject = store.save(ocrProject.copy(sourceMode = VideoSourceMode.OCR,
                        roi = listOf(.06f, .76f, .94f, .86f), stage = VideoStage.EXTRACTING))
                    var checkpointed = false
                    try {
                        VideoDecoder.scan(context, ocrProject, gate = { assertDisconnected(context) }) { ms, cues, carry ->
                            ocrProject = store.save(ocrProject.copy(scanMs = ms, cues = cues, carry = carry,
                                stage = VideoStage.PAUSED, resumeStage = VideoStage.EXTRACTING))
                            report.put("ocr_checkpoint_ms", ms).put("ocr_checkpoint_cues", cues.size); persist()
                            checkpointed = true
                            throw CheckpointStop()
                        }
                        fail("Decoder must reach the checkpoint used to simulate cancellation")
                    } catch (_: CheckpointStop) { /* Decoder closes its codec and OCR processor. */ }
                    assertTrue(checkpointed)
                    val restored = VideoStore(context).load(ocrProject.id)
                    assertTrue(restored.scanMs >= 3000)
                    assertEquals(VideoStage.PAUSED, restored.stage)
                    val resumed = VideoDecoder.scan(context, restored, gate = { assertDisconnected(context) }) { ms, cues, carry ->
                        ocrProject = store.save(restored.copy(scanMs = ms, cues = cues, carry = carry,
                            stage = VideoStage.EXTRACTING, resumeStage = VideoStage.EXTRACTING))
                        report.put("ocr_resume_ms", ms); persist()
                    }
                    assertTrue("Real burned Chinese must be found in the specified ROI", resumed.isNotEmpty())
                    assertEquals(resumed.size, resumed.map { it.id }.distinct().size)
                    VideoTimeline.validate(resumed, metadata.durationMs)
                    val translatedOcr = VideoOfflineTranslator().use { local ->
                        resumed.map { cue ->
                            assertDisconnected(context)
                            cue.copy(translation = local.translate(cue.source), approved = false, model = VideoOfflineTranslator.MODEL)
                        }
                    }
                    val finalOcr = store.save(ocrProject.copy(cues = translatedOcr, scanMs = metadata.durationMs,
                        carry = JSONObject(), stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
                        progress = 100, model = VideoOfflineTranslator.MODEL))
                    assertTrue(VideoStore(context).load(finalOcr.id).cues.none { it.approved })
                    val ocrSrt = File(output, "recorded-modern-ocr-translated.srt")
                    ocrSrt.writeText(VideoTimeline.srt(finalOcr.cues),Charsets.UTF_8)
                    val translatedMp4=File(output,"recorded-modern-ocr-translated.mp4")
                    VideoExporter.export(context,finalOcr,translatedMp4,gate={assertDisconnected(context)})
                    report.put("ocr_translated_mp4",translatedMp4.absolutePath)
                    report.put("ocr_project_id", finalOcr.id).put("ocr_cues_draft", JSONArray(translatedOcr.map { it.json() }))
                        .put("ocr_total_ms", SystemClock.elapsedRealtime() - ocrStarted)
                        .put("ocr_translated_srt", ocrSrt.absolutePath)
                        .put("checkpoint_resume_verified", true)
                    assertEquals("Source video must be unchanged", originalHash, sha256(source))
                    report.put("source_unchanged", true)
                    assertDisconnected(context)
                    if(requireDisconnected)VideoOfflineNetworkWitness.verify(context,force=true)
                }
                assertEquals("No Gemini dispatch may be attempted", 0, attempts.get())
                report.put("network_after", networkState(context)).put("gemini_dispatch_attempts", attempts.get()).put("completed", true)
            }
        } catch (e: Throwable) {
            report.put("error", e.message ?: e.javaClass.simpleName).put("error_type", e.javaClass.name)
            throw e
        } finally {
            report.put("gemini_dispatch_attempts", attempts.get())
            StandaloneTranslator.beforeNetworkCallForTest = previousNetworkHook
            persist()
        }
    }

    private fun networkState(context: Context): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
        return JSONObject().put("internet_networks", networks.count { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
            .put("validated_networks", networks.count { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) })
    }
    private suspend fun assertDisconnected(context: Context) {
        if(!requireDisconnected)return // Explicitly labelled local-only validation; never an offline acceptance run.
        VideoOfflineNetworkWitness.verify(context)
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
