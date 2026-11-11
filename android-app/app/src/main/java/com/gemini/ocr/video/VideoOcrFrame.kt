package com.gemini.ocr.video

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** A retained ROI sample is luma only; Android/ML Kit receives a bitmap only when OCR is due. */
internal data class VideoOcrFrame(val ms:Long,val width:Int,val height:Int,val luma:ByteArray) {
    init { require(width>0 && height>0 && luma.size==width*height) }
    fun bitmap():Bitmap {
        val pixels=IntArray(luma.size){index->
            // YUV limited-range luma to full-range gray, without retaining chroma/ARGB for the ring.
            val gray=(((luma[index].toInt() and 255)-16)*255/219).coerceIn(0,255)
            -0x1000000 or (gray shl 16) or (gray shl 8) or gray
        }
        val original=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
        val target=VideoOcrInputSize.forRoi(width,height)
        if(target.first==width && target.second==height)return original
        return try { Bitmap.createScaledBitmap(original,target.first,target.second,true) }
        finally { original.recycle() }
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
        reusableLuma:ByteArray?=null):VideoOcrFrame {
        require(sourceWidth>0 && sourceHeight>0 && rowStride>0 && pixelStride>0)
        require(ms>=0 && cropLeft>=0 && cropTop>=0) { "FRAME_INVALID: vị trí ảnh không hợp lệ." }
        require(roi.size==4 && roi.all{it.isFinite() && it in 0f..1f} && roi[2]>roi[0] && roi[3]>roi[1]) {
            "ROI_INVALID: vùng chữ phải nằm trong khung hình video."
        }
        val finalOffset=(cropTop.toLong()+sourceHeight-1)*rowStride+(cropLeft.toLong()+sourceWidth-1)*pixelStride
        require(finalOffset<yPlane.limit() && finalOffset>=0) { "FRAME_INVALID: dữ liệu ảnh YUV bị thiếu." }
        val rotate=((rotation%360)+360)%360
        require(rotate in setOf(0,90,180,270))
        val dw=if(rotate%180==0)sourceWidth else sourceHeight
        val dh=if(rotate%180==0)sourceHeight else sourceWidth
        val left=(roi[0]*dw).toInt().coerceIn(0,dw-1)
        val top=(roi[1]*dh).toInt().coerceIn(0,dh-1)
        // Derive extent from integer endpoints, avoiding a lost final row when normalized
        // subtraction yields 107.99999 instead of 108 (e.g. the real .80.. .95 subtitle band).
        val right=(roi[2]*dw).toInt().coerceIn(left+1,dw)
        val bottom=(roi[3]*dh).toInt().coerceIn(top+1,dh)
        val rw=right-left;val rh=bottom-top
        val scale=minOf(1.0,1280.0/rw,720.0/rh)
        val w=(rw*scale).toInt().coerceAtLeast(1);val h=(rh*scale).toInt().coerceAtLeast(1)
        // A decoder tail may supply its exclusive scratch. Other callers still own a new sample.
        val out=reusableLuma?.takeIf{it.size==w*h} ?: ByteArray(w*h)
        val plane=yPlane.duplicate()
        if(rotate==0 && scale==1.0 && pixelStride==1){
            // Ordinary H.264: one bulk read per cropped row, replacing three absolute plane reads
            // and RGB conversion per pixel on every retained 50ms sample.
            for(y in 0 until h){plane.position((cropTop+top+y)*rowStride+cropLeft+left);plane.get(out,y*w,w)}
        }else{
            for(y in 0 until h)for(x in 0 until w){
                val dx=(left+x/scale).toInt().coerceIn(0,dw-1)
                val dy=(top+y/scale).toInt().coerceIn(0,dh-1)
                val sx:Int;val sy:Int
                when(rotate){90->{sx=dy;sy=sourceHeight-1-dx};180->{sx=sourceWidth-1-dx;sy=sourceHeight-1-dy}
                    270->{sx=sourceWidth-1-dy;sy=dx};else->{sx=dx;sy=dy}}
                out[y*w+x]=plane.get((cropTop+sy)*rowStride+(cropLeft+sx)*pixelStride)
            }
        }
        return VideoOcrFrame(ms,w,h,out)
    }
}
