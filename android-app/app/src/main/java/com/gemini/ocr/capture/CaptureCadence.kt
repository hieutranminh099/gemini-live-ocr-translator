package com.gemini.ocr.capture

/** Shared scheduling policy for the producer and its diagnostic trace. No capture or OCR work. */
object CaptureCadence {
    fun minSpacingMs(turbo: Boolean): Long = if (turbo) 50L else 75L

    /**
     * The periodic wait, including the existing backoff for a slow OCR consumer. Wakeup hints
     * can shorten this wait; [minSpacingMs] still bounds successive hinted captures.
     */
    fun periodMs(
        interval: Long,
        subtitleFocus: Boolean,
        standaloneActive: Boolean,
        turbo: Boolean,
        oneFrame: Boolean,
        ocrMs: Long,
        awaitingConfirmation: Boolean = false,
    ): Long {
        // A fast reply may consume its wakeup on an older in-flight image. Do not
        // carry the previous OCR backoff into the next fresh-image confirmation.
        // Capture still uses one mailbox and one OCR consumer, at bounded spacing.
        if (!oneFrame && awaitingConfirmation) return minSpacingMs(turbo)
        val fastAuto = (subtitleFocus || (standaloneActive && turbo)) && !oneFrame
        val basePeriod = (if (fastAuto) minOf(interval, if (turbo) 65L else 100L) else interval)
            .coerceAtLeast(minSpacingMs(turbo))
        return maxOf(basePeriod, ocrMs * 3 / 2)
    }
}
