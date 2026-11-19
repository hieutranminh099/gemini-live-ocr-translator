package com.gemini.ocr.batch

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.movie.MovieRecord
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPolishClient
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTimeline
import com.gemini.ocr.video.VideoTranslationClient
import com.gemini.ocr.video.VideoTranslationMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LongVideoBenchmarkTest {
    @Test fun resumesLongVideoThroughLocalMovieLibrary() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("longVideoBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        check(context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        val input = File(context.filesDir, "long-video-ocr-benchmark.mp4")
        check(input.isFile)
        val originalBytes = input.length()
        val originalSha = fileHash(input)
        val settings = AppSettings(context)
        val jobs = BatchJobs.get(context)
        val videos = VideoJobs.get(context)
        val store = VideoStore(context)
        val batchStore = BatchStore(context)
        val duration = 2_185_000L
        val report = JSONObject().put("profile", "36m25s generated media, real OCR, offline fixture translation, local library")
        val output = File(context.filesDir, "long-video-result.json")
        val checkpoint = File(context.filesDir, "long-video-progress.json")
        var translateRequests = 0
        var polishRequests = 0
        var requestProfile = ""
        var requestGlossary = ""
        val requests = JSONArray()
        settings.standaloneApiKey = "offline-fixture"
        StandaloneTranslator.beforeNetworkCallForTest = { error("No provider requests are permitted in this test") }
        VideoTranslationClient.overrideResponseForTest = { request ->
            translateRequests++
            requests.put(VideoTranslationClient.buildRequest(request, requestProfile, requestGlossary))
            response(request.cueIds, editorial = false)
        }
        VideoPolishClient.overrideResponseForTest = { request ->
            polishRequests++
            requests.put(VideoPolishClient.buildRequest(request, requestProfile, requestGlossary))
            response(request.cueIds, editorial = true)
        }
        try {
            ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { activity ->
                val start = SystemClock.elapsedRealtime()
                val batch = jobs.create("Long synthetic movie", BatchKind.SERIES)
                val item = jobs.add(batch.id, listOf(Uri.fromFile(input))).items.single()
                assertEquals(duration, store.load(item.projectId).durationMs)
                jobs.configure(batch.id, VideoTranslationMode.GEMINI, VideoSourceMode.OCR)
                jobs.applySharedRoi(batch.id, listOf(0f, .65f, 1f, .97f))
                val configured = store.load(item.projectId)
                requestProfile = configured.profile
                requestGlossary = configured.glossary
                val ready = SystemClock.elapsedRealtime()
                activity.onActivity { jobs.start(batch.id) }
                withTimeout(12 * 60_000L) {
                    var lastLogged = -60_000L
                    while (jobs.busy.value != null) {
                        val video = videos.projects.value.firstOrNull { it.id == item.projectId }
                        if (video != null) {
                            if (video.scanMs - lastLogged >= 60_000L) {
                                checkpoint.writeText(JSONObject().put("scan_ms", video.scanMs)
                                    .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                                    .put("stage", video.stage.name).toString())
                                lastLogged = video.scanMs
                            }
                            if (video.stage == VideoStage.EXTRACTING && video.scanMs in (duration * 99 / 100) until duration) {
                                jobs.pause()
                                return@withTimeout
                            }
                        }
                        delay(50)
                    }
                    error("OCR stopped before the pause point")
                }
                withTimeout(20_000L) { jobs.busy.first { it == null } }
                val paused = store.load(item.projectId)
                assertEquals("PAUSED", batchStore.load(batch.id).state)
                assertTrue(paused.scanMs in (duration * 99 / 100) until duration)
                assertTrue(paused.cues.any { it.startMs in 1_075_000L..1_085_000L })
                val resumeAt = SystemClock.elapsedRealtime()
                activity.onActivity { jobs.start(batch.id) }
                withTimeout(240_000L) { jobs.busy.first { it == null } }
                val reviewAt = SystemClock.elapsedRealtime()
                val reviewed = batchStore.load(batch.id)
                assertEquals("Stage errors ${reviewed.items.map { it.errorCode }}", "REVIEW", reviewed.state)
                val sources = jobs.validatedSources(batch.id, approveAll = true)
                val library = MovieLibrary.get(context)
                library.confirmSources(batch.id, reviewed.title, sources)
                val savedAt = SystemClock.elapsedRealtime()
                val metadata = File(library.folder(batch.id), "movie.json")
                val saved = MovieRecord.from(JSONObject(AtomicFile(metadata).openRead().bufferedReader().use { it.readText() }))
                val episode = saved.episodes.single()
                assertTrue(library.isAvailable(episode))
                val snapshot = requireNotNull(episode.source)
                assertEquals(originalBytes, snapshot.bytes)
                assertEquals(originalSha, snapshot.sha256)
                assertEquals(originalSha, fileHash(File(requireNotNull(Uri.parse(episode.mediaUri).path))))
                val cues = snapshot.cues
                assertTrue(cues.all { it.approved && it.translation.isNotBlank() })
                for ((startMs, han) in listOf(200L to "小明今天去公园看花", 1_080_200L to "老师今天去车站等车",
                    2_182_200L to "小雨今天去电影院看电影")) {
                    assertTrue("Missing expected OCR caption near ${startMs}ms", cues.any { cue ->
                        cue.startMs in (startMs - 1_000L)..(startMs + 1_000L) &&
                            cue.source.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }.contains(han)
                    })
                }
                val project = store.load(item.projectId)
                assertEquals(duration, project.scanMs)
                assertEquals(translateRequests + polishRequests, project.cloudCalls)
                val timeline = cues.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.source}" }
                report.put("duration_ms", duration).put("import_ms", ready - start)
                    .put("scan_to_pause_ms", resumeAt - ready).put("resume_to_review_ms", reviewAt - resumeAt)
                    .put("save_ms", savedAt - reviewAt).put("wall_ms", savedAt - start)
                    .put("pause_ms", paused.scanMs).put("restored_cues", paused.cues.size)
                    .put("saved_cues", cues.size).put("timeline_sha256", hash(timeline))
                    .put("reviewed_cues_sha256", hash(JSONArray(cues.map { it.json() }).toString()))
                    .put("request_sha256", hash(requests.toString()))
                    .put("srt_sha256", hash(VideoTimeline.srt(cues, duration)))
                    .put("ass_sha256", hash(VideoTimeline.ass(snapshot.project(episode))))
                    .put("source_video_sha256", originalSha)
                    .put("fixture_responses", translateRequests + polishRequests).put("ocr", project.ocrMetrics)
                output.writeText(report.toString())
            }
        } finally {
            if (jobs.busy.value != null) jobs.cancel()
            VideoTranslationClient.overrideResponseForTest = null
            VideoPolishClient.overrideResponseForTest = null
            StandaloneTranslator.beforeNetworkCallForTest = null
            settings.standaloneApiKey = ""
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun response(ids: List<String>, editorial: Boolean): String {
        val translations = JSONArray(ids.mapIndexed { index, id ->
            JSONObject().put("id", id).put("translation", "Câu phụ đề thử nghiệm số ${index + 1}.").apply {
                if (editorial) put("needsReview", false)
            }
        })
        val body = JSONObject().put("translations", translations).toString()
        val parts = JSONArray().put(JSONObject().put("text", body))
        val candidate = JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", parts))
        return JSONObject().put("candidates", JSONArray().put(candidate)).toString()
    }
}
