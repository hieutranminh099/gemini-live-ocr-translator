package com.gemini.ocr.video

import android.os.Process
import java.util.Locale

internal enum class VideoProfileStage {
    EXTRACT, CLEAN, TRANSLATE, POLISH, FINISH, EXPORT, LIVE_WAIT;

    val metricPrefix = name.lowercase(Locale.ROOT)

    companion object {
        fun forWorker(stage: BatchPipelineStage?, intended: VideoStage): VideoProfileStage =
            stage?.let { valueOf(it.name) } ?: when (intended) {
                VideoStage.EXTRACTING -> EXTRACT
                VideoStage.EXPORTING -> EXPORT
                else -> TRANSLATE
            }
    }
}

/** Best-effort timing for one worker run. Existing project checkpoints remain authoritative. */
internal class VideoBatchProfiler(
    private val metrics: VideoBatchTelemetry,
    initialStage: VideoProfileStage,
    private val clock: () -> Reading = { Reading(System.nanoTime() / 1_000_000L, Process.getElapsedCpuTime()) }
) {
    data class Reading(val wallMs: Long, val processCpuMs: Long)

    private var stage = initialStage
    private var previous = clock()

    /** Reuse the PSS observation already made at the checkpoint. No additional I/O here. */
    fun checkpoint(nextStage: VideoProfileStage = stage, pssKiB: Long? = null) {
        val now = clock()
        val wallMs = (now.wallMs - previous.wallMs).coerceAtLeast(0)
        val processCpuMs = (now.processCpuMs - previous.processCpuMs).coerceAtLeast(0)
        metrics.add("${stage.metricPrefix}_wall_ms", wallMs)
        metrics.add("${stage.metricPrefix}_process_cpu_ms", processCpuMs)
        metrics.add("worker_wall_ms", wallMs)
        metrics.add("worker_process_cpu_ms", processCpuMs)
        pssKiB?.let {
            val observedPss = it.coerceAtLeast(0)
            metrics.maximum("peak_pss_kib", observedPss)
            metrics.maximum("${stage.metricPrefix}_peak_pss_kib", observedPss)
        }
        stage = nextStage
        previous = now
    }

    fun finish(project: VideoProject) {
        checkpoint()
        // Count the final persisted timeline once per run, not at every decoder checkpoint.
        val hybrid = project.translationMode == VideoTranslationMode.HYBRID
        var source = 0L
        var translated = 0L
        var completed = 0L
        var approved = 0L
        var needsReview = 0L
        for (cue in project.cues) {
            val hasTranslation = cue.translation.isNotBlank()
            val complete = hasTranslation && VideoTranslationPlan.isComplete(cue, hybrid)
            if (cue.source.isNotBlank()) source++
            if (hasTranslation) translated++
            if (complete) completed++
            if (cue.approved) approved++
            if (!complete || (!cue.protectedText &&
                    (cue.error.isNotBlank() || cue.qualityFlags.any { it != "ASR_UNAVAILABLE" }))) {
                needsReview++
            }
        }
        metrics.set("source_cues", source)
        metrics.set("translated_cues", translated)
        metrics.set("completed_cues", completed)
        metrics.set("pending_cues", project.cues.size - completed)
        metrics.set("approved_cues", approved)
        metrics.set("needs_review_cues", needsReview)
        val ocrMetrics = project.ocrMetrics.takeIf { project.sourceMode == VideoSourceMode.OCR && project.scanMs > 0 }
        for (counter in OCR_COUNTERS) {
            metrics.set(counter, (ocrMetrics?.optLong(counter) ?: 0L).coerceAtLeast(0L))
        }
    }

    private companion object {
        val OCR_COUNTERS = listOf("decoded_frames", "recognition_requests", "ocr_native_calls",
            "exact_pixel_reused_observations", "luma_buffer_reuses", "crop_wall_ms", "bitmap_wall_ms", "ocr_wall_ms",
            "decode_and_other_wall_ms", "gate_wall_ms")
    }
}
