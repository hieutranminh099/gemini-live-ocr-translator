package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

/** Simulate the ordering of a slow OCR completion and a continuing video producer. */
class OcrCompletionGateTest {
    private fun image(id: Long, pixels: Long) = OcrFrameFingerprint(id, pixels, 10, 400, 900, 540)
    private fun decision(completed: OcrFrameFingerprint = image(1, 10), latest: OcrFrameFingerprint? = image(3, 30),
                         epoch: Long = 7, currentEpoch: Long = 7, blocked: Boolean = false,
                         oneFrame: Boolean = false, responseAt: Long = 0, imageAt: Long = 100) =
        OcrCompletionGate.afterNative(epoch, currentEpoch, blocked, oneFrame, responseAt, imageAt, completed, latest)

    @Test fun movingSceneryCannotStarveAnUnchangedSubtitleWhileNativeOcrIsRunning() {
        val pipeline = SubtitlePipeline("movie-roi")
        val completed = image(1, 10)
        // Two frames arrived during native OCR. The previous blacklist would drop
        // this observation even though the completed OCR still reads the same cue.
        val latest = image(3, 30)
        assertTrue(completed.supersededByPixelChange(latest))
        val result = decision(completed, latest)
        assertTrue(result.pixelsAdvanced)
        assertTrue(result.canPublish)
        pipeline.observe(StableText.signature(listOf("帮帮我")))
        val token = pipeline.request("first-visible-cue")
        assertNotNull(token)
        assertTrue(pipeline.accepts(token!!))
        // Continuing camera motion is diagnostic; it does not create text generations.
        assertTrue(decision(image(4, 40), image(6, 60)).canPublish)
        assertFalse(pipeline.observe(StableText.signature(listOf("帮帮我"))))
        assertNull(pipeline.request("duplicate"))
    }

    @Test fun aLaterOcrTextChangeStillRejectsTheOldTranslationEvenWhenPixelsMoveAgain() {
        val pipeline = SubtitlePipeline("movie-roi")
        pipeline.observe("A")
        val old = pipeline.request("old-A")!!
        assertTrue(decision().canPublish)
        pipeline.observe("B")
        val current = pipeline.request("new-B")!!
        assertFalse(pipeline.accepts(old))
        assertTrue(pipeline.accepts(current))
        pipeline.observe("A")
        val repeated = pipeline.request("later-A")!!
        assertFalse(pipeline.accepts(old))
        assertFalse(pipeline.accepts(current))
        assertTrue(pipeline.accepts(repeated))
    }

    @Test fun movingPixelsDoNotAllowAnImageCapturedBeforeTheResponseToConfirmIt() {
        val rejected = decision(responseAt = 201, imageAt = 200)
        assertFalse(rejected.canPublish)
        assertEquals("pre_response_image", rejected.rejection)
        assertTrue(rejected.pixelsAdvanced)
        assertTrue(decision(responseAt = 201, imageAt = 201).canPublish)
    }

    @Test fun resetRotationAndPauseRemainHardBoundariesForAnOcrCompletion() {
        assertEquals("capture_epoch", decision(epoch = 7, currentEpoch = 8).rejection)
        assertEquals("capture_epoch", decision(blocked = true).rejection)
        val pipeline = SubtitlePipeline("old-region")
        pipeline.observe("A")
        val old = pipeline.request("old-request")!!
        pipeline.updateRegion("new-region")
        assertFalse(pipeline.accepts(old))
    }

    @Test fun explicitFrameKeepsItsFrozenImagePolicyButCannotBypassSessionReset() {
        assertTrue(decision(oneFrame = true, responseAt = 201, imageAt = 100).canPublish)
        assertFalse(decision(oneFrame = true, epoch = 7, currentEpoch = 8).canPublish)
    }

    @Test fun unchangedPixelsAndDifferentCropGeometryDoNotInventAMotionDiagnostic() {
        assertFalse(decision(image(1, 10), image(3, 10)).pixelsAdvanced)
        assertFalse(decision(latest = null).pixelsAdvanced)
        assertFalse(decision(latest = OcrFrameFingerprint(3, 30, 10, 390, 900, 540)).pixelsAdvanced)
    }
}
