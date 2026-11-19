package com.gemini.ocr.video

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** A retained ROI sample is luma only; Android/ML Kit receives a bitmap only when OCR is due. */
internal data class VideoOcrFrame(val ms:Long,val width:Int,val height:Int,val luma:ByteArray) {
    init { require(width>0 && height>0 && luma.size==width*height) }
    fun isUniform(): Boolean {
        val first = luma[0]
        for (index in 1 until luma.size) if (luma[index] != first) return false
        return true
    }

    fun bitmap():Bitmap {
        val pixels=IntArray(luma.size)
        writePixels(pixels)
        val original=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
        val target=VideoOcrInputSize.forRoi(width,height)
        if(target.first==width && target.second==height)return original
        return try { Bitmap.createScaledBitmap(original,target.first,target.second,true) }
        finally { original.recycle() }
    }

    fun writePixels(pixels: IntArray) {
        for (index in luma.indices) pixels[index] = grayscale[luma[index].toInt() and 255]
    }

    private companion object {
        // YUV limited-range luma to full-range gray.
        val grayscale = IntArray(256) { value ->
            val gray = ((value - 16) * 255 / 219).coerceIn(0, 255)
            -0x1000000 or (gray shl 16) or (gray shl 8) or gray
        }
    }
}

/** The transition samples own their luma until they leave this ring. */
internal class VideoOcrFrameRing {
    private val frames = ArrayDeque<VideoOcrFrame>(CAPACITY)

    fun evictLumaForNextSample(ocrReference: VideoOcrFrame?, tail: VideoOcrFrame?): ByteArray? {
        if (frames.size < CAPACITY) return null
        val oldest = frames.removeFirst()
        return oldest.luma.takeUnless { oldest === ocrReference || oldest === tail }
    }

    fun add(frame: VideoOcrFrame) {
        check(frames.size < CAPACITY)
        frames.addLast(frame)
    }

    operator fun iterator(): Iterator<VideoOcrFrame> = frames.iterator()

    fun clear() = frames.clear()

    companion object {
        const val CAPACITY = 6
    }
}

/** One scratch bitmap per serial video OCR scan. Release only after native recognition returns. */
internal class VideoOcrBitmapBuffer {
    private var pixels = IntArray(0)
    private var original: Bitmap? = null
    private var target = 0 to 0

    fun bitmap(frame: VideoOcrFrame): Bitmap {
        val width = frame.width
        val height = frame.height
        val scratch = original?.takeIf { it.width == width && it.height == height } ?: run {
            original?.recycle()
            target = VideoOcrInputSize.forRoi(width, height)
            if (pixels.size != frame.luma.size) pixels = IntArray(frame.luma.size)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { original = it }
        }
        frame.writePixels(pixels)
        scratch.setPixels(pixels, 0, width, 0, 0, width, height)
        return if (target.first == width && target.second == height) scratch
            else Bitmap.createScaledBitmap(scratch, target.first, target.second, true)
    }

    fun release(bitmap: Bitmap) { if (bitmap !== original) bitmap.recycle() }

    fun close() {
        original?.recycle()
        original = null
        pixels = IntArray(0)
    }
}

/** Only a tail candidate can reuse this buffer. Ring and OCR-reuse frames keep their own bytes. */
internal class VideoOcrTail {
    var frame: VideoOcrFrame? = null
        private set
    private var scratch: ByteArray? = null

    fun reusableLuma(ms: Long, retained: Boolean): ByteArray? =
        if (!retained && ms > (frame?.ms ?: -1)) scratch else null

    fun record(candidate: VideoOcrFrame, retained: Boolean) {
        if (candidate.ms <= (frame?.ms ?: -1)) return
        frame = candidate
        if (!retained) scratch = candidate.luma
    }

    fun clear() {
        frame = null
        scratch = null
    }
}

/** Enlarge small video hardsubs before confidence filtering; never grow beyond the pixel budget. */
internal object VideoOcrInputSize {
    const val MAX_PIXELS=1_500_000L
    fun forRoi(width:Int,height:Int):Pair<Int,Int> {
        require(width>0 && height>0)
        // A narrow subtitle ROI can contain two 18–24px lines. The old path never enlarged a
        // partially accepted observation, so a weaker second line could be discarded forever.
        val desired=if(height<180)2.0 else 1.0
        val scale=minOf(desired,kotlin.math.sqrt(MAX_PIXELS.toDouble()/(width.toLong()*height)),
            2560.0/width,1440.0/height)
        return (width*scale).toInt().coerceAtLeast(1) to (height*scale).toInt().coerceAtLeast(1)
    }
}

