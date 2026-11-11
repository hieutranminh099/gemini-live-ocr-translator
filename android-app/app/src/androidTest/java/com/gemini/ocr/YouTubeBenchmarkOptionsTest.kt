package com.gemini.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBenchmarkOptionsTest {
    @Test fun eitherExplicitPerformanceFlagEnablesOneSampler() {
        assertTrue(YouTubeBenchmarkOptions.performanceRequested("true", null))
        assertTrue(YouTubeBenchmarkOptions.performanceRequested(null, "true"))
        assertTrue(YouTubeBenchmarkOptions.performanceRequested("true", "false"))
        assertTrue(YouTubeBenchmarkOptions.performanceRequested("true", "true"))
    }
    @Test fun absentOrUnrecognizedFlagsDoNotStartProcessSampling() {
        assertFalse(YouTubeBenchmarkOptions.performanceRequested(null, null))
        assertFalse(YouTubeBenchmarkOptions.performanceRequested("false", null))
        assertFalse(YouTubeBenchmarkOptions.performanceRequested("TRUE", "yes"))
    }
}
