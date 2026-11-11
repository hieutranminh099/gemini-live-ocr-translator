package com.gemini.ocr

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional process telemetry. A service-owned coroutine may call sampleIfDue once per second;
 * cancelling that coroutine and disabling counters must accompany session shutdown. This object
 * creates no timer, extra capture worker, frame queue, database, or persistent diagnostics.
 */
object AndroidPerformanceTelemetry {
    val counters = PerformanceTelemetry(SystemClock::elapsedRealtime) {
        val cpu = Process.getElapsedCpuTime()
        val cpuSampleAt = SystemClock.elapsedRealtime()
        val pss = try {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
        } catch (_: Exception) { null }
        PerformanceProcessCounters(cpu, cpuSampleAt, pss)
    }

    val snapshot = counters.snapshot

    /** PSS is read only by this worker path and only after the core's one-second reservation. */
    suspend fun sampleIfDue(): Boolean = withContext(Dispatchers.Default) { counters.sampleIfDue() }
}
