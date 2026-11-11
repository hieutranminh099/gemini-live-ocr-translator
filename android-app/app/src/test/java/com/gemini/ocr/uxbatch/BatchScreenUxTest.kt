package com.gemini.ocr.uxbatch

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.batch.*
import com.gemini.ocr.ui.GeminiTheme
import com.gemini.ocr.video.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchScreenUxTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("video-google-sync", Context.MODE_PRIVATE).edit().clear().commit()
        // These UI tests create a fresh Robolectric application per method. Do not let
        // a process singleton keep another method's application directory/snapshot.
        ReflectionHelpers.setStaticField(VideoJobs::class.java, "instance", null)
        ReflectionHelpers.setStaticField(BatchJobs::class.java, "instance", null)
    }

    private fun savedBatch(reviewed: Boolean): BatchProject {
        val project = VideoStore(context).save(VideoProject(UUID.randomUUID().toString(), "Synthetic video", "file:///synthetic.mp4", 12_000, 640, 360,
            translationMode = VideoTranslationMode.GEMINI,
            cues = if (reviewed) listOf(VideoCue("cue", 500, 1500, "你好", "Xin chào")) else emptyList(),
            stage = if (reviewed) VideoStage.COMPLETE else VideoStage.READY))
        val batch = BatchStore(context).create("Saved project", BatchKind.SERIES)
        val saved = BatchStore(context).save(batch.copy(items = listOf(BatchItem(project.id, "EP01.mp4", episode = "1", durationMs = 12_000)),
            outputs = if (reviewed) listOf(BatchOutput("1", "EP01", "/synthetic-output.mp4", 12_000)) else emptyList(),
            state = if (reviewed) "REVIEW" else "READY"))
        VideoJobs.get(context).refresh()
        BatchJobs.get(context).refresh()
        return saved
    }

    @Test fun existingCloudChoiceIsPreservedAndNoRequestStartsByNavigatingSteps() {
        val saved = savedBatch(false)
        compose.setContent { GeminiTheme { BatchScreen(saved.id) {} } }
        compose.onNodeWithTag("batch_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Nguồn và cách dịch nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Gemini · cần mạng").assertExists()
        compose.onNodeWithTag("batch_start").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("batch_step_2").performClick()
        compose.onNodeWithTag("batch_stage_progress").assertExists()
        compose.onNodeWithTag("batch_start").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(VideoTranslationMode.GEMINI, BatchStore(context).load(saved.id).translationMode)
            assertNull(BatchJobs.get(context).busy.value)
            assertNull(VideoJobs.get(context).busy.value)
        }
    }

    @Test fun reviewShowsUnresolvedCueCountWithoutOpeningTechnicalDetails() {
        val saved = savedBatch(true)
        val videoStore = VideoStore(context)
        val project = videoStore.load(saved.items.single().projectId)
        val flagged = project.cues.single().copy(approved = false, qualityFlags = listOf("OCR_UNCERTAIN"))
        videoStore.save(project.copy(cues = listOf(flagged)))
        VideoJobs.get(context).refresh()

        compose.setContent { GeminiTheme { BatchScreen(saved.id) {} } }

        // No disclosure, scrolling or other interaction is needed to see the review warning.
        compose.onNodeWithTag("batch_step_3").assertIsSelected()
        compose.onNodeWithTag("batch_review_warning").assertIsDisplayed()
        compose.onNodeWithText("Còn 1 câu được đánh dấu chưa duyệt.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(flagged, videoStore.load(project.id).cues.single())
            assertEquals("REVIEW", BatchStore(context).load(saved.id).state)
            assertNull(BatchJobs.get(context).busy.value)
        }
    }

    @Test fun aFinishedProjectOpensReviewAndCanReachExportWithoutImportAgain() {
        val saved = savedBatch(true)
        compose.setContent { GeminiTheme { BatchScreen(saved.id) {} } }
        compose.onNodeWithTag("batch_step_3").assertIsSelected()
        compose.onNodeWithText("Xem và sửa phụ đề").assertExists()
        compose.onNodeWithTag("batch_step_4").assertDoesNotExist()
        compose.onNodeWithTag("batch_confirm_library").assertIsDisplayed()
        compose.onNodeWithTag("batch_content_list").performScrollToNode(hasText("Lưu phụ đề / video"))
        compose.onNodeWithText("Lưu phụ đề / video").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Lưu SRT tiếng Trung").assertExists()
        compose.runOnIdle {
            assertEquals("REVIEW", BatchStore(context).load(saved.id).state)
            assertEquals(1, BatchStore(context).load(saved.id).outputs.size)
            assertNull(BatchJobs.get(context).busy.value)
        }
    }

    @Test fun bulkApprovalWorksWhileSeriesMergeWaitsAndOnlyApprovesTheLibrarySnapshot() {
        val saved = BatchStore(context).save(savedBatch(true).copy(
            delivery = BatchDelivery.LIBRARY_AND_MP4,
            mergeEpisodes = true
        ))
        BatchJobs.get(context).refresh()
        val videoStore = VideoStore(context)
        val projectId = saved.items.single().projectId
        val project = videoStore.load(projectId)
        videoStore.save(project.copy(scanMs = project.durationMs,
            pipeline = project.pipeline.record(BatchPipelineStage.FINISH, "COMPLETE")))
        VideoJobs.get(context).refresh()
        val original = project.cues.single()
        assertFalse(original.approved)

        compose.setContent { GeminiTheme { BatchScreen(saved.id) {} } }
        compose.onNodeWithTag("batch_confirm_library").assertIsNotEnabled()
        compose.onNodeWithTag("batch_content_list").performScrollToNode(hasTestTag("batch_approve_all_to_library"))
        compose.onNodeWithTag("batch_approve_all_to_library").assertIsEnabled().performClick()
        compose.onNodeWithText("Duyệt toàn bộ và lưu Kho phim?").assertIsDisplayed()
        compose.onNodeWithText("Xem lại").performClick()

        val jobs = BatchJobs.get(context)
        assertEquals(original, runBlocking { jobs.validatedSources(saved.id) }.single().project.cues.single())
        val approved = runBlocking { jobs.validatedSources(saved.id, approveAll = true) }.single().project.cues.single()
        assertEquals(original.copy(approved = true), approved)
        assertEquals(original, videoStore.load(projectId).cues.single())
    }
}
