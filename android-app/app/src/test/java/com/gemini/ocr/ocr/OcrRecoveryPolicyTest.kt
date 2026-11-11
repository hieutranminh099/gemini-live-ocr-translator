package com.gemini.ocr.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrRecoveryPolicyTest {
    private fun candidate(text: String = "快快快进来进来", confidence: Double = .2,
        bounds: OcrRecoveryPolicy.Bounds? = OcrRecoveryPolicy.Bounds(100, 30, 300, 60)) =
        OcrRecoveryPolicy.Line(text, confidence, bounds)

    @Test fun cropPadsOnlyTheSuspectedLineAndUsesUniformTwoTimesScale() {
        val plan = OcrRecoveryPolicy.plan(640, 120, listOf(candidate()))!!
        assertEquals(OcrRecoveryPolicy.Bounds(94, 24, 306, 66), plan.crop)
        assertEquals(424, plan.targetWidth); assertEquals(84, plan.targetHeight)
        assertEquals(OcrRecoveryPolicy.Bounds(100, 30, 300, 60),
            plan.mapToSource(OcrRecoveryPolicy.Bounds(12, 12, 412, 72), 640, 120))
    }

    @Test fun recoveryMappingRoundsOutwardsAndClipsToSourceBounds() {
        val plan = OcrRecoveryPolicy.Plan(OcrRecoveryPolicy.Bounds(10, 20, 60, 50))
        assertEquals(OcrRecoveryPolicy.Bounds(10, 20, 15, 25),
            plan.mapToSource(OcrRecoveryPolicy.Bounds(1, 1, 9, 9), 100, 100))
        assertEquals(OcrRecoveryPolicy.Bounds(10, 20, 60, 50),
            plan.mapToSource(OcrRecoveryPolicy.Bounds(-100, -100, 999, 999), 100, 100))
        assertNull(plan.mapToSource(OcrRecoveryPolicy.Bounds(99, 59, 100, 60), 100, 100))
    }

    @Test fun memoryBoundNeverSilentlyChangesTheTwoTimesAspectRatio() {
        assertNull(OcrRecoveryPolicy.plan(4000, 2000,
            listOf(candidate(bounds = OcrRecoveryPolicy.Bounds(0, 0, 3000, 500)))))
        assertNull(OcrRecoveryPolicy.plan(Int.MAX_VALUE, Int.MAX_VALUE,
            listOf(candidate(bounds = OcrRecoveryPolicy.Bounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)))))
        val edge = OcrRecoveryPolicy.plan(640, 120,
            listOf(candidate(bounds = OcrRecoveryPolicy.Bounds(-10, -10, 100, 30))))!!
        assertEquals(OcrRecoveryPolicy.Bounds(0, 0, 106, 36), edge.crop)
        assertTrue(edge.targetWidth.toLong() * edge.targetHeight <= OcrRecoveryPolicy.MAX_RECOVERY_PIXELS)
    }

    @Test fun onlyFiniteLowConfidenceHanWithValidGeometryCanRequestRecovery() {
        for (line in listOf(candidate(confidence = Double.NaN), candidate(confidence = Double.NEGATIVE_INFINITY),
            candidate(confidence = .35), candidate(text = "Giúp tôi với"), candidate(bounds = null),
            candidate(bounds = OcrRecoveryPolicy.Bounds(100, 30, 100, 60)),
            candidate(bounds = OcrRecoveryPolicy.Bounds(900, 30, 1000, 60)))) {
            assertNull(line.toString(), OcrRecoveryPolicy.plan(640, 120, listOf(line)))
        }
    }

    @Test fun longestHanCandidateDeterminesTheSingleCrop() {
        val plan = OcrRecoveryPolicy.plan(640, 120, listOf(
            candidate("我", bounds = OcrRecoveryPolicy.Bounds(10, 5, 30, 20)), candidate()))!!
        assertEquals(OcrRecoveryPolicy.Bounds(94, 24, 306, 66), plan.crop)
    }

    @Test fun retryAcceptanceKeepsTheOriginalConfidenceFloor() {
        assertFalse(OcrRecoveryPolicy.isReliableHan(candidate(confidence = .34999)))
        assertFalse(OcrRecoveryPolicy.isReliableHan(candidate(confidence = Double.NaN)))
        assertFalse(OcrRecoveryPolicy.isReliableHan(candidate(text = "Come in", confidence = .99)))
        assertTrue(OcrRecoveryPolicy.isReliableHan(candidate(confidence = .35)))
        assertTrue(OcrRecoveryPolicy.hasHan("𠀀"))
    }
}
