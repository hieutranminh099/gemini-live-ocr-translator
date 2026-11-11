package com.gemini.ocr.batch

import android.content.Context
import android.net.Uri
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStagedPipeline
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import java.io.File

internal enum class BatchEpisodeFile(val suffix: String, val savedMessage: String) {
    SRT("-vi.srt", "Đã lưu SRT tiếng Việt của tập."),
    MP4("-vi.mp4", "Đã lưu MP4 của tập.")
}

/** Export readiness belongs to the persisted episode, independent of the batch's overall state. */
internal object BatchEpisodeExport {
    fun canSave(project: VideoProject?, activeVideoId: String?): Boolean {
        if (project == null || project.id == activeVideoId || project.cues.isEmpty() ||
            !VideoStagedPipeline.extracted(project) ||
            !VideoStagedPipeline.completed(project, BatchPipelineStage.TRANSLATE) ||
            project.cues.any { it.translation.isBlank() }) return false

        if (VideoStagedPipeline.completed(project, BatchPipelineStage.POLISH)) return true
        return when (project.translationMode) {
            // HYBRID already receives contextual Gemini translations at TRANSLATE.
            VideoTranslationMode.HYBRID -> true
            VideoTranslationMode.GEMINI -> project.cues.none(VideoStagedPipeline::needsPolish)
            VideoTranslationMode.OFFLINE -> when (project.offlinePolishPolicy) {
                VideoPolishPolicy.NONE -> true
                VideoPolishPolicy.FLAGGED -> project.cues.none(VideoStagedPipeline::needsPolish)
                VideoPolishPolicy.ALL -> project.cues.all { it.protectedText || it.polishedVi.isNotBlank() }
            }
        }
    }

    fun fileName(item: BatchItem, format: BatchEpisodeFile): String =
        item.name.substringBeforeLast('.').trim().take(160).ifBlank { "Tap-phim" } + format.suffix

    suspend fun save(context: Context, jobs: VideoJobs, batchId: String, projectId: String, format: BatchEpisodeFile, destination: Uri) {
        val validate: (VideoProject) -> Unit = { project ->
            require(BatchStore(context).load(batchId).items.any { it.projectId == project.id }) {
                "Video không còn trong danh sách này."
            }
            require(canSave(project, jobs.busy.value)) {
                "Tập chưa hoàn thành hoặc đang thay đổi. Thử lưu lại sau."
            }
            if (format == BatchEpisodeFile.MP4) {
                val owned = VideoStore(context).exportFile(project.id, "mp4").canonicalFile
                val ready = project.exportedMp4.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
                require(ready == owned && owned.isFile && owned.length() > 0) {
                    "MP4 chưa có sẵn. Mở phần xuất của tập để tạo bản mới."
                }
            }
        }
        when (format) {
            BatchEpisodeFile.SRT -> jobs.exportText(projectId, destination, validateSnapshot = validate)
            BatchEpisodeFile.MP4 -> jobs.copyMp4(projectId, destination, validateSnapshot = validate)
        }
    }
}
