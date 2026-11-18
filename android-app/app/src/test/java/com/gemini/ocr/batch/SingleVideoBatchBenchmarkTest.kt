package com.gemini.ocr.batch

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.movie.MovieRecord
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPolishClient
import com.gemini.ocr.video.VideoSmartBatchSettings
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStagedPipeline
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoSubtitles
import com.gemini.ocr.video.VideoTranslationClient
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoTranslationPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.security.MessageDigest

/** Measures the sidecar-to-library path using real project I/O and deterministic text responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SingleVideoBatchBenchmarkTest {
    @Test fun oneVideoFromImportThroughConfirmation(): Unit = runBlocking {
        val path = System.getenv("GEMINI_BATCH_BENCH_FIXTURE").orEmpty()
        assumeTrue("Set GEMINI_BATCH_BENCH_FIXTURE to a synthetic fixture folder.", path.isNotBlank())
        val folder = File(path).canonicalFile
        val video = File(folder, "single-EP01.mp4")
        val sidecar = File(folder, "${video.nameWithoutExtension}.srt")
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val cueCount = fixture.getInt("cueCount")
        val expectedIds = List(cueCount) { "sub-${it + 1}" }
        val videoSha = fixture.getString("videoSha256")
        val sidecarSha = fixture.getString("sidecarSha256")
        val goldenSha = fixture.getString("timelineSha256")
        require(sha256(video.readBytes()) == videoSha && sha256(sidecar.readBytes()) == sidecarSha)
        val warmups = benchmarkCount("GEMINI_BATCH_BENCH_WARMUPS", 1, 0..3)
        val runs = benchmarkCount("GEMINI_BATCH_BENCH_RUNS", 3, 1..7)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val priorKey = preferences.getString("standalone_api_key", null)
        val priorFallback = preferences.getString("standalone_api_key_plain_fallback", null)
        val videos = VideoJobs.get(context)
        val jobs = BatchJobs(context, videos)
        val store = VideoStore(context)
        val batchStore = BatchStore(context)
        val library = MovieLibrary.get(context)
        val tuning = VideoSmartBatchSettings(context)
        val priorSize = tuning.groupSize
        val priorEconomy = tuning.economyMode
        val priorLive = SessionState.running.value
        val priorNetworkHook = StandaloneTranslator.beforeNetworkCallForTest
        val priorTranslate = VideoTranslationClient.overrideResponseForTest
        val priorPolish = VideoPolishClient.overrideResponseForTest
        var networkCalls = 0
        val translate = RequestTrace()
        val polish = RequestTrace()
        val measurements = JSONArray()
        val elapsed = mutableListOf<Long>()
        var parity: List<String>? = null
        try {
            tuning.groupSize = 20
            tuning.economyMode = false
            SessionState.running.value = false
            StandaloneTranslator.beforeNetworkCallForTest = {
                networkCalls++
                error("External Gemini calls are disabled in this benchmark.")
            }
            preferences.edit().putString("standalone_api_key", "synthetic-invalid-ciphertext")
                .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
            VideoTranslationClient.overrideResponseForTest = { request ->
                translate.record(request)
                response(request.cueIds, editorial = false)
            }
            VideoPolishClient.overrideResponseForTest = { request ->
                polish.record(request)
                response(request.cueIds, editorial = true)
            }
            repeat(warmups + runs) { iteration ->
                translate.clear()
                polish.clear()
                registerMedia(context, video, fixture)
                var batchId: String? = null
                var projectId: String? = null
                try {
                    val importStart = System.nanoTime()
                    val batch = jobs.create("Benchmark film", BatchKind.SERIES)
                    batchId = batch.id
                    val imported = jobs.add(batch.id, listOf(Uri.fromFile(video)))
                    assertEquals(imported.message, 1, imported.items.size)
                    val item = imported.items.single()
                    assertEquals("1", item.episode)
                    projectId = item.projectId
                    jobs.configure(batch.id, VideoTranslationMode.GEMINI, VideoSourceMode.SIDECAR)
                    videos.selectSubtitle(item.projectId, Uri.fromFile(sidecar), sidecar.name)

                    val processingStart = System.nanoTime()
                    val done = CompletableDeferred<Unit>()
                    // Robolectric leaves service delivery to the test. Both entry points use the real queue.
                    jobs.start(batch.id)
                    jobs.execute(batch.id) { done.complete(Unit) }
                    withTimeout(180_000L) { done.await() }

                    val confirmationStart = System.nanoTime()
                    val result = batchStore.load(batch.id)
                    assertEquals(result.message, "REVIEW", result.state)
                    val source = jobs.validatedSources(batch.id, approveAll = true)
                    library.confirmSources(BatchLibraryTarget.movieId(result), result.title, source)
                    val confirmedAt = System.nanoTime()

                    val project = store.load(item.projectId)
                    assertEquals(cueCount, project.cues.size)
                    assertTrue(project.cues.none { it.approved })
                    assertTrue(project.exportedMp4.isBlank())
                    assertTrue(BatchPipelineStage.entries.all { VideoStagedPipeline.completed(project, it) })
                    assertEquals(expectedIds, translate.ids)
                    assertEquals(expectedIds, polish.ids)
                    assertEquals(translate.sizes.size + polish.sizes.size, project.cloudCalls)
                    assertEquals(0, networkCalls)
                    val media = File(library.folder(batch.id), "movie.json")
                    val saved = MovieRecord.from(JSONObject(AtomicFile(media).openRead().bufferedReader().use { it.readText() }))
                    val snapshot = requireNotNull(saved.episodes.single().source)
                    assertEquals("LOCAL", saved.syncState)
                    assertEquals(source.single().project.cues, snapshot.cues)
                    assertEquals(videoSha, snapshot.sha256)
                    assertEquals(video.length(), snapshot.bytes)
                    assertTrue(library.isAvailable(saved.episodes.single()))
                    val timelineSha = timelineDigest(snapshot.cues)
                    assertEquals(goldenSha, timelineSha)
                    val subtitles = store.exportFile(item.projectId, "srt")
                    val parsed = VideoSubtitles.parse(subtitles.name, subtitles.readBytes(), project.durationMs)
                    assertEquals(project.cues.map { Triple(it.startMs, it.endMs, it.translation) },
                        parsed.map { Triple(it.startMs, it.endMs, it.source) })
                    assertTrue(store.exportFile(item.projectId, "ass").length() > 0L)
                    val translateSha = translate.finish()
                    val polishSha = polish.finish()
                    val check = listOf(timelineSha, translateSha, polishSha, sha256(subtitles.readBytes()), snapshot.revision)
                    parity?.let { assertEquals("A repeated run changed the outputs or requests", it, check) }
                    parity = check

                    if (iteration >= warmups) {
                        val wallNs = confirmedAt - importStart
                        elapsed += wallNs
                        val metrics = JSONObject(File(store.folder(item.projectId), "smart-batch-metrics.json").readText())
                        val measured = JSONObject()
                            .put("wall_ns", wallNs)
                            .put("import_ns", processingStart - importStart)
                            .put("processing_ns", confirmationStart - processingStart)
                            .put("confirmation_ns", confirmedAt - confirmationStart)
                            .put("checkpoint_writes", metrics.optLong("checkpoint_writes"))
                            .put("checkpoint_wall_ms", metrics.optLong("checkpoint_wall_ms"))
                            .put("project_refresh_wall_ms", metrics.optLong("project_refresh_wall_ms"))
                        for (stage in BatchPipelineStage.entries) {
                            val metric = "${stage.name.lowercase(java.util.Locale.ROOT)}_wall_ms"
                            measured.put(metric, metrics.optLong(metric))
                        }
                        measurements.put(measured)
                    }
                } finally {
                    if (jobs.busy.value != null) jobs.cancel()
                    batchId?.let { id ->
                        library.remove(id)
                        library.folder(id).deleteRecursively()
                        batchStore.folder(id).deleteRecursively()
                    }
                    projectId?.let { store.folder(it).deleteRecursively() }
                    videos.refresh()
                    jobs.refresh()
                }
            }
            assertEquals(videoSha, sha256(video.readBytes()))
            assertEquals(sidecarSha, sha256(sidecar.readBytes()))
            val check = requireNotNull(parity)
            val sorted = elapsed.sorted()
            val median = sorted[sorted.size / 2] - (sorted[sorted.size / 2] - sorted[(sorted.size - 1) / 2]) / 2
            val report = JSONObject()
                .put("scope", "Robolectric JVM, real BatchJobs, Media3 sidecar extraction, AtomicFile and MovieLibrary, fixture video metadata and deterministic mocked Gemini, no OCR or device playback")
                .put("source_video_sha256", videoSha).put("source_sidecar_sha256", sidecarSha)
                .put("cue_count", cueCount).put("retained_cue_count", expectedIds.size)
                .put("warmups", warmups).put("measured_runs", runs).put("median_wall_ns", median)
                .put("timeline_sha256", check[0]).put("translate_request_sha256", check[1])
                .put("polish_request_sha256", check[2]).put("exported_srt_sha256", check[3])
                .put("movie_revision", check[4]).put("real_network_calls", networkCalls)
                .put("translate_requests", translate.sizes.size).put("polish_requests", polish.sizes.size)
                .put("translate_request_sizes", JSONArray(translate.sizes)).put("polish_request_sizes", JSONArray(polish.sizes))
                .put("measurements", measurements)
            val output = File(requireNotNull(System.getProperty("gemini.test.screenshots")), "single-video-batch-benchmark.json")
            output.parentFile!!.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
        } finally {
            preferences.edit().apply {
                if (priorKey == null) remove("standalone_api_key") else putString("standalone_api_key", priorKey)
                if (priorFallback == null) remove("standalone_api_key_plain_fallback")
                else putString("standalone_api_key_plain_fallback", priorFallback)
            }.commit()
            VideoTranslationClient.overrideResponseForTest = priorTranslate
            VideoPolishClient.overrideResponseForTest = priorPolish
            StandaloneTranslator.beforeNetworkCallForTest = priorNetworkHook
            SessionState.running.value = priorLive
            tuning.groupSize = priorSize
            tuning.economyMode = priorEconomy
        }
    }

    private fun registerMedia(context: Context, video: File, fixture: JSONObject) {
        val data = listOf(DataSource.toDataSource(context, Uri.fromFile(video)), DataSource.toDataSource(video.absolutePath))
        data.distinct().forEach { source ->
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, fixture.getLong("durationMs").toString())
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, fixture.getInt("width").toString())
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, fixture.getInt("height").toString())
        }
        ShadowMediaMetadataRetriever.addFrame(video.absolutePath, 0L, Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888))
        ShadowMediaMetadataRetriever.addScaledFrame(DataSource.toDataSource(video.absolutePath), 0L, 320, 180,
            Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888))
    }

    private fun response(ids: List<String>, editorial: Boolean): String {
        val rows = ids.map { id ->
            val number = id.removePrefix("sub-").toInt()
            val text = if (editorial) "Bản hiệu đính thử số $number." else "Bản dịch thử số $number."
            JSONObject().put("id", id).put("translation", text).apply { if (editorial) put("needsReview", false) }
        }
        val body = JSONObject().put("translations", JSONArray(rows)).toString()
        val content = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", body)))
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP").put("content", content))).toString()
    }

    private fun benchmarkCount(name: String, default: Int, allowed: IntRange): Int =
        (System.getenv(name)?.toInt() ?: default).also { require(it in allowed) { "$name must be in $allowed" } }

    private fun timelineDigest(cues: List<VideoCue>) = sha256(buildString {
        for (cue in cues) {
            append(listOf(cue.id, cue.startMs, cue.endMs, cue.source, cue.draftVi, cue.polishedVi, cue.translation, cue.approved).joinToString("|"))
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private class RequestTrace {
        val ids = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        private val digest = MessageDigest.getInstance("SHA-256")

        fun record(batch: VideoTranslationPlan.Batch) {
            ids += batch.cueIds
            sizes += batch.cueIds.size
            val request = JSONArray(listOf(batch.cueIds, batch.texts, batch.offlineDrafts, batch.videoContext,
                batch.maxOutputTokens, batch.fullDialogue, batch.compactTargets)).toString()
            digest.update(request.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }

        fun clear() {
            ids.clear()
            sizes.clear()
            digest.reset()
        }

        fun finish() = hex(digest.digest())
    }

    private companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
