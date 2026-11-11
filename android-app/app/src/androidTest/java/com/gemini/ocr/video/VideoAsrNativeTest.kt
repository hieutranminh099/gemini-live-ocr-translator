package com.gemini.ocr.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.asr.ChineseAsrModels
import com.gemini.ocr.asr.ChineseAsrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Real Mandarin PCM decoding + pinned native ASR; no injected transcript or cloud response. */
@RunWith(AndroidJUnit4::class)
class VideoAsrNativeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder: File get() = File(requireNotNull(context.getExternalFilesDir(null)), "asr187-fixtures")
    private val manifest: JSONObject get() = JSONObject(File(folder, "manifest.json").readText())

    @Test(timeout = 360_000L)
    fun genuineMandarinHasMeasuredTextAndBoundedTimestamps(): Unit = runBlocking {
        requireScope()
        witness("native-asr-recognition.json") { report ->
            val models = models()
            val loadAt = SystemClock.elapsedRealtime()
            ChineseAsrSession.open(models, 2).use { session ->
                report.put("modelLoadMs", SystemClock.elapsedRealtime() - loadAt)
                try { models.remove(); fail("An active model session must prevent removal") } catch (_: IllegalStateException) { }
                assertTrue(models.status().ready)
                val rows = JSONArray()
                for (name in listOf("mandarin-1.mp4", "mandarin-2.mp4", "two-speaker.mp4", "mandarin-noisy.mp4", "long-silence.mp4")) {
                    val fixture = fixture(name)
                    val project = project(name)
                    val beforeCalls = session.transcriptionCalls
                    val started = SystemClock.elapsedRealtime()
                    val cpu = Process.getElapsedCpuTime()
                    var checkpoints = 0
                    var finalState = JSONObject()
                    val cues = withContext(Dispatchers.Default) {
                        VideoSpeechExtractor.scan(context, project, session, { coroutineContext.ensureActive() }) { _, _, state ->
                            checkpoints++
                            finalState = JSONObject(state.toString())
                        }
                    }
                    val row = JSONObject().put("fixture", name).put("sourceSha256", sha(File(folder, name)))
                        .put("wallMs", SystemClock.elapsedRealtime() - started).put("cpuMs", Process.getElapsedCpuTime() - cpu)
                        .put("recognitionCalls", session.transcriptionCalls - beforeCalls).put("checkpointCount", checkpoints)
                        .put("cues", JSONArray(cues.map { it.json() })).put("sourceDurationMs", project.durationMs)
                    rows.put(row)
                    report.put("fixtures", rows)
                    assertTrue("Spoken Mandarin must create real cues", cues.isNotEmpty())
                    assertTrue(cues.all { it.origin == "ASR" && it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= project.durationMs })
                    assertTrue("Unavailable confidence must be marked, not invented", cues.all { it.qualityFlags.contains("ASR_CONFIDENCE_UNAVAILABLE") })
                    assertTrue(finalState.optBoolean("complete"))
                    assertFalse(finalState.optBoolean("noSpeech"))
                    val references = fixture.getJSONArray("utterances")
                    val reference = (0 until references.length()).joinToString("") { references.getJSONObject(it).getString("reference") }
                    val recognized = cues.joinToString("") { it.source }
                    val error = cer(reference, recognized)
                    row.put("reference", reference).put("recognized", recognized).put("characterErrorRate", error)
                    // Declared before running: clean speech <=25%; added-noise witness <=45%.
                    val maximum = if (name == "mandarin-noisy.mp4") .45 else .25
                    row.put("maximumCharacterErrorRate", maximum)
                    assertTrue("Mandarin CER exceeds the declared fixture bound: $error", error <= maximum)
                    val expectedStart = references.getJSONObject(0).getLong("start_ms")
                    val expectedEnd = references.getJSONObject(references.length() - 1).getLong("end_ms")
                    row.put("firstBoundaryErrorMs", cues.first().startMs - expectedStart)
                        .put("lastBoundaryErrorMs", cues.last().endMs - expectedEnd)
                        .put("timingReference", "Published WAV utterance envelope; not phoneme alignment")
                    assertTrue("First speech boundary differs by more than 1 second", abs(cues.first().startMs - expectedStart) <= 1000)
                    assertTrue("Last speech boundary differs by more than 1 second", abs(cues.last().endMs - expectedEnd) <= 1000)
                    if (name == "long-silence.mp4") {
                        val quietStart = references.getJSONObject(0).getLong("end_ms") + 1000
                        val quietEnd = references.getJSONObject(1).getLong("start_ms") - 1000
                        assertTrue("Long silence must not acquire invented speech", cues.none { it.startMs < quietEnd && quietStart < it.endMs })
                    }
                }
                val embedded = project("mandarin-embedded.mp4")
                val trackRows = VideoSubtitles.embedded(context, Uri.parse(embedded.uri), embedded.durationMs).second
                val reference = fixture("mandarin-1.mp4").getJSONArray("utterances").getJSONObject(0).getString("reference")
                assertEquals(normalize(reference), normalize(trackRows.joinToString("") { it.source }))
                val sidecar = embedded.copy(subtitleUri = Uri.fromFile(File(folder, "mandarin-1.srt")).toString(), subtitleName = "mandarin-1.srt")
                assertEquals(normalize(reference), normalize(VideoSubtitles.readSidecar(context, sidecar).joinToString("") { it.source }))
                report.put("existingTrackAndSidecarReadable", true)
                    .put("subtitleTrackScope", "Real parser regression; AUTO source scheduling has separate integration tests")
            }
        }
    }

    @Test(timeout = 180_000L)
    fun silenceAndGeneratedMusicDoNotInventDialogue(): Unit = runBlocking {
        requireScope()
        witness("native-asr-no-speech.json") { report ->
            ChineseAsrSession.open(models(), 2).use { session ->
                val results = JSONArray()
                for (name in listOf("silence.mp4", "noise-music.mp4")) {
                    var state = JSONObject()
                    val before = session.transcriptionCalls
                    val started = SystemClock.elapsedRealtime()
                    val cues = withContext(Dispatchers.Default) {
                        VideoSpeechExtractor.scan(context, project(name), session, { coroutineContext.ensureActive() }) { _, _, current -> state = current }
                    }
                    results.put(JSONObject().put("fixture", name).put("wallMs", SystemClock.elapsedRealtime() - started)
                        .put("recognitionCalls", session.transcriptionCalls - before).put("cues", JSONArray(cues.map { it.json() }))
                        .put("complete", state.optBoolean("complete")).put("noSpeech", state.optBoolean("noSpeech")))
                    report.put("fixtures", results)
                    assertTrue("Silence and generated non-speech audio must not create dialogue", cues.isEmpty())
                    assertTrue(state.optBoolean("complete"))
                    assertTrue(state.optBoolean("noSpeech"))
                }
            }
        }
    }

    @Test(timeout = 240_000L)
    fun sharedBatchExtractionChoosesEmbeddedOcrAndThenRealSpeechPerVideo(): Unit = runBlocking {
        requireScope()
        witness("native-asr-routing.json") { report ->
            models() // Installs real pinned weights but leaves no native-session lease held.
            assertFalse("Live keeps priority over fixture extraction", SessionState.running.value)
            val store = VideoStore(context)
            val jobs = VideoJobs.get(context)
            assertNull(jobs.busy.value)
            assertNull(jobs.batchBusy.value)
            val owner = "asr-fixture-" + UUID.randomUUID()
            val rows = JSONArray()
            jobs.reserveBatch(owner)
            try {
                for ((name, expected) in listOf("mandarin-embedded.mp4" to "EMBEDDED",
                    "mandarin-hardsub.mp4" to "OCR", "mandarin-1.mp4" to "ASR")) {
                    val original = store.save(project(name).copy(sourceMode = VideoSourceMode.AUTO))
                    val started = SystemClock.elapsedRealtime()
                    val extracted = withTimeout(180_000) { jobs.runStageForBatch(owner, original.id, BatchPipelineStage.EXTRACT) }
                    rows.put(JSONObject().put("fixture", name).put("expectedSource", expected)
                        .put("resolvedSource", extracted.speech.optString("resolvedSource"))
                        .put("wallMs", SystemClock.elapsedRealtime() - started).put("stage", extracted.stage.name)
                        .put("stageStatus", extracted.pipeline.task(BatchPipelineStage.EXTRACT).status)
                        .put("message", extracted.message).put("speech", extracted.speech)
                        .put("ocrMetrics", extracted.ocrMetrics).put("cloudCalls", extracted.cloudCalls)
                        .put("cues", JSONArray(extracted.cues.map { it.json() })))
                    report.put("fixtures", rows)
                    assertEquals("EXTRACT must really complete: ${extracted.message}", "COMPLETE", extracted.pipeline.task(BatchPipelineStage.EXTRACT).status)
                    assertEquals(expected, extracted.speech.optString("resolvedSource"))
                    assertTrue(extracted.scanMs >= extracted.durationMs)
                    assertTrue(extracted.cues.isNotEmpty())
                    assertTrue(extracted.cues.all { it.translation.isBlank() })
                    assertEquals(0, extracted.cloudCalls)
                    when (expected) {
                        "EMBEDDED" -> {
                            assertTrue("Embedded cues retain the existing track provenance",
                                extracted.cues.all { it.origin == "EMBEDDED" })
                            assertFalse(extracted.speech.optBoolean("asrActive"))
                            assertEquals(0, extracted.speech.optInt("recognitions"))
                        }
                        "OCR" -> {
                            assertTrue(extracted.cues.all { it.origin == "OCR" })
                            assertTrue(extracted.speech.optBoolean("ocrComplete"))
                            assertFalse(extracted.speech.optBoolean("asrActive"))
                            assertEquals(0, extracted.speech.optInt("recognitions"))
                        }
                        else -> {
                            assertTrue("AUTO must finish full OCR before acoustic fallback", extracted.speech.optBoolean("ocrComplete"))
                            assertTrue(extracted.speech.optBoolean("complete"))
                            assertTrue(extracted.cues.all { it.origin == "ASR" })
                            val reference = fixture("mandarin-1.mp4").getJSONArray("utterances").getJSONObject(0).getString("reference")
                            val error = cer(reference, extracted.cues.joinToString("") { it.source })
                            assertTrue("Actual shared worker Mandarin CER exceeds25%", error <= .25)
                        }
                    }
                    assertEquals(extracted.cues, VideoStore(context).load(extracted.id).cues)
                    assertTrue(File(store.folder(extracted.id), "source.zh.srt").isFile)
                }
                report.put("scope", "Three real VideoJobs EXTRACT stages under one batch reservation; no translation model or cloud request")
            } finally { jobs.releaseBatch(owner) }
            assertNull(jobs.batchBusy.value)
            assertNull(jobs.busy.value)
        }
    }

    @Test(timeout = 240_000L)
    fun persistedCheckpointResumesWithStableIdsAndCompletedSourceNeedsNoRecognition(): Unit = runBlocking {
        requireScope()
        witness("native-asr-resume.json") { report ->
            val manager = models()
            val initial = project("long-silence.mp4")
            val store = VideoStore(context)
            var saved: VideoProject? = null
            ChineseAsrSession.open(manager, 2).use { session ->
                try {
                    withContext(Dispatchers.Default) {
                        VideoSpeechExtractor.scan(context, initial, session, { coroutineContext.ensureActive() }) { ms, rows, speech ->
                            if (rows.isNotEmpty() && ms < initial.durationMs && !speech.optBoolean("complete")) {
                                saved = store.save(initial.copy(cues = rows, scanMs = ms, speech = JSONObject(speech.toString())))
                                throw FixturePause()
                            }
                        }
                    }
                    fail("The fixture must reach and pause a saved speech checkpoint")
                } catch (_: FixturePause) { }
            }
            val stopped = requireNotNull(saved)
            assertFalse(stopped.speech.optBoolean("complete"))
            val loaded = store.load(stopped.id)
            assertEquals(stopped.json().toString(), loaded.json().toString())
            report.put("savedCheckpointMs", loaded.speech.optLong("checkpointMs"))
                .put("savedCueCount", loaded.cues.size).put("savedCues", JSONArray(loaded.cues.map { it.json() }))
                .put("recoveryScope", "Serialized store reload plus fresh native session; not an Android process-kill test")
            ChineseAsrSession.open(manager, 2).use { session ->
                var completed: VideoProject? = null
                val resumed = withContext(Dispatchers.Default) {
                    VideoSpeechExtractor.scan(context, loaded, session, { coroutineContext.ensureActive() }) { ms, rows, speech ->
                        completed = store.save(loaded.copy(cues = rows, scanMs = ms, speech = JSONObject(speech.toString())))
                    }
                }
                val finished = requireNotNull(completed)
                assertTrue(finished.speech.optBoolean("complete"))
                val byId = resumed.associateBy { it.id }
                for (old in stopped.cues) assertEquals("A committed cue must survive resume", old, byId[old.id])
                val expected = fixture("long-silence.mp4").getJSONArray("utterances")
                val reference = (0 until expected.length()).joinToString("") { expected.getJSONObject(it).getString("reference") }
                val error = cer(reference, resumed.joinToString("") { it.source })
                report.put("resumedCues", JSONArray(resumed.map { it.json() })).put("characterErrorRate", error)
                assertTrue("Resumed Mandarin text exceeds declared CER bound", error <= .25)
                val calls = session.transcriptionCalls
                val cached = withContext(Dispatchers.Default) {
                    VideoSpeechExtractor.scan(context, finished, session, { coroutineContext.ensureActive() }) { _, _, _ -> }
                }
                assertEquals(resumed, cached)
                assertEquals("Completed source text must not invoke ASR again", calls, session.transcriptionCalls)
                report.put("recognitionCallsAfterResume", calls).put("completedSourceReusedWithoutAsr", true)
            }
        }
    }

    private suspend fun models(): ChineseAsrModels = ChineseAsrModels(context).also {
        it.importVerifiedFiles(File(folder, "models"))
        check(it.status().ready)
    }

    private fun requireScope() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeAsr187") == "true")
        require(context.packageName == "com.gemini.ocr.asr187" && BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
    }

    private fun project(name: String): VideoProject {
        val file = File(folder, name)
        require(file.isFile && file.length() in 1..64L * 1024 * 1024)
        val uri = Uri.fromFile(file)
        val metadata = VideoDecoder.metadata(context, uri)
        return VideoProject(UUID.randomUUID().toString(), "Public Mandarin ASR fixture", uri.toString(),
            metadata.durationMs, metadata.width, metadata.height, sourceMode = VideoSourceMode.ASR)
    }

    private fun fixture(name: String): JSONObject {
        val rows = manifest.getJSONArray("fixtures")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("name") == name }
    }

    private suspend fun witness(name: String, block: suspend (JSONObject) -> Unit) = kotlinx.coroutines.coroutineScope {
        val report = JSONObject().put("schema", 1).put("completed", false).put("processPid", Process.myPid())
            .put("scope", "Real MediaCodec audio + pinned SenseVoiceSmall/Silero models on public Mandarin fixtures; no cloud or synthetic transcripts")
            .put("cloudCalls", 0).put("modelFingerprint", ChineseAsrModels.FINGERPRINT)
            .put("referenceProvenance", manifest.getJSONArray("provenance"))
        val target = File(folder, name)
        require(!target.exists()) { "Preserve earlier native ASR evidence before running again" }
        val peak = AtomicLong()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                peak.updateAndGet { maxOf(it, memory.totalPss.toLong()) }
                delay(100)
            }
        }
        val start = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        try {
            block(report)
            report.put("completed", true)
        } catch (error: Throwable) {
            report.put("failure", error.javaClass.simpleName).put("failureMessage", error.message.orEmpty().take(300))
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                sampler.cancelAndJoin()
                report.put("elapsedMs", SystemClock.elapsedRealtime() - start).put("cpuMs", Process.getElapsedCpuTime() - cpu)
                    .put("maxProcessPssKiB", peak.get()).put("memoryScope", "Sampled whole fixture process, including native models and Android runtime")
                target.writeText(report.toString(2))
            }
        }
    }

    private class FixturePause : CancellationException("Pause at the public fixture checkpoint")
    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }
    private fun cer(reference: String, hypothesis: String): Double {
        val expected = normalize(reference)
        val actual = normalize(hypothesis)
        var previous = IntArray(actual.length + 1) { it }
        for (i in expected.indices) {
            val next = IntArray(actual.length + 1)
            next[0] = i + 1
            for (j in actual.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (expected[i] == actual[j]) 0 else 1)
            previous = next
        }
        return previous.last().toDouble() / maxOf(1, expected.length)
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
