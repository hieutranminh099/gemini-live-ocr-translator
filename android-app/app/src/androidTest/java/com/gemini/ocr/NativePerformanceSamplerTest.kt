package com.gemini.ocr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePerformanceSamplerTest {
    @Test fun androidProcessSamplerPublishesOnlyRealProcessCountersOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gemini.ocr.codexbench" && !SessionState.running.value)
        val telemetry = AndroidPerformanceTelemetry.counters
        telemetry.setEnabled(false); telemetry.setEnabled(true)
        try {
            delay(1100)
            assertTrue(AndroidPerformanceTelemetry.sampleIfDue())
            assertNotNull(telemetry.snapshot.value.sampledAtMs)
            assertNotNull(telemetry.snapshot.value.pssKiB)
            delay(1100)
            assertTrue(AndroidPerformanceTelemetry.sampleIfDue())
            assertNotNull(telemetry.snapshot.value.cpuOneCorePercent)
            assertTrue(telemetry.snapshot.value.pssKiB!! > 0)
        } finally { telemetry.setEnabled(false) }
        Unit
    }
}
