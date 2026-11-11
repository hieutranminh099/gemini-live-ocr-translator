package com.gemini.ocr.network

import kotlinx.coroutines.CancellationException

/** A cache disk error must not crash the capture service after its translation was drawn. */
object BackgroundPersistence {
    suspend fun run(write: suspend () -> Unit, failed: (Exception) -> Unit) {
        try { write() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(error) }
    }
}
