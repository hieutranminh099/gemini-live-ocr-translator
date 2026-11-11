package com.gemini.ocr.mtprobe

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** One native decoder owner; cancellation can stop between steps, never during session.run. */
internal class Mt60NativeOwner<Input, Output>(
    private val executeNative: suspend (Input, () -> Unit) -> Output,
    private val closeNative: suspend () -> Unit,
) {
    private val gate = Mutex()
    private val closing = AtomicBoolean(false)
    private var closed = false // Access only under the gate.

    suspend fun execute(input: Input): Output {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        check(!closing.get()) { "Translation candidate is closing or closed" }
        return gate.withLock {
            caller.ensureActive()
            check(!closing.get() && !closed) { "Translation candidate is closing or closed" }
            val result = withContext(NonCancellable) { executeNative(input) { caller.ensureActive() } }
            caller.ensureActive()
            result
        }
    }

    suspend fun release() = withContext(NonCancellable) {
        closing.set(true) // Reject already queued requests before close acquires the gate.
        gate.withLock {
            if (!closed) {
                closed = true
                closeNative()
            }
        }
    }
}
