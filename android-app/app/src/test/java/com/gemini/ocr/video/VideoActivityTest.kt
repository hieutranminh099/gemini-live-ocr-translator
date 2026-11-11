package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File

/** Opens native Compose UI, but never starts translation, model download, or a cloud call. */
@RunWith(RobolectricTestRunner::class)
// API 34's Robolectric 4.17 Spatializer shadow references BluetoothAddress, which is absent
// in that android-all image. Exercise the unchanged production player on supported API 28;
// actual current-device playback is verified by VideoWorkflowTest, with network disabled.
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi", shadows=[VideoActivityTest.EditorFrameRetriever::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoActivityTest {
    /** Only the editor's still image is synthetic here. Device tests use the real decoder. */
    @Implements(MediaMetadataRetriever::class)
    class EditorFrameRetriever {
        @Implementation fun setDataSource(context:Context,uri:Uri) {}
        @Implementation fun extractMetadata(key:Int):String?=when(key){
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH->"640"
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT->"360"
            MediaMetadataRetriever.METADATA_KEY_DURATION->"6000"
            else->"0"
        }
        @Implementation fun getScaledFrameAtTime(time:Long,option:Int,width:Int,height:Int):Bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        @Implementation fun release() {}
    }
    private val compose = createAndroidComposeRule<VideoActivity>()
    private val id = "aabbccdd-1122-3344-5566-778899aabbcc"
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            VideoJobs::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            SessionState.running.value = false
            val source = File(context.cacheDir, "ui-saved-timeline.mp4")
            source.writeBytes(byteArrayOf()) // Playback failure must not trigger processing or fallback.
            VideoStore(context).save(VideoProject(id, "Saved offline timeline", source.toURI().toString(), 6000, 640, 360,
                stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, progress = 100,
                message = "Đã lưu timeline", cues = listOf(VideoCue("saved-1", 1000, 3000, "不是三个", "Không phải ba.", model = "fixture"))))
            VideoJobs.get(context).refresh()
        }
        override fun after() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.filesDir, "video-projects/$id/project.json").delete()
            File(context.cacheDir, "ui-saved-timeline.mp4").delete()
            VideoJobs.get(context).refresh()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun opensSavedLibraryWithoutStartingOcrTranslationOrDownload() {
        compose.onNodeWithTag("video_select").assertIsDisplayed()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_library"))
        compose.onNodeWithTag("video_library").assertIsDisplayed()
        openSavedProject()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasText("Saved offline timeline"))
        compose.onNodeWithText("Saved offline timeline").assertIsDisplayed()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasText("Không phải ba."))
        compose.onNodeWithText("Không phải ba.").assertExists()
        compose.onNodeWithTag("video_jump_exports").assertIsDisplayed()
        compose.onNodeWithTag("video_start").assertDoesNotExist()
        compose.runOnIdle {
            val jobs = VideoJobs.get(compose.activity)
            assertNull("Opening a saved timeline must not start a worker", jobs.busy.value)
            val saved = VideoStore(compose.activity).load(id)
            assertEquals(VideoStage.REVIEW, saved.stage)
            assertEquals("Không phải ba.", saved.cues.single().translation)
            assertFalse(saved.cues.single().approved)
            assertEquals(0, saved.cloudCalls)
        }
    }

    @Test fun timeEditorRejectsMalformedAndOutOfRangeClockValues() {
        assertEquals(1250L, parseVideoTime("1.250"))
        assertEquals(3723125L, parseVideoTime("01:02:03,125"))
        listOf("abc", "00:61:00", "00:00:60", "-1", "NaN", "Infinity", "25:00:00").forEach { value ->
            assertThrows("Reject $value", IllegalArgumentException::class.java) { parseVideoTime(value) }
        }
    }

    @Test fun translatedFrameCanBeSelectedMovedAndSavedWithoutDiscardingTimeline() {
        openSavedProject()
        val original=VideoStore(compose.activity).load(id)
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_output_frame_toggle"))
        compose.onNodeWithTag("video_output_frame_toggle").performClick()
        compose.onNodeWithTag("video_region_reset").performClick()
        compose.runOnIdle { assertEquals("Preview must not save the frame", original.style, VideoStore(compose.activity).load(id).style) }
        compose.waitUntil(10000){compose.onAllNodes(androidx.compose.ui.test.hasTestTag("video_region_confirm")).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        compose.onNodeWithTag("video_region_confirm").performClick()
        compose.waitUntil(10000){VideoStore(compose.activity).load(id).style.frame!=null}
        val selected=VideoStore(compose.activity).load(id).style.frame!!
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_output_frame_toggle"))
        compose.onNodeWithTag("video_output_frame_toggle").performClick()
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("video_region_canvas")).fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("video_region_canvas").performTouchInput{
            swipe(start=Offset(width*.5f,height*.5f),end=Offset(width*.6f,height*.40f),durationMillis=500)
        }
        compose.runOnIdle { assertEquals("Moving the preview still requires Save", selected, VideoStore(compose.activity).load(id).style.frame) }
        compose.onNodeWithTag("video_region_confirm").performClick()
        compose.waitUntil(10000){VideoStore(compose.activity).load(id).style.frame!=selected}
        compose.runOnIdle {
            val updated=VideoStore(compose.activity).load(id)
            assertEquals(original.cues,updated.cues)
            assertEquals(original.roi,updated.roi)
            // The LazyColumn can clip the preview: check persisted editing here, while the
            // standalone gesture test checks exact movement on an entirely visible viewport.
            assertNotEquals(selected,updated.style.frame)
            assertEquals(original.cloudCalls,updated.cloudCalls)
            assertNull(VideoJobs.get(compose.activity).busy.value)
        }
    }

    private fun openSavedProject() {
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_library"))
        compose.onNodeWithTag("video_library").performClick()
        compose.onNodeWithText("Saved offline timeline · 100%").performClick()
    }

    private fun saveStyleDraft() {
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_save_style_draft"))
        compose.onNodeWithTag("video_save_style_draft").performClick()
    }
}
