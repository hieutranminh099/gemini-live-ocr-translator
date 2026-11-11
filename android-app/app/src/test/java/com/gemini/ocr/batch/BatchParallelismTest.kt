package com.gemini.ocr.batch

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchParallelismTest {
    private val mib = 1024L * 1024

    @Test fun legacyAndUnknownModesRetainSequentialExecution() {
        val original = BatchProject("batch", "Series", BatchKind.SERIES)
        val old = original.json().apply { remove("executionMode") }
        assertEquals(BatchExecutionMode.SEQUENTIAL, BatchProject.from(old).executionMode)
        old.put("executionMode", "FUTURE_MODE")
        assertEquals(BatchExecutionMode.SEQUENTIAL, BatchProject.from(old).executionMode)
    }

    @Test fun bothMemoryModesRoundTripWithoutChangingOtherBatchState() {
        val original = BatchProject("batch", "Series", BatchKind.SERIES,
            items = listOf(BatchItem("project", "EP01.mp4", state = "DONE")),
            state = "REVIEW", retryAfterMs = 12345, autoRetryAttempts = 2)
        for (mode in listOf(BatchExecutionMode.RAM_8_GB, BatchExecutionMode.RAM_16_GB)) {
            val expected = original.copy(executionMode = mode)
            assertEquals(expected, BatchProject.from(expected.json()))
        }
    }

    @Test fun selectedModeAndCpuBoundTheMaximumNumberOfWorkers() {
        assertEquals(1, limit(BatchExecutionMode.SEQUENTIAL))
        assertEquals(2, limit(BatchExecutionMode.RAM_8_GB))
        assertEquals(8, limit(BatchExecutionMode.RAM_16_GB))
        assertEquals(4, limit(BatchExecutionMode.RAM_16_GB, processors = 4))
        assertEquals(1, limit(BatchExecutionMode.RAM_16_GB, processors = 0))
    }

    @Test fun memoryPressureReducesAdmissionWithoutDisablingTheBatch() {
        assertEquals(1, limit(BatchExecutionMode.RAM_16_GB, lowMemory = true))
        assertEquals(1, limit(BatchExecutionMode.RAM_16_GB, availableMemory = 0))
        assertEquals(2, limit(BatchExecutionMode.RAM_16_GB, availableMemory = 2048 * mib))
        assertEquals(2, limit(BatchExecutionMode.RAM_16_GB, heapLimit = 64 * mib))
        assertEquals(1, limit(BatchExecutionMode.RAM_16_GB, heapLimit = 0))
    }

    private fun limit(mode: BatchExecutionMode, processors: Int = 16,
                      availableMemory: Long = 16 * 1024 * mib,
                      heapLimit: Long = 512 * mib, lowMemory: Boolean = false): Int =
        BatchParallelism.workerLimit(mode, processors, availableMemory, heapLimit, lowMemory)
}
