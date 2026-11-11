package com.paddle.ocr

import kotlin.math.ceil

/** Explicit preprocessing contract. Selecting a profile never substitutes model assets. */
enum class OcrPreprocessProfile(
    val detMean: List<Double>,
    val detStd: List<Double>,
    val recognitionUsesRgb: Boolean,
) {
    // Preserve the frozen upstream SDK baseline, including its recognition channel order.
    PP_OCR_V6_TINY(listOf(.485, .456, .406), listOf(.229, .224, .225), true),
    RAPIDOCR_V4(listOf(.5, .5, .5), listOf(.5, .5, .5), false);

    data class CropSize(val width: Int, val height: Int) {
        init { require(width > 0 && height > 0) { "Recognition crop dimensions must be positive" } }
    }
    data class RecognitionPlan(val resizedWidths: List<Int>, val tensorWidth: Int)

    /** Mirrors the normalization contract consumed by native channel operations. */
    fun normalizedDetectorSample(channel: Int, pixel: Int): Double {
        require(channel in 0..2 && pixel in 0..255)
        return (pixel * (1.0 / 255.0) - detMean[channel]) / detStd[channel]
    }

    fun recognitionPlan(crops: List<CropSize>, maximumWidth: Int = 3200): RecognitionPlan {
        require(crops.isNotEmpty()) { "Recognition batch must not be empty" }
        require(maximumWidth > 0)
        val naturalWidths = crops.map { ceil(48.0 * (it.width.toDouble() / it.height)).toInt() }
        if (this == PP_OCR_V6_TINY) {
            val resized = naturalWidths.map { it.coerceAtMost(maximumWidth) }
            return RecognitionPlan(resized, resized.max())
        }
        // RapidOCR 1.4.4 starts its per-batch maximum ratio at 320/48, floors the
        // tensor width, then clamps each crop's ceil-rounded resize width to it.
        val maxRatio = maxOf(320.0 / 48.0, crops.maxOf { it.width.toDouble() / it.height })
        val paddedWidth = (48.0 * maxRatio).toInt()
        require(paddedWidth <= maximumWidth) { "RapidOCR v4 crop exceeds the bounded recognition profile" }
        return RecognitionPlan(naturalWidths.map { minOf(it, paddedWidth) }, paddedWidth)
    }
}
