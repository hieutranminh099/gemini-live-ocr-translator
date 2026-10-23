package com.gemini.ocr.capture

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A capture hint, never a frame queue or another OCR worker. Multiple hints coalesce into one
 * pending wakeup, and consuming it restores the normal timed wait. The service retains its
 * minimum capture spacing and checks the current screen generation before creating a hint.
 */
class CaptureWakeup {
    enum class Reason { RESPONSE, SETTLED_SOURCE, EXPLICIT_FRAME }

    private val hints = Channel<Reason>(Channel.CONFLATED)

    fun request(reason: Reason) { hints.trySend(reason) }

    suspend fun awaitNext(periodMs: Long): Reason? {
        require(periodMs > 0)
        return withTimeoutOrNull(periodMs) { hints.receive() }
    }

    /** Pausing/resetting must not carry an old source's hint into the next session. */
    fun reset() { hints.tryReceive() }
}
