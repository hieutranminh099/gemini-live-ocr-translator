package com.gemini.ocr.video

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import kotlin.math.abs

/** Generated-media export witness. No user media, capture, OCR, model or cloud request is used. */
@RunWith(AndroidJUnit4::class)
class SmartExportQualityNativeTest {
    @Test(timeout = 240_000L)
    fun sourceAndExportKeepPictureGeometryFramePacingAndCompatibleAudio(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeExportQuality") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName in setOf("com.gemini.ocr.codexbench", "com.gemini.ocr.batchunified186") &&
            BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value)
        val label = args.getString("qualityLabel").orEmpty().also { require(it.matches(Regex("[a-z0-9-]{1,32}"))) }
        val name = args.getString("qualityFixture").orEmpty().also { require(it.matches(Regex("synthetic-[a-z0-9-]+\\.mp4"))) }
        val folder = File(context.getExternalFilesDir(null), "smart-quality").apply { mkdirs() }
        val source = File(folder, name).also { require(it.isFile && it.length() in 1..64L * 1024 * 1024) }
        val output = File(folder, "$label-${source.nameWithoutExtension}.mp4")
        require(!output.exists()) { "Preserve the previous measurement before another run." }
        val result = File(folder, "$label-${source.nameWithoutExtension}.json")
        val report = JSONObject().put("completed", false).put("label", label).put("source", name)
            .put("scope", "Original URI through real Media3/MediaCodec; manually injected Vietnamese cues; no OCR/translation/cloud")
            .put("cloudCalls", 0).put("sourceSha256", hash(source)).put("output", output.name)
        val peak = AtomicLong()
        val sampler = launch(Dispatchers.Default) { while (isActive) {
            val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
            peak.updateAndGet { maxOf(it, memory.totalPss.toLong()) }; delay(100)
        } }
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(210_000) {
                val input = inspect(source)
                report.put("input", input.json)
                require(input.durationUs in 3_000_000L..12_000_000L && input.videoPts.size <= 720)
                val codecs = JSONArray()
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
                    codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", true) }
                }.forEach { codecs.put(it.name) }
                report.put("availableAvcEncoders", codecs)
                // Parse the persisted schema so this same test APK can exercise the delivered old
                // app before installing the new app, without relying on Kotlin constructor ABI.
                val project = VideoProject.from(JSONObject().put("schema", 1).put("id", UUID.randomUUID().toString())
                    .put("title", "Generated quality witness").put("uri", Uri.fromFile(source).toString())
                    .put("durationMs", input.durationUs / 1000).put("width", input.width).put("height", input.height)
                    .put("sourceMode", "OCR").put("translationMode", "OFFLINE")
                    .put("roi", JSONArray(listOf(.06, .72, .94, .96)))
                    .put("stage", "REVIEW").put("resumeStage", "EXPORTING")
                    .put("style", JSONObject().put("position", .9).put("sizeFraction", .04)
                        .put("background", true).put("outline", true).put("frame", JSONArray(listOf(.06, .78, .94, .94))))
                    .put("cues", JSONArray().put(JSONObject().put("id", "generated-cue").put("startMs", 1000)
                        .put("endMs", 3000).put("source", "你好").put("translation", "Xin chào Việt Nam · 250 người")
                        .put("approved", true).put("origin", "MANUAL_GENERATED_FIXTURE"))))
                var progress = -1f
                val renderStarted = SystemClock.elapsedRealtime()
                VideoExporter.export(context, project, output, progress = { value ->
                    assertTrue(value in 0f..1f && value >= progress); progress = value
                })
                report.put("renderElapsedMs", SystemClock.elapsedRealtime() - renderStarted)
                assertEquals(1f, progress, 0f)
                val exported = inspect(output)
                report.put("outputMetadata", exported.json).put("outputBytes", output.length())
                    .put("outputSha256", hash(output))
                val sameGeometry = input.width == exported.width && input.height == exported.height
                // Android MediaExtractor on API28 ignores the source MP4 B-frame edit-list
                // offset. Compare intervals here; the host ffprobe comparison verifies canonical
                // absolute presentation timestamps on both files before accepting sync.
                val inputFirst = input.videoPts.minOrNull() ?: 0L
                val outputFirst = exported.videoPts.minOrNull() ?: 0L
                val picturePacing = input.videoPts.sorted().zip(exported.videoPts.sorted()).all { (a, b) -> abs((a - inputFirst) - (b - outputFirst)) <= 1000 }
                    && input.videoPts.size == exported.videoPts.size
                val audioBytesPreserved = input.audioHash == exported.audioHash && input.audioPts.size == exported.audioPts.size
                val audioPacing = input.audioPts.zip(exported.audioPts).all { (a, b) -> abs(a - b) <= 1000 }
                    && input.audioPts.size == exported.audioPts.size
                report.put("samePictureGeometry", sameGeometry).put("sameVideoFrameIntervalsWithin1ms", picturePacing)
                    .put("mediaExtractorVideoOriginDeltaUs", outputFirst - inputFirst)
                    .put("absoluteVideoPtsVerification", "Host ffprobe required; Android MediaExtractor may ignore input MP4 edit lists")
                    .put("compatibleAacCompressedBytesPreserved", audioBytesPreserved)
                    .put("audioTimestampsWithin1ms", audioPacing)
                    .put("durationDeltaUs", exported.durationUs - input.durationUs)
                assertEquals("Source media must remain untouched", report.getString("sourceSha256"), hash(source))
                assertTrue("Source AAC packets must remain untouched", audioBytesPreserved)
                assertTrue("A/V duration drift", abs(exported.durationUs - input.durationUs) <= 100_000)
                if (label.startsWith("new")) {
                    assertTrue("High-quality export cannot silently reduce source resolution", sameGeometry)
                    assertTrue("Do not drop/duplicate source pictures or change their PTS", picturePacing)
                    assertTrue("Compatible audio must keep PTS", audioPacing)
                }
                assertFalse(folder.listFiles().orEmpty().any { it.name.startsWith(".gemini-export-") })
                report.put("completed", true)
            }
        } catch (error: Throwable) {
            report.put("failure", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}")
            throw error
        } finally {
            sampler.cancelAndJoin()
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("maxProcessPssKiB", peak.get())
            result.writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private data class SampleInfo(val width: Int, val height: Int, val durationUs: Long,
        val videoPts: List<Long>, val audioPts: List<Long>, val audioHash: String, val json: JSONObject)

    private fun inspect(file: File): SampleInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = JSONArray(); val videoPts = mutableListOf<Long>(); val audioPts = mutableListOf<Long>()
            val audioDigest = MessageDigest.getInstance("SHA-256")
            var width = 0; var height = 0; var duration = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val row = JSONObject().put("mime", mime).put("format", format.toString())
                if (mime.startsWith("video/")) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH); height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    duration = format.getLong(MediaFormat.KEY_DURATION)
                    row.put("width", width).put("height", height).put("durationUs", duration)
                }
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                var bytes = 0L; var count = 0
                while (extractor.sampleTime >= 0) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                    require(size < buffer.capacity() && count++ < 20_000)
                    bytes += size
                    if (mime.startsWith("video/")) videoPts += extractor.sampleTime
                    if (mime.startsWith("audio/")) {
                        audioPts += extractor.sampleTime
                        val packet = ByteArray(size); buffer.position(0); buffer.get(packet); audioDigest.update(packet)
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(index)
                row.put("sampleBytes", bytes).put("sampleCount", count)
                if (format.containsKey(MediaFormat.KEY_DURATION)) row.put("measuredBitrate", bytes * 8_000_000.0 / format.getLong(MediaFormat.KEY_DURATION))
                tracks.put(row)
            }
            val audioHash = audioDigest.digest().joinToString("") { "%02x".format(it) }
            return SampleInfo(width, height, duration, videoPts, audioPts, audioHash,
                JSONObject().put("tracks", tracks).put("videoPtsUs", JSONArray(videoPts)).put("audioPtsUs", JSONArray(audioPts))
                    .put("audioCompressedSha256", audioHash))
        } finally { extractor.release() }
    }

    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(128 * 1024)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
