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
import com.gemini.ocr.batch.*
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Same generated sidecars/video on both APKs; reports rendered and source-library paths separately. */
@RunWith(AndroidJUnit4::class)
class SmartBatchPerformanceNativeTest {
    @Test(timeout = 720_000L)
    fun measureOneThreeTenWithoutCloud(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeSmartBenchmark") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val label = args.getString("smartLabel").orEmpty().also { require(it.matches(Regex("[a-z0-9-]{1,32}"))) }
        val sourceOnly = args.getString("sourceOnly") == "true"
        val jobs = BatchJobs.get(context); val videos = VideoJobs.get(context)
        require(!SessionState.running.value && jobs.busy.value == null && videos.busy.value == null)
        require(VideoOfflineTranslator.ready()) { "BLOCKED: Existing offline language models unavailable; no automatic download or cloud fallback" }
        val folder = File(context.getExternalFilesDir(null), "smart-benchmark").apply { mkdirs() }
        val reportFile = File(folder, "$label.json"); require(!reportFile.exists())
        val report = JSONObject().put("label", label).put("completed", false).put("sourceOnly", sourceOnly)
            .put("scope", "Generated 12-second video with four chosen SRT cues; real ML Kit offline translation and actual batch/render; not OCR or Gemini speed")
            .put("cloudCalls", 0).put("tokens", 0)
        val cases = JSONArray(); report.put("cases", cases)
        var network = 0
        StandaloneTranslator.beforeNetworkCallForTest = { network++; error("Cloud forbidden in benchmark") }
        try {
            val original = File(folder, "generated-source.mp4")
            if (!original.exists()) instrumentation.context.assets.open("stability-source.mp4").use { input -> original.outputStream().use { input.copyTo(it) } }
            val subtitle = File(folder, "generated-source.srt").apply { writeText("""
                1
                00:00:00,500 --> 00:00:02,000
                你好

                2
                00:00:02,500 --> 00:00:04,000
                我们一起走吧

                3
                00:00:05,000 --> 00:00:07,000
                我有两本书

                4
                00:00:08,000 --> 00:00:10,000
                请稍等
            """.trimIndent(), Charsets.UTF_8) }
            for (count in listOf(1, 3, 10)) {
                val peak = AtomicLong()
                val sampler = launch(Dispatchers.Default) { while (isActive) {
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    peak.updateAndGet { maxOf(it, memory.totalPss.toLong()) }; delay(150)
                } }
                val start = SystemClock.elapsedRealtime()
                try {
                    val batch = jobs.create("Synthetic $label $count", BatchKind.SERIES)
                    val store = BatchStore(context)
                    // Schema round-trip keeps this test compatible with the delivered old APK ABI.
                    store.save(BatchProject.from(batch.json().put("delivery", if (sourceOnly) "SOURCE_LIBRARY" else "MP4_ONLY")))
                    jobs.refresh()
                    jobs.configure(batch.id, VideoTranslationMode.OFFLINE, VideoSourceMode.SIDECAR)
                    val files = (1..count).map { i -> File(folder, "$label-$count-EP$i.mp4").also { original.copyTo(it, overwrite = false) } }
                    val imported = jobs.add(batch.id, files.map(Uri::fromFile))
                    for (item in imported.items) videos.selectSubtitle(item.projectId, Uri.fromFile(subtitle), subtitle.name)
                    val importedMs = SystemClock.elapsedRealtime() - start
                    jobs.start(batch.id)
                    // The real foreground service owns execute(). Calling it a second time can
                    // race the service and lose this test's callback despite completed processing.
                    withTimeout(480_000) { while (jobs.busy.value == batch.id) delay(100) }
                    val saved = store.load(batch.id)
                    val projects = saved.items.map { VideoStore(context).load(it.projectId) }
                    val row = JSONObject().put("videos", count).put("importAndMetadataMs", importedMs)
                        .put("totalMs", SystemClock.elapsedRealtime() - start).put("peakPssKiB", peak.get())
                        .put("state", saved.state).put("errors", JSONArray(saved.items.filter { it.error.isNotBlank() }.map { it.error }))
                        .put("extractionMs", projects.sumOf { it.extractionElapsedMs }).put("translationMs", projects.sumOf { it.translationElapsedMs })
                        .put("renderMs", projects.sumOf { it.exportElapsedMs }).put("cues", projects.sumOf { it.cues.size })
                        .put("cloudCalls", projects.sumOf { it.cloudCalls }).put("batchId", batch.id)
                    cases.put(row); reportFile.writeText(report.toString(2))
                    assertEquals("All four SRT cues per video must survive", count * 4, projects.sumOf { it.cues.size })
                    assertTrue(projects.all { it.cues.all { cue -> cue.translation.isNotBlank() && !cue.approved } })
                    assertEquals("REVIEW", saved.state)
                    if (sourceOnly) assertTrue(projects.all { it.exportedMp4.isBlank() })
                    else assertTrue(projects.all { File(it.exportedMp4).length() > 0 })
                    assertEquals(0, network)
                } finally { sampler.cancelAndJoin() }
            }
            report.put("completed", true)
        } finally {
            jobs.cancel(); StandaloneTranslator.beforeNetworkCallForTest = null
            report.put("cloudCalls", network); reportFile.writeText(report.toString(2))
        }
    }
}
