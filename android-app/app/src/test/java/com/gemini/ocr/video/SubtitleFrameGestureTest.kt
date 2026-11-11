package com.gemini.ocr.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w500dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleFrameGestureTest {
    @get:Rule val compose=createComposeRule()
    @Test fun draggingTheVisibleFrameMovesItAndCornerDragResizesItWithOneCommitEach() {
        val value=mutableStateOf(VideoSubtitleFrame(.2f,.3f,.8f,.7f));var commits=0
        compose.setContent{Box(Modifier.size(400.dp,300.dp)){
            SubtitleFrameEditor(value.value,{value.value=it;commits++},Modifier.fillMaxSize())
        }}
        compose.onNodeWithTag("video_subtitle_frame_editor").performTouchInput {
            swipe(Offset(width*.5f,height*.5f),Offset(width*.6f,height*.6f),500)
        }
        compose.runOnIdle{
            assertEquals(1,commits)
            assertTrue(value.value.top>.3f);assertTrue(value.value.left>.2f)
            assertEquals(.6f,value.value.width,.0001f);assertEquals(.4f,value.value.height,.0001f)
        }
        val before=value.value
        compose.onNodeWithTag("video_subtitle_frame_editor").performTouchInput{
            swipe(Offset(width*before.right,height*before.bottom),Offset(width*.98f,height*.96f),500)
        }
        compose.runOnIdle{
            assertEquals(2,commits)
            assertEquals(before.left,value.value.left,.0001f);assertEquals(before.top,value.value.top,.0001f)
            assertTrue(value.value.width>before.width);assertTrue(value.value.height>before.height)
        }
    }
}
