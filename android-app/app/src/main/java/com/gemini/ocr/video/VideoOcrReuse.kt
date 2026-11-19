package com.gemini.ocr.video

/** Exact ROI pixels only. A changed digit, missing line or disappearance always requires OCR.
 * One bounded luma reference; no perceptual/fuzzy hash can certify unchanged subtitle text. */
internal class VideoOcrReuse<T : Any> {
    var referenceFrame: VideoOcrFrame? = null
        private set
    private var reading: T? = null
    fun lookup(frame: VideoOcrFrame, needsConfirmation: Boolean): T? {
        val old = referenceFrame ?: return null
        if (needsConfirmation || old.width != frame.width || old.height != frame.height) return null
        return reading?.takeIf { old.luma.contentEquals(frame.luma) }
    }
    fun record(frame: VideoOcrFrame, recognized: T?) {
        referenceFrame = frame.takeIf { recognized != null }
        reading = recognized
    }
}
