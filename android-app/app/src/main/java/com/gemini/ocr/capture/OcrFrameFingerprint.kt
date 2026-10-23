package com.gemini.ocr.capture

/** A pixel hash is comparable only when both frames sampled exactly the same rectangle. */
data class OcrFrameFingerprint(
    val frameId: Long,
    val crc: Long,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun supersededByPixelChange(latest: OcrFrameFingerprint?): Boolean =
        latest != null && latest.frameId > frameId &&
            left == latest.left && top == latest.top && right == latest.right && bottom == latest.bottom &&
            crc != latest.crc
}