/** Pure Y-plane crop/rotation mapping, tested separately from Android's vendor decoder. */
internal object VideoLumaCrop {
    fun sample(ms:Long,yPlane:ByteBuffer,rowStride:Int,pixelStride:Int,
        cropLeft:Int,cropTop:Int,sourceWidth:Int,sourceHeight:Int,roi:List<Float>,rotation:Int,
        reusableLuma:ByteArray?=null):VideoOcrFrame =
        Sampler(roi, rotation).sample(ms, yPlane, rowStride, pixelStride, cropLeft, cropTop, sourceWidth,
            sourceHeight, reusableLuma)

    /** Cache decoder geometry, recalculating only when stride or the crop rectangle changes. */
    internal class Sampler(private val roi: List<Float>, rotation: Int) {
        private val rotation = ((rotation % 360) + 360) % 360
        private var mapping: Mapping? = null

        init {
            require(roi.size == 4 && roi.all { it.isFinite() && it in 0f..1f } &&
                roi[2] > roi[0] && roi[3] > roi[1]) { "ROI_INVALID: vùng chữ phải nằm trong khung hình video." }
            require(this.rotation in setOf(0, 90, 180, 270))
        }

        fun sample(ms: Long, plane: ByteBuffer, rowStride: Int, pixelStride: Int, cropLeft: Int, cropTop: Int,
                   sourceWidth: Int, sourceHeight: Int, reusableLuma: ByteArray? = null): VideoOcrFrame {
            require(sourceWidth > 0 && sourceHeight > 0 && rowStride > 0 && pixelStride > 0)
            require(ms >= 0 && cropLeft >= 0 && cropTop >= 0) { "FRAME_INVALID: vị trí ảnh không hợp lệ." }
            val finalOffset = (cropTop.toLong() + sourceHeight - 1) * rowStride +
                (cropLeft.toLong() + sourceWidth - 1) * pixelStride
            require(finalOffset in 0 until plane.limit().toLong()) { "FRAME_INVALID: dữ liệu ảnh YUV bị thiếu." }
            val geometry = Geometry(sourceWidth, sourceHeight, rowStride, pixelStride)
            val crop = mapping?.takeIf { it.geometry == geometry } ?: map(geometry).also { mapping = it }
            val width = crop.columns.size
            val height = crop.rows.size
            val output = reusableLuma?.takeIf { it.size == width * height } ?: ByteArray(width * height)
            val base = cropTop * rowStride + cropLeft * pixelStride
            if (crop.bulk) {
                val view = plane.duplicate()
                for (y in crop.rows.indices) {
                    view.position(base + crop.rows[y] + crop.columns[0])
                    view.get(output, y * width, width)
                }
            } else {
                for (y in crop.rows.indices) {
                    val row = base + crop.rows[y]
                    for (x in crop.columns.indices) output[y * width + x] = plane.get(row + crop.columns[x])
                }
            }
            return VideoOcrFrame(ms, width, height, output)
        }

        private data class Geometry(val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int)
        private data class Mapping(val geometry: Geometry, val columns: IntArray, val rows: IntArray, val bulk: Boolean)

        private fun map(geometry: Geometry): Mapping {
            val (width, height, rowStride, pixelStride) = geometry
            val turned = rotation % 180 != 0
            val dw = if (turned) height else width
            val dh = if (turned) width else height
            val left = (roi[0] * dw).toInt().coerceIn(0, dw - 1)
            val top = (roi[1] * dh).toInt().coerceIn(0, dh - 1)
            // Integer endpoints preserve the last row of fractional subtitle bands.
            val rw = (roi[2] * dw).toInt().coerceIn(left + 1, dw) - left
            val rh = (roi[3] * dh).toInt().coerceIn(top + 1, dh) - top
            val scale = minOf(1.0, 1280.0 / rw, 720.0 / rh)
            val columns = IntArray((rw * scale).toInt().coerceAtLeast(1)) { x ->
                val dx = (left + x / scale).toInt().coerceIn(0, dw - 1)
                when (rotation) {
                    90 -> (height - 1 - dx) * rowStride
                    180 -> (width - 1 - dx) * pixelStride
                    270 -> dx * rowStride
                    else -> dx * pixelStride
                }
            }
            val rows = IntArray((rh * scale).toInt().coerceAtLeast(1)) { y ->
                val dy = (top + y / scale).toInt().coerceIn(0, dh - 1)
                when (rotation) {
                    90 -> dy * pixelStride
                    180 -> (height - 1 - dy) * rowStride
                    270 -> (width - 1 - dy) * pixelStride
                    else -> dy * rowStride
                }
            }
            return Mapping(geometry, columns, rows, rotation == 0 && scale == 1.0 && pixelStride == 1)
        }
    }
}
