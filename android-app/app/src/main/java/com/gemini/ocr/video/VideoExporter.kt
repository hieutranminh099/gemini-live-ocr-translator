@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.gemini.ocr.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Streaming MP4 export. No source frame or completed cue bitmap is retained by this class. */
object VideoExporter {
    /** The caller can wait for Live to stop, then restart the EXPORTING stage from its checkpoint. */
    class LivePreemptedException : Exception("Đã nhường bộ mã hóa video cho Live. Xuất lại khi Live dừng.")

    private val exportMutex = Mutex()

    /**
     * [progress] is in 0..1. The input remains untouched and [outputFile] must not already exist.
     * Cancellation and Live both synchronously release Transformer before deleting the partial MP4.
     * Transformer has no pause API: a paused/cancelled export restarts from the saved subtitle timeline.
     * Callbacks may run off the main thread; use the UI dispatcher for direct UI updates.
     */
    suspend fun export(
        context: Context,
        project: VideoProject,
        outputFile: File,
        gate: suspend () -> Unit = {},
        progress: suspend (Float) -> Unit = {}
    ): File = exportMutex.withLock {
        val cues = validate(project)
        exportComposition(context, outputFile, gate, progress, qualitySource = Uri.parse(project.uri)) {
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(project.uri)))
                .setRemoveAudio(false)
                // No resizing, clipping or frame-rate override: retain A/V PTS.
                .setEffects(Effects(emptyList(), subtitleBackdropEffects(project) +
                    OverlayEffect(listOf(TimelineBitmapOverlay(cues, project.style)))))
                .build()
            Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                // Our subtitle canvas is SDR, including when the source is HDR.
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
        }
    }

    /** Joins already subtitled MP4 parts in the explicitly confirmed order, without re-encoding. */
    suspend fun concatenate(
        context: Context,
        inputs: List<File>,
        outputFile: File,
        gate: suspend () -> Unit = {},
        progress: suspend (Float) -> Unit = {}
    ): File = exportMutex.withLock {
        currentCoroutineContext().ensureActive()
        checkLive()
        gate()
        checkLive()
        val sources = withContext(Dispatchers.IO) {
            require(inputs.isNotEmpty()) { "Chọn ít nhất một phần đã xuất MP4 để ghép." }
            require(!outputFile.exists()) { "Tệp xuất đã tồn tại; hãy chọn tên mới." }
            inputs.map { it.canonicalFile }.also { files ->
                require(files.all { it.isFile && it.canRead() && it.length() > 0 && it.extension.equals("mp4", true) }) {
                    "Có phần MP4 bị thiếu hoặc không đọc được. Xuất lại phần đó trước khi ghép."
                }
            }
        }
        val parts = sources.map { input ->
            currentCoroutineContext().ensureActive()
            checkLive()
            gate()
            withContext(Dispatchers.IO) { readMergePart(input) }
        }
        val plan = planConcatenation(parts)
        withContext(Dispatchers.IO) {
            val parent = outputFile.absoluteFile.parentFile!!
            require(parent.isDirectory || parent.mkdirs()) { "Không tạo được thư mục xuất video." }
            val reserve = 256L * 1024 * 1024
            require(plan.sizeBytes <= Long.MAX_VALUE - reserve && parent.usableSpace >= plan.sizeBytes + reserve) {
                "Không đủ bộ nhớ trống để ghép MP4. Giải phóng ít nhất dung lượng các phần cộng thêm 256 MiB."
            }
        }
        exportComposition(context, outputFile, gate, progress, useInAppMuxer = true, verify = { working ->
            val output = readMergePart(working)
            require(output.video.width == parts.first().video.width && output.video.height == parts.first().video.height &&
                output.video.rotation == parts.first().video.rotation && (output.audio != null) == (parts.first().audio != null)) {
                "Bản ghép có định dạng hình/tiếng không đúng; các phần gốc vẫn được giữ. Hãy xuất lại với cùng cấu hình."
            }
            // Container rounding may accumulate by one frame per part. Never publish a truncated join.
            val toleranceUs = Math.multiplyExact(parts.size.toLong(), 100_000L)
            require(kotlin.math.abs(output.durationUs - plan.durationUs) <= toleranceUs) {
                "Thời lượng bản ghép không khớp các phần. Các phần gốc vẫn được giữ; hãy xuất riêng từng phần."
            }
            verifyMergeTimestamps(sources, working, plan, parts.first().audio != null, gate)
        }) {
            val edited = sources.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).setRemoveAudio(false).build() }
            Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                .setTransmuxVideo(true).setTransmuxAudio(true).build()
        }
    }

    /** Shared Transformer lifecycle for single-video subtitles and compatible sequence joining. */
    private suspend fun exportComposition(
        context: Context,
        outputFile: File,
        gate: suspend () -> Unit,
        progress: suspend (Float) -> Unit,
        useInAppMuxer: Boolean = false,
        verify: suspend (File) -> Unit = {},
        qualitySource: Uri? = null,
        composition: () -> Composition
    ): File {
        currentCoroutineContext().ensureActive()
        checkLive()
        gate()
        checkLive()
        val encoderFactory = qualitySource?.let { uri ->
            val sourceBitrate = withContext(Dispatchers.IO) { readSourceVideoBitrate(context, uri) }
            checkLive()
            SourceQualityEncoderFactory(context.applicationContext, sourceBitrate)
        }
        var partial: File? = null
        var thread: HandlerThread? = null
        var published = false
        try {
            withContext(Dispatchers.IO) {
                require(!outputFile.exists()) { "Tệp xuất đã tồn tại; hãy chọn tên mới." }
                val parent = outputFile.absoluteFile.parentFile!!
                require(parent.isDirectory || parent.mkdirs()) { "Không tạo được thư mục xuất video." }
                // Assign inside the IO block, so prompt cancellation returning to the caller cannot
                // lose the path before finally gets a chance to delete it.
                partial = File.createTempFile(".gemini-export-", ".mp4", parent)
            }
            val workingFile = checkNotNull(partial)
            val workerThread = HandlerThread("VideoMp4Export", Process.THREAD_PRIORITY_BACKGROUND).also { thread = it }
            workerThread.start()
            val dispatcher = Handler(workerThread.looper).asCoroutineDispatcher("VideoMp4Export")
            withContext(dispatcher) {
                coroutineScope {
                    val result = CompletableDeferred<ExportResult>()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setLooper(workerThread.looper)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .apply {
                            if (encoderFactory != null) setEncoderFactory(encoderFactory)
                            // Some platform muxers shift B-frame video and the first AAC packet by
                            // different amounts. The pinned Media3 muxer preserves composition PTS.
                            if (useInAppMuxer) setMuxerFactory(InAppMuxer.Factory.Builder().build())
                        }
                        // Keep compatible source audio; Transformer transcodes if its MP4 muxer needs it.
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                result.complete(exportResult)
                            }
                            override fun onError(
                                composition: Composition, exportResult: ExportResult, exportException: ExportException
                            ) { result.completeExceptionally(exportException) }
                        }).build()
                    val liveWatcher = launch {
                        SessionState.running.first { it }
                        throw LivePreemptedException()
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        checkLive()
                        progress(0f)
                        transformer.start(composition(), workingFile.absolutePath)
                        val reporter = launch {
                            val holder = ProgressHolder()
                            var last = -1
                            while (!result.isCompleted) {
                                checkLive()
                                gate()
                                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    val value = holder.progress.coerceIn(0, 99)
                                    if (value != last) { last = value; progress(value / 100f) }
                                }
                                delay(200)
                            }
                        }
                        result.await()
                        reporter.cancelAndJoin()
                        checkLive()
                    } finally {
                        liveWatcher.cancel()
                        // Media3 1.5.1 cancel() waits for its internal release condition. Run on its
                        // application looper, never the Android main thread, even on coroutine cancel.
                        withContext(NonCancellable) { transformer.cancel() }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            checkLive()
            withContext(Dispatchers.IO) {
                if (workingFile.length() == 0L) throw IOException("Bộ mã hóa không tạo được MP4.")
                verify(workingFile)
                // Same directory; do not replace an existing export or the source file.
                Files.move(workingFile.toPath(), outputFile.toPath())
                published = true
            }
            progress(1f)
            return outputFile
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                thread?.quitSafely()
                thread?.let { if (it.isAlive) it.join() }
                if (!published) partial?.delete()
            }
        }
    }

    internal fun validate(project: VideoProject): List<VideoCue> {
        require(project.durationMs > 0) { "Video thiếu thời lượng." }
        require(Uri.parse(project.uri).scheme in listOf("content", "file")) { "Chọn video trong máy để xuất." }
        val style = project.style
        require(style.position.isFinite() && style.position in .05f.. .95f &&
            style.sizeFraction.isFinite() && style.sizeFraction in .02f.. .09f) { "Kiểu phụ đề không hợp lệ." }
        val cues = VideoTimeline.validate(project.cues, project.durationMs)
        require(cues.isNotEmpty()) { "Chưa có phụ đề để xuất." }
        require(cues.all { it.translation.isNotBlank() }) { "Dịch hoặc sửa các câu còn trống trước khi xuất MP4." }
        return cues
    }

    private fun checkLive() { if (SessionState.running.value) throw LivePreemptedException() }
}

