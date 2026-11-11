package com.gemini.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Counts actual operations, not the refresh rate of the video or display. */
enum class PerformanceFrameStage {
    /** One non-null Image acquired from ImageReader, including images later replaced in the mailbox. */
    CAPTURE_ACQUIRED,
    /** One completed native OCR pass. Reusing an OCR answer does not increment this counter. */
    OCR_COMPLETED,
    /** One real overlay renderer draw, including redraws of unchanged content. */
    CANVAS_DRAWN,
}

/** Each tier has its own denominator. Never combine a batch count with a source-line count. */
enum class PerformanceCacheTier(val lookupUnit: String) {
    PHONE_EXACT("source_line"),
    REVIEWED_MEMORY("source_line"),
    DIRECT_LRU("source_line"),
    LEGACY_BANK("request_batch"),
}

/** Explicit origin of the last accepted translation that reached Canvas; never inferred from latency. */
enum class PerformanceTranslationSource {
    PHONE_CACHE, REVIEWED_MEMORY, GLOSSARY, LEGACY_BANK, ON_DEVICE, GEMINI, WINDOWS, MIXED, UNKNOWN,
}

data class PerformanceFrameMetric(val completed: Long, val perSecond: Double?)

data class PerformanceCacheMetric(val hits: Long, val misses: Long) {
    /** Completed lookup attempts only. No attempt means unknown, not a 0% hit rate. */
    val hitRate: Double?
        get() = if (hits == 0L && misses == 0L) null else hits.toDouble() / (hits.toDouble() + misses.toDouble())
}

/** A sampler must provide process CPU time, not total device CPU or wall time spent awaiting work. */
data class PerformanceProcessCounters(
    val cpuTimeMs: Long? = null,
    val cpuSampleAtMs: Long? = null,
    val pssKiB: Long? = null,
)

data class PerformanceSnapshot(
    val enabled: Boolean = false,
    val sampledAtMs: Long? = null,
    val windowMs: Long? = null,
    val frames: Map<PerformanceFrameStage, PerformanceFrameMetric> = emptyMap(),
    val caches: Map<PerformanceCacheTier, PerformanceCacheMetric> = emptyMap(),
    /** 100% means one logical CPU fully occupied. Multithreaded process usage can exceed 100%. */
    val cpuOneCorePercent: Double? = null,
    val cpuWindowMs: Long? = null,
    /** A process proportional-set-size snapshot; neither peak RAM nor the Java heap size. */
    val pssKiB: Long? = null,
    val lastVisibleTranslationSource: PerformanceTranslationSource? = null,
    val resourceSampleFailed: Boolean = false,
)

/**
 * Opt-in, constant-space telemetry. Frame hooks only increment atomics; they never sample PSS,
 * publish a StateFlow value, allocate a per-frame record, retain text, or perform database I/O.
 *
 * Call [sampleIfDue] on a worker at roughly 1 Hz. It reserves at most one resource sample per
 * 1000 monotonic milliseconds and rejects concurrent sampling. Counters still accumulate if
 * the caller is delayed: rates divide by the actual interval, not an assumed one-second tick.
 * Disabling/re-enabling creates an independent counter set, so an old in-flight sample cannot
 * republish data from the previous opt-in session. No sample history is retained.
 */
