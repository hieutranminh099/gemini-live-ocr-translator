@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.VideoEncoderSettings
import java.io.IOException
import kotlin.math.roundToInt

/** Source bitrate is only a quality hint. Decoder pixels and PTS always come from the original URI. */
internal fun readSourceVideoBitrate(context: Context, uri: Uri): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    } catch (_: Exception) { 0 }
    finally { retriever.release() }
}

/** Bounded VBR budget for one re-encode, with a high-motion floor and a source-bitrate hint. */
internal fun videoQualityBitrate(width: Int, height: Int, frameRate: Float, sourceBitrate: Int): Int {
    require(width > 0 && height > 0)
    val fps = frameRate.takeIf { it.isFinite() && it > 0 } ?: 30f
    // Media3 1.5.1's unset-bitrate path budgets 0.14 bits/pixel/frame. A second AVC
    // generation benefits from a high-motion (0.28) budget, especially for fine text.
    val detailBudget = width.toDouble() * height * fps * .28
    return maxOf(detailBudget, sourceBitrate.coerceAtLeast(0).toDouble())
        .coerceIn(1_000_000.0, 100_000_000.0).roundToInt()
}

/** Keep Media3's encoder selection/bitrate capability fallback, but never silently downscale. */
internal class SourceQualityEncoderFactory(private val context: Context, private val sourceBitrate: Int) : Codec.EncoderFactory {
    private val defaultAudio = DefaultEncoderFactory.Builder(context).build()

    override fun createForAudioEncoding(format: Format): Codec = defaultAudio.createForAudioEncoding(format)
    override fun videoNeedsEncoding() = true

    override fun createForVideoEncoding(format: Format): Codec {
        val settings = VideoEncoderSettings.Builder()
            .setBitrate(videoQualityBitrate(format.width, format.height, format.frameRate, sourceBitrate)).build()
        val codec = DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).build()
            .createForVideoEncoding(format)
        val actual = codec.configurationFormat
        if (actual.width != format.width || actual.height != format.height) {
            val codecName = codec.name
            codec.release()
            val message = "Thiết bị không xuất được MP4 ở độ phân giải nguồn. Lưu video gốc cùng phụ đề vào Kho phim để giữ chất lượng."
            throw ExportException.createForCodec(IOException(message),
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ExportException.CodecInfo("$message Format: $actual", true, false, codecName))
        }
        return codec
    }
}
