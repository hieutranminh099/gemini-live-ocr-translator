package com.gemini.ocr.capture

import android.graphics.Rect
import com.gemini.ocr.ocr.OcrBlock

/** Selection diagnostics contain counts and decisions, never recognized or translated text. */
object OcrSourceSelection {
    data class Result(
        val rawCount: Int,
        val recognizedCount: Int,
        val filteredCount: Int,
        val blocks: List<OcrBlock>,
        val renderedCopy: Boolean,
        val decision: String,
    ) {
        fun trace(frameId: Long, captureMs: Long, queueMs: Long, ocrMs: Long,
                  sourceImageId: Long, sourceImageAgeMs: Long) {
            PipelineTrace.event("ocr_evaluated", "frame_id" to frameId,
                "raw_blocks" to rawCount, "source_blocks" to recognizedCount,
                "region_blocks" to filteredCount, "stable_blocks" to blocks.size,
                "decision" to decision, "applied" to !renderedCopy,
                "empty" to if (renderedCopy) null else blocks.isEmpty(),
                "capture_ms" to captureMs, "ocr_queue_ms" to queueMs, "ocr_ms" to ocrMs,
                "source_image_id" to sourceImageId, "source_image_age_ms" to sourceImageAgeMs)
        }
    }

    fun select(raw: List<OcrBlock>, crop: Rect?, language: String, frameHeight: Int,
               region: String, subtitleFocus: Boolean, oneFrame: Boolean,
               standaloneTurbo: Boolean, stabilizeBeforeCloud: Boolean, displayed: String?,
               stability: StableText, cloudStability: StableText): Result {
        // The explicit frame action still translates the selected source language. Without
        // this guard an old Vietnamese overlay can become a new Chinese subtitle request.
        val recognized = if (crop != null) SubtitleRegion.merge(raw, crop, language, subtitleFocus || !oneFrame) else raw
        if (subtitleFocus && displayed != null &&
            SubtitleText.isRenderedCopy(recognized.joinToString(" ") { it.text }, displayed)) {
            // Preserve the existing early return: an overlay copy must not advance stability.
            return Result(raw.size, recognized.size, 0, emptyList(), true, "rendered_copy")
        }
        val filtered = recognized.filter {
            val middle = it.rect.exactCenterY() / frameHeight
            when (region) { "dialogue" -> middle >= .5f; "center" -> middle in .2f.. .85f; else -> true }
        }
        val cloudConfirmed = cloudStability.observe(filtered.map { it.text })
        val stable = if (subtitleFocus || standaloneTurbo) {
            filtered.map { it.text }.toSet()
        } else if (stabilizeBeforeCloud && !oneFrame) {
            cloudConfirmed
        } else {
            stability.observe(filtered.map { it.text })
        }
        val blocks = filtered.filter { it.text in stable }.mapIndexed { i, block -> block.copy(id = "b$i") }
        val decision = when {
            raw.isEmpty() -> "ocr_empty"
            recognized.isEmpty() -> "source_script_missing"
            filtered.isEmpty() -> "region_filtered"
            blocks.isEmpty() -> "awaiting_stability"
            else -> "accepted"
        }
        return Result(raw.size, recognized.size, filtered.size, blocks, false, decision)
    }
}