class PerformanceTelemetry(
    private val monotonicMs: () -> Long,
    private val sampleProcess: () -> PerformanceProcessCounters,
) {
    private class Window(val startedAtMs: Long) {
        val frames = Array(PerformanceFrameStage.entries.size) { AtomicLong() }
        val caches = Array(PerformanceCacheTier.entries.size) { AtomicReference(PerformanceCacheMetric(0, 0)) }
        val source = AtomicReference<PerformanceTranslationSource?>(null)
        var lastAttemptAtMs = startedAtMs
        var lastPublishedAtMs = startedAtMs
        var previousFrames = LongArray(PerformanceFrameStage.entries.size)
        var previousCpu: PerformanceProcessCounters? = null
    }

    private val gate = Any()
    @Volatile private var active: Window? = null
    private var sampling = false // Access only with gate held.
    private val mutableSnapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = mutableSnapshot.asStateFlow()

    fun setEnabled(enabled: Boolean) = synchronized(gate) {
        if (enabled == (active != null)) return@synchronized
        active = if (enabled) Window(monotonicMs()) else null
        mutableSnapshot.value = PerformanceSnapshot(enabled = enabled)
    }

    fun recordFrame(stage: PerformanceFrameStage) {
        active?.frames?.get(stage.ordinal)?.incrementAndGet()
    }

    /** Call only after the attempted lookups finish; cancelled/unattempted work is not a miss. */
    fun recordCacheLookups(tier: PerformanceCacheTier, hits: Int, misses: Int) {
        require(hits >= 0 && misses >= 0)
        val window = active ?: return
        if (hits == 0 && misses == 0) return
        // Publish the completed attempt group atomically: a sampler must not see only its hits.
        window.caches[tier.ordinal].updateAndGet {
            PerformanceCacheMetric(it.hits + hits.toLong(), it.misses + misses.toLong())
        }
    }

    /** Call from the existing guarded on-drawn callback, not when dispatching or receiving a reply. */
    fun recordVisibleTranslation(source: PerformanceTranslationSource) {
        active?.source?.set(source)
    }

    /** Returns true only when a new snapshot from the currently enabled session was published. */
    fun sampleIfDue(): Boolean {
        val window = synchronized(gate) {
            val current = active ?: return false
            if (sampling) return false
            val now = monotonicMs()
            if (now < current.lastAttemptAtMs || now < current.lastPublishedAtMs) {
                // Defensive clock reset: do not produce negative rates or an immediate CPU spike.
                current.lastAttemptAtMs = now
                current.lastPublishedAtMs = now
                current.previousFrames = LongArray(current.frames.size) { current.frames[it].get() }
                current.previousCpu = null
                return false
            }
            if (now - current.lastAttemptAtMs < 1000L) return false
            current.lastAttemptAtMs = now
            sampling = true
            current
        }
        var failed = false
        val process = try {
            sampleProcess()
        } catch (_: Exception) {
            // Exception text is deliberately not retained: a platform probe is optional telemetry.
            failed = true
            PerformanceProcessCounters()
        } catch (fatal: Throwable) {
            synchronized(gate) { sampling = false }
            throw fatal
        }
        return synchronized(gate) {
            sampling = false
            if (active !== window) return@synchronized false
            val now = monotonicMs()
            val interval = now - window.lastPublishedAtMs
            if (interval <= 0L) {
                window.lastPublishedAtMs = now
                window.previousFrames = LongArray(window.frames.size) { window.frames[it].get() }
                window.previousCpu = null
                return@synchronized false
            }
            val totals = LongArray(window.frames.size) { window.frames[it].get() }
            val frameMetrics = PerformanceFrameStage.entries.associateWith { stage ->
                val index = stage.ordinal
                PerformanceFrameMetric(totals[index],
                    (totals[index] - window.previousFrames[index]).coerceAtLeast(0L).toDouble() * 1000.0 / interval)
            }
            val currentCpu = process.takeIf {
                it.cpuTimeMs != null && it.cpuTimeMs >= 0 && it.cpuSampleAtMs != null &&
                    it.cpuSampleAtMs >= window.startedAtMs && it.cpuSampleAtMs <= now
            }
            val previousCpu = window.previousCpu
            var cpuPercent: Double? = null
            var cpuInterval: Long? = null
            if (currentCpu != null && previousCpu != null) {
                val elapsed = checkNotNull(currentCpu.cpuSampleAtMs) - checkNotNull(previousCpu.cpuSampleAtMs)
                val used = checkNotNull(currentCpu.cpuTimeMs) - checkNotNull(previousCpu.cpuTimeMs)
                if (elapsed > 0 && used >= 0) {
                    cpuPercent = used.toDouble() * 100.0 / elapsed
                    cpuInterval = elapsed
                }
            }
            window.previousCpu = currentCpu
            window.previousFrames = totals
            window.lastPublishedAtMs = now
            mutableSnapshot.value = PerformanceSnapshot(
                enabled = true, sampledAtMs = now, windowMs = interval,
                frames = frameMetrics,
                caches = PerformanceCacheTier.entries.associateWith { tier ->
                    window.caches[tier.ordinal].get()
                },
                cpuOneCorePercent = cpuPercent, cpuWindowMs = cpuInterval,
                pssKiB = process.pssKiB?.takeIf { it >= 0 },
                lastVisibleTranslationSource = window.source.get(), resourceSampleFailed = failed,
            )
            true
        }
    }
}
