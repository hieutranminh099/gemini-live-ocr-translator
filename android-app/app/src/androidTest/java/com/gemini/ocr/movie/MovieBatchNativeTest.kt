@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchPlan
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoExporter
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoSubtitleStyle
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.sync.VideoCloudSync
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real local mux/playback verification. It does not test OCR, translation quality or Google sync. */
@RunWith(AndroidJUnit4::class)
class MovieBatchNativeTest {
    @Test(timeout = 180_000L)
    fun existingSingleVideoExportStillBurnsVietnameseAndPreservesSourceAudio(): Unit = runBlocking {
        val context = guard()
        val fixtures = File(checkNotNull(context.getExternalFilesDir(null)), "movie-fixtures")
        val source = File(fixtures, "source.mp4")
        require(source.isFile && source.length() > 0)
        val sourceHash = sha256(source)
        val input = VideoDecoder.metadata(context, Uri.fromFile(source))
        require(input.durationMs in 5_000L..60_000L && input.hasAudio)
        val id = UUID.randomUUID().toString()
        val output = File(fixtures, "single-export-$id.mp4")
        val reportFile = File(fixtures, "single-export-$id.json")
        val report = JSONObject().put("schema", 1).put("completed", false).put("cloudCalls", 0)
            .put("scope", "Existing single-video MP4 export with manual Vietnamese cue; native decode/audio/subtitle pixels/source preservation, not OCR or translation quality")
            .put("sourceSha256", sourceHash).put("outputPath", output.absolutePath)
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(150_000L) {
                val project = VideoProject(id, "Native single export regression", Uri.fromFile(source).toString(),
                    input.durationMs, input.width, input.height,
                    cues = listOf(VideoCue("manual-vietnamese", 2_000, 4_000, "你好", "Xin chào Việt Nam · 250 người",
                        approved = true, origin = "MANUAL_TEST_FIXTURE", model = "none")),
                    style = VideoSubtitleStyle(position = .86f, sizeFraction = .045f, background = true, outline = true),
                    stage = VideoStage.REVIEW, resumeStage = VideoStage.EXPORTING)
                var progress = -1f
                VideoExporter.export(context, project, output, progress = { value ->
                    assertTrue(value in 0f..1f && value >= progress)
                    progress = value
                })
                assertEquals(1f, progress, 0f)
                val exported = VideoDecoder.metadata(context, Uri.fromFile(output))
                assertTrue(output.length() > 0 && exported.hasAudio)
                assertEquals(input.width.toDouble() / input.height, exported.width.toDouble() / exported.height, .01)
                assertTrue("Single export duration drift", abs(exported.durationMs - input.durationMs) <= 500)
                val inputAudio = samples(source, "audio/")
                val outputAudio = samples(output, "audio/")
                assertEquals("Compatible AAC must survive the existing export without replacement", inputAudio.hashes, outputAudio.hashes)
                assertTrue(outputAudio.timestampsUs.maxOrNull()!! >= (input.durationMs - 500) * 1000)
                assertTrue(samples(output, "video/").hashes.isNotEmpty())
                val sourceFrame = checkNotNull(VideoDecoder.preview(context, Uri.fromFile(source), 3_000))
                val outputFrame = checkNotNull(VideoDecoder.preview(context, Uri.fromFile(output), 3_000))
                try {
                    File(fixtures, "single-source-$id.png").outputStream().use { sourceFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(fixtures, "single-output-$id.png").outputStream().use { outputFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    assertEquals(sourceFrame.width, outputFrame.width)
                    assertEquals(sourceFrame.height, outputFrame.height)
                    var visiblyChanged = 0
                    val top = (outputFrame.height * .76f).toInt()
                    val bottom = (outputFrame.height * .96f).toInt()
                    for (y in top until bottom step 2) for (x in 0 until outputFrame.width step 2) {
                        val before = sourceFrame.getPixel(x, y)
                        val after = outputFrame.getPixel(x, y)
                        val difference = abs(android.graphics.Color.red(after) - android.graphics.Color.red(before)) +
                            abs(android.graphics.Color.green(after) - android.graphics.Color.green(before)) +
                            abs(android.graphics.Color.blue(after) - android.graphics.Color.blue(before))
                        if (difference > 120) visiblyChanged++
                    }
                    assertTrue("Burned subtitle strip must visibly change the source frame", visiblyChanged > 100)
                    report.put("changedSubtitlePixelsSampled", visiblyChanged)
                } finally { sourceFrame.recycle(); outputFrame.recycle() }
                assertEquals(sourceHash, sha256(source))
                assertFalse(fixtures.listFiles().orEmpty().any { it.name.startsWith(".gemini-export-") })
                report.put("sourceDurationMs", input.durationMs).put("outputDurationMs", exported.durationMs)
                    .put("outputAudioSamples", outputAudio.hashes.size).put("sourcePreserved", true).put("completed", true)
            }
        } catch (error: Throwable) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message.orEmpty().take(300))
            throw error
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    @Test(timeout = 180_000L)
    fun compatiblePartsJoinAndEnterOfflineLibraryOnlyAfterConfirmation(): Unit = runBlocking {
        val context = guard()

        val fixtures = File(checkNotNull(context.getExternalFilesDir(null)), "movie-fixtures")
        val fixture = File(fixtures, "source.mp4")
        require(fixture.isFile && fixture.length() > 0) { "Stage generated source.mp4 in this disposable app's movie-fixtures folder." }
        val fixtureHash = sha256(fixture)
        val id = UUID.randomUUID().toString()
        val outputDirectory = File(context.filesDir, "movie-native-$id").apply { check(mkdirs()) }
        val reportFile = File(fixtures, "movie-batch-$id.json")
        val report = JSONObject().put("schema", 1).put("completed", false).put("cloudCalls", 0)
            .put("scope", "Native compatible MP4 stream-copy, A/V sample boundaries, explicit confirmation and offline player; no OCR/translation/cloud verification")
            .put("fixtureSha256", fixtureHash).put("outputDirectory", outputDirectory.absolutePath)
        val library = MovieLibrary.get(context)
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(150_000L) {
                assertFalse(library.movies.value.any { it.id == id })
                val first = File(outputDirectory, "part-1.mp4").also { fixture.copyTo(it) }
                val second = File(outputDirectory, "part-2.mp4").also { fixture.copyTo(it) }
                val input = VideoDecoder.metadata(context, Uri.fromFile(first))
                require(input.durationMs in 1000L..60_000L && input.hasAudio) { "Use a short generated AVC/AAC fixture with audio." }
                val inputVideo = samples(first, "video/")
                val inputAudio = samples(first, "audio/")
                val joined = File(outputDirectory, "joined.mp4")
                VideoExporter.concatenate(context, listOf(first, second), joined)
                val output = VideoDecoder.metadata(context, Uri.fromFile(joined))
                assertTrue(joined.length() > 0)
                assertEquals(input.width, output.width)
                assertEquals(input.height, output.height)
                assertEquals(input.rotation, output.rotation)
                assertTrue(output.hasAudio)
                assertTrue("Joined duration drift", abs(output.durationMs - 2 * input.durationMs) <= 200)
                val outputVideo = samples(joined, "video/")
                val outputAudio = samples(joined, "audio/")
                report.put("sourceDurationMs", input.durationMs).put("joinedDurationMs", output.durationMs)
                    .put("videoTiming", timing(inputVideo, outputVideo)).put("audioTiming", timing(inputAudio, outputAudio))
                // Keep diagnostics before assertions so a boundary defect remains inspectable.
                reportFile.writeText(report.toString(2), Charsets.UTF_8)
                joined.copyTo(File(fixtures, "joined-$id.mp4"))
                val videoBoundary = assertTwoCopies(inputVideo, outputVideo, input.durationMs)
                val audioBoundary = assertTwoCopies(inputAudio, outputAudio, input.durationMs)
                assertTrue("Audio/video boundary drift", abs(videoBoundary - audioBoundary) <= 100_000L)
                verifyBoundaryFrames(joined, input.durationMs)
                assertFalse("An exported draft must stay outside the library", library.movies.value.any { it.id == id })
                report.put("sourceDurationMs", input.durationMs).put("joinedDurationMs", output.durationMs)
                    .put("videoSamples", outputVideo.hashes.size).put("audioSamples", outputAudio.hashes.size)
                    .put("videoBoundaryUs", videoBoundary).put("audioBoundaryUs", audioBoundary)

                val incompatible = File(fixtures, "landscape.mp4")
                if (incompatible.isFile) {
                    val rejected = File(outputDirectory, "must-not-exist.mp4")
                    var failure: Throwable? = null
                    try { VideoExporter.concatenate(context, listOf(first, incompatible), rejected) }
                    catch (error: IllegalArgumentException) { failure = error }
                    assertNotNull("Different fixture geometry must be refused", failure)
                    assertFalse(rejected.exists())
                    report.put("incompatibleGeometry", "rejected_without_output")
                } else report.put("incompatibleGeometry", "not_run_optional_landscape_fixture_missing")
                assertFalse(outputDirectory.listFiles().orEmpty().any { it.name.startsWith(".gemini-export-") })

                val episode = MovieEpisode("episode-1", "Native episode", joined.absolutePath, output.durationMs)
                val confirmed = library.confirm(id, "Native confirmed movie", listOf(episode))
                val repeated = library.confirm(id, "Duplicate press", listOf(episode))
                assertEquals(1, library.movies.value.count { it.id == id })
                assertEquals(confirmed.episodes.map { it.path }, repeated.episodes.map { it.path })
                assertEquals("LOCAL", repeated.syncState)
                val offline = File(repeated.episodes.single().path)
                assertNotEquals(joined.canonicalPath, offline.canonicalPath)
                assertEquals(sha256(joined), sha256(offline))
                assertEquals(output.durationMs, VideoDecoder.metadata(context, Uri.fromFile(offline)).durationMs)
                assertTrue(repeated.coverPath.isNotBlank())
                val cover = checkNotNull(BitmapFactory.decodeFile(repeated.coverPath))
                try { assertTrue(cover.width > 0 && cover.height > 0) } finally { cover.recycle() }
                val resume = (output.durationMs / 3).coerceAtLeast(500L)
                library.progress(id, episode.id, resume)
                verifyOfflinePlayback(id, episode.id, resume)
                assertTrue(library.movies.value.single { it.id == id }.episodes.single().positionMs >= resume)
                assertEquals(fixtureHash, sha256(fixture))
                assertEquals(fixtureHash, sha256(first))
                assertEquals(fixtureHash, sha256(second))
                require(VideoCloudSync.get(context).state.value.accountEmail.isBlank())
                report.put("confirmedRecords", 1).put("syncState", repeated.syncState)
                    .put("resumePositionMs", resume).put("offlinePlayback", "decoded_and_advanced")
                    .put("sourcePreserved", true).put("completed", true)
            }
        } catch (error: Throwable) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message.orEmpty().take(300))
            throw error
        } finally {
            // Remove only this test's UUID record. Preserve media/report evidence for the caller.
            library.remove(id)
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    @Test(timeout = 120_000L)
    fun fiveVideoImportKeepsNaturalAndManualOrderAndResumesAroundOneFailedOutput(): Unit = runBlocking {
        val context = guard()
        val fixtures = File(checkNotNull(context.getExternalFilesDir(null)), "movie-fixtures")
        val fixture = File(fixtures, "source.mp4")
        require(fixture.isFile && fixture.length() > 0)
        val fixtureHash = sha256(fixture)
        val runId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "batch-intake-native-$runId").apply { check(mkdirs()) }
        val jobs = BatchJobs.get(context)
        require(jobs.busy.value == null)
        val store = BatchStore(context)
        val videos = VideoStore(context)
        val report = JSONObject().put("schema", 1).put("completed", false).put("cloudCalls", 0)
            .put("scope", "Real five-file intake, ordering, foreground batch scheduler and checkpoint retry using injected approved completed outputs; no fresh OCR/translation/cloud verification")
            .put("outputDirectory", directory.absolutePath)
        val reportFile = File(fixtures, "batch-intake-$runId.json")
        try {
            withTimeout(90_000L) {
                val names = listOf("EP10.mp4", "EP02.mp4", "EP01.mp4", "EP04.mp4", "EP03.mp4")
                val inputs = names.map { name -> File(directory, name).also { fixture.copyTo(it) } }
                val created = jobs.create("Native five-video series", BatchKind.SERIES)
                report.put("batchId", created.id)
                val imported = jobs.add(created.id, inputs.map(Uri::fromFile))
                assertEquals(listOf("EP01.mp4", "EP02.mp4", "EP03.mp4", "EP04.mp4", "EP10.mp4"), imported.items.map { it.name })
                assertEquals(setOf("1", "2", "3", "4", "10"), BatchPlan.groups(imported).keys)
                imported.items.forEach { item ->
                    assertEquals(fixture.length(), item.bytes)
                    assertTrue(item.durationMs > 0)
                    val project = videos.load(item.projectId)
                    assertTrue(project.width > 0 && project.height > 0)
                    assertEquals(created.id, File(videos.folder(item.projectId), "batch-owner").readText(Charsets.UTF_8))
                }
                val duplicate = jobs.add(created.id, inputs.map(Uri::fromFile))
                assertEquals(imported.items.map { it.projectId }, duplicate.items.map { it.projectId })
                val reordered = jobs.reorder(created.id, 0, 4)
                assertEquals(listOf("EP02.mp4", "EP03.mp4", "EP04.mp4", "EP10.mp4", "EP01.mp4"), reordered.items.map { it.name })
                val grouped = jobs.setEpisode(created.id, reordered.items.first().projectId, "manual-episode")
                assertEquals(5, BatchPlan.groups(grouped).size)
                assertEquals("manual-episode", grouped.items.first().episode)
                jobs.configure(created.id, VideoTranslationMode.OFFLINE, VideoSourceMode.SIDECAR)

                // Seed reviewed completed output checkpoints only. This is not an AI/translation witness.
                val prepared = store.load(created.id)
                val outputHashes = mutableMapOf<String, String>()
                prepared.items.forEachIndexed { index, item ->
                    val project = videos.load(item.projectId)
                    val output = videos.exportFile(project.id, "mp4")
                    if (index == 2) output.writeBytes(byteArrayOf()) else {
                        fixture.copyTo(output)
                        outputHashes[item.projectId] = sha256(output)
                    }
                    val cue = VideoCue("approved-fixture", 100L, 500L, "你好", "Xin chào", approved = true,
                        model = "native-completed-fixture", origin = "MANUAL_TEST_FIXTURE")
                    videos.save(project.copy(cues = listOf(cue), scanMs = project.durationMs,
                        stage = VideoStage.COMPLETE, resumeStage = VideoStage.TRANSLATING, progress = 100,
                        exportedMp4 = output.absolutePath, model = "native-completed-fixture", cloudCalls = 0))
                }
                ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java)).use { scenario ->
                    scenario.onActivity { jobs.start(created.id) }
                    withTimeout(30_000L) { while (jobs.busy.value != null) delay(100) }
                    val interrupted = BatchStore(context).load(created.id)
                    assertEquals("FAILED", interrupted.state)
                    assertEquals(4, interrupted.items.count { it.state == "DONE" })
                    assertEquals(1, interrupted.items.count { it.state == "FAILED" })
                    assertEquals(4, interrupted.outputs.size)
                    assertEquals(grouped.items.map { it.projectId }, interrupted.items.map { it.projectId })
                    interrupted.items.filter { it.state == "DONE" }.forEach { item ->
                        assertEquals(outputHashes[item.projectId], sha256(File(videos.load(item.projectId).exportedMp4)))
                    }
                    assertFalse(MovieLibrary.get(context).movies.value.any { it.id == created.id })
                    report.put("initialCompleted", 4).put("isolatedFailures", 1)

                    val failed = interrupted.items.single { it.state == "FAILED" }
                    val failedOutput = File(videos.load(failed.projectId).exportedMp4)
                    fixture.copyTo(failedOutput, overwrite = true)
                    scenario.onActivity { jobs.start(created.id) }
                    withTimeout(30_000L) { while (jobs.busy.value != null) delay(100) }
                    val recovered = BatchStore(context).load(created.id)
                    assertEquals("REVIEW", recovered.state)
                    assertEquals(5, recovered.items.count { it.state == "DONE" })
                    assertEquals(5, recovered.outputs.size)
                    assertEquals(interrupted.outputs.associate { it.episode to it.path },
                        recovered.outputs.filter { it.episode != failed.episode }.associate { it.episode to it.path })
                    recovered.items.forEach { item ->
                        val project = videos.load(item.projectId)
                        assertEquals(0, project.cloudCalls)
                        assertEquals("native-completed-fixture", project.model)
                        assertTrue(project.cues.single().approved)
                    }
                    assertFalse(MovieLibrary.get(context).movies.value.any { it.id == created.id })
                    report.put("batchId", created.id).put("naturalOrder", JSONArray(imported.items.map { it.name }))
                        .put("manualOrder", JSONArray(recovered.items.map { it.name }))
                        .put("recoveredOutputs", recovered.outputs.size)
                }
                inputs.forEach { assertEquals(fixtureHash, sha256(it)) }
                assertEquals(fixtureHash, sha256(fixture))
                report.put("sourcePreserved", true).put("completed", true)
            }
        } catch (error: Throwable) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message.orEmpty().take(300))
            throw error
        } finally {
            if (jobs.busy.value != null) jobs.pause()
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private fun guard(): Context {
        assumeTrue("Explicit movie batch opt-in required",
            InstrumentationRegistry.getArguments().getString("executeMovieBatch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.VERIFICATION_OFFLINE_NETWORK && BuildConfig.GEMINI_API_KEY.isEmpty())
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value && VideoJobs.get(context).busy.value == null)
        require(VideoCloudSync.get(context).state.value.accountEmail.isBlank()) { "Use a fresh disposable app without an account." }
        return context
    }

    private data class TrackSamples(val hashes: List<String>, val timestampsUs: List<Long>)

    private fun samples(file: File, mimePrefix: String): TrackSamples {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(mimePrefix)
            }
            assertEquals("One $mimePrefix track required", 1, tracks.size)
            extractor.selectTrack(tracks.single())
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val hashes = mutableListOf<String>()
            val timestamps = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                require(extractor.sampleSize in 1..buffer.capacity().toLong()) { "Fixture sample exceeds bounded verification buffer." }
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                assertTrue("Each compressed sample must be readable", read > 0)
                buffer.position(0); buffer.limit(read)
                hashes += hex(MessageDigest.getInstance("SHA-256").apply { update(buffer) }.digest())
                timestamps += extractor.sampleTime
                if (!extractor.advance()) break
            }
            assertTrue("Track must contain media samples", hashes.isNotEmpty())
            return TrackSamples(hashes, timestamps)
        } finally { extractor.release() }
    }

    private fun assertTwoCopies(input: TrackSamples, output: TrackSamples, durationMs: Long): Long {
        assertEquals("Stream-copy must preserve both complete sets of compressed samples", input.hashes + input.hashes, output.hashes)
        // Native MediaExtractor reports an edit-list bias for the original B-frame fixture.
        // Compare two copies within the same output timeline; production independently checks
        // both tracks against Media3's canonical edit-list timeline before publishing the MP4.
        val boundary = output.timestampsUs[input.hashes.size] - output.timestampsUs.first()
        assertTrue("Second part boundary ${boundary}us differs from expected ${durationMs * 1000}us; input first=${input.timestampsUs.first()}, output first=${output.timestampsUs.first()}", abs(boundary - durationMs * 1000) <= 100_000L)
        for (index in input.timestampsUs.indices) {
            val originalDelta = input.timestampsUs[index] - input.timestampsUs.first()
            val repeatedDelta = output.timestampsUs[index + input.hashes.size] - output.timestampsUs[input.hashes.size]
            assertTrue("Sample spacing changes at join", abs(originalDelta - repeatedDelta) <= 1000L)
        }
        assertTrue("Media must extend through the second part", output.timestampsUs.maxOrNull()!! >= (2 * durationMs - 200) * 1000)
        return boundary
    }

    private fun timing(input: TrackSamples, output: TrackSamples) = JSONObject()
        .put("inputSamples", input.hashes.size).put("outputSamples", output.hashes.size)
        .put("inputFirstUs", input.timestampsUs.first()).put("inputLastUs", input.timestampsUs.last())
        .put("inputMinUs", input.timestampsUs.minOrNull()).put("inputMaxUs", input.timestampsUs.maxOrNull())
        .put("outputFirstUs", output.timestampsUs.first()).put("outputLastUs", output.timestampsUs.last())
        .put("outputBoundaryUs", output.timestampsUs.getOrNull(input.hashes.size))
        .put("inputTimestampsUs", JSONArray(input.timestampsUs)).put("outputTimestampsUs", JSONArray(output.timestampsUs))

    private fun verifyBoundaryFrames(file: File, boundaryMs: Long) {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            for (timeMs in listOf(boundaryMs - 100, boundaryMs, boundaryMs + 100)) {
                val frame = checkNotNull(reader.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                try { assertTrue(frame.width > 0 && frame.height > 0) } finally { frame.recycle() }
            }
        } finally { reader.release() }
    }

    private fun verifyOfflinePlayback(movie: String, episode: String, resumeMs: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MoviePlaybackActivity::class.java).putExtra("movie", movie).putExtra("episode", episode)
        ActivityScenario.launch<MoviePlaybackActivity>(intent).use { scenario ->
            val position = AtomicLong(-1)
            val failure = AtomicReference<String?>(null)
            val readyDeadline = SystemClock.elapsedRealtime() + 15_000
            while (position.get() < 0 && SystemClock.elapsedRealtime() < readyDeadline) {
                scenario.onActivity { activity ->
                    playerView(activity.window.decorView)?.player?.let { player ->
                        player.playerError?.let { failure.set(it.errorCodeName) }
                        if (player.playbackState == Player.STATE_READY) { position.set(player.currentPosition); player.play() }
                    }
                }
                Thread.sleep(100)
            }
            assertNull("Offline player must decode without an error", failure.get())
            assertTrue("Offline player did not restore position", abs(position.get() - resumeMs) <= 250)
            val beginning = position.get()
            Thread.sleep(500)
            scenario.onActivity { activity ->
                val player = checkNotNull(playerView(activity.window.decorView)?.player)
                assertNull(player.playerError)
                assertTrue("Decoded local playback must advance", player.currentPosition >= beginning + 100)
                player.pause()
            }
        }
    }

    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) playerView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
