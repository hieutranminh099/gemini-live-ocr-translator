package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.ocr.OcrRecoveryPolicy
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.ocr.OcrVideoDraft
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/** Sequential decoder. Retained subtitle samples contain bounded luma, not full RGB frames. */
object VideoDecoder {
    private const val INPUT_BURST_LIMIT = 8
    private const val OUTPUT_TIMEOUT_US = 10_000L

    /** Disabled outside debug builds; native verification observes its own public fixture only. */
    internal var draftObserverForTest:((Long,OcrVideoDraft)->Unit)?=null
    fun metadata(context:Context,uri:Uri):VideoMetadata {
        require(uri.scheme in setOf("content","file")) { "Chọn video cục bộ." }
        val r=MediaMetadataRetriever()
        var retrieverFailure:Exception?=null
        var duration=0L;var width=0;var height=0;var rotation=0;var audio=false
        try {
            r.setDataSource(context,uri)
            fun value(key:Int)=r.extractMetadata(key)?.toLongOrNull()?:0
            rotation=value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            width=value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            height=value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            duration=value(MediaMetadataRetriever.METADATA_KEY_DURATION)
            audio=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)=="yes"
        } catch(e:SecurityException) {
            throw IllegalArgumentException("Không có quyền đọc video. Chọn lại tệp bằng trình chọn của Android.",e)
        } catch(e:Exception) { retrieverFailure=e }
        finally { runCatching { r.release() } }
        // Some Android providers/containers expose tracks to the decoder but omit retriever
        // metadata. Missing retriever dimensions alone do not mean that the file has no video.
        if(width<=0 || height<=0 || duration<=0) {
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(context,uri,null)
                var video:MediaFormat?=null
                for(index in 0 until extractor.trackCount) {
                    val format=extractor.getTrackFormat(index)
                    val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if(mime.startsWith("video/") && video==null)video=format
                    if(mime.startsWith("audio/"))audio=true
                }
                val format=video?:throw IllegalArgumentException("Tệp không có track hình ảnh video; có thể là tệp âm thanh hoặc tệp hỏng.")
                fun int(key:String)=if(format.containsKey(key))format.getInteger(key) else 0
                if(width<=0 || height<=0) {
                    width=int(MediaFormat.KEY_WIDTH);height=int(MediaFormat.KEY_HEIGHT)
                }
                if(rotation==0)rotation=int(MediaFormat.KEY_ROTATION)
                if(duration<=0 && format.containsKey(MediaFormat.KEY_DURATION))duration=format.getLong(MediaFormat.KEY_DURATION)/1000
                require(width>0 && height>0){"Track video thiếu kích thước hình ảnh. Tệp có thể bị hỏng hoặc định dạng chưa được Android hỗ trợ."}
            } catch(e:SecurityException) {
                throw IllegalArgumentException("Không có quyền đọc video. Chọn lại tệp bằng trình chọn của Android.",e)
            } catch(e:IllegalArgumentException) {
                if(e.message?.startsWith("Tệp không có track")==true || e.message?.startsWith("Track video thiếu")==true)throw e
                throw IllegalArgumentException("Android không mở được dữ liệu video (${e.javaClass.simpleName}). Chọn bản cục bộ đã tải xong hoặc kiểm tra định dạng tệp.",e)
            } catch(e:Exception) {
                throw IllegalArgumentException("Android không đọc được video (${e.javaClass.simpleName}). Chọn bản cục bộ đã tải xong hoặc kiểm tra định dạng tệp.",retrieverFailure?:e)
            } finally { runCatching { extractor.release() } }
        }
        return VideoMetadata(duration,if(rotation%180==0)width else height,
            if(rotation%180==0)height else width,rotation,audio)
    }
    fun preview(context:Context,uri:Uri,timeMs:Long):Bitmap? {
        val m=metadata(context,uri);val scale=minOf(1.0,960.0/m.width,540.0/m.height)
        val r=MediaMetadataRetriever()
        try { r.setDataSource(context,uri)
            val w=(m.width*scale).roundToInt().coerceAtLeast(1);val h=(m.height*scale).roundToInt().coerceAtLeast(1)
            if(Build.VERSION.SDK_INT>=27)return r.getScaledFrameAtTime(timeMs.coerceAtLeast(0)*1000,MediaMetadataRetriever.OPTION_CLOSEST,w,h)
            val original=r.getFrameAtTime(timeMs.coerceAtLeast(0)*1000,MediaMetadataRetriever.OPTION_CLOSEST)?:return null
            if(original.width==w && original.height==h)return original
            return try{Bitmap.createScaledBitmap(original,w,h,true)}finally{original.recycle()}
        } finally { r.release() }
    }
    suspend fun scan(context:Context,project:VideoProject,gate:suspend()->Unit,
        checkpoint:suspend(Long,List<VideoCue>,JSONObject)->Unit):List<VideoCue> = scanInternal(context,project,gate,checkpoint,false)
    suspend fun scanDrafts(context:Context,project:VideoProject,gate:suspend()->Unit,
        checkpoint:suspend(Long,List<VideoCue>,JSONObject)->Unit):List<VideoCue> = scanInternal(context,project,gate,checkpoint,true)
    private suspend fun scanInternal(context:Context,project:VideoProject,gate:suspend()->Unit,
        checkpoint:suspend(Long,List<VideoCue>,JSONObject)->Unit,videoDrafts:Boolean):List<VideoCue> {
        val roi=project.roi
        require(roi.size==4 && roi.all{it.isFinite() && it in 0f..1f} && roi[2]-roi[0]>=.02f && roi[3]-roi[1]>=.02f){"Vùng chữ quá nhỏ hoặc không hợp lệ."}
        val extractor=VideoCodecBoundary.call("create_extractor",project.scanMs){MediaExtractor()};var codec:MediaCodec?=null;var started=false
        val ocr=OcrProcessor("zh");val ring=VideoOcrFrameRing()
        val tail=if(videoDrafts) VideoOcrTail() else null
        val bitmapBuffer=if(videoDrafts) VideoOcrBitmapBuffer() else null
        val draftTimeline = if (videoDrafts) VideoDraftTimeline(project.cues, project.carry) else null
        val acc: VideoOcrTimeline = draftTimeline ?: VideoOcrStabilizer(project.cues, project.carry)
        val reuse = VideoOcrReuse<VideoOcrReading>()
        val quality=VideoOcrDraftQuality(project.carry.optJSONArray("draftQuality"))
        var decoratedCues=acc.cues.size
        fun decorateNew(){while(decoratedCues<acc.cues.size){acc.cues[decoratedCues]=quality.close(acc.cues[decoratedCues]);decoratedCues++}}
        var reusedObservations=0L
        var lastEmitted=project.carry.optLong("scanLast",project.carry.optLong("last",-1))
        fun accept(frame: VideoOcrFrame, reading: VideoOcrReading?, fromNative: Boolean) {
            if (frame.ms <= lastEmitted) return
            if (draftTimeline != null) {
                if (reading != null && fromNative) quality.record(reading.displayText, reading.confidence,
                    reading.flags, draftTimeline.representativeSource, frame.ms)
                draftTimeline.addReading(frame.ms, reading)
                decorateNew()
            } else acc.add(frame.ms, reading?.raw)
            lastEmitted = frame.ms
        }
        var decoderName=""
        fun <T> codecStep(operation:String,block:()->T):T=VideoCodecBoundary.call(operation,
            lastEmitted.coerceAtLeast(project.scanMs),decoderName,block)
        val priorMetrics=project.carry.optJSONObject("metrics")?:JSONObject()
        val startedAt=SystemClock.elapsedRealtime()
        var decodedFrames=priorMetrics.optLong("decoded_frames")
        var cropConversions=priorMetrics.optLong("crop_conversions")
        var lumaBufferReuses=priorMetrics.optLong("luma_buffer_reuses")
        var recognitionRequests=priorMetrics.optLong("recognition_requests")
        var refinementRequests=priorMetrics.optLong("refinement_requests")
        var nativeCalls=priorMetrics.optLong("ocr_native_calls")
        var uniformRoiSkips=priorMetrics.optLong("uniform_roi_skips")
        var upscaledRequests=priorMetrics.optLong("upscaled_requests")
        var uncertainObservations=priorMetrics.optLong("uncertain_observations")
        var multilineObservations=priorMetrics.optLong("multiline_observations")
        var inputSamples=priorMetrics.optLong("input_samples")
        var maxInputBurst=priorMetrics.optInt("max_input_burst")
        var outputBuffers=priorMetrics.optLong("output_buffers")
        var readyOutputBuffers=priorMetrics.optLong("ready_output_buffers")
        var zeroSizeOutputs=priorMetrics.optLong("zero_size_outputs")
        var lastInputMs=priorMetrics.optLong("last_input_ms",-1)
        var maxOutputMs=priorMetrics.optLong("max_output_ms",-1)
        var trackDurationMs=project.durationMs
        var cropMs=priorMetrics.optLong("crop_wall_ms")
        var bitmapMs=priorMetrics.optLong("bitmap_wall_ms")
        var ocrMs=priorMetrics.optLong("ocr_wall_ms")
        var gateMs=priorMetrics.optLong("gate_wall_ms")
        var codecInputNs=priorMetrics.optLong("codec_input_wall_ms")*1_000_000
        var codecOutputDequeueNs=priorMetrics.optLong("codec_output_dequeue_wall_ms")*1_000_000
        var codecFrameNs=priorMetrics.optLong("codec_frame_with_crop_wall_ms")*1_000_000
        var scanCheckpointNs=priorMetrics.optLong("scan_checkpoint_wall_ms")*1_000_000
        fun metrics(sourceMs:Long):JSONObject {
            val total=priorMetrics.optLong("total_wall_ms")+SystemClock.elapsedRealtime()-startedAt
            return JSONObject().put("schema",1).put("source_video_ms",sourceMs)
                .put("decoded_frames",decodedFrames).put("crop_conversions",cropConversions)
                .put("luma_buffer_reuses",lumaBufferReuses)
                .put("input_samples",inputSamples).put("max_input_burst",maxInputBurst)
                .put("output_buffers",outputBuffers).put("ready_output_buffers",readyOutputBuffers)
                .put("zero_size_outputs",zeroSizeOutputs)
                .put("last_input_ms",lastInputMs).put("max_output_ms",maxOutputMs).put("video_track_duration_ms",trackDurationMs)
                .put("video_raw_drafts",videoDrafts)
                .put("recognition_requests",recognitionRequests).put("refinement_requests",refinementRequests)
                .put("ocr_native_calls",nativeCalls).put("upscaled_requests",upscaledRequests)
                .put("uniform_roi_skips",uniformRoiSkips)
                .put("uncertain_observations",uncertainObservations).put("multiline_observations",multilineObservations)
                .put("exact_pixel_reused_observations",priorMetrics.optLong("exact_pixel_reused_observations")+reusedObservations)
                .put("consolidated_ocr_variants", draftTimeline?.consolidatedVariants ?: 0)
                .put("crop_wall_ms",cropMs).put("bitmap_wall_ms",bitmapMs).put("ocr_wall_ms",ocrMs)
                .put("gate_wall_ms",gateMs).put("total_wall_ms",total)
                .put("codec_input_wall_ms",codecInputNs/1_000_000)
                .put("codec_output_dequeue_wall_ms",codecOutputDequeueNs/1_000_000)
                .put("codec_frame_with_crop_wall_ms",codecFrameNs/1_000_000)
                .put("scan_checkpoint_wall_ms",scanCheckpointNs/1_000_000)
                .put("decode_and_other_wall_ms",(total-cropMs-bitmapMs-ocrMs-gateMs).coerceAtLeast(0))
                .put("retained_samples_max",VideoOcrFrameRing.CAPACITY + if(videoDrafts)1 else 0)
                .put("retained_format","ROI luma bytes; bitmap only for OCR")
                .put("stable_sample_interval_ms",200).put("confirmation_sample_interval_ms",65)
        }
        fun carry(sourceMs:Long)=acc.snapshot().also{if(videoDrafts)it.put("draftQuality",quality.snapshot(it.optString("source")))}.put("metrics",metrics(sourceMs))
        suspend fun saveCheckpoint(sourceMs: Long, cues: List<VideoCue>) {
            val checkpointAt = System.nanoTime()
            checkpoint(sourceMs, cues, carry(sourceMs))
            scanCheckpointNs += System.nanoTime() - checkpointAt
        }
        suspend fun recognize(frame:VideoOcrFrame,refinement:Boolean=false,forceNative:Boolean=false):VideoOcrReading? {
            currentCoroutineContext().ensureActive()
            recognitionRequests++;if(refinement)refinementRequests++
            // A completely uniform luma image has no glyphs. Keep its timestamp as a blank
            // observation so it can close an occurrence and confirm a subtitle disappeared.
            if (videoDrafts && !forceNative && frame.isUniform()) {
                uniformRoiSkips++
                return VideoOcrReading("")
            }
            val prepareAt=SystemClock.elapsedRealtime()
            val bitmap=bitmapBuffer?.bitmap(frame) ?: frame.bitmap()
            bitmapMs+=SystemClock.elapsedRealtime()-prepareAt
            if(bitmap.width>frame.width || bitmap.height>frame.height)upscaledRequests++
            val ocrAt=SystemClock.elapsedRealtime()
            if(videoDrafts){
                val draft=try{withContext(NonCancellable){ocr.processVideoDraft(bitmap)}}
                    finally{ocrMs+=SystemClock.elapsedRealtime()-ocrAt;checkNotNull(bitmapBuffer).release(bitmap)}
                currentCoroutineContext().ensureActive()
                nativeCalls+=draft.nativePasses
                if(draft.needsReview)uncertainObservations++
                if(draft.lines.size>1)multilineObservations++
                if(BuildConfig.DEBUG)draftObserverForTest?.invoke(frame.ms,draft)
                if (draft.text.isEmpty() && draft.needsReview) return null
                val confidence = draft.minConfidence.takeUnless { draft.lines.any { line -> line.confidence == null } }
                return VideoOcrReading(draft.text, draft.lines.singleOrNull()?.bounds, confidence, draft.flags)
            }
            val observation=try {withContext(NonCancellable){ocr.processObservation(bitmap,
                retainSourceFragments=true,fastDownscale=false,ocrPreCorrection=false,enforceSubtitlePolicy=true)}}
            finally {ocrMs+=SystemClock.elapsedRealtime()-ocrAt;bitmap.recycle()}
            nativeCalls+=observation.nativePasses
            if(observation.uncertain)uncertainObservations++
            if(observation.blocks.count{OcrRecoveryPolicy.hasHan(it.text)}>1)multilineObservations++
            currentCoroutineContext().ensureActive()
            if(observation.uncertain)return null
            return VideoOcrReading(observation.blocks.sortedWith(compareBy({it.rect.top},{it.rect.left}))
                .filter{OcrRecoveryPolicy.hasHan(it.text)}.joinToString("\n"){it.text})
        }
        try {
            val uri=Uri.parse(project.uri);val metadata=metadata(context,uri)
            val cropper=VideoLumaCrop.Sampler(roi,metadata.rotation)
            codecStep("open_source"){extractor.setDataSource(context,uri,null)}
            val track=(0 until codecStep("read_track"){extractor.trackCount}).firstOrNull{codecStep("read_track"){extractor.getTrackFormat(it)}.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
                ?:error("Video không có track hình ảnh.")
            codecStep("select_track"){extractor.selectTrack(track)}
            if(project.scanMs>0)codecStep("seek"){extractor.seekTo(project.scanMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)}
            val format=codecStep("read_track"){extractor.getTrackFormat(track)}
            if(format.containsKey(MediaFormat.KEY_DURATION))trackDurationMs=(format.getLong(MediaFormat.KEY_DURATION)/1000).takeIf{it>0}?:project.durationMs
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder=codecStep("create_codec"){MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))}
            codec=decoder;decoderName=runCatching{decoder.name}.getOrDefault("")
            codecStep("configure"){decoder.configure(format,null,null,0)}
            codecStep("start"){decoder.start()};started=true
            val info=MediaCodec.BufferInfo();var inputEnded=false;var outputEnded=false
            var probeReadyOutput = false
            fun dequeueOutput(timeoutUs: Long): Int {
                val at = System.nanoTime()
                val index = codecStep("dequeue_output") { decoder.dequeueOutputBuffer(info, timeoutUs) }
                codecOutputDequeueNs += System.nanoTime() - at
                return index
            }
            fun queueAvailableInputs() {
                val inputAt = System.nanoTime()
                var queued = 0
                while (!inputEnded && queued < INPUT_BURST_LIMIT) {
                    val inputIndex = codecStep("dequeue_input") { decoder.dequeueInputBuffer(0) }
                    if (inputIndex < 0) break
                    val buffer = codecStep("read_input") { decoder.getInputBuffer(inputIndex) }
                        ?: throw VideoDecodeException("FRAME_NULL", "read_input", lastEmitted)
                    val size = codecStep("read_input") { extractor.readSampleData(buffer, 0) }
                    if (size < 0) {
                        codecStep("queue_input") { decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                        inputEnded = true
                    } else {
                        val time = codecStep("read_input") { extractor.sampleTime }
                        lastInputMs = time / 1000
                        inputSamples++
                        queued++
                        codecStep("queue_input") { decoder.queueInputBuffer(inputIndex, 0, size, time, 0) }
                        codecStep("advance_input") { extractor.advance() }
                    }
                }
                maxInputBurst = maxOf(maxInputBurst, queued)
                codecInputNs += System.nanoTime() - inputAt
            }
            var lastSample=project.scanMs-50;var nextOcr=project.scanMs;var lastCheckpoint=project.scanMs
            var lastText = project.carry.optString("source").let { raw ->
                if (videoDrafts) VideoOcrReading(raw).normalizedText else VideoOcrStabilizer.identity(raw)
            }
            val watchdog=VideoDecodeWatchdog()
            while(!outputEnded){
                currentCoroutineContext().ensureActive();val gateAt=SystemClock.elapsedRealtime();gate()
                val waited=SystemClock.elapsedRealtime()-gateAt;watchdog.excludeWait(waited);gateMs+=waited
                // Consume decoded buffers already waiting, then refill without waiting on input.
                // Between frames the same gate still checks Live priority and cancellation.
                var outputIndex = if (probeReadyOutput && !inputEnded) dequeueOutput(0) else MediaCodec.INFO_TRY_AGAIN_LATER
                if (outputIndex >= 0) readyOutputBuffers++
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!inputEnded) queueAvailableInputs()
                    outputIndex = dequeueOutput(OUTPUT_TIMEOUT_US)
                }
                val index = outputIndex
                probeReadyOutput = index >= 0
                var captured:VideoOcrFrame?=null
                if(index>=0){watchdog.progress();val ms=info.presentationTimeUs/1000
                    val frameAt = System.nanoTime()
                    VideoCodecBoundary.withRelease(read={
                        outputBuffers++;if(info.size==0)zeroSizeOutputs++
                        if(info.size>0)maxOutputMs=maxOf(maxOutputMs,ms)
                        if(info.size>0)decodedFrames++
                        val retain=ms-lastSample>=50
                        if(info.size>0 && ms>=project.scanMs && ms<project.durationMs && (videoDrafts || retain)){
                            val decoded=codecStep("read_frame"){decoder.getOutputImage(index)}
                                ?:throw VideoDecodeException("FRAME_NULL","read_frame",lastEmitted)
                            val cropAt=SystemClock.elapsedRealtime()
                            val reusableLuma = if (retain) {
                                ring.evictLumaForNextSample(reuse.referenceFrame, tail?.frame)
                            } else {
                                tail?.reusableLuma(ms, false)
                            }
                            val frame=try {
                                val crop=decoded.cropRect;val plane=decoded.planes[0]
                                cropper.sample(ms,plane.buffer,plane.rowStride,plane.pixelStride,
                                    crop.left,crop.top,crop.width(),crop.height(),reusableLuma)
                            }finally{runCatching{decoded.close()};cropMs+=SystemClock.elapsedRealtime()-cropAt}
                            cropConversions++
                            if(frame.luma===reusableLuma)lumaBufferReuses++
                            // One extra owned ROI records the actual final decoded image, even
                            // when it falls between the normal 50ms retained samples.
                            tail?.record(frame,retain)
                            if(retain){captured=frame;lastSample=ms}
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    },release={codecStep("release_output"){decoder.releaseOutputBuffer(index,false)}})
                    codecFrameNs += System.nanoTime() - frameAt
                } else watchdog.check(lastEmitted.coerceAtLeast(project.scanMs))
                // The ROI owns its bytes. Never hold a vendor decoder output across native OCR,
                // a Live gate or an AtomicFile write; these operations may take seconds.
                captured?.let { frame ->
                    val ms=frame.ms
                    ring.add(frame)
                    if(ms>=nextOcr){
                        val reused=reuse.lookup(frame,acc.needsConfirmation)
                        val reading = if (reused != null) { reusedObservations++; reused } else recognize(frame)
                        reuse.record(frame, reading)
                        val key = reading?.let { if (videoDrafts) it.normalizedText else VideoOcrStabilizer.identity(it.raw) }
                        if(key!=null && key!=lastText){
                            // Revisit retained 50 ms samples in time order around a detected transition.
                            for (old in ring) if (old.ms > lastEmitted && old !== frame) {
                                accept(old, recognize(old, refinement = true), fromNative = true)
                            }
                        }
                        accept(frame, reading, fromNative = reused == null)
                        if(key!=null)lastText=key
                        // Background pixel changes are not subtitle changes. Confirm a
                        // candidate quickly; otherwise scan every 200ms and refine the ring
                        // only when recognized source identity actually changes.
                        nextOcr=ms+if(acc.needsConfirmation)65 else 200
                        if(ms-lastCheckpoint>=3000){saveCheckpoint(ms+1,acc.cues.toList());lastCheckpoint=ms}
                    }
                    watchdog.progress()
                }
            }
            if(videoDrafts)VideoDecodeCoverage.requireComplete(lastInputMs,maxOutputMs,trackDurationMs)
            if (draftTimeline != null) {
                val finalFrame=tail?.frame
                finalFrame?.takeIf{it.ms>lastEmitted}?.let{frame->
                    gate()
                    accept(frame, recognize(frame, forceNative = true), fromNative = true)
                }
                if(finalFrame!=null)draftTimeline.finishAtValidatedEnd(project.durationMs,finalFrame.ms)
                else acc.finish(project.durationMs)
            }else acc.finish(project.durationMs)
            if(videoDrafts)decorateNew()
            val complete=VideoTimeline.validate(acc.cues.toList(),project.durationMs)
            // Report final counters before the caller clears extraction carry.
            saveCheckpoint(project.durationMs,complete)
            return complete
        } catch(error:VideoDecodeException) {
            if(videoDrafts){
                // Save the open occurrence without finishing it. Only validated EOS may store durationMs.
                decorateNew()
                val resume=maxOf(project.scanMs,lastEmitted+1).coerceIn(0,(project.durationMs-1).coerceAtLeast(0))
                saveCheckpoint(resume,acc.cues.toList())
            }
            throw error
        } finally {
            ring.clear();tail?.clear()
            VideoCodecBoundary.releaseAll({bitmapBuffer?.close()},{ocr.close()},{if(started)codec?.stop()},{codec?.release()},{extractor.release()})
        }
    }
}
