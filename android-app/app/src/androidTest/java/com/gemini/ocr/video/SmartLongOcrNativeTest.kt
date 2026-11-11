package com.gemini.ocr.video

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.network.StandaloneTranslator
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
import kotlin.math.abs

/** Three-minute synthetic local OCR only. No translator, model preparation, export or cloud sync. */
@RunWith(AndroidJUnit4::class)
class SmartLongOcrNativeTest {
    @Test(timeout = 240_000L)
    fun localOcrKeepsBoundedFramesAndDurableNinetyOccurrenceTimeline(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeSmartLongOcr") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value && StandaloneTranslator.beforeNetworkCallForTest == null)
        val jobs = VideoJobs.get(context)
        require(jobs.busy.value == null && jobs.batchBusy.value == null && BatchJobs.get(context).busy.value == null)
        val folder = File(checkNotNull(context.getExternalFilesDir(null)), "smart-ocr-fixtures").apply { mkdirs() }
        val source = File(folder, "chinese-180s.mp4")
        require(source.isFile && source.length() in 1..512_000_000L)
        val metadata = VideoDecoder.metadata(context, Uri.fromFile(source))
        require(metadata.durationMs in 179_500L..180_500L && metadata.width == 640 && metadata.height == 360)
        val output = File(folder, "long-local-ocr-${UUID.randomUUID()}.json")
        val report = JSONObject().put("schema", 1).put("completed", false).put("status", "RUNNING")
            .put("scope", "Local ML Kit OCR of a generated three-minute 45-loop clip; no fresh Gemini translation, export or whole-movie claim")
            .put("modelPreparationRequested", false).put("networkScope", "Gemini dispatch hook; Disposable package INTERNET permission removed by Android")
            .put("sourceDurationMs", metadata.durationMs).put("expectedOccurrences", 90).put("timestampToleranceMs", 500)
        val samples = JSONArray()
        val started = SystemClock.elapsedRealtime()
        var lastMemorySample = -1_000L
        var peakPss = 0
        var baselinePss = 0
        var endingPss = 0
        var sourceMs = 0L
        val networkAttempts = AtomicInteger()
        fun sample(force: Boolean = false) {
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!force && elapsed - lastMemorySample < 1_000L) return
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            if (samples.length() == 0) baselinePss = info.totalPss
            endingPss = info.totalPss; peakPss = maxOf(peakPss, info.totalPss)
            samples.put(JSONObject().put("lastCheckpointSourceMs", sourceMs).put("elapsedMs", elapsed).put("pssKiB", info.totalPss))
            lastMemorySample = elapsed
        }
        var checkpointCount = 0
        try {
            sample(true)
            val store = VideoStore(context)
            val created = jobs.create(Uri.fromFile(source), "Synthetic three-minute local OCR audit")
            File(store.folder(created.id), "batch-owner").writeText(UUID.randomUUID().toString())
            val project = jobs.save(created.copy(sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.OFFLINE,
                roi = listOf(0f, .70f, 1f, .99f), glossary = "", profile = "manga"))
            val originalHash = sha256(source)
            report.put("projectId", project.id).put("sourceSha256", originalHash).put("automaticCloudBackupBlocked", true)
            output.writeText(report.toString(2))
            StandaloneTranslator.beforeNetworkCallForTest = {
                networkAttempts.incrementAndGet()
                throw IllegalStateException("Network dispatch forbidden in local OCR stability test")
            }
            val rows = withTimeout(210_000L) {
                VideoDecoder.scan(context, project, gate = { sample() }) { ms, cues, carry ->
                    sourceMs = ms
                    checkpointCount++
                    val metrics = carry.optJSONObject("metrics") ?: JSONObject()
                    store.save(project.copy(scanMs = ms, cues = cues, carry = carry, ocrMetrics = metrics,
                        stage = VideoStage.PAUSED, resumeStage = VideoStage.EXTRACTING,
                        progress = (ms * 100 / project.durationMs).toInt(), cloudCalls = 0,
                        message = "Native local OCR checkpoint; translation was not requested."))
                    report.put("lastCheckpointMs", ms).put("checkpointCount", checkpointCount)
                        .put("ocrMetrics", metrics).put("checkpointCueCount", cues.size)
                    sample(true)
                    report.put("memorySamples", samples).put("sampledPeakPssKiB", peakPss)
                    output.writeText(report.toString(2))
                }
            }
            val recovered = VideoStore(context).load(project.id)
            assertEquals(project.durationMs, recovered.scanMs)
            assertEquals(rows, recovered.cues)
            assertEquals(0, recovered.cloudCalls)
            assertTrue(rows.all { it.translation.isEmpty() && !it.approved })
            fun han(text: String) = text.filter { it in '\u3400'..'\u9fff' }
            val first = han("你好，我是小明")
            val second = han("我没有两本书")
            val timing = JSONArray()
            val matched = mutableSetOf<String>()
            var missed = 0
            var maxStartError = 0L
            var maxEndError = 0L
            var outsideTolerance = 0
            repeat(45) { loop ->
                for ((text, start, end) in listOf(Triple(first, 500L, 1_750L), Triple(second, 2_000L, 3_250L))) {
                    val candidates = rows.filter { han(it.source) == text && it.startMs / 4_000L == loop.toLong() }
                    val item = JSONObject().put("loop", loop).put("source", text).put("matches", candidates.size)
                    if (candidates.isEmpty()) { missed++; item.put("missing", true) }
                    else {
                        val cue = candidates.minBy { abs(it.startMs - (loop * 4_000L + start)) }
                        matched += cue.id
                        val startError = abs(cue.startMs - (loop * 4_000L + start))
                        val endError = abs(cue.endMs - (loop * 4_000L + end))
                        maxStartError = maxOf(maxStartError, startError); maxEndError = maxOf(maxEndError, endError)
                        if (startError > 500L || endError > 500L) outsideTolerance++
                        item.put("startMs", cue.startMs).put("endMs", cue.endMs)
                            .put("startErrorMs", startError).put("endErrorMs", endError)
                    }
                    timing.put(item)
                }
            }
            val extra = rows.count { it.id !in matched }
            report.put("actualOccurrences", rows.size).put("firstLineOccurrences", rows.count { han(it.source) == first })
                .put("secondLineOccurrences", rows.count { han(it.source) == second }).put("missedOccurrences", missed)
                .put("extraOccurrences", extra).put("maxStartErrorMs", maxStartError).put("maxEndErrorMs", maxEndError)
                .put("occurrencesOutside500ms", outsideTolerance).put("perOccurrenceTiming", timing)
                .put("checkpointRestoredAtDuration", true).put("ocrMetrics", recovered.ocrMetrics)
            output.writeText(report.toString(2))
            assertEquals("Every generated subtitle occurrence must remain separate", 90, rows.size)
            assertEquals(45, rows.count { han(it.source) == first })
            assertEquals(45, rows.count { han(it.source) == second })
            assertEquals(0, missed); assertEquals(0, extra)
            assertEquals("Known source onsets/disappearances must stay within 500ms", 0, outsideTolerance)
            assertEquals(0, networkAttempts.get())
            assertEquals(6, recovered.ocrMetrics.getInt("retained_samples_max"))
            assertEquals(originalHash, sha256(source))
            report.put("sourcePreserved", true).put("completed", true).put("status", "PASS")
        } catch (error: Throwable) {
            report.put("status", "FAIL").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            sample(true)
            report.put("geminiDispatchAttempts", networkAttempts.get()).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("checkpointCount", checkpointCount).put("memorySamples", samples)
                .put("baselinePssKiB", baselinePss).put("endingPssKiB", endingPss).put("sampledPeakPssKiB", peakPss)
            output.writeText(report.toString(2))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
