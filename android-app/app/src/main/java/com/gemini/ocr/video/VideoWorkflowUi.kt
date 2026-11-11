package com.gemini.ocr.video

/** Presentation state only. It never starts work or changes a saved processing mode. */
internal enum class VideoWorkflowStep { SELECT, SETUP, PROCESS, REVIEW, EXPORT }

internal fun videoWorkflowEntry(project: VideoProject?, requested: String = ""): VideoWorkflowStep {
    if (project == null) return VideoWorkflowStep.SELECT
    when (requested) {
        "setup" -> return VideoWorkflowStep.SETUP
        "review" -> if (project.cues.isNotEmpty()) return VideoWorkflowStep.REVIEW
        "export" -> if (project.cues.isNotEmpty()) return VideoWorkflowStep.EXPORT
    }
    return when (project.stage) {
        VideoStage.COMPLETE -> VideoWorkflowStep.REVIEW
        VideoStage.REVIEW -> if (project.cues.isNotEmpty()) VideoWorkflowStep.REVIEW else VideoWorkflowStep.SETUP
        VideoStage.EXTRACTING, VideoStage.TRANSLATING, VideoStage.EXPORTING,
        VideoStage.PAUSED, VideoStage.WAITING_LIVE, VideoStage.FAILED -> VideoWorkflowStep.PROCESS
        VideoStage.READY -> VideoWorkflowStep.SETUP
    }
}

internal fun videoStartIssue(project: VideoProject, keyReady: Boolean, offlineReady: Boolean): String? = when {
    project.resumeStage == VideoStage.EXPORTING && VideoExportReadiness.from(project).complete -> null
    project.sourceMode == VideoSourceMode.SIDECAR && project.subtitleUri.isBlank() && project.cues.isEmpty() -> "Chọn tệp phụ đề để tiếp tục, hoặc dùng chữ trong hình."
    project.sourceMode in setOf(VideoSourceMode.AUTO, VideoSourceMode.ASR) && project.sourceExtractionPending -> null
    VideoHybridWorkflow.requiresExtraction(project) -> null
    project.translationMode == VideoTranslationMode.HYBRID && !keyReady &&
        project.cues.any { !VideoTranslationPlan.isComplete(it, true) } -> "Đã lấy đủ nguồn (100%). Thiết lập kết nối Gemini để dịch toàn bộ hội thoại."
    project.translationMode == VideoTranslationMode.GEMINI && !keyReady -> "Chưa thiết lập kết nối Gemini. Mở Tùy chỉnh hoặc chọn dịch trên thiết bị."
    project.translationMode == VideoTranslationMode.OFFLINE && !offlineReady -> "Cần chuẩn bị gói dịch trên thiết bị trước khi bắt đầu."
    else -> null
}

internal fun videoSourceUsesRoi(source: VideoSourceMode): Boolean =
    source == VideoSourceMode.OCR || source == VideoSourceMode.AUTO

internal fun videoCanExportSource(project: VideoProject): Boolean =
    !project.sourceExtractionPending && project.cues.isNotEmpty() && VideoStagedPipeline.extracted(project)

internal fun videoUnclearSpeechNotice(project: VideoProject): String? {
    val count = project.speech.optInt("unclearWindows").coerceAtLeast(0)
    return if (count == 0) null else "Có $count đoạn có tiếng nhưng chưa nhận được lời thoại rõ. Hãy nghe lại; ứng dụng chưa tạo chữ cho các đoạn này."
}

internal fun videoSourceLabel(source: VideoSourceMode): String = when (source) {
    VideoSourceMode.AUTO -> "Tự động đề xuất"
    VideoSourceMode.EMBEDDED -> "Phụ đề có sẵn trong video"
    VideoSourceMode.SIDECAR -> "Phụ đề có sẵn · tệp riêng"
    VideoSourceMode.OCR -> "OCR hình ảnh"
    VideoSourceMode.ASR -> "Nhận diện giọng nói"
}

internal fun videoResolvedSourceLabel(project: VideoProject): String {
    when (project.speech.optString("resolvedSource")) {
        "OCR_ASR" -> return "OCR hình ảnh và nhận diện giọng nói"
        "ASR" -> return "Nhận diện giọng nói"
        "OCR" -> return "OCR hình ảnh"
        "EMBEDDED", "SIDECAR" -> return "Phụ đề có sẵn"
    }
    val speech = project.cues.any { it.origin.contains("ASR", true) || it.origin.contains("SPEECH", true) }
    val ocr = project.cues.any { it.origin.contains("OCR", true) }
    return when {
        speech && ocr -> "OCR hình ảnh và nhận diện giọng nói"
        speech -> "Nhận diện giọng nói"
        ocr -> "OCR hình ảnh"
        project.sourceMode == VideoSourceMode.AUTO && project.cues.isNotEmpty() -> "Phụ đề có sẵn"
        else -> videoSourceLabel(project.sourceMode)
    }
}

internal data class VideoAsrOverlapPreview(val startMs: Long, val endMs: Long, val source: String,
    val existingSources: List<String>)

/** Bounded read-only view of recognition observations excluded from the saved subtitle timeline. */
internal fun videoAsrOverlapPreviews(project: VideoProject): List<VideoAsrOverlapPreview> {
    val rows = project.speech.optJSONArray("reviewOverlaps") ?: return emptyList()
    return (0 until minOf(rows.length(), 64)).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val start = row.optLong("startMs", -1)
        val end = row.optLong("endMs", -1)
        val source = row.optString("source").take(2_000)
        if (start < 0 || end <= start || end > project.durationMs || source.isBlank()) return@mapNotNull null
        VideoAsrOverlapPreview(start, end, source, project.cues.asSequence()
            .filter { it.startMs < end && it.endMs > start }
            .map { it.source.take(2_000) }.distinct().take(6).toList())
    }
}
