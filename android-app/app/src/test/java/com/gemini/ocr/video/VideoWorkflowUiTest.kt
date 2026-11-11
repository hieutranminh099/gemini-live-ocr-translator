package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoWorkflowUiTest {
    private fun video() = VideoProject("11111111-2222-3333-4444-555555555555", "fixture", "file:///fixture.mp4", 5000, 640, 360,
        sourceMode = VideoSourceMode.SIDECAR)
    @Test fun automaticSourceDoesNotMistakeAnEnglishTrackForChinese() {
        val english = VideoTextTrack(0, "en", "text/vtt")
        val chinese = VideoTextTrack(1, "zh-Hans", "text/vtt")
        assertEquals(chinese, preferredVideoTextTrack(listOf(english, chinese)))
        assertNull(preferredVideoTextTrack(listOf(english)))
        assertNull(preferredVideoTextTrack(listOf(chinese, chinese.copy(id = 2))))
        assertEquals(2, preferredVideoTextTrack(listOf(VideoTextTrack(2, "und", "text/vtt")))?.id)
    }
    @Test fun newVideoAndFinishedProjectsHaveDifferentUsefulEntrySteps() {
        assertEquals(VideoWorkflowStep.SELECT, videoWorkflowEntry(null))
        assertEquals(VideoWorkflowStep.SETUP, videoWorkflowEntry(video()))
        val complete = video().copy(stage = VideoStage.COMPLETE, scanMs = 5000,
            cues = listOf(VideoCue("1", 0, 1000, "你好", "Xin chào")))
        assertEquals(VideoWorkflowStep.REVIEW, videoWorkflowEntry(complete))
        assertEquals(VideoWorkflowStep.EXPORT, videoWorkflowEntry(complete, "export"))
        assertEquals(VideoWorkflowStep.SETUP, videoWorkflowEntry(complete, "setup"))
        assertEquals(VideoWorkflowStep.SETUP, videoWorkflowEntry(video(), "review"))
    }
    @Test fun uiDoesNotEnableUnavailableCloudOrOfflineProcessingAndNeverChangesMode() {
        val cloud = video().copy(sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.GEMINI)
        assertNotNull(videoStartIssue(cloud, keyReady = false, offlineReady = true))
        assertEquals(VideoTranslationMode.GEMINI, cloud.translationMode)
        val local = cloud.copy(translationMode = VideoTranslationMode.OFFLINE)
        assertNotNull(videoStartIssue(local, keyReady = true, offlineReady = false))
        assertNull(videoStartIssue(local, keyReady = false, offlineReady = true))
        assertNotNull(videoStartIssue(video(), keyReady = true, offlineReady = true))
    }
    @Test fun restoringAnExportCheckpointDoesNotRequireCallingTheTranslatorAgain() {
        val p = video().copy(stage = VideoStage.PAUSED, resumeStage = VideoStage.EXPORTING, scanMs = 5000,
            translationMode = VideoTranslationMode.GEMINI,
            cues = listOf(VideoCue("1", 0, 1000, "你好", "Xin chào")))
        assertEquals(VideoWorkflowStep.PROCESS, videoWorkflowEntry(p))
        assertNull(videoStartIssue(p, keyReady = false, offlineReady = false))
        assertNotNull(videoStartIssue(p.copy(scanMs = 1000, resumeStage = VideoStage.EXTRACTING), false, false))
    }

    @Test fun hybridCanExtractTheWholeVideoBeforeKeyOrOfflineTranslationModelAreReady() {
        val partial = video().copy(sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID,
            scanMs = 2000, resumeStage = VideoStage.TRANSLATING,
            cues = listOf(VideoCue("1", 0, 1000, "你好")))
        assertNull(videoStartIssue(partial, keyReady = false, offlineReady = false))
        val extracted = partial.copy(scanMs = partial.durationMs)
        assertTrue(videoStartIssue(extracted, keyReady = false, offlineReady = false).orEmpty().contains("100%"))
        assertNull(videoStartIssue(extracted, keyReady = true, offlineReady = false))
        assertEquals(VideoTranslationMode.HYBRID, partial.translationMode)
    }

    @Test fun completeHybridOnlyRequestsAKeyWhenCloudTargetsStillExist() {
        val complete = video().copy(translationMode = VideoTranslationMode.HYBRID, scanMs = 5000,
            cues = listOf(VideoCue("1", 0, 1000, "你好", "Xin chào", approved = true)))
        assertNull(videoStartIssue(complete, keyReady = false, offlineReady = false))
        assertNull(videoStartIssue(complete.copy(cues = complete.cues.map { it.copy(approved = false,
            refinementState = "done") }), keyReady = false, offlineReady = false))
        assertNotNull(videoStartIssue(complete.copy(cues = complete.cues.map { it.copy(approved = false,
            refinementState = "pending") }), keyReady = false, offlineReady = false))
    }
}
