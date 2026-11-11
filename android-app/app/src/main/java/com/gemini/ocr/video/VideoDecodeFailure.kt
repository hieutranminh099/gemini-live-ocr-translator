package com.gemini.ocr.video

import android.media.MediaCodec
import android.os.SystemClock
import com.gemini.ocr.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Local decoder failure only. Never copy a URI, subtitle or native exception message. */
internal class VideoDecodeException(
    val code:String,val operation:String,val sourceMs:Long,val retryable:Boolean=true,
    val nativeErrorCode:Int?=null,val diagnostic:String="",val codecName:String=""
):IOException(buildString {
    append(code);append(": ")
    append(when(code){
        "FRAME_DECODE_TIMEOUT" -> "Bộ giải mã không trả khung hình mới trong 30 giây."
        "FRAME_DECODE_INCOMPLETE" -> "Bộ giải mã chưa đọc đến cuối video."
        "FRAME_NULL" -> "Bộ giải mã chưa cung cấp dữ liệu khung hình."
        else -> "Bộ giải mã video bị gián đoạn."
    })
    append(" Đã giữ tiến độ; có thể tiếp tục từ phần đã lưu.")
    append(" Thao tác=");append(safeDecodeField(operation));append("; mốc=");append(sourceMs.coerceAtLeast(0));append("ms")
    if(codecName.isNotBlank()){append("; codec=");append(safeDecodeField(codecName))}
    if(nativeErrorCode!=null){append("; mã=");append(nativeErrorCode)}
    if(diagnostic.isNotBlank()){append("; chẩn đoán=");append(safeDecodeField(diagnostic))}
})

private fun safeDecodeField(value:String)=value.takeIf{it.length in 1..120 && it.all{c->c.isLetterOrDigit() || c in "_.-"}}?:"unknown"

/** Only native codec calls belong inside this boundary, never OCR, gate or persistence. */
internal object VideoCodecBoundary {
    @Volatile internal var beforeOperationForTest:((String,Long)->Unit)?=null
    fun <T> call(operation:String,sourceMs:Long,codecName:String="",block:()->T):T {
        try {
            if(BuildConfig.DEBUG)beforeOperationForTest?.invoke(operation,sourceMs)
            return block()
        }catch(cancel:CancellationException){throw cancel}
        catch(error:MediaCodec.CodecException){
            throw VideoDecodeException("FRAME_CODEC_ERROR",operation,sourceMs,
                error.isRecoverable || error.isTransient || error.errorCode==MediaCodec.CodecException.ERROR_RECLAIMED,
                error.errorCode,runCatching{error.diagnosticInfo}.getOrDefault(""),codecName)
        }catch(_:IllegalStateException){
            throw VideoDecodeException("FRAME_CODEC_STATE",operation,sourceMs,codecName=codecName)
        }
    }
    /** A secondary release error must not replace a frame error or cancellation. */
    fun <T> withRelease(read:()->T,release:()->Unit):T {
        var failed=false
        try{return read()}catch(error:Throwable){failed=true;throw error}
        finally{if(failed)runCatching{release()}else release()}
    }
    /** Always attempt every release, preserving both successful output and the primary error. */
    fun releaseAll(vararg releases:()->Unit):Int {
        var failures=0
        for(release in releases)try{release()}catch(_:Exception){failures++}
        return failures
    }
}

/** Counts decoder idle time only; waiting for Live and processing an owned ROI are excluded. */
internal class VideoDecodeWatchdog(private val now:()->Long={SystemClock.elapsedRealtime()}) {
    private var lastProgress=now()
    fun progress(){lastProgress=now()}
    fun excludeWait(elapsedMs:Long){lastProgress+=elapsedMs.coerceAtLeast(0)}
    fun check(sourceMs:Long){
        if(now()-lastProgress>=30_000)throw VideoDecodeException("FRAME_DECODE_TIMEOUT","dequeue_output",sourceMs)
    }
}
