package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject

enum class BatchPipelineStage(val label: String) {
    EXTRACT("Trích xuất"), CLEAN("Đối chiếu"), TRANSLATE("Dịch"), POLISH("Hiệu đính"), FINISH("Hoàn thiện")
}

data class VideoStageCheckpoint(
    val status: String = "PENDING", val lastCheckpoint: Long = 0, val attempts: Int = 0,
    val errorCode: String = "", val nextRetryAt: Long = 0,
    val targetCueLimit: Int = 0
) {
    fun json() = JSONObject().put("status",status).put("lastCheckpoint",lastCheckpoint).put("attempts",attempts)
        .put("errorCode",errorCode).put("nextRetryAt",nextRetryAt)
        .apply { if (targetCueLimit > 0) put("targetCueLimit", targetCueLimit) }
    companion object {
        fun from(j: JSONObject) = VideoStageCheckpoint(j.optString("status","PENDING"),j.optLong("lastCheckpoint"),
            j.optInt("attempts"),j.optString("errorCode"),j.optLong("nextRetryAt"),
            j.optInt("targetCueLimit").coerceAtLeast(0))
    }
}

/** Stored in the same AtomicFile as the timeline: stage completion cannot outrun its data. */
data class VideoPipelineCheckpoint(
    val version: Int = 0, val currentStage: BatchPipelineStage = BatchPipelineStage.EXTRACT,
    val stages: Map<BatchPipelineStage,VideoStageCheckpoint> = emptyMap(),
    val asrStatus: String = "UNAVAILABLE", val contextProfile: String = "", val contextRevision: Int = 0, val sharedRoiRevision: Int = 0
) {
    fun task(stage: BatchPipelineStage) = stages[stage] ?: VideoStageCheckpoint()
    fun record(stage: BatchPipelineStage,status: String,checkpoint: Long = 0,errorCode: String = "",nextRetryAt: Long = 0): VideoPipelineCheckpoint {
        val old=task(stage)
        return copy(version=1,currentStage=stage,stages=stages+(stage to old.copy(status=status,
            lastCheckpoint=checkpoint,attempts=old.attempts+if(status=="RUNNING")1 else 0,errorCode=errorCode,nextRetryAt=nextRetryAt)))
    }
    fun invalidateFrom(stage: BatchPipelineStage) = copy(stages=stages.filterKeys{it.ordinal<stage.ordinal},currentStage=stage)
    fun json() = JSONObject().put("version",version).put("currentStage",currentStage.name)
        .put("stages",JSONObject().also{j->stages.forEach{(stage,value)->j.put(stage.name,value.json())}})
        .put("asrStatus",asrStatus).put("contextProfile",contextProfile).put("contextRevision",contextRevision).put("sharedRoiRevision",sharedRoiRevision)
    companion object {
        fun from(j: JSONObject?): VideoPipelineCheckpoint {
            if(j==null)return VideoPipelineCheckpoint()
            require(j.optInt("version") in 0..1){"Mốc xử lý thuộc phiên bản mới hơn; dữ liệu được giữ."}
            val rows=j.optJSONObject("stages")?:JSONObject()
            return VideoPipelineCheckpoint(j.optInt("version"),BatchPipelineStage.valueOf(j.optString("currentStage","EXTRACT")),
                BatchPipelineStage.entries.mapNotNull{stage->rows.optJSONObject(stage.name)?.let{stage to VideoStageCheckpoint.from(it)}}.toMap(),
                j.optString("asrStatus","UNAVAILABLE"),j.optString("contextProfile"),j.optInt("contextRevision"),j.optInt("sharedRoiRevision"))
        }
    }
}

/** Optional ASR is a real capability, never substituted with invented speech. */
interface VideoChineseAsr {
    val available: Boolean
    suspend fun recognize(project: VideoProject,startMs: Long,endMs: Long): AsrObservation?
}
data class AsrObservation(val text: String,val confidence: Float?,val engine: String)
object UnavailableChineseAsr : VideoChineseAsr {
    override val available=false
    override suspend fun recognize(project: VideoProject,startMs: Long,endMs: Long): AsrObservation? = null
}

