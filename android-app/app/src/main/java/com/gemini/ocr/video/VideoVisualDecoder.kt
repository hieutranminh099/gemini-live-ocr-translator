package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Explicit Gemini OCR after the user's consent to send their selected subtitle ROI.
 * Retains a bounded batch of compressed ROI images; original video/audio is never sent here.
 * Boundaries follow a 250ms source-time grid, before translation or export. */
object VideoVisualDecoder {
    suspend fun scan(context:Context,project:VideoProject,gate:suspend()->Unit,
        beforeDispatch:suspend()->Unit,
        checkpoint:suspend(Long,List<VideoCue>,JSONObject)->Unit):List<VideoCue> {
        val roi=project.roi
        require(project.sourceMode==VideoSourceMode.OCR){"Chỉ đọc ảnh khi bạn chọn OCR phụ đề trong hình."}
        require(roi.size==4 && roi.all{it.isFinite() && it in 0f..1f} &&
            roi[2]-roi[0]>=.02f && roi[3]-roi[1]>=.02f){"Vùng chữ quá nhỏ hoặc không hợp lệ."}
        require(project.translationMode!=VideoTranslationMode.OFFLINE){"Chế độ offline không gửi ảnh phụ đề."}
        val interval=project.visualSampleMs.also{require(it in setOf(250L,500L))}
        val extractor=MediaExtractor();var codec:MediaCodec?=null;var started=false
        val accumulator=VideoCueAccumulator(project.cues,project.carry)
        val client=VideoVisualOcrClient(context)
        // Short first-use clips keep small payloads; longer clips amortize per-request quota.
        val batchLimit=if(project.durationMs<60_000L)16 else VideoVisualOcrClient.MAX_FRAMES
        val batch=ArrayList<VisualOcrFrame>(batchLimit)
        var batchBytes=0L
        val previous=project.carry.optJSONObject("metrics")?:JSONObject()
        val startedAt=SystemClock.elapsedRealtime()
        var decodedFrames=previous.optLong("decoded_frames")
        var sampledFrames=previous.optLong("sampled_frames")
        var completedFrames=previous.optLong("completed_frames")
        var cloudCalls=previous.optLong("visual_cloud_calls")
        var cropMs=previous.optLong("crop_wall_ms")
        var cloudMs=previous.optLong("visual_ocr_wall_ms")
        var gateMs=previous.optLong("gate_wall_ms")
        fun metrics(sourceMs:Long):JSONObject=JSONObject().put("schema",1).put("engine","gemini-vision")
            .put("source_video_ms",sourceMs).put("sample_interval_ms",interval)
            .put("decoded_frames",decodedFrames).put("sampled_frames",sampledFrames)
            .put("completed_frames",completedFrames).put("visual_cloud_calls",cloudCalls)
            .put("crop_wall_ms",cropMs).put("visual_ocr_wall_ms",cloudMs).put("gate_wall_ms",gateMs)
            .put("total_wall_ms",previous.optLong("total_wall_ms")+SystemClock.elapsedRealtime()-startedAt)
            .put("retained_jpegs_max",batchLimit)
            .put("retained_jpeg_bytes_max",VideoVisualOcrClient.MAX_JPEG_BYTES)
        fun carry(sourceMs:Long)=accumulator.snapshot().put("visualScan",true).put("metrics",metrics(sourceMs))
        suspend fun waitForGate(){
            val at=SystemClock.elapsedRealtime();gate();gateMs+=SystemClock.elapsedRealtime()-at
            currentCoroutineContext().ensureActive()
        }
        suspend fun flush(){
            if(batch.isEmpty())return
            waitForGate()
            val frames=batch.toList()
            val at=SystemClock.elapsedRealtime()
            try {client.recognize(frames,onValidated={validated,texts->
                currentCoroutineContext().ensureActive()
                require(texts.size==validated.size){"Số ảnh và câu OCR không khớp; checkpoint trước vẫn được giữ."}
                validated.forEachIndexed{index,frame->
                    // Normalize punctuation only for equality, retaining the first actual source.
                    // Every Han character, number, negation and confirmed disappearance remains distinct.
                    val prior=accumulator.snapshot().optString("source")
                    val actual=texts[index].trim()
                    val stable=if(actual.isNotBlank() && prior.isNotBlank() &&
                        VideoOcrStabilizer.identity(actual)==VideoOcrStabilizer.identity(prior))prior else actual
                    accumulator.add(frame.ms,stable)
                }
                completedFrames+=validated.size
                val next=(validated.last().ms+1).coerceAtMost(project.durationMs)
                checkpoint(next,accumulator.cues.toList(),carry(next))
            }){waitForGate();beforeDispatch();cloudCalls++}}
            finally {cloudMs+=SystemClock.elapsedRealtime()-at}
            currentCoroutineContext().ensureActive()
            batch.clear();batchBytes=0L
        }
        try {
            val uri=Uri.parse(project.uri);val metadata=VideoDecoder.metadata(context,uri)
            extractor.setDataSource(context,uri,null)
            val track=(0 until extractor.trackCount).firstOrNull{
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            }?:error("Video không có track hình ảnh.")
            extractor.selectTrack(track)
            if(project.scanMs>0)extractor.seekTo(project.scanMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format=extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec=MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format,null,null,0);codec.start();started=true
            val info=MediaCodec.BufferInfo();var inputEnded=false;var outputEnded=false
            var nextSample=VideoVisualSampling.firstGridAtOrAfter(project.scanMs,interval)
            var lastOutput=SystemClock.elapsedRealtime()
            while(!outputEnded){
                currentCoroutineContext().ensureActive()
                val gateAt=SystemClock.elapsedRealtime();waitForGate();lastOutput+=SystemClock.elapsedRealtime()-gateAt
                if(!inputEnded){val inputIndex=codec.dequeueInputBuffer(10000)
                    if(inputIndex>=0){val buffer=checkNotNull(codec.getInputBuffer(inputIndex))
                        val size=extractor.readSampleData(buffer,0)
                        if(size<0){codec.queueInputBuffer(inputIndex,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else{codec.queueInputBuffer(inputIndex,0,size,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                var captured:VisualOcrFrame?=null
                val outputIndex=codec.dequeueOutputBuffer(info,10000)
                if(outputIndex>=0){lastOutput=SystemClock.elapsedRealtime();val ms=info.presentationTimeUs/1000
                    try {
                        if(info.size>0)decodedFrames++
                        if(info.size>0 && ms>=nextSample && ms>=project.scanMs && ms<project.durationMs){
                            val at=SystemClock.elapsedRealtime()
                            val image=codec.getOutputImage(outputIndex)
                                ?:error("Bộ giải mã không cung cấp ảnh YUV; chọn video H.264 hoặc phụ đề văn bản.")
                            val retained=try{val crop=image.cropRect;val plane=image.planes[0]
                                VideoLumaCrop.sample(ms,plane.buffer,plane.rowStride,plane.pixelStride,
                                    crop.left,crop.top,crop.width(),crop.height(),roi,metadata.rotation)
                            }finally{image.close()}
                            val bitmap=retained.bitmap()
                            val jpeg=try {ByteArrayOutputStream().use{out->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out)){"Không thể chuẩn bị ảnh phụ đề."}
                                out.toByteArray()
                            }}finally{bitmap.recycle()}
                            cropMs+=SystemClock.elapsedRealtime()-at
                            require(jpeg.size<=VideoVisualOcrClient.MAX_JPEG_BYTES){"Vùng ảnh phụ đề quá lớn; hãy khoanh gọn hơn."}
                            captured=VisualOcrFrame(ms,jpeg);sampledFrames++
                            nextSample=VideoVisualSampling.firstGridStrictlyAfter(ms,interval)
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    }finally{codec.releaseOutputBuffer(outputIndex,false)}
                }else check(SystemClock.elapsedRealtime()-lastOutput<30000){"Bộ giải mã không tiến triển trong 30 giây."}
                // Release the vendor output buffer before any gate or HTTP request.
                captured?.let{frame->
                    if(batch.isNotEmpty() && batchBytes+frame.jpeg.size>VideoVisualOcrClient.MAX_JPEG_BYTES){
                        flush();lastOutput=SystemClock.elapsedRealtime()
                    }
                    batch+=frame;batchBytes+=frame.jpeg.size
                    if(batch.size==batchLimit){flush();lastOutput=SystemClock.elapsedRealtime()}
                }
            }
            flush()
            val complete=VideoTimeline.validate(accumulator.finish(project.durationMs),project.durationMs)
            checkpoint(project.durationMs,complete,carry(project.durationMs))
            return complete
        }finally{
            batch.clear()
            if(started)runCatching{codec?.stop()};codec?.release();extractor.release()
        }
    }
}
