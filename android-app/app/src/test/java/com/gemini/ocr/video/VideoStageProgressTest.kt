package com.gemini.ocr.video

import com.gemini.ocr.batch.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoStageProgressTest {
    private fun project() = VideoProject("video", "Synthetic", "file:///fixture.mp4", 4000, 640, 360,
        translationMode = VideoTranslationMode.HYBRID, scanMs = 4000,
        stage = VideoStage.FAILED, progress = 100, cues = List(4) {
            VideoCue("cue-$it", it * 1000L, it * 1000L + 800, "你好")
        }, pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "COMPLETE", 4000)
            .record(BatchPipelineStage.CLEAN, "COMPLETE", 4)
            .record(BatchPipelineStage.TRANSLATE, "NEEDS_REVIEW", 4000, "TEXT_INVALID_RESPONSE"))

    @Test fun oldHundredPercentFailureShowsZeroTranslationAndSeparateCompletionCounts() {
        val p = project()
        val batch = BatchProject("batch", "Fixture", BatchKind.SERIES,
            listOf(BatchItem(p.id, "Episode", stage = BatchPipelineStage.TRANSLATE,
                stageStatus = "NEEDS_REVIEW", state = "NEEDS_REVIEW")))
        assertEquals(100, VideoStageProgress.percent(p, BatchPipelineStage.EXTRACT))
        assertEquals(100, VideoStageProgress.percent(p, BatchPipelineStage.CLEAN))
        assertEquals(0, VideoStageProgress.percent(p, BatchPipelineStage.TRANSLATE))
        assertEquals(0, VideoStageProgress.percent(p, BatchPipelineStage.FINISH))
        assertEquals(BatchWorkflowUi.CompletionCounts(1, 0, 0, 1), BatchWorkflowUi.completionCounts(batch, listOf(p)))
        assertEquals(.4f, BatchWorkflowUi.progress(batch, listOf(p)), .001f)
        // A saved final label alone cannot override the missing translations.
        assertTrue(BatchWorkflowUi.progress(batch.copy(items = batch.items.map { it.copy(state = "DONE") }), listOf(p)) < 1f)
    }

    @Test fun partialHybridProgressCountsAcceptedAndProtectedTranslationsButNotLocalDrafts() {
        val p = project().let { it.copy(cues = listOf(
            it.cues[0].copy(translation = "Câu đã duyệt", approved = true),
            it.cues[1].copy(translation = "Bản Việt", refinementState = "done"),
            it.cues[2].copy(translation = "Bản nháp", localDraft = "Bản nháp", refinementState = "pending"),
            it.cues[3].copy(approved = true) // Invalid empty approval must not count as translated.
        )) }
        for (state in listOf(VideoStage.FAILED, VideoStage.PAUSED, VideoStage.TRANSLATING, VideoStage.WAITING_LIVE)) {
            assertEquals(50, VideoStageProgress.percent(p.copy(stage = state), BatchPipelineStage.TRANSLATE))
        }
        assertEquals(2, VideoStageProgress.translatedCueCount(p))
        assertFalse(VideoStageProgress.translationComplete(p))
    }

    @Test fun completeTranslationAndFinalizationRemainDistinctAcrossSerialization() {
        val p = project().let { it.copy(cues = it.cues.map { cue ->
            cue.copy(translation = "Bản Việt", refinementState = "done")
        }, pipeline = it.pipeline.record(BatchPipelineStage.TRANSLATE, "COMPLETE", 4)) }
        val restored = VideoProject.from(p.json())
        assertTrue(VideoStageProgress.translationComplete(restored))
        assertEquals(100, VideoStageProgress.percent(restored, BatchPipelineStage.TRANSLATE))
        assertEquals(0, VideoStageProgress.percent(restored, BatchPipelineStage.FINISH))
        assertEquals(100, VideoStageProgress.percent(restored.copy(pipeline = restored.pipeline.record(
            BatchPipelineStage.FINISH, "COMPLETE", 4)), BatchPipelineStage.FINISH))
    }

    @Test fun sourceAndPolishPercentagesDoNotInheritAnEarlierHundred() {
        val p = project()
        assertEquals(25, VideoStageProgress.percent(p.copy(scanMs = 1000), BatchPipelineStage.EXTRACT))
        assertEquals(99, VideoStageProgress.percent(p.copy(scanMs = 4000, carry = org.json.JSONObject(),
            speech = org.json.JSONObject().put("asrActive", true)), BatchPipelineStage.EXTRACT))
        assertEquals(0, VideoStageProgress.percent(p, BatchPipelineStage.POLISH))
        assertEquals(0, VideoStageProgress.percent(p.copy(cues = emptyList()), BatchPipelineStage.TRANSLATE))
    }
}