/** Values required to prove stream-copy compatibility; codec buffers use value equality. */
internal data class MergeTrackSignature(
    val mime: String,
    val codecData: List<List<Byte>>,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val frameRateMilli: Long = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val attributes: Map<String, Int> = emptyMap()
)

internal data class MergePart(
    val video: MergeTrackSignature,
    val audio: MergeTrackSignature?,
    val durationUs: Long,
    val sizeBytes: Long
)

internal data class MergePlan(val offsetsUs: List<Long>, val durationUs: Long, val sizeBytes: Long)

internal fun planConcatenation(parts: List<MergePart>): MergePlan {
    require(parts.isNotEmpty()) { "Chọn ít nhất một phần MP4 để ghép." }
    val first = parts.first()
    require(first.video.mime == "video/avc" && first.video.codecData.isNotEmpty() &&
        first.video.width > 0 && first.video.height > 0 && first.video.frameRateMilli > 0 &&
        first.video.rotation in setOf(0, 90, 180, 270) &&
        (first.audio == null || (first.audio.mime == "audio/mp4a-latm" && first.audio.codecData.isNotEmpty() &&
            first.audio.sampleRate > 0 && first.audio.channels > 0))) {
        "Chỉ ghép MP4 H.264 với âm thanh AAC tương thích. Hãy xuất lại các phần bằng cùng cấu hình trước khi ghép."
    }
    var duration = 0L
    var bytes = 0L
    val offsets = parts.mapIndexed { index, part ->
        require(part.durationUs > 0 && part.sizeBytes > 0) { "Phần ${index + 1} thiếu thời lượng hoặc dữ liệu video." }
        require(part.video == first.video && part.audio == first.audio) {
            "Phần ${index + 1} khác codec, độ phân giải, FPS, hướng hoặc âm thanh. Xuất lại các phần với cùng cấu hình; có thể xem riêng từng phần."
        }
        val offset = duration
        try {
            duration = Math.addExact(duration, part.durationUs)
            bytes = Math.addExact(bytes, part.sizeBytes)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Tổng dung lượng hoặc thời lượng vượt khả năng ghép. Chia thành các phim nhỏ hơn.")
        }
        offset
    }
    return MergePlan(offsets, duration, bytes)
}

