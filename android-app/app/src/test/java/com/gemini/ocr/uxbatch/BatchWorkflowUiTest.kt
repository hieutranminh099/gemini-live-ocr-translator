package com.gemini.ocr.uxbatch

import com.gemini.ocr.batch.*
import com.gemini.ocr.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchWorkflowUiTest {
    private val item = BatchItem("video", "EP01.mp4", durationMs = 12_000)
    private fun batch(state: String = "READY") = BatchProject("batch", "Film", BatchKind.SERIES, listOf(item), state = state)
    private fun video(stage: VideoStage, progress: Int) = VideoProject("video", "EP01", "file:///fixture.mp4", 12_000, 640, 360,
        stage = stage, progress = progress)

    @Test fun completedAndConfirmedProjectsSkipTheImportWizard() {
        val reviewed = batch("REVIEW").copy(outputs = listOf(BatchOutput("1", "Film", "/fixture.mp4", 12_000)))
        assertEquals(3, BatchWorkflowUi.initialStep(reviewed, confirmed = false, running = false))
        assertEquals(3, BatchWorkflowUi.initialStep(reviewed, confirmed = true, running = false))
        assertEquals(2, BatchWorkflowUi.initialStep(batch("PAUSED"), false, false))
        assertEquals(2, BatchWorkflowUi.initialStep(batch(), false, true))
        assertEquals(0, BatchWorkflowUi.initialStep(null, false, false))
    }

    @Test fun extractionCompletionCannotBeShownAsCompletedVideo() {
        val processing = batch("RUNNING").copy(items = listOf(item.copy(state = "TRANSLATING", stage = BatchPipelineStage.EXTRACT, stageStatus = "RUNNING")))
        val source = video(VideoStage.EXTRACTING, 100).copy(scanMs = 12_000,
            cues = listOf(VideoCue("cue", 0, 1000, "你好")))
        assertEquals(.2f, BatchWorkflowUi.progress(processing, listOf(source)), .001f)
        val translating = processing.copy(items = listOf(item.copy(stage = BatchPipelineStage.TRANSLATE, stageStatus = "RUNNING")))
        val checkpoint = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "COMPLETE").record(BatchPipelineStage.CLEAN, "COMPLETE").record(BatchPipelineStage.TRANSLATE, "RUNNING")
        val awaitingTranslation = source.copy(stage = VideoStage.TRANSLATING, pipeline = checkpoint)
        assertEquals(.4f, BatchWorkflowUi.progress(translating, listOf(awaitingTranslation.copy(progress = 0))), .001f)
        assertEquals(.4f, BatchWorkflowUi.progress(translating, listOf(awaitingTranslation.copy(progress = 100))), .001f)
        assertEquals(.6f, BatchWorkflowUi.progress(translating, listOf(awaitingTranslation.copy(cues = source.cues.map {
            it.copy(translation = "Xin chào")
        }))), .001f)
        assertEquals(1f, BatchWorkflowUi.progress(batch().copy(items = listOf(item.copy(state = "DONE"))), emptyList()), .001f)
        assertEquals(4, BatchWorkflowUi.steps.size)
    }

    @Test fun partialSubtitlesRemainReachableWithoutRestartingEarlierSteps() {
        val partial = video(VideoStage.PAUSED, 20).copy(cues = listOf(VideoCue("cue", 0, 1000, "你好")))
        assertTrue(BatchWorkflowUi.reviewAvailable(batch("PAUSED"), listOf(partial)))
        assertFalse(BatchWorkflowUi.reviewAvailable(batch(), listOf(partial.copy(id = "different-project"))))
        assertEquals(0f, BatchWorkflowUi.progress(batch().copy(items = emptyList()), emptyList()), 0f)
    }
}
