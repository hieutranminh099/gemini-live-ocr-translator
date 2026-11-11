package com.gemini.ocr.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiGenerationMetricsTest {
    @Test fun keepsNumericUsageWithoutRetainingResponseContent() {
        val metrics = GeminiGenerationMetrics.fromResponse("""{"usageMetadata":{"promptTokenCount":123,"candidatesTokenCount":15,"thoughtsTokenCount":0,"cachedContentTokenCount":20},"private":"must not escape"}""")!!
        assertEquals(123, metrics.inputTokens)
        assertEquals(15, metrics.outputTokens)
        assertEquals(0, metrics.thinkingTokens)
        assertEquals(20, metrics.cachedInputTokens)
        assertFalse(metrics.toString().contains("must not escape"))
    }
    @Test fun absentOrInvalidMetricsStayUnknownInsteadOfZero() {
        assertNull(GeminiGenerationMetrics.fromResponse("not JSON"))
        assertNull(GeminiGenerationMetrics.fromResponse("{}"))
        val metrics = GeminiGenerationMetrics.fromResponse("""{"usageMetadata":{"promptTokenCount":-3}}""")!!
        assertNull(metrics.inputTokens); assertNull(metrics.outputTokens)
        assertNull(metrics.thinkingTokens); assertNull(metrics.cachedInputTokens)
    }
}