private fun readMergePart(file: File): MergePart {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        var video: MergeTrackSignature? = null
        var audio: MergeTrackSignature? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == "video/avc" || mime == "audio/mp4a-latm") {
                "Phần MP4 có luồng không hỗ trợ ghép trực tiếp. Xuất lại thành H.264/AAC trước khi ghép."
            }
            require(format.containsKey(MediaFormat.KEY_DURATION)) { "Không đọc được thời lượng phần MP4; hãy xuất lại phần này." }
            val codecData = (0..2).mapNotNull { item ->
                format.getByteBuffer("csd-$item")?.duplicate()?.let { buffer ->
                    require(buffer.remaining() in 1..65536) { "Thông tin codec MP4 không hợp lệ; hãy xuất lại phần này." }
                    ByteArray(buffer.remaining()).also { buffer.get(it) }.toList()
                }
            }
            fun integer(key: String, default: Int = 0) = if (format.containsKey(key)) format.getInteger(key) else default
            val attributes = listOf("profile", "level", "color-standard", "color-range", "color-transfer",
                "crop-left", "crop-right", "crop-top", "crop-bottom", "sar-width", "sar-height",
                "encoder-delay", "encoder-padding").filter { format.containsKey(it) }.associateWith { format.getInteger(it) }
            if (mime.startsWith("video/")) {
                require(video == null) { "Phần MP4 có nhiều luồng hình ảnh. Xuất lại với một luồng hình trước khi ghép." }
                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                        .getOrElse { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() } else 0.0
                require(fps.isFinite() && fps > 0) { "Không xác minh được FPS của phần MP4. Xuất lại các phần với cùng FPS trước khi ghép." }
                video = MergeTrackSignature(mime, codecData, integer(MediaFormat.KEY_WIDTH), integer(MediaFormat.KEY_HEIGHT),
                    integer("rotation-degrees"), kotlin.math.round(fps * 1000).toLong(), attributes = attributes)
            } else {
                require(audio == null) { "Phần MP4 có nhiều luồng âm thanh. Chọn một luồng khi xuất lại trước khi ghép." }
                audio = MergeTrackSignature(mime, codecData, sampleRate = integer(MediaFormat.KEY_SAMPLE_RATE),
                    channels = integer(MediaFormat.KEY_CHANNEL_COUNT), attributes = attributes)
            }
        }
        // Transformer places consecutive assets on the canonical media timeline, which includes
        // any AAC tail beyond the last video frame. Use that same common boundary for both
        // tracks: using video duration alone shifts every later part relative to its audio.
        return MergePart(checkNotNull(video) { "MP4 thiếu luồng hình ảnh; hãy xuất lại phần này." }, audio,
            canonicalMergeDurationUs(file), file.length())
    } finally { extractor.release() }
}

