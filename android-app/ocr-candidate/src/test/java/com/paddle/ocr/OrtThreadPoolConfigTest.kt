package com.paddle.ocr

import org.junit.Assert.*
import org.junit.Test

class OrtThreadPoolConfigTest {
    @Test fun baselineConfigurationStaysExplicitlyEnabledAndExperimentalSleepIsSessionLocal() {
        val baseline = EngineConfig(numThreads = 2)
        val sleeping = baseline.copy(allowSpinning = false)
        assertTrue(baseline.allowSpinning)
        assertEquals(2, sleeping.numThreads)
        assertEquals(setOf("session.intra_op.allow_spinning", "session.inter_op.allow_spinning"),
            OrtThreadPoolConfig.entries(false).keys)
        assertTrue(OrtThreadPoolConfig.entries(sleeping.allowSpinning).values.all { it == "0" })
        assertTrue(OrtThreadPoolConfig.entries(baseline.allowSpinning).values.all { it == "1" })
    }
}
