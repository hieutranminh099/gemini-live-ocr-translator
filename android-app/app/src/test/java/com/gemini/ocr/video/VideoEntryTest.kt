package com.gemini.ocr.video

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gemini.ocr.MainActivity
import com.gemini.ocr.SessionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun videoButtonOpensIndependentActivityWithoutStartingCapture() {
        compose.runOnIdle { SessionState.running.value = false }
        compose.onNodeWithTag("open_video_translation").performScrollTo().performClick()
        compose.runOnIdle {
            val launched = shadowOf(compose.activity).nextStartedActivity
            assertNotNull(launched)
            assertEquals(VideoActivity::class.java.name, launched.component?.className)
            assertFalse(SessionState.running.value)
        }
    }
}
