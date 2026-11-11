package com.gemini.ocr.capture

/** A new snapshot time cannot make a retained pre-response hardware image fresh. */
object ResponseFrameGate {
    fun requiredImageAt(oneFrame: Boolean, responseAt: Long, lastAcceptedImageAt: Long): Long? =
        responseAt.takeIf { !oneFrame && it > 0 && lastAcceptedImageAt < it }

    fun canConfirm(oneFrame: Boolean, responseAt: Long, imageAt: Long): Boolean =
        oneFrame || responseAt <= 0 || imageAt >= responseAt
}