/** Walk PTS in decode order, including reordered B frames; never buffer compressed media in RAM. */
private suspend fun verifyMergeTimestamps(
    sources: List<File>, output: File, plan: MergePlan, hasAudio: Boolean, gate: suspend () -> Unit
) {
    for (type in if (hasAudio) listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else listOf(C.TRACK_TYPE_VIDEO)) {
        // Android MediaExtractor can ignore MP4 edit lists for B-frame input yet apply signed
        // composition offsets in an in-app muxed output. Use the same canonical parser as the
        // player/Transformer on both sides, with one shared media time origin and no track shifts.
        MergeSampleTimes(output, type).use { joined ->
            sources.forEachIndexed { partIndex, source ->
                MergeSampleTimes(source, type).use { input ->
                    var count = 0
                    while (true) {
                        val timeUs = input.next() ?: break
                        currentCoroutineContext().ensureActive()
                        if (count++ % 128 == 0) gate()
                        val actual = requireNotNull(joined.next()) { "Bản ghép thiếu dữ liệu hình/tiếng; các phần gốc vẫn được giữ." }
                        val expected = Math.addExact(timeUs, plan.offsetsUs[partIndex])
                        require(kotlin.math.abs(actual - expected) <= 2_000L) {
                            "Timestamp hình/tiếng bị thay đổi khi ghép (track=$type, part=${partIndex + 1}, sample=$count, expectedUs=$expected, actualUs=$actual). Chưa lưu phim lỗi; hãy xuất riêng từng phần hoặc xuất lại cùng cấu hình."
                        }
                    }
                    require(count > 0) { "Phần MP4 không còn dữ liệu hình/tiếng đọc được." }
                }
            }
            require(joined.next() == null) { "Bản ghép có dữ liệu thừa ngoài timeline; các phần gốc vẫn được giữ." }
        }
    }
}

/** Iterates canonical MP4 sample times while discarding media bytes through a fixed scratch buffer. */
internal fun canonicalMergeDurationUs(file: File): Long = MergeSampleTimes(file, C.TRACK_TYPE_VIDEO).use { it.durationUs() }

