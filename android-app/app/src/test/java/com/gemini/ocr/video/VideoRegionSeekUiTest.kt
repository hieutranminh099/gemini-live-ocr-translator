package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gemini.ocr.ui.GeminiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the shared batch/single-video picker without reading user media or calling AI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w800dp-h600dp-mdpi", shadows=[VideoRegionSeekUiTest.Frames::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoRegionSeekUiTest {
    @get:Rule val compose=createComposeRule()
    @Implements(MediaMetadataRetriever::class)
    class Frames {
        companion object { val requests=CopyOnWriteArrayList<Long>() }
        @Implementation fun setDataSource(context:Context,uri:Uri) {}
        @Implementation fun extractMetadata(key:Int):String?=when(key) {
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH->"640"
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT->"360"
            MediaMetadataRetriever.METADATA_KEY_DURATION->"12000"
            else->"0"
        }
        @Implementation fun getScaledFrameAtTime(time:Long,option:Int,width:Int,height:Int):Bitmap {
            requests.add(time)
            return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        }
        @Implementation fun release() {}
    }
    @Test fun seeksDecodeNewTimeAndConfirmKeepsSelectedRegion() {
        Frames.requests.clear()
        val project=VideoProject("seek-fixture","Synthetic","file:///fixture.mp4",12000,640,360,
            roi=listOf(.1f,.7f,.9f,.95f))
        var confirmed:VideoSubtitleFrame?=null
        compose.setContent { GeminiTheme { VideoRegionDialog(project,0,true,{}, {confirmed=it}) } }
        compose.waitUntil(10000){Frames.requests.contains(0L)}
        compose.onNodeWithTag("video_region_seek_5000").assertIsDisplayed().performClick()
        compose.waitUntil(10000){Frames.requests.contains(5_000_000L)}
        compose.onNodeWithTag("video_region_time").assertTextContains("00:05 / 00:12",substring=true)
        compose.onNodeWithTag("video_region_seek_-1000").performClick()
        compose.waitUntil(10000){Frames.requests.contains(4_000_000L)}
        compose.waitUntil(10000){compose.onNodeWithTag("video_region_confirm").isEnabled()}
        compose.onNodeWithTag("video_region_confirm").performClick()
        compose.runOnIdle { assertEquals(VideoSubtitleFrame.fromRoi(project.roi),confirmed) }
    }
    private fun SemanticsNodeInteraction.isEnabled():Boolean=runCatching{assertIsEnabled();true}.getOrDefault(false)
}
