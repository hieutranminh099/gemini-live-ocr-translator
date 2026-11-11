package com.gemini.ocr.video

/** Derived from the current stage's data, never a previous stage's saved percentage. */
object VideoStageProgress {
    fun translatedCueCount(project: VideoProject): Int = project.cues.count {
        it.translation.isNotBlank() && VideoTranslationPlan.isComplete(it, project.translationMode == VideoTranslationMode.HYBRID)
    }

    fun translationComplete(project: VideoProject): Boolean = VideoStagedPipeline.extracted(project) &&
        if (project.cues.isEmpty()) project.speech.optBoolean("noSpeech") &&
            project.pipeline.task(BatchPipelineStage.TRANSLATE).status == "COMPLETE"
        else translatedCueCount(project) == project.cues.size

    fun completed(project: VideoProject, stage: BatchPipelineStage): Boolean = when (stage) {
        BatchPipelineStage.EXTRACT -> VideoStagedPipeline.extracted(project)
        BatchPipelineStage.TRANSLATE -> translationComplete(project) &&
            (project.pipeline.version == 0 || project.pipeline.task(stage).status == "COMPLETE")
        BatchPipelineStage.FINISH -> translationComplete(project) && VideoStagedPipeline.completed(project, stage)
        else -> VideoStagedPipeline.completed(project, stage)
    }

    fun percent(project: VideoProject, stage: BatchPipelineStage): Int = when (stage) {
        BatchPipelineStage.EXTRACT -> if (completed(project, stage)) 100 else {
            if (project.durationMs <= 0) 0 else ((project.scanMs.coerceIn(0, project.durationMs) * 100L) /
                project.durationMs).toInt().coerceAtMost(99)
        }
        BatchPipelineStage.TRANSLATE -> if (project.cues.isEmpty()) {
            if (completed(project, stage)) 100 else 0
        } else fraction(translatedCueCount(project), project.cues.size)
        BatchPipelineStage.POLISH -> if (completed(project, stage)) 100 else {
            val targets = project.cues.filter { !it.protectedText && (it.polishedVi.isNotBlank() ||
                project.translationMode == VideoTranslationMode.OFFLINE && project.offlinePolishPolicy == VideoPolishPolicy.ALL ||
                VideoStagedPipeline.needsPolish(it)) }
            fraction(targets.count { it.polishedVi.isNotBlank() && "POLISH_PENDING" !in it.qualityFlags }, targets.size)
                .coerceAtMost(99)
        }
        BatchPipelineStage.CLEAN, BatchPipelineStage.FINISH -> if (completed(project, stage)) 100 else 0
    }

    private fun fraction(done: Int, total: Int) = if (total <= 0) 0 else (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
}
