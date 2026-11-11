package com.gemini.ocr.capture

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PipelineTraceObserverTest {
    @After fun cleanup() { PipelineTrace.observerForTest = null; ShadowLog.clear() }
    @Test fun observerKeepsEarlierEventsWhenTheLogBufferIsCleared() {
        ShadowLog.clear()
        val observed = mutableListOf<String>()
        PipelineTrace.observerForTest = { observed.add(it) }
        PipelineTrace.event("request_sent", "request_id" to "first")
        ShadowLog.clear()
        PipelineTrace.event("result_received", "request_id" to "first")
        assertEquals(2, observed.size)
        assertEquals("request_sent", JSONObject(observed.first()).getString("event"))
        assertEquals(1, ShadowLog.getLogsForTag("OCRPipeline").size)
    }
    @Test fun failingObserverDoesNotBreakOrSuppressThePipelineEvent() {
        ShadowLog.clear()
        PipelineTrace.observerForTest = { throw IllegalStateException("synthetic observer failure") }
        PipelineTrace.event("source_changed", "empty" to true)
        assertTrue(JSONObject(ShadowLog.getLogsForTag("OCRPipeline").single().msg).getBoolean("empty"))
    }
}
