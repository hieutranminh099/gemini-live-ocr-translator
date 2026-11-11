package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoPipelineCheckpoint
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoTranslationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchRetryUiTest {
    private val stage = BatchPipelineStage.TRANSLATE
    private val item = BatchItem("episode-a", "Tập 1", stage = stage)
    private val batch = BatchProject("batch", "Phim", BatchKind.SERIES, items = listOf(item))

    private fun project(id: String = item.projectId, deadline: Long = 0) = VideoProject(
        id, "Tập 1", "file:///fixture.mp4", 5_000, 640, 360,
        translationMode = VideoTranslationMode.GEMINI,
        pipeline = VideoPipelineCheckpoint().record(stage, "RETRY_WAIT", nextRetryAt = deadline),
        cues = listOf(
            VideoCue("approved", 500, 1_500, "你好", "Xin chào", approved = true),
            VideoCue("pending", 2_000, 3_000, "再见"),
        ),
    )

    @Test fun foregroundRetryDeadlineTakesPrecedenceAndCountdownNeverShowsNegativeTime() {
        val waiting = batch.copy(state = "RETRY_WAIT", autoRetryAtMs = 61_001)
        val deadline = batchRetryDeadline(waiting, listOf(project(deadline = 10_000)))
        assertEquals(61_001L, deadline)
        assertEquals(60L, batchRetrySeconds(deadline, 1_001))
        assertEquals(1L, batchRetrySeconds(deadline, 61_000))
        assertEquals(0L, batchRetrySeconds(deadline, 61_001))
        assertEquals(0L, batchRetrySeconds(deadline, 62_001))
    }

    @Test fun inactiveProjectDeadlinesDoNotOverrideSelectedBatch() {
        val other = project(id = "unrelated-episode", deadline = 2_000)
        assertEquals(0L, batchRetryDeadline(batch, listOf(other)))
        assertEquals(8_000L, batchRetryDeadline(batch, listOf(other, project(deadline = 8_000))))
        assertEquals(0L, batchRetryDeadline(batch.copy(state = "PAUSED", autoRetryAtMs = 12_000), emptyList()))
    }

    @Test fun partialGroupGuidanceCountsOnlyUnfinishedTranslationAndHidesRawProviderDetails() {
        val raw = "provider response content never belongs in the hint"
        val error = item.copy(errorCode = "TEXT_INCOMPLETE_RESPONSE", error = raw)
        val guidance = batchRetryGuidance(error, project())
        assertEquals(1, guidance.unfinishedCues)
        assertEquals("TEXT_INCOMPLETE_RESPONSE", guidance.code)
        assertTrue(guidance.action.contains("Xem và sửa phụ đề"))
        assertFalse(guidance.toString().contains(raw))
    }

    @Test fun manualFixesDistinguishMissingModelsAuthorizationAndSourceAccess() {
        val asr = batchRetryGuidance(item.copy(error = "[ASR_MODEL_MISSING] Đã lưu tiến độ."), null)
        assertTrue(asr.action.contains("tải gói tiếng Trung"))
        val auth = batchRetryGuidance(item.copy(errorCode = "API_AUTH", error = "HTTP 403"), null)
        assertTrue(auth.action.contains("Tùy chỉnh"))
        assertFalse(auth.action.contains("Tự tiếp tục"))
        val permission = batchRetryGuidance(item.copy(errorCode = "SOURCE_PERMISSION"), null)
        assertTrue(permission.action.contains("quyền truy cập tệp"))
        val unknown = batchRetryGuidance(item.copy(error = "UNRECOGNIZED_PROVIDER_DETAIL"), null)
        assertTrue(unknown.code.isBlank())
    }
}
