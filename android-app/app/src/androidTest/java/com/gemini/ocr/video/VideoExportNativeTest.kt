package com.gemini.ocr.video

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opt-in export/codec verification with an owner-prepared public-video fixture and synthetic tone.
 * Manual cues are injected for rendering; this is not an OCR, translation-quality or Live test.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportNativeTest {
    @Test(timeout = 660_000L)
    fun manualTimelineExportsMp4WithAudioAndSupportsCancelRestart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required", args.getString("executeVideo") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.codexbench") {
            "Run only in the disposable com.gemini.ocr.codexbench debug package."
        }
        require(BuildConfig.GEMINI_API_KEY.isEmpty()) { "Verification APK must not embed a key." }
        require(!SessionState.running.value) { "Stop Live before this isolated codec verification." }
        val maximumMs = (args.getString("videoMaxRuntimeMs")?.toLongOrNull() ?: 480_000L)
            .also { require(it in 60_000L..600_000L) }
        val verification = File(checkNotNull(context.getExternalFilesDir(null)), "verification")
        val source = File(verification, "video-source-modern.mp4")
        require(source.isFile && source.length() > 0L) {
            "Prepare the authorized 45s fixture at /sdcard/Android/data/com.gemini.ocr.codexbench/files/verification/video-source-modern.mp4."
        }
        val directory = File(verification, "video-export-smoke")
        check(!directory.exists()) { "Preserve the previous video-export-smoke result before another run." }
        check(directory.mkdirs())
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + maximumMs
        val maxPss = AtomicLong()
        val report = JSONObject()
            .put("schema", 1)
            .put("test", "native_export_only_manual_timeline")
            .put("scope", "MP4 codec/mux/subtitle timing/cancel-restart; NOT OCR, translation quality or Live latency")
            .put("source", source.name)
            .put("sourceProvenance", "45s excerpt of owner-prepared public YouTube screen recording")
            .put("audioProvenance", "Synthetic tone added solely to verify audio muxing; not original YouTube audio")
            .put("cloudCalls", 0)
            .put("translationEngineUsed", false)
            .put("ocrEngineUsed", false)
            .put("fixtureCuesAreInjected", true)
            .put("maximumRuntimeMs", maximumMs)
            .put("completed", false)
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                maxPss.updateAndGet { previous -> maxOf(previous, memory.totalPss.toLong()) }
                delay(100)
            }
        }
        try {
            withTimeout(maximumMs) {
                val input = inspect(source, deadline)
                report.put("input", input.json)
                require(input.durationMs in 44_500L..46_000L) { "Fixture must be the prepared 45s excerpt." }
                require(input.audioTracks > 0) { "Fixture must include the documented synthetic audio tone." }
                require(input.displayWidth.toLong() * input.displayHeight <= 3840L * 2160L) { "Bound fixture to at most 4K." }
                val cues = listOf(
                    VideoCue("manual-start", 1000, 3000, "我不是陈明，我有二百五十元。",
                        "Tôi không phải Trần Minh. Tôi có 250 nhân dân tệ.", origin = "MANUAL_EXPORT_FIXTURE", model = "none"),
                    VideoCue("manual-middle", 20_000, 22_000, "阮安没有来，只有三个人。",
                        "Nguyễn An chưa đến; chỉ có 3 người.", origin = "MANUAL_EXPORT_FIXTURE", model = "none"),
                    VideoCue("manual-end", 42_000, 44_000, "不要把十二写成二十。",
                        "Đừng viết 12 thành 20.", origin = "MANUAL_EXPORT_FIXTURE", model = "none")
                )
                val project = VideoProject(
                    id = "00000000-0000-0000-0000-000000000045", title = "Export-only public video fixture",
                    uri = Uri.fromFile(source).toString(), durationMs = input.durationMs,
                    width = input.displayWidth, height = input.displayHeight,
                    stage = VideoStage.EXPORTING, resumeStage = VideoStage.EXPORTING,
                    cues = VideoTimeline.validate(cues, input.durationMs),
                    style = VideoSubtitleStyle(position = .86f, sizeFraction = .045f, background = true, outline = true),
                    message = "Manual injected cues; export verification only, no OCR or translation inference.",
                    model = "none-manual-export-fixture", cloudCalls = 0
                )
                File(directory, "project.json").writeText(project.json().toString(2), Charsets.UTF_8)
                File(directory, "translated.srt").writeText(VideoTimeline.srt(cues), Charsets.UTF_8)
                File(directory, "translated.ass").writeText(VideoTimeline.ass(project), Charsets.UTF_8)
                val output = File(directory, "translated.mp4")

                // An already-running Live session must refuse export before allocating a partial file.
                SessionState.running.value = true
                val liveStarted = SystemClock.elapsedRealtime()
                try {
                    VideoExporter.export(context, project, output)
                    fail("Live must preempt export")
                } catch (_: VideoExporter.LivePreemptedException) {
                    report.put("liveImmediatePreemptionMs", SystemClock.elapsedRealtime() - liveStarted)
                } finally { SessionState.running.value = false }
                assertFalse("Preempted export must not publish MP4", output.exists())
                assertNoPartial(directory)

                // The second gate is the reporter gate after Transformer.start(), unlike preflight.
                // Suspend that gate, then cancel while the native encoder has been started.
                val cancellationStarted = SystemClock.elapsedRealtime()
                supervisorScope {
                    val enteredEncoderStage = CompletableDeferred<Unit>()
                    val gateCalls = AtomicInteger()
                    val attempt = async(Dispatchers.Default) {
                        VideoExporter.export(context, project, output, gate = {
                            if (gateCalls.incrementAndGet() >= 2) {
                                enteredEncoderStage.complete(Unit)
                                awaitCancellation()
                            }
                        })
                    }
                    try {
                        withTimeout(45_000) { enteredEncoderStage.await() }
                        delay(100)
                        assertTrue("Cancellation fixture must reach a running export", attempt.isActive)
                        withTimeout(30_000) { attempt.cancelAndJoin() }
                        assertTrue(attempt.isCancelled)
                    } finally {
                        withContext(NonCancellable) { attempt.cancelAndJoin() }
                    }
                }
                report.put("cancelAndCleanupMs", SystemClock.elapsedRealtime() - cancellationStarted)
                assertFalse("Cancelled MP4 must not be published", output.exists())
                assertNoPartial(directory)

                val exportedAt = SystemClock.elapsedRealtime()
                var previousProgress = -1f
                VideoExporter.export(context, project, output, progress = { value ->
                    require(value in 0f..1f && value >= previousProgress) { "Export progress regressed." }
                    previousProgress = value
                })
                report.put("exportElapsedMs", SystemClock.elapsedRealtime() - exportedAt)
                assertEquals(1f, previousProgress, 0f)
                assertTrue("Resume must produce a nonempty MP4", output.isFile && output.length() > 0L)
                assertNoPartial(directory)
                val exported = inspect(output, deadline)
                report.put("output", exported.json).put("outputBytes", output.length())
                assertTrue("MP4 must retain an audio track", exported.audioTracks > 0)
                assertTrue("Output duration drifted by more than 500ms", abs(exported.durationMs - input.durationMs) <= 500)
                val inputAspect = input.displayWidth.toDouble() / input.displayHeight
                val outputAspect = exported.displayWidth.toDouble() / exported.displayHeight
                assertEquals("Display aspect must survive rotation/encoding", inputAspect, outputAspect, .015)
                assertTrue("Audio should reach the end of the 45s fixture", exported.lastAudioSampleUs >= (input.durationMs - 500) * 1000)
                val frameRows = JSONArray()
                for (timeMs in listOf(500L, 2000L, 21_000L, 43_000L, 44_500L)) {
                    check(SystemClock.elapsedRealtime() < deadline)
                    val stem = timeMs.toString().padStart(6, '0')
                    val sourceFrame = File(directory, "source-$stem.png")
                    val outputFrame = File(directory, "output-$stem.png")
                    savePreview(source, input, timeMs, sourceFrame)
                    savePreview(output, exported, timeMs, outputFrame)
                    frameRows.put(JSONObject().put("timeMs", timeMs)
                        .put("expectedCue", VideoTimeline.active(cues, timeMs)?.id ?: JSONObject.NULL)
                        .put("sourcePng", sourceFrame.name).put("outputPng", outputFrame.name))
                }
                report.put("previewFrames", frameRows)
                    .put("reviewRequired", "Inspect paired PNGs and MP4 playback: Vietnamese glyphs, masking, cue boundaries and end sync.")
                    .put("completed", true)
            }
        } catch (error: Throwable) {
            report.put("errorType", error.javaClass.simpleName).put("error", error.message?.take(400))
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                sampler.cancelAndJoin()
                report.put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                    .put("maxProcessPssKiB", maxPss.get())
                    .put("memoryScope", "Whole verification app/test process; includes codec and preview decode")
                File(directory, "report.json").writeText(report.toString(2), Charsets.UTF_8)
            }
        }
    }

    private fun assertNoPartial(directory: File) {
        assertTrue("Exporter must release/delete its private partial files", directory.listFiles().orEmpty()
            .none { it.name.startsWith(".gemini-export-") })
    }

    private data class MediaInfo(
        val displayWidth: Int, val displayHeight: Int, val durationMs: Long,
        val audioTracks: Int, val lastAudioSampleUs: Long, val json: JSONObject
    )

    private fun inspect(file: File, deadline: Long): MediaInfo {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            val height = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val displayWidth = if (rotation % 180 == 0) width else height
            val displayHeight = if (rotation % 180 == 0) height else width
            require(displayWidth > 0 && displayHeight > 0 && duration > 0)
            extractor.setDataSource(file.absolutePath)
            val tracks = JSONArray()
            var audioTracks = 0
            var lastAudioSampleUs = -1L
            for (track in 0 until extractor.trackCount) {
                check(SystemClock.elapsedRealtime() < deadline)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                val row = JSONObject().put("index", track).put("mime", mime)
                for (key in listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_SAMPLE_RATE)) {
                    if (format.containsKey(key)) row.put(key, format.getInteger(key))
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) row.put("durationUs", format.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("audio/")) {
                    audioTracks++
                    extractor.selectTrack(track)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var samples = 0
                    var first = -1L
                    var last = -1L
                    while (extractor.sampleTime >= 0) {
                        if (samples == 0) first = extractor.sampleTime
                        last = extractor.sampleTime
                        samples++
                        if (samples % 128 == 0) check(SystemClock.elapsedRealtime() < deadline)
                        if (!extractor.advance()) break
                    }
                    extractor.unselectTrack(track)
                    lastAudioSampleUs = maxOf(lastAudioSampleUs, last)
                    row.put("samples", samples).put("firstSampleUs", first).put("lastSampleUs", last)
                }
                tracks.put(row)
            }
            val json = JSONObject().put("encodedWidth", width).put("encodedHeight", height)
                .put("rotationDegrees", rotation).put("displayWidth", displayWidth).put("displayHeight", displayHeight)
                .put("durationMs", duration).put("audioTracks", audioTracks).put("lastAudioSampleUs", lastAudioSampleUs)
                .put("tracks", tracks)
            return MediaInfo(displayWidth, displayHeight, duration, audioTracks, lastAudioSampleUs, json)
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    private fun savePreview(file: File, media: MediaInfo, timeMs: Long, output: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val scale = min(1.0, min(1280.0 / media.displayWidth, 720.0 / media.displayHeight))
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                    (media.displayWidth * scale).roundToInt().coerceAtLeast(1),
                    (media.displayHeight * scale).roundToInt().coerceAtLeast(1))
            } else retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            requireNotNull(frame) { "Cannot decode fixture frame at $timeMs ms." }
            try {
                output.outputStream().use { stream -> check(frame.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            } finally { frame.recycle() }
        } finally { retriever.release() }
    }
}
