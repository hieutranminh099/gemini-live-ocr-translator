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

    fun percent(project: VideoProject, stage: BatchPipelineStage): Int = percent(project, stage, null)

    internal data class TextCheckpoint(val completedCues: Long, val workingPercent: Int)

    /** A metadata-only dispatch can reuse these counts from its last saved timeline. */
    internal fun measureText(project: VideoProject, stage: BatchPipelineStage): TextCheckpoint? = when (stage) {
        BatchPipelineStage.TRANSLATE -> {
            val hybrid = project.translationMode == VideoTranslationMode.HYBRID
            var checkpoint = 0L
            var translated = 0
            for (cue in project.cues) {
                if (!VideoTranslationPlan.isComplete(cue, hybrid)) continue
                checkpoint++
                if (cue.translation.isNotBlank()) translated++
            }
            TextCheckpoint(checkpoint, fraction(translated, project.cues.size))
        }
        BatchPipelineStage.POLISH -> {
            val all = project.translationMode == VideoTranslationMode.OFFLINE && project.offlinePolishPolicy == VideoPolishPolicy.ALL
            var checkpoint = 0L
            var targets = 0
            var done = 0
            for (cue in project.cues) {
                if (cue.protectedText) {
                    checkpoint++
                } else if (cue.polishedVi.isNotBlank()) {
                    checkpoint++
                    targets++
                    if ("POLISH_PENDING" !in cue.qualityFlags) done++
                } else if (all || VideoStagedPipeline.needsPolish(project, cue)) {
                    targets++
                }
            }
            TextCheckpoint(checkpoint, fraction(done, targets).coerceAtMost(99))
        }
        else -> null
    }

    internal fun percent(project: VideoProject, stage: BatchPipelineStage, text: TextCheckpoint?): Int = when (stage) {
        BatchPipelineStage.EXTRACT -> if (completed(project, stage)) 100 else {
            if (project.durationMs <= 0) 0 else ((project.scanMs.coerceIn(0, project.durationMs) * 100L) /
                project.durationMs).toInt().coerceAtMost(99)
        }
        BatchPipelineStage.TRANSLATE -> if (project.cues.isEmpty()) {
            if (completed(project, stage)) 100 else 0
        } else text?.workingPercent ?: fraction(translatedCueCount(project), project.cues.size)
        BatchPipelineStage.POLISH -> if (completed(project, stage)) 100 else {
            (text ?: requireNotNull(measureText(project, stage))).workingPercent
        }
        BatchPipelineStage.CLEAN, BatchPipelineStage.FINISH -> if (completed(project, stage)) 100 else 0
    }

    private fun fraction(done: Int, total: Int) = if (total <= 0) 0 else (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
}
