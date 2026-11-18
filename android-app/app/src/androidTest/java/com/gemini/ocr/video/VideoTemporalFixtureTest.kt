package com.gemini.ocr.video

import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Synthetic labeled H.264 footage. Reads and writes only the disposable test package's files. */
@RunWith(AndroidJUnit4::class)
class VideoTemporalFixtureTest {
    private data class Label(val start: Long, val end: Long, val text: String)

    @Test fun decodeCfrAndVfrAndExportFromTheConsolidatedTimeline(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeTemporalFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK &&
            context.packageName == "com.gemini.ocr.subtitles1816")
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "temporal-fixture")
        val runToken = requireNotNull(args.getString("fixtureRunToken"))
        require(runToken.matches(Regex("[a-f0-9]{32}")))
        val labels = listOf(Label(500, 1350, "等你回来"), Label(1550, 1870, "好的"),
            Label(1870, 2400, "再见"), Label(2950, 3600, "等你回来"), Label(3880, 4750, "我们明天在这里见面"))
        val reports = JSONArray()
        val failures = mutableListOf<String>()
        for (variant in listOf("cfr", "vfr")) {
            val input = File(folder, "$variant.mp4")
            val uri = Uri.fromFile(input)
            val hash = sha256(input)
            assertEquals(args.getString("${variant}Sha"), hash)
            val metadata = VideoDecoder.metadata(context, uri)
            val project = VideoProject("fixture-$variant", variant, uri.toString(), metadata.durationMs,
                metadata.width, metadata.height, sourceMode = VideoSourceMode.OCR,
                roi = listOf(.11f, .60f, .89f, .96f))
            var metrics = JSONObject()
            val wall = SystemClock.elapsedRealtime()
            val cpu = Process.getElapsedCpuTime()
            val pss = Debug.getPss()
            val readings = JSONArray()
            val previousObserver = VideoDecoder.draftObserverForTest
            VideoDecoder.draftObserverForTest = { time, draft ->
                val box = draft.lines.singleOrNull()?.bounds
                readings.put(JSONObject().put("ms", time).put("raw", draft.text)
                    .put("confidence", draft.minConfidence ?: JSONObject.NULL)
                    .put("bounds", box?.toJson() ?: JSONObject.NULL))
            }
            val cues = try {
                VideoDecoder.scanDrafts(context, project, {}) { _, _, carry -> metrics = carry.getJSONObject("metrics") }
            } finally {
                VideoDecoder.draftObserverForTest = previousObserver
            }
            val row = JSONObject().put("variant", variant).put("durationMs", metadata.durationMs)
                .put("wallMs", SystemClock.elapsedRealtime() - wall).put("processCpuMs", Process.getElapsedCpuTime() - cpu)
                .put("pssBeforeKb", pss).put("pssAfterKb", Debug.getPss()).put("decoder", metrics)
                .put("cues", JSONArray(cues.map(VideoCue::json))).put("nativeReadings", readings)
            val matched = mutableSetOf<String>()
            val missing = mutableListOf<String>()
            val errors = JSONArray()
            for (label in labels) {
                val candidates = cues.filter { cue ->
                    cue.startMs < label.end && cue.endMs > label.start &&
                        cue.source.filterNot { it.isWhitespace() || it == '。' } == label.text
                }
                val cue = candidates.maxByOrNull { minOf(it.endMs, label.end) - maxOf(it.startMs, label.start) }
                if (cue == null) missing += label.text
                else {
                    if (!matched.add(cue.id)) failures += "$variant: merged distinct occurrences of ${label.text}"
                    val timingToleranceMs = if (variant == "cfr") 250L else 300L
                    if (abs(cue.startMs - label.start) > timingToleranceMs || abs(cue.endMs - label.end) > timingToleranceMs) {
                        failures += "$variant: timing error exceeds ${timingToleranceMs}ms for ${label.text}"
                    }
                    errors.put(JSONObject().put("caption", label.text).put("startMs", cue.startMs - label.start)
                        .put("endMs", cue.endMs - label.end))
                }
            }
            val unexpected = cues.count { it.id !in matched }
            row.put("expected", labels.size).put("missing", JSONArray(missing)).put("unexpected", unexpected)
                .put("timingErrorMs", errors)
            if (missing.isNotEmpty() || unexpected != 0) failures += "$variant: missing=$missing unexpected=$unexpected"
            if (variant == "cfr" && args.getString("executeTemporalExport") == "true" && cues.isNotEmpty()) {
                val output = File(folder, "fixture-subtitled-${SystemClock.elapsedRealtime()}.mp4")
                try {
                    VideoExporter.export(context, project.copy(cues = cues.map { it.copy(translation = "Câu thử ${it.id}") }), output)
                    val exported = VideoDecoder.metadata(context, Uri.fromFile(output))
                    check(metadata.width == exported.width && metadata.height == exported.height &&
                        metadata.hasAudio == exported.hasAudio && abs(metadata.durationMs - exported.durationMs) <= 200) {
                        "Export dimensions, audio or duration changed"
                    }
                    row.put("exportFile", output.name)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    row.put("exportError", e.message ?: e.javaClass.simpleName)
                    failures += "MP4 export: ${e.message}"
                }
            }
            assertEquals(hash, sha256(input))
            reports.put(row)
            File(folder, "report-$runToken.json").writeText(reports.toString(2))
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
