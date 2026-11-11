package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class ResponseFrameGateTest {
    @Test fun aNewSnapshotOfOldHardwareCannotConfirmTheReply() {
        assertEquals(100L, ResponseFrameGate.requiredImageAt(false, 100, 99))
        assertFalse(ResponseFrameGate.canConfirm(false, 100, 99))
        assertTrue(ResponseFrameGate.canConfirm(false, 100, 100))
        assertTrue(ResponseFrameGate.canConfirm(false, 100, 101))
    }
    @Test fun acceptedFreshImageEndsTheForcedConfirmationCapture() {
        assertNull(ResponseFrameGate.requiredImageAt(false, 100, 100))
        assertNull(ResponseFrameGate.requiredImageAt(false, 100, 101))
        assertEquals(200L, ResponseFrameGate.requiredImageAt(false, 200, 101))
    }
    @Test fun explicitFrameAndNoReplyKeepTheirOriginalSemantics() {
        assertNull(ResponseFrameGate.requiredImageAt(true, 100, 90))
        assertTrue(ResponseFrameGate.canConfirm(true, 100, 90))
        assertNull(ResponseFrameGate.requiredImageAt(false, 0, 0))
        assertTrue(ResponseFrameGate.canConfirm(false, 0, 0))
    }
}
