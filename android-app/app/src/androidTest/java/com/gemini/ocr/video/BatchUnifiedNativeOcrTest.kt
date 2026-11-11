package com.gemini.ocr.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.movie.MoviePlaybackActivity
import com.gemini.ocr.movie.MovieSourceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** MediaCodec and bundled Chinese OCR, isolated from the owner's app and all network services. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BatchUnifiedNativeOcrTest {
    @Test(timeout = 45_000L) fun savedSourcePlaysSeeksAndRestoresItsTimeline(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeBatchUnifiedOcr") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gemini.ocr.batchunified186" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "batch-unified-fixtures")
        val file = File(folder, "chinese-4s.mp4")
        val uri = Uri.fromFile(file)
        val meta = VideoDecoder.metadata(context, uri)
        val cues = listOf(VideoCue("approved", 500, 1750, "你好", "Xin chào", approved = true),
            VideoCue("draft", 2000, 3250, "再见", "Hẹn gặp lại"))
        val project = VideoProject(UUID.randomUUID().toString(), "Synthetic movie", uri.toString(), meta.durationMs,
            meta.width, meta.height, cues = cues, scanMs = meta.durationMs)
        val library = MovieLibrary.get(context)
        val movie = library.confirmSources(UUID.randomUUID().toString(), "Synthetic movie", listOf(MovieSourceInput("ep1", "EP01", project)))
        library.progress(movie.id, "ep1", 1000)
        val intent = Intent(context, MoviePlaybackActivity::class.java).putExtra("movie", movie.id).putExtra("episode", "ep1")
        fun player(view: View): Player? {
            if (view is PlayerView) return view.player
            if (view is ViewGroup) for (index in 0 until view.childCount) player(view.getChildAt(index))?.let { return it }
            return null
        }
        fun await(scenario: ActivityScenario<MoviePlaybackActivity>, condition: (Player) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false
                scenario.onActivity { activity -> player(activity.window.decorView)?.let { p ->
                    assertNull(p.playerError)
                    done = condition(p)
                } }
                if (done) return
                SystemClock.sleep(100)
            }
            fail("Player did not reach expected position/state")
        }
        ActivityScenario.launch<MoviePlaybackActivity>(intent).use { scenario ->
            await(scenario) { it.playbackState == Player.STATE_READY && abs(it.currentPosition - 1000) <= 300 }
            scenario.onActivity { player(it.window.decorView)!!.apply { seekTo(2000); play() } }
            await(scenario) { it.isPlaying && it.currentPosition > 2100 }
            scenario.onActivity { player(it.window.decorView)!!.apply { pause(); seekTo(1000) } }
            await(scenario) { !it.isPlaying && abs(it.currentPosition - 1000) <= 100 }
        }
        assertEquals(cues, library.find(movie.id)!!.episodes.single().source!!.cues)
        JSONObject().put("completed", true).put("source_playback", true).put("seek_pause_resume", true)
            .put("approved_preserved", true).put("network_calls", 0).also {
                File(folder, "native-playback-result.json").writeText(it.toString(2))
            }
    }

    @Test(timeout = 360_000L) fun extractSameVideoWithOneAndTwoDecoderSlots(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeBatchUnifiedOcr") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gemini.ocr.batchunified186" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "batch-unified-fixtures")
        val short = File(folder, "chinese-4s.mp4")
        val long = File(folder, "chinese-180s.mp4")
        val blank = File(folder, "blank-4s.mp4")
        val multiline = File(folder, "multiline-4s.mp4")
        val embedded = File(folder, "embedded-4s.mp4")
        val traditional = File(folder, "traditional-4s.mp4")
        require(listOf(short, long, blank, multiline, embedded, traditional).all { it.isFile && it.length() in 1..64_000_000L })
        val results = JSONArray()
        val report = JSONObject().put("scope", "Native synthetic video extraction only; parallel slots are test-only")
            .put("completed", false).put("process_pid", Process.myPid()).put("runs", results)
        val output = File(folder, "native-ocr-result.json")
        try {
            val warmed = scan(context, short)
            report.put("short_raw_cues", JSONArray(warmed.cues.map { it.json() }))
            checkTimeline(warmed.cues, 1)
            val reference = digest(warmed.cues)
            for (episodes in listOf(1, 3, 5, 10)) {
                val (rows, measurement) = measure(context, short, episodes, 1)
                results.put(measurement)
                assertTrue(rows.all { digest(it.cues) == reference })
            }
            try {
                val (parallel, parallelMetrics) = measure(context, short, 3, 2)
                results.put(parallelMetrics)
                assertTrue(parallel.all { digest(it.cues) == reference })
            } catch (e: VideoDecodeException) {
                results.put(JSONObject().put("episodes", 3).put("decoder_slots", 2)
                    .put("resource_failure", e.code))
            }
            val (longRuns, longMetrics) = measure(context, long, 1, 1)
            results.put(longMetrics.put("long_fixture", true))
            checkTimeline(longRuns.single().cues, 45)
            assertTrue(scan(context, blank).cues.isEmpty())
            assertTrue(scan(context, multiline).cues.any { '\n' in it.source || '\n' in it.rawZh })
            val traditionalCues = scan(context, traditional).cues
            assertTrue(traditionalCues.any { cue ->
                val han = cue.source.filter { it in '\u3400'..'\u9fff' }
                han == "我沒有兩本書" || han == "我没有两本书"
            })
            val uri = Uri.fromFile(embedded)
            val metadata = VideoDecoder.metadata(context, uri)
            val tracks = VideoSubtitles.embedded(context, uri, metadata.durationMs, probeOnly = true).first
            val (_, embeddedCues) = VideoSubtitles.embedded(context, uri, metadata.durationMs, tracks.single().id)
            assertEquals(2, embeddedCues.size)
            report.put("blank_cues", 0).put("multiline_preserved", true).put("embedded_cues", embeddedCues.size)
                .put("traditional_cues", traditionalCues.size).put("long_expected_occurrences_matched", 90)
                .put("long_raw_cues", longRuns.single().cues.size).put("completed", true)
        } finally {
            output.writeText(report.toString(2))
        }
    }

    private data class Scan(val cues: List<VideoCue>, val checkpoints: Int, val metrics: JSONObject)

    private suspend fun scan(context: Context, file: File, sample: () -> Unit = {}): Scan {
        val uri = Uri.fromFile(file)
        val meta = VideoDecoder.metadata(context, uri)
        val project = VideoProject(UUID.randomUUID().toString(), "Synthetic OCR", uri.toString(), meta.durationMs,
            meta.width, meta.height, roi = listOf(0f, .64f, 1f, .99f))
        var terminal = 0L
        var checkpoints = 0
        var metrics = JSONObject()
        val cues = VideoDecoder.scanDrafts(context, project, gate = { sample() }) { ms, _, carry ->
            terminal = ms
            checkpoints++
            metrics = carry.optJSONObject("metrics") ?: metrics
            sample()
        }
        assertEquals(meta.durationMs, terminal)
        return Scan(cues, checkpoints, metrics)
    }

    private suspend fun measure(context: Context, file: File, episodes: Int, slots: Int): Pair<List<Scan>, JSONObject> = supervisorScope {
        val limit = Semaphore(slots)
        val start = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        var lastSample = -1000L
        var peakPss = 0
        val sampleLock = Any()
        fun sample(): Unit = synchronized(sampleLock) {
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed - lastSample >= 1000) {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                peakPss = maxOf(peakPss, info.totalPss)
                lastSample = elapsed
            }
        }
        val scans = List(episodes) { async(Dispatchers.Default) { limit.withPermit { scan(context, file, ::sample) } } }.awaitAll()
        val result = JSONObject().put("episodes", episodes).put("decoder_slots", slots)
            .put("wall_ms", SystemClock.elapsedRealtime() - start).put("process_cpu_ms", Process.getElapsedCpuTime() - cpu)
            .put("sampled_peak_pss_kib", peakPss).put("cues", scans.sumOf { it.cues.size })
            .put("checkpoints", scans.sumOf { it.checkpoints }).put("timeline_sha256", digest(scans.first().cues))
        for (name in listOf("decoded_frames", "recognition_requests", "exact_pixel_reused_observations", "luma_buffer_reuses")) {
            result.put(name, scans.sumOf { it.metrics.optLong(name) })
        }
        scans to result
    }

    private fun checkTimeline(cues: List<VideoCue>, loops: Int) {
        fun han(text: String) = text.filter { it in '\u3400'..'\u9fff' }
        repeat(loops) { loop ->
            val base = 4000L * loop
            for ((i, expected) in listOf("你好我是小明", "我没有两本书").withIndex()) {
                val cue = cues.single { han(it.source) == expected && it.startMs / 4000L == loop.toLong() }
                assertTrue(abs(cue.startMs - base - if (i == 0) 500L else 2000L) <= 500)
                assertTrue(abs(cue.endMs - base - if (i == 0) 1750L else 3250L) <= 500)
            }
        }
    }

    private fun digest(cues: List<VideoCue>): String {
        val source = cues.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.source}" }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
