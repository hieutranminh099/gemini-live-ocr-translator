package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class FrameTimelineTest {
    private val source = FrameTimeline.Source("region", 2, 3, 1000, 5, 7, 40, 1054)
    private val token = SubtitlePipeline.RequestToken("request", "region", 2, "source")
    private fun values(draw: FrameTimeline.Draw, at: Long) = draw.fields(at).toMap()

    @Test fun additiveStagesRecoverTheWholeFrameToCanvasInterval() {
        val timing = FrameTimeline(); timing.observed(source)
        assertTrue(timing.requested(token, 1140)); assertTrue(timing.received(token, 1800))
        val measured = values(timing.drawing(1810)!!, 1826)
        val sum = listOf("capture_ms", "ocr_queue_ms", "ocr_ms", "pre_request_ms",
            "request_to_result_ms", "result_to_ready_ms", "overlay_ms").sumOf { measured[it] as Long }
        assertEquals(826L, sum); assertEquals(sum, measured["observed_to_draw_ms"])
        assertEquals(86L, measured["stabilization_ms"])
        assertEquals(26L, measured["received_to_draw_ms"])
    }
    @Test fun unchangedSourceKeepsTheInitialFramesMeasurements() {
        val timing = FrameTimeline(); timing.observed(source)
        timing.requested(token, 1140); timing.received(token, 1800)
        assertEquals(40L, values(timing.drawing(1810)!!, 1826)["ocr_ms"])
        assertEquals(3L, values(timing.drawing(1810)!!, 1826)["source_frame_id"])
    }
    @Test fun staleRequestAndRegionCannotSupplyAReceiptTimestamp() {
        val timing = FrameTimeline(); timing.observed(source); timing.requested(token, 1140)
        assertFalse(timing.received(token.copy(requestId = "old"), 1700))
        assertFalse(timing.received(token.copy(regionId = "old"), 1700))
        assertNull(values(timing.drawing(1710)!!, 1720)["received_to_draw_ms"])
    }
    @Test fun sourceChangeInvalidatesTheOldTimelineIncludingReturningText() {
        val timing = FrameTimeline(); timing.observed(source); timing.requested(token, 1140)
        timing.observed(source.copy(generation = 4, frameId = 9))
        assertFalse(timing.received(token, 1800)); assertFalse(timing.requested(token, 1801))
        assertNull(values(timing.drawing(1810)!!, 1820)["request_to_result_ms"])
    }
    @Test fun cacheDrawDoesNotInventAZeroMillisecondNetworkReceipt() {
        val timing = FrameTimeline(); timing.observed(source)
        val measured = values(timing.drawing(1060)!!, 1076)
        assertNull(measured["request_to_result_ms"]); assertNull(measured["received_to_draw_ms"])
        assertEquals(76L, measured["observed_to_draw_ms"])
    }
    @Test fun resetAndRetryDiscardThePreviousReceipt() {
        val timing = FrameTimeline(); timing.observed(source); timing.requested(token, 1140)
        timing.received(token, 1800); timing.requested(token.copy(requestId = "retry"), 1810)
        assertNull(timing.drawing(1820)!!.receivedAt)
        timing.clear(); assertNull(timing.drawing(1900)); assertFalse(timing.requested(token, 1901))
    }
}
