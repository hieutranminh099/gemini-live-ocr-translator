package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class VideoSourceSelectionTest {
    private fun project(duration:Long=60000)=VideoProject("00000000-0000-0000-0000-000000000187","source","file:///fixture.mp4",duration,640,360,scanMs=duration)
    @Test(expected=IllegalArgumentException::class) fun emptyPreviewFramesCannotTriggerAsr() {
        VideoSourceSelection.assessSpeech(project().copy(scanMs=500),emptyList())
    }
    @Test fun sparseOcrIsAssessedAfterFullScanAndNormalSubtitleCoverageIsKept() {
        assertTrue(VideoSourceSelection.assessSpeech(project(),listOf(VideoCue("1",1000,2000,"你好"))))
        assertFalse(VideoSourceSelection.assessSpeech(project(),listOf(VideoCue("1",1000,20000,"你好"))))
        assertFalse(VideoSourceSelection.assessSpeech(project(4000),listOf(VideoCue("1",1000,1300,"你好"))))
    }
    @Test fun sameWordsAtOtherTimesRemainSeparateAndOverlapNeverChangesApproved() {
        val approved=VideoCue("ocr",1000,2000,"你好","Xin chào",approved=true)
        val overlap=VideoSourceSelection.merge(listOf(approved),VideoCue("asr1",1500,2500,"你好",origin="ASR"))
        assertTrue(overlap.second);assertEquals(listOf(approved),overlap.first)
        val separate=VideoSourceSelection.merge(listOf(approved),VideoCue("asr2",3000,4000,"你好",origin="ASR"))
        assertFalse(separate.second);assertEquals(2,separate.first.size)
    }
    @Test fun legacySourceAndNewOptionalSpeechStateRoundTrip() {
        assertEquals(VideoSourceMode.AUTO,project().sourceMode)
        val old=project().copy(sourceMode=VideoSourceMode.OCR).json()
        old.remove("speech")
        assertEquals(VideoSourceMode.OCR,VideoProject.from(old).sourceMode)
        val active=project().copy(speech=JSONObject().put("asrActive",true).put("complete",false))
        assertTrue(VideoProject.from(active.json()).sourceExtractionPending)
        assertFalse(VideoStagedPipeline.extracted(active))
    }
    @Test fun silenceIsCompleteOnlyWithExplicitAsrEvidence() {
        assertFalse(VideoStagedPipeline.extracted(project()))
        val silence=project().copy(speech=JSONObject().put("asrActive",true).put("complete",true).put("noSpeech",true))
        assertTrue(VideoStagedPipeline.extracted(silence))
    }
    @Test(expected=IllegalArgumentException::class) fun changingRoiCannotEraseApprovedSource() {
        val p=project().copy(cues=listOf(VideoCue("1",1000,2000,"你好","Xin chào",approved=true)))
        VideoStagedPipeline.applyRoi(p,listOf(.1f,.2f,.8f,.9f),1)
    }
}
