package com.gemini.ocr.video

/** Exact ROI pixels only. A changed digit, missing line or disappearance always requires OCR.
 * One bounded luma reference; no perceptual/fuzzy hash can certify unchanged subtitle text. */
internal class VideoOcrReuse {
    private var previous: VideoOcrFrame? = null
    private var text: String? = null
    fun lookup(frame: VideoOcrFrame, needsConfirmation: Boolean): String? {
        val old = previous ?: return null
        if (needsConfirmation || text == null || old.width != frame.width || old.height != frame.height) return null
        return text.takeIf { old.luma.contentEquals(frame.luma) }
    }
    fun record(frame: VideoOcrFrame, recognized: String?) { previous = frame; text = recognized }
}
