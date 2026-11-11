package com.gemini.ocr.batch

import android.content.Context
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoRoiPreview
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStagedPipeline
import com.gemini.ocr.video.VideoSubtitleFrame
import com.gemini.ocr.video.videoSourceUsesRoi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BatchRoiCheck(val projectId: String, val confirmed: Boolean, val message: String)

/** Three decoded samples per video, one OCR consumer, no cloud and no retained bitmaps. */
internal object BatchRoiValidation {
    private fun hasSavedAudioContinuation(project: VideoProject): Boolean = project.sourceMode == VideoSourceMode.AUTO &&
        project.speech.optBoolean("ocrComplete") && project.speech.optBoolean("asrActive")
    fun identity(project: VideoProject): String = listOf(project.id, project.uri, project.width, project.height,
        project.durationMs, project.sourceMode.name, project.roi.joinToString(",")).joinToString("|")

    /** Use the same effective OCR region in preflight and after a partially saved batch configuration. */
    fun withSharedRoi(project: VideoProject, batch: BatchProject): VideoProject {
        val roi = batch.sharedRoi ?: return project
        if (!videoSourceUsesRoi(project.sourceMode) || project.pipeline.sharedRoiRevision == batch.sharedRoiRevision) return project
        return VideoStagedPipeline.applyRoi(project, roi, batch.sharedRoiRevision)
    }

    fun hasSavedOcrProgress(project: VideoProject): Boolean {
        if (hasSavedAudioContinuation(project)) return true
        val extract = project.pipeline.task(BatchPipelineStage.EXTRACT)
        return videoSourceUsesRoi(project.sourceMode) && project.scanMs > 0 &&
            extract.lastCheckpoint > 0 && extract.errorCode.isBlank()
    }

    fun sampleTimes(durationMs: Long): List<Long> {
        require(durationMs > 0)
        return listOf(durationMs / 5, durationMs / 2, durationMs * 4 / 5)
            .map { it.coerceIn(0, durationMs - 1) }.distinct()
    }

    suspend fun check(context: Context, projects: List<VideoProject>): List<BatchRoiCheck> {
        require(!SessionState.running.value) { "Tạm dừng Dịch trực tiếp trước khi kiểm tra vùng chữ." }
        return projects.map { project ->
            currentCoroutineContext().ensureActive()
            if (!videoSourceUsesRoi(project.sourceMode)) {
                BatchRoiCheck(project.id, true, if (project.sourceMode == VideoSourceMode.ASR)
                    "Dùng giọng nói, không cần vùng OCR." else "Dùng phụ đề văn bản, không cần vùng OCR.")
            } else if (hasSavedAudioContinuation(project)) {
                BatchRoiCheck(project.id, true, "OCR trước đã hoàn tất; tiếp tục nhận diện giọng nói từ mốc đã lưu.")
            } else {
                var found = 0
                var failed = 0
                val frame = VideoSubtitleFrame.fromRoi(project.roi)
                for (time in sampleTimes(project.durationMs)) {
                    currentCoroutineContext().ensureActive()
                    check(!SessionState.running.value) { "Dịch trực tiếp vừa bắt đầu. Đã dừng kiểm tra vùng chữ." }
                    try {
                        if (VideoRoiPreview.recognize(context, project, time, frame).any { it in '\u3400'..'\u9fff' }) found++
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed++ }
                }
                BatchRoiCheck(project.id, found >= 2,
                    when {
                        found >= 2 -> "Đã thấy chữ Trung ở $found thời điểm."
                        failed > 0 -> "Chưa đủ bằng chứng; $failed ảnh không đọc được, cần kiểm tra vùng."
                        else -> "Chưa đủ bằng chứng ở các ảnh mẫu; cần kiểm tra vùng."
                    })
            }
        }
    }
}
