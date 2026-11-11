package com.gemini.ocr.ocr

/** Empty + uncertain is not evidence that a visible source line disappeared. */
data class OcrObservation(
    val blocks: List<OcrBlock>,
    val uncertain: Boolean = false,
    val nativePasses: Int = 1,
    val recoveryReason: String = "none",
) {
    init {
        require(!uncertain || blocks.isEmpty())
        require(nativePasses in 0..2)
    }
}
