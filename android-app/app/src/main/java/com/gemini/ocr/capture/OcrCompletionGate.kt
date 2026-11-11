package com.gemini.ocr.capture

/**
 * A completed OCR observation belongs to its capture session and source-image time.
 * A newer ROI with different pixels can be moving scenery behind the same subtitle;
 * only a subsequent OCR observation establishes a different text generation.
 */
object OcrCompletionGate {
    data class Decision(val rejection: String?, val pixelsAdvanced: Boolean) {
        val canPublish: Boolean get() = rejection == null
    }

    fun afterNative(
        capturedEpoch: Long,
        currentEpoch: Long,
        blocked: Boolean,
        oneFrame: Boolean,
        responseAt: Long,
        sourceImageAt: Long,
        completedFrame: OcrFrameFingerprint,
        latestFrame: OcrFrameFingerprint?,
    ): Decision {
        val rejection = when {
            !ResponseFrameGate.canConfirm(oneFrame, responseAt, sourceImageAt) -> "pre_response_image"
            capturedEpoch != currentEpoch || blocked -> "capture_epoch"
            else -> null
        }
        return Decision(rejection, completedFrame.supersededByPixelChange(latestFrame))
    }
}
