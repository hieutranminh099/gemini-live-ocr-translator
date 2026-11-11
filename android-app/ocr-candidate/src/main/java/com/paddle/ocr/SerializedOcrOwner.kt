package com.paddle.ocr

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one native session's lifetime; cancellation discards output after native work returns. */
internal class SerializedOcrOwner<Input, Output>(
    private val recognizeNative: suspend (Input) -> Output,
    private val releaseNative: suspend () -> Unit,
) {
    private val gate = Mutex()
    private val closing = AtomicBoolean(false)
    private var released = false // Access only while gate is held.

    suspend fun recognize(input: Input): Output {
        currentCoroutineContext().ensureActive()
        check(!closing.get()) { "OCR session is closing or closed" }
        return gate.withLock {
            currentCoroutineContext().ensureActive()
            check(!closing.get() && !released) { "OCR session is closing or closed" }
            // Native session.run cannot be interrupted. Keep the gate until native work completes.
            val output = withContext(NonCancellable) { recognizeNative(input) }
            currentCoroutineContext().ensureActive()
            output
        }
    }

    suspend fun release() = withContext(NonCancellable) {
        // Refuse new work immediately, including callers queued before release acquired the gate.
        closing.set(true)
        gate.withLock {
            if (!released) {
                released = true
                releaseNative()
            }
        }
    }
}