private class MergeSampleTimes(file: File, private val type: Int) : Closeable {
    private val source = RandomAccessFile(file, "r")
    private val reader = DataReader { buffer, offset, length -> source.read(buffer, offset, length) }
    private var input = DefaultExtractorInput(reader, 0, source.length())
    private val extractor = Mp4Extractor()
    private val position = PositionHolder()
    private val pending = java.util.ArrayDeque<Long>()
    private val scratch = ByteArray(16 * 1024)
    private var ended = false
    private var mediaDurationUs = C.TIME_UNSET
    init {
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, trackType: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(data: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val read = data.read(scratch, 0, minOf(length, scratch.size))
                    if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
                    return read
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    require(cryptoData == null) { "Không ghép trực tiếp video mã hóa; hãy dùng bản xuất MP4 có thể đọc." }
                    if (trackType == type) {
                        require(pending.size < 64) { "Không đọc được thứ tự thời gian của video." }
                        pending.addLast(timeUs)
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { mediaDurationUs = seekMap.durationUs }
        })
    }
    fun durationUs(): Long {
        while (mediaDurationUs == C.TIME_UNSET && !ended) advance()
        require(mediaDurationUs > 0) { "Không đọc được thời lượng timeline MP4; hãy xuất lại phần này." }
        return mediaDurationUs
    }
    fun next(): Long? {
        while (pending.isEmpty() && !ended) advance()
        return pending.pollFirst()
    }
    private fun advance() {
        when (extractor.read(input, position)) {
            Extractor.RESULT_END_OF_INPUT -> ended = true
            Extractor.RESULT_SEEK -> {
                require(position.position in 0..source.length()) { "MP4 có vị trí dữ liệu không hợp lệ." }
                source.seek(position.position)
                input = DefaultExtractorInput(reader, position.position, source.length())
            }
        }
    }
    override fun close() { try { extractor.release() } finally { source.close() } }
}

