package com.gemini.ocr.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal data class VideoRequestCapacity(val limit: Int, val ceiling: Int)

/** Batch-local admission only: provider/account cooldowns remain authoritative in their owners. */
internal class VideoAdaptiveAdmission(private val ceiling: Int, private val capacityLimit: () -> Int = { 8 }) {
    init { require(ceiling in 1..8) }
    private val lock = Any()
    private var limit = ceiling
    private var active = 0
    private var generation = 0L
    private var successes = 0
    private var changed = CompletableDeferred<Unit>()

    private fun effectiveLimit() = minOf(limit, capacityLimit().coerceIn(1, ceiling))
    fun snapshot(): VideoRequestCapacity = synchronized(lock) { VideoRequestCapacity(effectiveLimit(), ceiling) }

    suspend fun <T> withPermit(block: suspend (Long) -> T): T {
        val admitted = acquire()
        try {
            currentCoroutineContext().ensureActive()
            return block(admitted)
        } finally {
            synchronized(lock) {
                active--
                signal()
            }
        }
    }

    suspend fun <T> execute(block: suspend () -> T): T = withPermit { admitted ->
        try { block().also { succeeded(admitted) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: IOException) {
            failed(if (error is VideoApiException) error else VideoApiRecovery.networkFailure(error))
            throw error
        }
    }

    fun succeeded(admitted: Long) = synchronized(lock) {
        // Responses admitted before an outage must not immediately undo its reduction.
        if (admitted == generation && ++successes >= 4) {
            successes = 0
            if (limit < ceiling) {
                limit++
                signal()
            }
        }
    }

    fun failed(failure: VideoApiException) = synchronized(lock) {
        if (failure.transient && failure.code in setOf("API_RATE_LIMIT", "API_NETWORK", "API_TIMEOUT",
                "API_SERVICE_408", "API_SERVICE_500", "API_SERVICE_502", "API_SERVICE_503", "API_SERVICE_504")) {
            limit = maxOf(1, limit / 2)
            successes = 0
            generation++
        }
    }

    private suspend fun acquire(): Long {
        while (true) {
            currentCoroutineContext().ensureActive()
            val waiting = synchronized(lock) {
                if (active < effectiveLimit()) {
                    active++
                    return generation
                }
                changed
            }
            // Playback may stop without an API completion to signal this gate.
            withTimeoutOrNull(250) { waiting.await() }
        }
    }

    private fun signal() {
        changed.complete(Unit)
        changed = CompletableDeferred()
    }
}
