package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage

import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStageProgress

/** Presentation only. Moving between steps never starts work or changes saved processing settings. */
object BatchWorkflowUi {
    val steps = listOf("Chọn phim", "Thiết lập phụ đề", "Tiến độ", "Kiểm tra và xuất")

    fun initialStep(batch: BatchProject?, confirmed: Boolean, running: Boolean): Int = when {
        batch == null -> 0
        confirmed -> 3
        running || batch.state in setOf("RUNNING", "PAUSED", "FAILED", "CANCELLED", "NEEDS_REVIEW") -> 2
        batch.state == "REVIEW" || batch.outputs.isNotEmpty() -> 3
        else -> 0
    }

    fun reviewAvailable(batch: BatchProject, projects: List<VideoProject>): Boolean =
        batch.outputs.isNotEmpty() || projects.any { p -> batch.items.any { it.projectId == p.id } && p.cues.isNotEmpty() }

    fun progress(batch: BatchProject, projects: List<VideoProject>): Float {
        if (batch.items.isEmpty()) return 0f
        return batch.items.map { item ->
            val video = projects.firstOrNull { it.id == item.projectId }
            if (item.state == "DONE" && (video == null || VideoStageProgress.completed(video, BatchPipelineStage.FINISH))) 1f
            else if (video != null && video.pipeline.version > 0) {
                val completed = BatchPipelineStage.entries.count { VideoStageProgress.completed(video, it) } / 5f
                val task = video.pipeline.task(video.pipeline.currentStage)
                val current = if (task.status in setOf("RUNNING", "PAUSED", "RETRY_WAIT", "NEEDS_REVIEW") &&
                    !VideoStageProgress.completed(video, video.pipeline.currentStage))
                    VideoStageProgress.percent(video, video.pipeline.currentStage) / 500f else 0f
                (completed + current).coerceIn(0f, .99f)
            } else {
                val current = when (item.stageStatus) {
                    "COMPLETE" -> .2f
                    "RUNNING", "PAUSED", "RETRY_WAIT", "NEEDS_REVIEW" -> video?.let {
                        VideoStageProgress.percent(it, item.stage) / 500f
                    } ?: 0f
                    else -> 0f
                }
                current
            }
        }.average().toFloat().coerceIn(0f, 1f)
    }

    data class CompletionCounts(val sources: Int, val translated: Int, val finished: Int, val total: Int)

    fun completionCounts(batch: BatchProject, projects: List<VideoProject>): CompletionCounts {
        val byId = projects.associateBy { it.id }
        val members = batch.items.mapNotNull { byId[it.projectId] }
        return CompletionCounts(
            members.count { VideoStageProgress.completed(it, BatchPipelineStage.EXTRACT) },
            members.count(VideoStageProgress::translationComplete),
            members.count { VideoStageProgress.completed(it, BatchPipelineStage.FINISH) }, batch.items.size)
    }

    fun stageLabel(stage: BatchPipelineStage): String = when (stage) {
        BatchPipelineStage.EXTRACT -> "Trích xuất"
        BatchPipelineStage.CLEAN -> "Đối chiếu"
        BatchPipelineStage.TRANSLATE -> "Dịch"
        BatchPipelineStage.POLISH -> "Hiệu đính"
        BatchPipelineStage.FINISH -> "Hoàn thiện"
    }

    fun reviewFlags(flags: List<String>): List<String> = flags.filter { it != "ASR_UNAVAILABLE" }

    fun unresolvedCueCount(batch: BatchProject, projects: List<VideoProject>): Int {
        val members = batch.items.map { it.projectId }.toSet()
        return projects.filter { it.id in members }.sumOf { project ->
            project.cues.count { !it.approved && reviewFlags(it.qualityFlags).isNotEmpty() }
        }
    }

    fun retryWaiting(batch: BatchProject, projects: List<VideoProject>): Boolean {
        val members = batch.items.map { it.projectId }.toSet()
        return batch.state == "RETRY_WAIT" || projects.any { it.id in members &&
            it.pipeline.task(it.pipeline.currentStage).status == "RETRY_WAIT" }
    }

    fun flagLabel(flag: String): String = when (flag) {
        "LOW_OCR_CONFIDENCE" -> "Chữ nhận diện chưa rõ"
        "OCR_UNCERTAIN" -> "Có chữ thiếu hoặc bất thường"
        "OCR_CONFIDENCE_UNKNOWN" -> "Chưa có điểm tin cậy nhận chữ"
        "OCR_SHORT_QUORUM" -> "Cần đối chiếu thêm chữ"
        "OCR_UNSTABLE_SINGLE_FRAME" -> "Chữ chưa ổn định, cần đối chiếu ảnh gốc"
        "OCR_UNCERTAIN_INTERVAL" -> "Khoảng xuất hiện cần đối chiếu video"
        "OCR_GEOMETRY_REJECTED", "OCR_BUDGET_REJECTED", "OCR_NOISE_REJECTED" -> "Một phần vùng chữ cần đối chiếu"
        "SHORT_SUBTITLE" -> "Phụ đề xuất hiện ngắn"
        "ASR_UNAVAILABLE" -> "Chưa đối chiếu âm thanh"
        "POLISH_UNCERTAIN" -> "Chưa đủ ngữ cảnh để hiệu đính"
        "POLISH_NEEDS_REVIEW" -> "Bản hiệu đính cần kiểm tra"
        "NEEDS_REVIEW" -> "Cần đối chiếu lại"
        else -> "Câu cần xem lại"
    }

    fun friendlyMessage(message: String): String = when {
        message.contains("RETRY_WAIT") -> "Đang tự khôi phục, tiến độ đã được lưu."
        message.contains("401") || message.contains("403") -> "Chưa được phép gọi dịch vụ. Kiểm tra khóa và quyền truy cập; tiến độ đã lưu."
        message.contains("QUOTA") -> "Hạn mức dịch vụ đã hết. Đã dừng an toàn và giữ tiến độ."
        message.contains("429") || message.contains("NETWORK") || message.contains("503") ->
            "Dịch vụ tạm thời chưa đáp ứng. Tiến độ đã lưu; xem chi tiết trạng thái khôi phục."
        message.contains("INVALID") || message.contains("NEEDS_REVIEW") -> "Một phần dữ liệu cần kiểm tra. Các công đoạn đã hoàn thành vẫn được giữ."
        else -> message
    }

    fun stateLabel(state: String): String = when (state) {
        "READY", "PENDING" -> "Sẵn sàng"
        "RUNNING", "TRANSLATING" -> "Đang xử lý"
        "RETRY_WAIT" -> "Đang tự khôi phục, tiến độ đã được lưu"
        "EXPORTING" -> "Đang xuất video"
        "PAUSED" -> "Đã tạm dừng"
        "CANCELLED" -> "Đã dừng"
        "FAILED", "NEEDS_REVIEW" -> "Cần kiểm tra"
        "REVIEW" -> "Chờ kiểm tra"
        "DONE" -> "Hoàn thành"
        else -> "Đã lưu"
    }

    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