/** One current subtitle strip and a 1px transparent sentinel, independent of video length. */
internal class TimelineBitmapOverlay(
    cues: List<VideoCue>, style: VideoSubtitleStyle
) : BitmapOverlay() {
    private val style = style.displayStyle()
    private var cues: List<VideoCue> = canonical(cues)
    private var videoWidth = 0
    private var videoHeight = 0
    private var current: VideoCue? = null
    private var bitmap: Bitmap? = null
    private var transparent: Bitmap? = null
    private var renderedText: String? = null

    /** A future cue edit must not tear down the bitmap of an unchanged visible caption. */
    internal fun updateTimeline(cues: List<VideoCue>) { this.cues = canonical(cues) }

    private fun canonical(rows: List<VideoCue>): List<VideoCue> =
        VideoTimeline.validate(rows, rows.maxOfOrNull { it.endMs } ?: 0).toList()

    override fun configure(videoSize: Size) {
        require(videoSize.width > 0 && videoSize.height > 0)
        if (videoWidth != videoSize.width || videoHeight != videoSize.height) {
            clearBitmap()
            videoWidth = videoSize.width
            videoHeight = videoSize.height
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        require(videoWidth > 0 && videoHeight > 0) { "Overlay chưa được cấu hình." }
        val cue = if (presentationTimeUs < 0) null else VideoTimeline.active(cues, presentationTimeUs / 1000)
        if (cue == null || cue.translation.isBlank()) {
            clearBitmap()
            return transparent ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { transparent = it }
        }
        if (renderedText != cue.translation || bitmap == null) {
            clearBitmap()
            bitmap = render(cue.translation)
            renderedText = cue.translation
        }
        current = cue
        return bitmap!!
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        // getTextureId() calls getBitmap() before Media3 obtains these settings.
        val bounds = bitmapBounds()
        return OverlaySettings.Builder()
            .setAlphaScale(if (current == null) 0f else 1f)
            .setBackgroundFrameAnchor(2f * bounds.centerX() / videoWidth - 1f,
                1f - 2f * bounds.centerY() / videoHeight)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
    }

    /** Placement in source-video pixels, shared by the native preview and MP4 renderer. */
    fun bitmapBounds(): RectF {
        style.frame?.let { return it.pixelBounds(videoWidth, videoHeight) }
        val width = bitmap?.width ?: 1
        val height = bitmap?.height ?: 1
        val bottom = (videoHeight * style.position).coerceIn(height.toFloat(), videoHeight.toFloat())
        val left = (videoWidth - width) / 2f
        return RectF(left, bottom - height, left + width, bottom)
    }

    private fun render(text: String): Bitmap {
        if (style.frame != null) return renderFrame(text)
        val padding = (videoHeight * style.sizeFraction * .3f).roundToInt().coerceAtLeast(2)
        val width = (videoWidth * .94f).roundToInt().coerceAtLeast(1)
        val textWidth = (width - 2 * padding).coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (videoHeight * this@TimelineBitmapOverlay.style.sizeFraction).coerceAtLeast(8f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val cleaned = text.replace("\r", "")
        val builder = StaticLayout.Builder.obtain(cleaned, 0, cleaned.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if(Build.VERSION.SDK_INT>=29)builder.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        val layout=builder.build()
        val height = layout.height + padding * 2
        require(height <= videoHeight * .85f) { "Câu phụ đề quá dài cho khung hình; hãy tách câu hoặc giảm cỡ chữ." }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (style.background) canvas.drawColor(Color.BLACK)
        canvas.translate(padding.toFloat(), padding.toFloat())
        if (style.outline) {
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ceil(paint.textSize * .08f)
            paint.strokeJoin = Paint.Join.ROUND
            layout.draw(canvas)
        }
        paint.color = if (style.yellowText) Color.rgb(255, 235, 59) else Color.WHITE
        paint.style = Paint.Style.FILL
        layout.draw(canvas)
        return output
    }

    private fun renderFrame(text: String): Bitmap {
        val measured = measureSubtitleFrame(text, style, videoWidth, videoHeight)
        val output = Bitmap.createBitmap(measured.width, measured.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (style.background) canvas.drawColor(Color.BLACK)
        canvas.translate(measured.padding.toFloat(), measured.textTop)
        val paint = measured.paint
        if (style.outline) {
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ceil(paint.textSize * .08f)
            paint.strokeJoin = Paint.Join.ROUND
            measured.layout.draw(canvas)
        }
        paint.color = if (style.yellowText) Color.rgb(255, 235, 59) else Color.WHITE
        paint.style = Paint.Style.FILL
        measured.layout.draw(canvas)
        return output
    }

    private fun clearBitmap() {
        bitmap?.recycle()
        bitmap = null
        current = null
        renderedText = null
    }

    override fun release() {
        try { super.release() } finally {
            clearBitmap()
            transparent?.recycle()
            transparent = null
        }
    }
}

internal fun VideoSubtitleFrame.pixelBounds(width: Int, height: Int) = RectF(
    (left * width).roundToInt().toFloat(), (top * height).roundToInt().toFloat(),
    (right * width).roundToInt().toFloat(), (bottom * height).roundToInt().toFloat())

/** Measure once per cue, shrinking long text to the chosen rectangle without drawing outside it. */
internal data class MeasuredSubtitleFrame(val width: Int, val height: Int, val padding: Int,
    val textTop: Float, val paint: TextPaint, val layout: StaticLayout, val text: String)

internal fun measureSubtitleFrame(text: String, style: VideoSubtitleStyle, width: Int, height: Int): MeasuredSubtitleFrame {
    val frame = requireNotNull(style.frame)
    val bounds = frame.pixelBounds(width, height)
    val stripWidth = bounds.width().roundToInt().coerceAtLeast(1)
    val stripHeight = bounds.height().roundToInt().coerceAtLeast(1)
    val padding = minOf((height * style.sizeFraction * .25f).roundToInt().coerceAtLeast(2),
        (stripHeight / 6).coerceAtLeast(1), (stripWidth / 6).coerceAtLeast(1))
    val requestedTextSize = (height * style.sizeFraction).coerceAtLeast(8f)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = requestedTextSize
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    val cleaned = text.replace("\r", "")
    fun layout(): StaticLayout {
        val builder = StaticLayout.Builder.obtain(cleaned, 0, cleaned.length, paint,
            (stripWidth - 2 * padding).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= 29) builder.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        return builder.build()
    }
    var measured = layout()
    val availableHeight = (stripHeight - 2 * padding).coerceAtLeast(1)
    if (measured.height > availableHeight && paint.textSize > 8f) {
        var low = 8f
        var high = requestedTextSize
        paint.textSize = low
        measured = layout()
        if (measured.height <= availableHeight) {
            // Line wrapping changes in steps: search for the largest size that still fits.
            repeat(16) {
                val candidate = (low + high) / 2f
                paint.textSize = candidate
                if (layout().height <= availableHeight) low = candidate else high = candidate
            }
            paint.textSize = low
            measured = layout()
        }
    }
    require(measured.height <= availableHeight) {
        "Câu quá dài cho khung phụ đề đã chọn; hãy tăng khung, giảm cỡ chữ hoặc tách câu."
    }
    return MeasuredSubtitleFrame(stripWidth, stripHeight, padding,
        (stripHeight - measured.height) / 2f, paint, measured, cleaned)
}
