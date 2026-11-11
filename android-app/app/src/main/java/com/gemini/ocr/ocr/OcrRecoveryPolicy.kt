package com.gemini.ocr.ocr

/** One bounded native retry may clarify an uncertain glyph; it never lowers the acceptance floor. */
object OcrRecoveryPolicy {
    const val MIN_CONFIDENCE = .35
    const val MAX_RECOVERY_PIXELS = 1_000_000L
    private const val MAX_RECOVERY_WIDTH = 4096
    private const val MAX_RECOVERY_HEIGHT = 2048

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun clipped(width: Int, height: Int): Bounds? {
            val value = Bounds(left.coerceIn(0, width), top.coerceIn(0, height),
                right.coerceIn(0, width), bottom.coerceIn(0, height))
            return value.takeIf { it.width >= 3 && it.height >= 3 }
        }
    }

    data class Line(val text: String, val confidence: Double, val bounds: Bounds?)
    data class Plan(val crop: Bounds) {
        val targetWidth: Int get() = crop.width * 2
        val targetHeight: Int get() = crop.height * 2

        /** Outward rounding retains the recognized box in the original bitmap coordinate system. */
        fun mapToSource(bounds: Bounds, sourceWidth: Int, sourceHeight: Int): Bounds? {
            val local = bounds.clipped(targetWidth, targetHeight) ?: return null
            return Bounds(crop.left + local.left / 2, crop.top + local.top / 2,
                crop.left + (local.right + 1) / 2, crop.top + (local.bottom + 1) / 2)
                .clipped(sourceWidth, sourceHeight)
        }
    }

    fun hasHan(text: String): Boolean = text.codePoints().anyMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }

    fun isUncertainHan(line: Line): Boolean = hasHan(line.text) &&
        line.confidence.isFinite() && line.confidence < MIN_CONFIDENCE

    fun isReliableHan(line: Line): Boolean = hasHan(line.text) &&
        line.confidence.isFinite() && line.confidence >= MIN_CONFIDENCE

    fun plan(sourceWidth: Int, sourceHeight: Int, candidates: List<Line>): Plan? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        // Prefer the longest suspected Han line; the retry cannot grow into the whole screen.
        val rect = candidates.asSequence().filter(::isUncertainHan)
            .mapNotNull { line -> line.bounds?.clipped(sourceWidth, sourceHeight)?.let { line to it } }
            .maxByOrNull { (line, _) -> line.text.codePoints().filter {
                Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
            }.count() }?.second ?: return null
        val padding = (rect.height / 5).coerceIn(4, 64)
        val crop = Bounds((rect.left - padding).coerceAtLeast(0),
            (rect.top - padding).coerceAtLeast(0),
            (rect.right.toLong() + padding).coerceAtMost(sourceWidth.toLong()).toInt(),
            (rect.bottom.toLong() + padding).coerceAtMost(sourceHeight.toLong()).toInt())
        if (crop.width.toLong() * 2 > MAX_RECOVERY_WIDTH || crop.height.toLong() * 2 > MAX_RECOVERY_HEIGHT ||
            crop.width.toLong() * crop.height * 4 > MAX_RECOVERY_PIXELS) return null
        return Plan(crop)
    }
}