object VideoStagedPipeline {
    fun extracted(project: VideoProject) = project.scanMs>=project.durationMs && !project.sourceExtractionPending &&
        (project.cues.isNotEmpty() || (project.speech.optBoolean("complete") && project.speech.optBoolean("noSpeech")))
    fun completed(project: VideoProject,stage: BatchPipelineStage): Boolean {
        // Finished, explicitly approved legacy work is already authoritative. Do not rewrite it
        // merely to add stage bookkeeping or repeat OCR/API/export on an older movie.
        if(project.pipeline.version==0 && extracted(project) && project.cues.all{it.protectedText && it.translation.isNotBlank()})return true
        return when(stage) {
        BatchPipelineStage.EXTRACT -> extracted(project)
        BatchPipelineStage.TRANSLATE -> project.pipeline.task(stage).status=="COMPLETE" &&
            project.cues.all{VideoTranslationPlan.isComplete(it,project.translationMode==VideoTranslationMode.HYBRID)}
        else -> project.pipeline.task(stage).status=="COMPLETE"
        }
    }
    /** Only whitespace is corrected automatically. Similar dialogue is never merged or invented. */
    fun clean(cues: List<VideoCue>,asrAvailable: Boolean=false): List<VideoCue> = cues.map{cue->
        val raw=cue.rawZh.ifBlank{cue.source}
        val checkedSource=if(cue.protectedText)cue.source else cue.correctedZh.ifBlank{raw}
        val corrected=checkedSource.replace("\r\n","\n").replace('\r','\n').split('\n')
            .map{it.trim().replace(Regex("[ \\t]+")," ")}.filter{it.isNotEmpty()}.joinToString("\n")
        val flags=cue.qualityFlags.toMutableSet()
        if(cue.ocrConfidence!=null && cue.ocrConfidence<.75f)flags+="LOW_OCR_CONFIDENCE"
        if(corrected.contains('\uFFFD') || corrected.count{it=='□'}>0)flags+="OCR_UNCERTAIN"
        if(cue.endMs-cue.startMs<400)flags+="SHORT_SUBTITLE"
        if(cue.origin.contains("OCR") && !asrAvailable)flags+="ASR_UNAVAILABLE"
        cue.copy(rawZh=raw,correctedZh=corrected,source=if(cue.protectedText)cue.source else corrected,
            qualityFlags=flags.toList(),version=if(raw!=cue.rawZh || corrected!=cue.correctedZh)cue.version+1 else cue.version)
    }
    fun needsPolish(cue: VideoCue) = !cue.protectedText && cue.translation.isNotBlank() && cue.polishedVi.isBlank() &&
        (cue.qualityFlags.any{it!="ASR_UNAVAILABLE"} || cue.translation.length>84 || cue.translation.lines().any{it.length>46} || cue.error.isNotBlank())
    fun sourceSrt(cues: List<VideoCue>,raw: Boolean=false,durationMs:Long?=null) = VideoTimeline.srt(cues.map{it.copy(
        translation=if(raw)it.rawZh.ifBlank{it.source} else it.correctedZh.ifBlank{it.source})},durationMs)
    fun applyRoi(project:VideoProject,roi:List<Float>,revision:Int):VideoProject {
        require(project.roi==roi || project.cues.none { it.protectedText }) { "Tạo dự án mới trước khi đổi vùng để giữ các câu đã duyệt và sửa tay." }
        val pipeline=VideoPipelineCheckpoint(contextProfile=project.pipeline.contextProfile,
            contextRevision=project.pipeline.contextRevision,sharedRoiRevision=revision)
        return if(project.roi==roi)project.copy(pipeline=project.pipeline.copy(sharedRoiRevision=revision))
        else project.copy(roi=roi,cues=emptyList(),scanMs=0,carry=JSONObject(),speech=JSONObject(),pipeline=pipeline,
            stage=VideoStage.READY,resumeStage=VideoStage.EXTRACTING,exportedMp4="",progress=0)
    }
    fun errorCode(error: Exception): String {
        if(error is VideoApiException)return error.code
        if(error is VideoDecodeException)return error.code
        if(error is VideoQuotaWaitException)return "API_RATE_LIMIT"
        if(error is SecurityException)return "SOURCE_PERMISSION"
        val text=error.message.orEmpty().trimStart().removePrefix("[").lowercase(java.util.Locale.ROOT)
        return when {
            text.startsWith("asr_") -> text.substringBefore(':').take(64).uppercase(java.util.Locale.ROOT)
            text.startsWith("offline_model_missing") -> "OFFLINE_MODEL_MISSING"
            "enospc" in text || "no space left" in text || "không đủ dung lượng" in text -> "STORAGE_FULL"
            "không có quyền đọc" in text || "permission denied" in text -> "SOURCE_PERMISSION"
            text.startsWith("roi_invalid") -> "ROI_INVALID"
            text.startsWith("frame_invalid") -> "FRAME_INVALID"
            text.startsWith("frame_null") -> "FRAME_NULL"
            text.startsWith("frame_codec_state") -> "FRAME_CODEC_STATE"
            text.startsWith("frame_codec_error") -> "FRAME_CODEC_ERROR"
            text.startsWith("frame_decode_timeout") -> "FRAME_DECODE_TIMEOUT"
            text.startsWith("frame_decode_incomplete") -> "FRAME_DECODE_INCOMPLETE"
            "gemini" in text && ("(429)" in text || "(http 429)" in text) -> "API_RATE_LIMIT"
            "gemini" in text && ("(401)" in text || "(403)" in text || "chưa cho phép nhận chữ" in text) -> "API_AUTH"
            "frame_decode" in text || "decode" in text || "khung hình" in text -> "FRAME_DECODE"
            "roi" in text || "crop" in text || "vùng chữ" in text || "vùng ocr" in text -> "ROI_INVALID_OR_UNCERTAIN"
            "không tìm thấy chữ" in text -> "OCR_NO_SUBTITLES"
            "thiếu" in text || "id" in text || "nhóm cần kiểm tra" in text -> "RESPONSE_NEEDS_REVIEW"
            else -> "STAGE_NEEDS_REVIEW"
        }
    }
    fun withContext(batch: VideoTranslationPlan.Batch,profile: String): VideoTranslationPlan.Batch {
        if(profile.isBlank())return batch
        return batch.copy(videoContext=JSONObject(batch.videoContext).put("filmContext",profile.take(8000)).toString())
    }
    fun contextProfile(project: VideoProject) = JSONObject().put("schema",1).put("profile",project.profile)
        .put("glossary",project.glossary.take(8000)).put("scope","project-private")
        .put("characters",JSONArray()).put("relations",JSONArray()).put("events",JSONArray()).toString()
}
