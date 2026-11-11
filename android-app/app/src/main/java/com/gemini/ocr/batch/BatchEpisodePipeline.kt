package com.gemini.ocr.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bounded episode orchestration. Only project IDs cross the channel; callbacks own durable
 * checkpoints and must return only after saving them. This class owns no video or cue buffers.
 * A failure callback decides whether an error is local (return) or shared/fatal (throw).
 */
internal class BatchEpisodePipeline(
    private val workers: Int,
    private val admission: () -> Admission = { Admission(workers, true) },
    private val waitForCapacity: suspend () -> Unit = { delay(100) }
) {
    data class Admission(val textWorkers: Int, val overlapExtraction: Boolean, val suspended: Boolean = false,
                         val extractAllowed: Boolean = true)

    init { require(workers in 1..8) }

    suspend fun run(
        projectIds: List<String>,
        extract: suspend (String) -> Boolean,
        translateValidateSave: suspend (String) -> Unit,
        onFailure: suspend (String, Exception) -> Unit,
        requiresExtraction: suspend (String) -> Boolean = { true }
    ) = coroutineScope {
        require(projectIds.all { it.isNotBlank() } && projectIds.distinct().size == projectIds.size) {
            "Each episode must have a unique non-empty project ID"
        }
        if (projectIds.isEmpty()) return@coroutineScope

        // Admission belongs to this run, so cancellation cannot leak counters into a restart.
        val lock = Mutex()
        var extracting = false
        var translating = 0
        suspend fun <T> admitted(media: Boolean, operation: suspend () -> T): T {
            while (true) {
                currentCoroutineContext().ensureActive()
                val allowed = lock.withLock {
                    val policy = admission()
                    if (policy.suspended) false
                    else if (media) {
                        (policy.extractAllowed && !extracting && (policy.overlapExtraction || translating == 0)).also {
                            if (it) extracting = true
                        }
                    } else {
                        (translating < policy.textWorkers.coerceIn(1, workers) &&
                            (policy.overlapExtraction || !extracting)).also {
                            if (it) translating++
                        }
                    }
                }
                if (allowed) break
                waitForCapacity()
            }
            try {
                currentCoroutineContext().ensureActive()
                return operation()
            } finally {
                withContext(NonCancellable) {
                    lock.withLock { if (media) extracting = false else translating-- }
                }
            }
        }
        suspend fun prepare(id: String): Boolean = try {
            if (requiresExtraction(id)) admitted(true) { extract(id) } else extract(id)
        } catch (cancel: CancellationException) {
            // A cancelled child must also stop the producer; otherwise its rendezvous send can hang.
            this@coroutineScope.cancel(cancel)
            throw cancel
        }
        catch (failure: Exception) { onFailure(id, failure); false }

        suspend fun finish(id: String) {
            try { admitted(false) { translateValidateSave(id) } }
            catch (cancel: CancellationException) {
            // A cancelled child must also stop the producer; otherwise its rendezvous send can hang.
            this@coroutineScope.cancel(cancel)
            throw cancel
        }
            catch (failure: Exception) { onFailure(id, failure) }
        }

        // The first episode reaches a text worker before extraction of the next one starts.
        // Do not wait for its network response before using an otherwise idle extraction lane.
        val first = projectIds.first()
        val firstReady = prepare(first)

        // Rendezvous limits the backlog to IDs held by text workers plus the extractor's ID.
        // In particular, a slow API cannot make us load every episode's timeline in RAM.
        val ready = Channel<String>(Channel.RENDEZVOUS)
        repeat(workers) { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                if (index == 0 && firstReady) finish(first)
                for (id in ready) finish(id)
            }
        }
        launch {
            try {
                for (id in projectIds.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    if (prepare(id)) ready.send(id)
                }
            } finally { ready.close() }
        }
    }
}
