package com.gemini.ocr.video

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoAsrSourceUiTest {
    private fun video(source: VideoSourceMode) = VideoProject("speech-ui-fixture", "Speech fixture", "file:///unused.mp4",
        5_000, 640, 360, sourceMode = source, translationMode = VideoTranslationMode.OFFLINE)

    @Test fun automaticAndSpeechExtractionCanStartBeforePreparingTheTranslator() {
        for (source in listOf(VideoSourceMode.AUTO, VideoSourceMode.ASR)) {
            val initial = video(source)
            assertNull(videoStartIssue(initial, keyReady = false, offlineReady = false))
            val extracted = initial.copy(scanMs = initial.durationMs, resumeStage = VideoStage.TRANSLATING,
                cues = listOf(VideoCue("speech", 500, 1_500, "你好", origin = "ASR")))
            assertNotNull(videoStartIssue(extracted, keyReady = false, offlineReady = false))
            assertNull(videoStartIssue(extracted, keyReady = false, offlineReady = true))
            assertEquals(source, extracted.sourceMode)
        }
    }

    @Test fun onlyAutomaticAndOcrSourcesNeedRegionControls() {
        assertTrue(videoSourceUsesRoi(VideoSourceMode.AUTO))
        assertTrue(videoSourceUsesRoi(VideoSourceMode.OCR))
        assertFalse(videoSourceUsesRoi(VideoSourceMode.ASR))
        assertFalse(videoSourceUsesRoi(VideoSourceMode.EMBEDDED))
        assertFalse(videoSourceUsesRoi(VideoSourceMode.SIDECAR))
        assertEquals("Nhận diện giọng nói", videoSourceLabel(VideoSourceMode.ASR))
    }

    @Test fun sourceDisplayUsesSavedEvidenceWithoutChangingCuesOrTimestamps() {
        val cue = VideoCue("speech", 500, 1_500, "你好", "Xin chào", origin = "ASR", approved = true)
        val project = video(VideoSourceMode.AUTO).copy(cues = listOf(cue),
            speech = JSONObject().put("resolvedSource", "OCR_ASR"))
        assertEquals("OCR hình ảnh và nhận diện giọng nói", videoResolvedSourceLabel(project))
        assertEquals(cue, project.cues.single())
        assertEquals("Nhận diện giọng nói", videoResolvedSourceLabel(project.copy(speech = JSONObject())))
        assertEquals("Phụ đề có sẵn", videoResolvedSourceLabel(project.copy(
            speech = JSONObject().put("resolvedSource", "EMBEDDED"))))
    }

    @Test fun overlapPreviewIsReadOnlyAndUsesExistingTimelineForComparison() {
        val approved = VideoCue("approved", 500, 1_500, "原字幕", "Phụ đề đã duyệt", origin = "OCR", approved = true)
        val project = video(VideoSourceMode.AUTO).copy(cues = listOf(approved),
            speech = JSONObject().put("reviewOverlaps", JSONArray().put(JSONObject()
                .put("startMs", 700).put("endMs", 1_200).put("source", "语音内容").put("origin", "ASR"))))
        val before = project.json().toString()
        val preview = videoAsrOverlapPreviews(project).single()
        assertEquals("语音内容", preview.source)
        assertEquals(listOf("原字幕"), preview.existingSources)
        assertEquals(700L, preview.startMs)
        assertEquals(1_200L, preview.endMs)
        assertEquals(before, project.json().toString())
        assertEquals(listOf(approved), project.cues)
    }

    @Test fun overlapPreviewIsBoundedAndDoesNotTreatTouchingIntervalsAsOverlaps() {
        val rows = JSONArray()
        repeat(80) { rows.put(JSONObject().put("startMs", 1_000).put("endMs", 2_000).put("source", "语".repeat(2_100))) }
        val project = video(VideoSourceMode.AUTO).copy(cues = listOf(
            VideoCue("before", 0, 1_000, "前"), VideoCue("during", 1_500, 2_500, "中"), VideoCue("after", 2_000, 3_000, "后")),
            speech = JSONObject().put("reviewOverlaps", rows))
        val preview = videoAsrOverlapPreviews(project)
        assertEquals(64, preview.size)
        assertEquals(2_000, preview.first().source.length)
        assertEquals(listOf("中"), preview.first().existingSources)
    }

    @Test fun malformedOverlapObservationsAreNotPresentedAsValidTimedSpeech() {
        val rows = JSONArray().put(JSONObject().put("startMs", -1).put("endMs", 1_000).put("source", "错"))
            .put(JSONObject().put("startMs", 100).put("endMs", 100).put("source", "错"))
            .put(JSONObject().put("startMs", 100).put("endMs", 5_001).put("source", "错"))
            .put(JSONObject().put("startMs", 100).put("endMs", 1_000).put("source", ""))
        assertTrue(videoAsrOverlapPreviews(video(VideoSourceMode.AUTO).copy(
            speech = JSONObject().put("reviewOverlaps", rows))).isEmpty())
    }

    @Test fun chineseSrtExportNeedsCompleteSourceButDoesNotRequireVietnameseTranslation() {
        val partial = video(VideoSourceMode.ASR).copy(scanMs = 1_000,
            cues = listOf(VideoCue("speech", 500, 1_000, "你好", origin = "ASR")))
        assertFalse(videoCanExportSource(partial))
        val complete = partial.copy(scanMs = partial.durationMs, resumeStage = VideoStage.TRANSLATING)
        assertTrue(videoCanExportSource(complete))
        assertTrue(complete.cues.single().translation.isBlank())
        assertFalse(videoCanExportSource(complete.copy(cues = emptyList())))
        assertFalse(videoCanExportSource(complete.copy(speech = JSONObject().put("asrActive", true).put("complete", false))))
    }

    @Test fun unclearAudioIsPresentedAsMissingEvidenceWithoutInventingSourceCues() {
        val project = video(VideoSourceMode.ASR).copy(speech = JSONObject().put("unclearWindows", 2))
        assertTrue(videoUnclearSpeechNotice(project).orEmpty().contains("2 đoạn"))
        assertTrue(project.cues.isEmpty())
        assertNull(videoUnclearSpeechNotice(project.copy(speech = JSONObject())))
    }
}
