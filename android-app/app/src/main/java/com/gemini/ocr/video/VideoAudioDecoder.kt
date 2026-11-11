package com.gemini.ocr.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded PCM conversion. Output positions are anchored to media PTS, never wall time. */
internal class VideoPcm16k(private val minimumSample: Long = 0) {
    private var nextSample = -1L
    data class Block(val firstSample: Long, val samples: FloatArray)

    fun convert(bytes: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int, ptsUs: Long): Block {
        require(sampleRate in 8000..192000 && channels in 1..8) { "ASR_AUDIO_FORMAT: Định dạng âm thanh chưa hỗ trợ." }
        val data = bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) { 3 -> 1; 2 -> 2; 4, 22 -> 4; 21 -> 3
            else -> error("ASR_AUDIO_FORMAT: Cần PCM 8/16/24/32 bit hoặc float.") }
        val frames = data.remaining() / (bytesPerSample * channels)
        require(frames <= sampleRate * 2) { "ASR_AUDIO_BUFFER: Gói âm thanh vượt giới hạn hai giây." }
        val mono = FloatArray(frames) {
            var sum = 0f
            repeat(channels) {
                val sample = when (encoding) {
                    3 -> ((data.get().toInt() and 255) - 128) / 128f
                    2 -> data.short / 32768f
                    4 -> data.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                    21 -> {
                        val raw = (data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8) or (data.get().toInt() shl 16)
                        raw / 8388608f
                    }
                    else -> data.int / 2147483648f
                }
                sum += sample
            }
            sum / channels
        }
        val first = ceil(ptsUs * .016).toLong()
        val from = maxOf(first, minimumSample, nextSample)
        val until = ceil(ptsUs * .016 + frames * 16000.0 / sampleRate).toLong()
        if (until <= from || frames == 0) return Block(from, FloatArray(0))
        val output = FloatArray((until - from).toInt()) { index ->
            val input = ((from + index) / 16000.0 - ptsUs / 1_000_000.0) * sampleRate
            val left = floor(input).toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (input - floor(input)).toFloat()
            mono[left] + (mono[right] - mono[left]) * fraction
        }
        nextSample = until
        return Block(from, output)
    }
}

/** Decode only the selected audio stream. No WAV, whole-video buffer or network is created. */
internal object VideoAudioDecoder {
    suspend fun scan(context: Context, uri: Uri, startMs: Long, gate: suspend () -> Unit,
                     consume: suspend (VideoPcm16k.Block) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: error("ASR_NO_AUDIO: Video không có luồng âm thanh. Chọn OCR hoặc phụ đề có sẵn.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.seekTo(startMs.coerceAtLeast(0) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val resampler = VideoPcm16k(startMs.coerceAtLeast(0) * 16)
            var inputEnded = false
            var outputEnded = false
            var lastActivity = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                val gateStarted = SystemClock.elapsedRealtime()
                gate()
                lastActivity += SystemClock.elapsedRealtime() - gateStarted
                var changed = false
                if (!inputEnded) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        changed = true
                    }
                }
                when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = codec.outputFormat
                        rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        changed = true
                    }
                    else -> if (output >= 0) {
                        val block = try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = requireNotNull(codec.getOutputBuffer(output)).duplicate()
                                require(info.size <= 4 * 1024 * 1024) { "ASR_AUDIO_BUFFER: Bộ đệm âm thanh quá lớn." }
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                resampler.convert(buffer, rate, channels, encoding, info.presentationTimeUs)
                            } else null
                        } finally { codec.releaseOutputBuffer(output, false) }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // The native decoder buffer has been released before VAD/inference can suspend.
                        if (block != null && block.samples.isNotEmpty()) {
                            var offset = 0
                            while (offset < block.samples.size) {
                                gate()
                                val end = minOf(offset + 4096, block.samples.size)
                                consume(VideoPcm16k.Block(block.firstSample + offset, block.samples.copyOfRange(offset, end)))
                                offset = end
                            }
                        }
                        changed = true
                    }
                }
                if (changed) lastActivity = SystemClock.elapsedRealtime()
                check(SystemClock.elapsedRealtime() - lastActivity < 30_000) { "ASR_AUDIO_TIMEOUT: Bộ đọc âm thanh không tiến triển; checkpoint được giữ." }
            }
        } finally {
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }
}
