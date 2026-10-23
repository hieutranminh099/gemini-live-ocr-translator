package com.gemini.ocr.capture

import android.graphics.Rect
import com.gemini.ocr.ocr.OcrBlock
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Optional vertical refinement inside the user's selected region, never an unrestricted screen
 * tracker. Call [observe] with source rows in full-frame coordinates after a current OCR result.
 * A missing row immediately restores the full user region; periodic full scans detect relocation
 * while another source row remains visible. No recognized text is retained by this class.
 */
class SourceRowTracker(
    private val fullScanIntervalMs: Long = 750,
    private val requiredObservations: Int = 2,
) {
    private var selection: Rect? = null
    private var candidate: Rect? = null
    private var band: Rect? = null
    private var observations = 0
    private var lastFullScanAt: Long? = null

    init { require(fullScanIntervalMs > 0); require(requiredObservations >= 2) }

    @Synchronized fun reset() {
        selection = null; candidate = null; band = null; observations = 0; lastFullScanAt = null
    }

    /** Returned rectangles are copies; callers cannot mutate the tracker's retained bounds. */
    @Synchronized fun captureRegion(selected: Rect, nowMs: Long): Rect {
        updateSelection(selected)
        val lastFull = lastFullScanAt
        if (band == null || lastFull == null || nowMs < lastFull || nowMs - lastFull >= fullScanIntervalMs) {
            lastFullScanAt = nowMs
            return Rect(selected)
        }
        return Rect(band!!)
    }

    @Synchronized fun observe(sourceRows: List<OcrBlock>, selected: Rect) {
        updateSelection(selected)
        val boxes = sourceRows.mapNotNull {
            // Full-frame coordinates are mandatory. Reject boxes whose centres are outside the
            // user's selection rather than letting a background/logo pull the region elsewhere.
            if (!selected.contains(it.rect.centerX(), it.rect.centerY())) null
            else Rect(it.rect).takeIf { box -> box.intersect(selected) && box.width() >= 3 && box.height() >= 3 }
        }
        if (boxes.isEmpty()) { releaseBand(); return }
        val union = Rect(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }
        // Widely separated source rows are ambiguous (e.g. credits plus a subtitle). Keep the
        // entire user region instead of following one arbitrary row or cropping a second line.
        if (union.height() > selected.height() * .6f) { releaseBand(); return }
        val previous = candidate
        val sameRow = previous != null && abs(previous.exactCenterY() - union.exactCenterY()) <=
            maxOf(previous.height(), union.height()) * .4f &&
            abs(previous.height() - union.height()) <= maxOf(previous.height(), union.height()) * .5f
        if (!sameRow) {
            candidate = Rect(union); observations = 1; band = null
            return
        }
        // Retain both observations' extent so glyph-box jitter does not cut accents or a row.
        val stable = Rect(previous!!).apply { union(union) }
        candidate = stable
        observations++
        if (observations >= requiredObservations) {
            val padding = maxOf(12, ceil(stable.height() * .6).toInt())
            val proposed = Rect(selected.left, maxOf(selected.top, stable.top - padding),
                selected.right, minOf(selected.bottom, stable.bottom + padding))
            band = proposed.takeIf { it.height() < selected.height() * .85f }
        }
    }

    private fun updateSelection(selected: Rect) {
        require(selected.width() > 0 && selected.height() > 0)
        if (selection != selected) {
            releaseBand(); selection = Rect(selected); lastFullScanAt = null
        }
    }

    private fun releaseBand() { candidate = null; band = null; observations = 0 }

    companion object {
        /**
         * A selected CJK source disappearing must not turn a Vietnamese/pinyin row into a source.
         * Fragments on the same source row (names, digits, blanks) remain part of the sentence.
         * For other configured languages the existing OCR output is left intact.
         */
        fun selectSourceRows(blocks: List<OcrBlock>, language: String, sourceOnly: Boolean = false): List<OcrBlock> {
            val scripts = when (language) {
                "zh" -> setOf(Character.UnicodeScript.HAN)
                "ja" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
                "ko" -> setOf(Character.UnicodeScript.HANGUL)
                else -> return blocks
            }
            val anchors = blocks.filter { block -> block.text.codePoints().anyMatch { Character.UnicodeScript.of(it) in scripts } }
            if (anchors.isEmpty()) return if (sourceOnly) emptyList() else blocks
            return blocks.filter { block -> block in anchors || anchors.any { anchor ->
                abs(anchor.rect.exactCenterY() - block.rect.exactCenterY()) < minOf(anchor.rect.height(), block.rect.height()) * .4f
            } }
        }
    }
}
