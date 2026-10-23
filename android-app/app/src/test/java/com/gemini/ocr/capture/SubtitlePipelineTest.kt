package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class SubtitlePipelineTest {
    @Test fun testA_lateTranslationCannotReplaceNextSentence() {
        val pipeline = SubtitlePipeline("bottom-crop")
        pipeline.observe("A")
        val requestA = pipeline.request("1")!!
        pipeline.observe("B")
        val requestB = pipeline.request("2")!!
        assertFalse(pipeline.accepts(requestA))
        assertTrue(pipeline.accepts(requestB))
        assertEquals(requestA.screenGeneration + 1, requestB.screenGeneration)
    }

    @Test fun testB_disappearingSourceInvalidatesPendingTranslation() {
        val pipeline = SubtitlePipeline("bottom-crop")
        pipeline.observe("A")
        val requestA = pipeline.request("1")!!
        assertTrue(pipeline.observe(""))
        assertEquals("", pipeline.signature)
        assertFalse(pipeline.accepts(requestA))
        assertNull(pipeline.request("2"))
        assertFalse(pipeline.observe(""))
    }

    @Test fun testC_unchangedFramesDoNotIssueRepeatedRequests() {
        val pipeline = SubtitlePipeline("bottom-crop")
        var calls = 0
        repeat(100) {
            pipeline.observe("same subtitle")
            if (pipeline.request("$it") != null) calls++
        }
        assertEquals(1, calls)
    }

    @Test fun returningToSameTextDoesNotAcceptEarlierOccurrence() {
        val pipeline = SubtitlePipeline("bottom-crop")
        pipeline.observe("A")
        val firstA = pipeline.request("1")!!
        pipeline.observe("B")
        pipeline.observe("A")
        val secondA = pipeline.request("2")!!
        assertFalse(pipeline.accepts(firstA))
        assertTrue(pipeline.accepts(secondA))
    }

    @Test fun oldOcrAndTranslationsCannotCrossRegionChanges() {
        val pipeline = SubtitlePipeline("bottom-crop")
        val oldOcr = pipeline.captureToken()
        pipeline.observe("A")
        val oldTranslation = pipeline.request("1")!!
        assertTrue(pipeline.updateRegion("top-crop"))
        assertFalse(pipeline.acceptsOcr(oldOcr))
        assertFalse(pipeline.accepts(oldTranslation))
        assertEquals("", pipeline.signature)
        assertTrue(pipeline.acceptsOcr(pipeline.captureToken()))
    }

    @Test fun resetInvalidatesOcrEvenWhenScreenWasAlreadyEmpty() {
        val pipeline = SubtitlePipeline("bottom-crop")
        val before = pipeline.captureToken()
        pipeline.reset()
        assertFalse(pipeline.acceptsOcr(before))
    }

    @Test fun failuresCanRetryCurrentSentenceWithoutResurrectingOldSentence() {
        val pipeline = SubtitlePipeline("bottom-crop")
        pipeline.observe("A")
        val requestA = pipeline.request("1")!!
        pipeline.retry(requestA)
        val retryA = pipeline.request("2")!!
        assertFalse(pipeline.accepts(requestA))
        assertTrue(pipeline.accepts(retryA))
        pipeline.observe("B")
        val requestB = pipeline.request("3")!!
        pipeline.retry(retryA)
        assertTrue(pipeline.accepts(requestB))
        assertNull(pipeline.request("4"))
    }

    @Test fun forgedResponseMetadataCannotMatchCurrentRequest() {
        val pipeline = SubtitlePipeline("bottom-crop")
        pipeline.observe("A")
        val current = pipeline.request("1")!!
        assertFalse(pipeline.accepts(current.copy(requestId = "other")))
        assertFalse(pipeline.accepts(current.copy(regionId = "other")))
        assertFalse(pipeline.accepts(current.copy(screenGeneration = current.screenGeneration + 1)))
        assertFalse(pipeline.accepts(current.copy(signature = "B")))
    }
}
