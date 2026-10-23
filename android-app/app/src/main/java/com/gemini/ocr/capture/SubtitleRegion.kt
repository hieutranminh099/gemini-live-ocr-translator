package com.gemini.ocr.capture

import android.graphics.Rect
import android.graphics.RectF
import com.gemini.ocr.ocr.OcrBlock
import kotlin.math.roundToInt

/** Coordinates remain relative to the captured content, including after rotation. */
object SubtitleRegion {
    fun pixels(region: RectF, width: Int, height: Int): Rect {
        val left = (region.left.coerceIn(0f, .95f) * width).roundToInt().coerceIn(0, width - 1)
        val top = (region.top.coerceIn(0f, .97f) * height).roundToInt().coerceIn(0, height - 1)
        return Rect(left, top, (region.right * width).roundToInt().coerceIn(left + 1, width),
            (region.bottom * height).roundToInt().coerceIn(top + 1, height))
    }

    /** One subtitle may be split across several ML Kit blocks. Send one sentence to AI. */
    fun merge(blocks: List<OcrBlock>, crop: Rect, language: String = "", sourceOnly: Boolean = false): List<OcrBlock> {
        if (blocks.isEmpty()) return emptyList()
        // Language-learning videos often put pinyin and Vietnamese below the Chinese row.
        // When a source-script row exists, exclude those other rows before constructing a sentence.
        val selected = SourceRowTracker.selectSourceRows(blocks, language, sourceOnly)
        if (selected.isEmpty()) return emptyList()
        val ordered = selected.sortedWith(compareBy({ it.rect.top / 20 }, { it.rect.left }))
        val box = Rect(ordered.first().rect)
        ordered.drop(1).forEach { box.union(it.rect) }
        box.offset(crop.left, crop.top)
        return listOf(OcrBlock("b0", ordered.joinToString(" ") { it.text }.take(2000), box))
    }
}
