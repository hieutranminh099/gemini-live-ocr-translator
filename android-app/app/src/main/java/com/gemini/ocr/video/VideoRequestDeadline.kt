package com.gemini.ocr.video

import kotlinx.coroutines.withTimeout

/** Staged text transport already has its own bounded HTTP timeout and attempt limit.
 * Admission, Live reservations and a provider's Retry-After must remain cancellable waits,
 * rather than consume that transport deadline before a request can be sent. */
internal object VideoRequestDeadline {
    suspend fun <T> run(
        fullDialogue: Boolean,
        staged: Boolean,
        economy: Boolean,
        timeoutMs: Long = if (fullDialogue) VideoFullDialoguePlan.REQUEST_TIMEOUT_MS else 45_000L,
        operation: suspend () -> T
    ): T = if (staged && !economy) operation()
        else if (fullDialogue || !staged) withTimeout(timeoutMs) { operation() }
        else operation()
}
