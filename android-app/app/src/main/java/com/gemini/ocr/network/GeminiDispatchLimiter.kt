package com.gemini.ocr.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.ArrayDeque

/** Local process budget. Calls from another app/device still share the provider's project quota. */
class GeminiDispatchLimiter(
    private val maximum: Int = 15,
    private val windowMs: Long = 61_000L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val reservations = ArrayDeque<Long>()
    private var lastClock = Long.MIN_VALUE
    init { require(maximum > 0 && windowMs > 0) }

    /** Waiting is cancellable. A dispatched/cancelled HTTP call never refunds a reserved slot. */
    suspend fun awaitTurn(onWait: suspend (Long) -> Unit = {}): Long {
        val started = clockMs()
        mutex.lock()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = maxOf(clockMs(), lastClock)
                lastClock = now
                while (reservations.peekFirst()?.let { now - it >= windowMs } == true) reservations.removeFirst()
                if (reservations.size < maximum) {
                    reservations.addLast(now)
                    return (clockMs() - started).coerceAtLeast(0)
                }
                val remaining = (windowMs - (now - reservations.first)).coerceAtLeast(1)
                onWait(remaining)
                sleepMs(remaining)
            }
        } finally { mutex.unlock() }
    }
}
