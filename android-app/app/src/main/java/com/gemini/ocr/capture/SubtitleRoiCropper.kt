package com.gemini.ocr.capture

import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Implements BlueStacks-safe ROI cropping with exact [rowStride] & [pixelStride] handling
 * and fast pixel Frame Diffing (300ms window) so static subtitle frames skip ML Kit OCR.
 */
object SubtitleRoiCropper {
    const val FRAME_DIFF_WINDOW_MS = 300L
    const val DEFAULT_PIXEL_DIFF_THRESHOLD = 3.5
    private const val SAMPLE_COLS = 16
    private const val SAMPLE_ROWS = 8

    /**
     * Copies only the subtitle [crop] rectangle from an RGBA_8888 [planeBuffer] into a tightly-packed
     * [ByteBuffer] of size `crop.width() * crop.height() * 4`, accounting for arbitrary [rowStride]
     * and [pixelStride] padding used by BlueStacks and hardware virtual displays.
     */
    fun extractRoiRgbaBuffer(
        planeBuffer: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        requestedCrop: Rect
    ): Pair<Rect, ByteBuffer>? {
        if (imageWidth <= 0 || imageHeight <= 0 || rowStride <= 0 || pixelStride < 4) return null
        val crop = Rect(
            requestedCrop.left.coerceIn(0, imageWidth - 1),
            requestedCrop.top.coerceIn(0, imageHeight - 1),
            requestedCrop.right.coerceIn(1, imageWidth),
            requestedCrop.bottom.coerceIn(1, imageHeight)
        )
        if (crop.width() <= 0 || crop.height() <= 0) return null

        val outBytes = crop.width() * crop.height() * 4
        val out = ByteBuffer.allocateDirect(outBytes)
        val src = planeBuffer.duplicate()
        val capacity = src.capacity()

        if (pixelStride == 4) {
            val rowBytes = crop.width() * 4
            for (y in crop.top until crop.bottom) {
                val offset = y * rowStride + crop.left * 4
                if (offset < 0 || offset + rowBytes > capacity) return null
                src.clear()
                src.position(offset)
                src.limit(offset + rowBytes)
                out.put(src)
            }
        } else {
            val pixelBytes = ByteArray(4)
            for (y in crop.top until crop.bottom) {
                val rowStart = y * rowStride
                for (x in crop.left until crop.right) {
                    val offset = rowStart + x * pixelStride
                    if (offset < 0 || offset + 4 > capacity) return null
                    src.clear()
                    src.position(offset)
                    src.get(pixelBytes, 0, 4)
                    out.put(pixelBytes)
                }
            }
        }
        out.flip()
        return crop to out
    }

    /**
     * Extracts a compact 128-point RGB/Luma sample vector from a tightly-packed RGBA ROI buffer.
     * Executes in < 0.05ms without allocating a Bitmap.
     */
    fun sampleRgbaBuffer(rgbaBuffer: ByteBuffer, width: Int, height: Int): IntArray {
        val samples = IntArray(SAMPLE_COLS * SAMPLE_ROWS)
        if (width <= 0 || height <= 0) return samples
        val dup = rgbaBuffer.duplicate()
        val cap = dup.limit()
        var idx = 0
        for (r in 0 until SAMPLE_ROWS) {
            val y = ((r + 0.5f) * height / SAMPLE_ROWS).toInt().coerceIn(0, height - 1)
            for (c in 0 until SAMPLE_COLS) {
                val x = ((c + 0.5f) * width / SAMPLE_COLS).toInt().coerceIn(0, width - 1)
                val offset = (y * width + x) * 4
                if (offset + 2 < cap) {
                    val red = dup.get(offset).toInt() and 0xFF
                    val green = dup.get(offset + 1).toInt() and 0xFF
                    val blue = dup.get(offset + 2).toInt() and 0xFF
                    // Fast integer luma approximation: (R*2 + G*5 + B) / 8
                    samples[idx] = (red * 2 + green * 5 + blue) shr 3
                }
                idx++
            }
        }
        return samples
    }

    /**
     * Computes mean absolute luma difference (0.0..255.0) between two sampled ROI frames.
     */
    fun meanPixelDelta(previous: IntArray?, current: IntArray): Double {
        if (previous == null || previous.size != current.size || current.isEmpty()) return 255.0
        var sum = 0L
        for (i in current.indices) {
            sum += abs(previous[i] - current[i])
        }
        return sum.toDouble() / current.size.toDouble()
    }

    class SubtitleFrameDiffer(
        windowMs: Long = FRAME_DIFF_WINDOW_MS,
        diffThreshold: Double = DEFAULT_PIXEL_DIFF_THRESHOLD
    ) : com.gemini.ocr.capture.SubtitleFrameDiffer(windowMs, diffThreshold)
}

/**
 * Skip OCR only for an unchanged whole ROI fingerprint within 300ms. Sparse samples can miss
 * newly appearing glyphs and must not decide whether the RGBA capture reaches OCR.
 * The sample-vector overload remains available for callers that explicitly compare samples.
 */
open class SubtitleFrameDiffer(
    private val windowMs: Long = SubtitleRoiCropper.FRAME_DIFF_WINDOW_MS,
    private val diffThreshold: Double = SubtitleRoiCropper.DEFAULT_PIXEL_DIFF_THRESHOLD
) {
    private var lastSample: IntArray? = null
    private var lastSampledAtMs: Long = 0L
    private var lastCropRect: Rect? = null
    private var lastRgbaCrc: Long? = null
    private var lastRgbaWidth = 0
    private var lastRgbaHeight = 0
    private var lastRgbaOcrAtMs = 0L

    @Synchronized
    fun shouldSkipOcr(rgbaBuffer: ByteBuffer, width: Int, height: Int, nowMs: Long): Boolean {
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixelCount > rgbaBuffer.limit() / 4) {
            lastRgbaCrc = null
            return false
        }
        // Use absolute ROI bytes without advancing the buffer later copied into the Bitmap.
        val pixels = rgbaBuffer.duplicate().apply { position(0); limit(pixelCount.toInt() * 4) }
        val crc = CRC32().apply { update(pixels) }.value
        val elapsed = nowMs - lastRgbaOcrAtMs
        if (lastRgbaCrc == crc && lastRgbaWidth == width && lastRgbaHeight == height &&
            elapsed in 0..windowMs) return true
        lastRgbaCrc = crc
        lastRgbaWidth = width
        lastRgbaHeight = height
        lastRgbaOcrAtMs = nowMs
        return false
    }

    @Synchronized
    fun shouldSkipOcr(currentSample: IntArray, crop: Rect, nowMs: Long): Boolean {
        val prev = lastSample
        val prevCrop = lastCropRect
        val elapsed = nowMs - lastSampledAtMs
        val sameCrop = prevCrop != null && prevCrop == crop
        if (prev != null && sameCrop && elapsed in 0..windowMs) {
            val delta = SubtitleRoiCropper.meanPixelDelta(prev, currentSample)
            if (delta < diffThreshold) {
                return true
            }
        }
        lastSample = currentSample
        lastSampledAtMs = nowMs
        lastCropRect = Rect(crop)
        return false
    }

    @Synchronized
    fun reset() {
        lastSample = null
        lastSampledAtMs = 0L
        lastCropRect = null
        lastRgbaCrc = null
        lastRgbaWidth = 0
        lastRgbaHeight = 0
        lastRgbaOcrAtMs = 0L
    }
}
