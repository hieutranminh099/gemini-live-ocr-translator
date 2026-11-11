package com.gemini.ocr.batch

import android.app.ActivityManager
import android.content.Context

enum class BatchExecutionMode(val label: String) {
    SEQUENTIAL("Tuần tự"),
    RAM_8_GB("8 GB"),
    RAM_16_GB("16 GB");

    companion object {
        fun fromPersisted(value: String): BatchExecutionMode = entries.firstOrNull { it.name == value } ?: SEQUENTIAL
    }
}

/** Admission limits for independent text timelines. Media decoding and export use their existing lane. */
internal object BatchParallelism {
    fun memory(context: Context): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    private const val MIB = 1024L * 1024

    fun workerLimit(context: Context, mode: BatchExecutionMode): Int {
        val memory = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.getMemoryInfo(memory)
        return workerLimit(mode, Runtime.getRuntime().availableProcessors(), memory.availMem,
            Runtime.getRuntime().maxMemory(), memory.lowMemory)
    }

    internal fun workerLimit(mode: BatchExecutionMode, processors: Int, availableMemory: Long,
                             heapLimit: Long, lowMemory: Boolean): Int {
        if (lowMemory || mode == BatchExecutionMode.SEQUENTIAL) return 1
        val requested = if (mode == BatchExecutionMode.RAM_8_GB) 2 else 8
        val systemCapacity = ((availableMemory - 256 * MIB).coerceAtLeast(0) / (768 * MIB)).coerceIn(1, 8).toInt()
        val heapCapacity = (heapLimit / (32 * MIB)).coerceIn(1, 8).toInt()
        return minOf(requested, processors.coerceAtLeast(1), systemCapacity, heapCapacity)
    }
}

/** Reductions are immediate; increases require stable resources for two seconds. */
internal class BatchResourceAdmission(
    private val context: Context, private val mode: BatchExecutionMode, private val ceiling: Int,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var admitted = ceiling
    private var candidate = ceiling
    private var candidateSince = nowMs()
    private var sampleAt = Long.MIN_VALUE
    private var memoryLimit = ceiling
    private var overlap = false

    @Synchronized fun snapshot(): BatchEpisodePipeline.Admission {
        val now = nowMs()
        if (sampleAt == Long.MIN_VALUE || now - sampleAt >= 1_000L) {
            val memory = BatchParallelism.memory(context)
            val processors = Runtime.getRuntime().availableProcessors()
            val heap = Runtime.getRuntime().maxMemory()
            memoryLimit = minOf(ceiling, BatchParallelism.workerLimit(mode, processors, memory.availMem, heap, memory.lowMemory))
            // A single configured API worker can overlap local extraction on a capable device.
            overlap = BatchParallelism.workerLimit(BatchExecutionMode.RAM_16_GB, processors,
                memory.availMem, heap, memory.lowMemory) > 1 && !memory.lowMemory
            sampleAt = now
        }
        val playback = BatchPlaybackPriority.current.restricted()
        val requested = if (playback) 1 else memoryLimit
        if (requested != candidate) { candidate = requested; candidateSince = now }
        if (requested < admitted || now - candidateSince >= 2_000L) admitted = requested
        return BatchEpisodePipeline.Admission(admitted, overlap && !playback, extractAllowed = !playback)
    }
}
