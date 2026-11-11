package com.gemini.ocr.network

import com.gemini.ocr.SessionState

/** Recheck on callback delivery, including when a document picker outlives its screen. */
object MemoryReviewWriteGuard {
    fun allowed(uiRunning: Boolean): Boolean = !uiRunning && !SessionState.running.value
    suspend fun <T> run(uiRunning: () -> Boolean, action: suspend () -> T): T {
        check(allowed(uiRunning())) { "Stop capture before changing reviewed memory" }
        return action()
    }
}
