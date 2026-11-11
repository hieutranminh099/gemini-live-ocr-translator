package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoProject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class BatchStagedUiTest {
    @Test fun fourScreensResumeAtThePersistedWorkRatherThanImport() {
        val batch = BatchProject("batch", "Phim", BatchKind.SERIES)
        assertEquals(4, BatchWorkflowUi.steps.size)
        assertEquals(2, BatchWorkflowUi.initialStep(batch.copy(state = "NEEDS_REVIEW"), false, false))
        assertEquals(3, BatchWorkflowUi.initialStep(batch.copy(state = "REVIEW"), false, false))
        assertEquals(3, BatchWorkflowUi.initialStep(batch, true, false))
    }

    @Test fun failedStageCannotBeDisplayedAsWholeVideoComplete() {
        val item = BatchItem("video", "EP01", state = "NEEDS_REVIEW", stage = BatchPipelineStage.TRANSLATE, stageStatus = "NEEDS_REVIEW")
        val batch = BatchProject("batch", "Phim", BatchKind.SERIES, items = listOf(item))
        val staleProgress = VideoProject("video", "EP01", "file:///fixture.mp4", 1000, 1920, 1080, progress = 100)
        assertEquals(0f, BatchWorkflowUi.progress(batch, listOf(staleProgress)), .001f)
        assertTrue(BatchWorkflowUi.progress(batch, listOf(staleProgress)) < 1f)
    }

    @Test fun sampleTimesAreDistinctAndInsideShortAndLongVideoBounds() {
        for (duration in listOf(1L, 2L, 1000L, 3_600_000L)) {
            val samples = BatchRoiValidation.sampleTimes(duration)
            assertEquals(samples.size, samples.distinct().size)
            assertTrue(samples.all { it in 0 until duration })
            assertTrue(samples.size <= 3)
        }
    }

    @Test fun unavailableOptionalAudioDoesNotMarkEveryOcrCueAsUncertain() {
        assertTrue(BatchWorkflowUi.reviewFlags(listOf("ASR_UNAVAILABLE")).isEmpty())
        assertEquals(listOf("OCR_UNCERTAIN"), BatchWorkflowUi.reviewFlags(listOf("ASR_UNAVAILABLE", "OCR_UNCERTAIN")))
        assertEquals("Có chữ thiếu hoặc bất thường", BatchWorkflowUi.flagLabel("OCR_UNCERTAIN"))
    }

    @Test fun roiValidationIdentityChangesWithRegionButNotWithCheckpointProgress() {
        val project = VideoProject("video", "EP01", "file:///fixture.mp4", 1000, 1920, 1080)
        assertEquals(BatchRoiValidation.identity(project), BatchRoiValidation.identity(project.copy(progress = 75, scanMs = 500)))
        assertNotEquals(BatchRoiValidation.identity(project), BatchRoiValidation.identity(project.copy(roi = listOf(.1f, .7f, .9f, .95f))))
        assertNotEquals(BatchRoiValidation.identity(project), BatchRoiValidation.identity(project.copy(uri = "file:///other.mp4")))
    }

    @Test fun unresolvedWarningCountsOnlyMemberCuesWithUnapprovedQualityFlags() {
        val batch = BatchProject("batch", "Phim", BatchKind.SERIES, items = listOf(BatchItem("video", "EP01")))
        val project = VideoProject("video", "EP01", "file:///fixture.mp4", 1000, 1920, 1080,
            cues = listOf(
                com.gemini.ocr.video.VideoCue("a", 0, 100, "甲", qualityFlags = listOf("OCR_UNCERTAIN", "SHORT_SUBTITLE")),
                com.gemini.ocr.video.VideoCue("b", 100, 200, "乙", qualityFlags = listOf("ASR_UNAVAILABLE")),
                com.gemini.ocr.video.VideoCue("c", 200, 300, "丙", approved = true, qualityFlags = listOf("OCR_UNCERTAIN"))))
        assertEquals(1, BatchWorkflowUi.unresolvedCueCount(batch, listOf(project, project.copy(id = "foreign"))))
        assertEquals(0, BatchWorkflowUi.unresolvedCueCount(batch, listOf(project.copy(cues = emptyList()))))
        assertEquals("Đang tự khôi phục, tiến độ đã được lưu.", BatchWorkflowUi.friendlyMessage("RETRY_WAIT"))
        assertTrue(BatchWorkflowUi.retryWaiting(batch.copy(state = "RETRY_WAIT"), emptyList()))
    }

    @Test fun authAndQuotaMessagesNeverClaimAutomaticRecovery() {
        assertFalse(BatchWorkflowUi.friendlyMessage("HTTP_401").contains("tự khôi phục"))
        assertTrue(BatchWorkflowUi.friendlyMessage("QUOTA_EXHAUSTED").contains("dừng an toàn"))
        assertFalse(BatchWorkflowUi.friendlyMessage("VISION_INVALID_RESPONSE").contains("thử lại", ignoreCase = true))
    }
}
