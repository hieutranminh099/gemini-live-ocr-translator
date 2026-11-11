package com.gemini.ocr.movie

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.MainActivity
import com.gemini.ocr.SessionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovieNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val reset = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("video-google-sync", Context.MODE_PRIVATE).edit().clear().commit()
            SessionState.running.value = false
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @Test fun libraryIsBesideDialogueAndExistingSettingsAndPerformanceRemainReachable() {
        val dialogue = compose.onNodeWithText("Kho thoại", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val movies = compose.onNodeWithText("Kho phim", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Tùy chỉnh", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(dialogue.center.x < movies.center.x && movies.center.x < settings.center.x)
        compose.onNodeWithText("Kho phim", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("movie_library").assertExists()
        compose.onNodeWithText("Kho phim đang trống").assertExists()
        compose.onNodeWithText("Tùy chỉnh", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithText("Hiệu năng", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Kho phim", useUnmergedTree = true).assertExists()
    }

    @Test fun libraryOpensBatchWithoutStartingTranslation() {
        compose.onNodeWithText("Kho phim", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("movie_new_batch").performClick()
        compose.runOnIdle {
            val next = shadowOf(compose.activity).nextStartedActivity
            org.junit.Assert.assertEquals("com.gemini.ocr.batch.BatchActivity", next.component?.className)
            org.junit.Assert.assertNull(com.gemini.ocr.video.VideoJobs.get(compose.activity).busy.value)
        }
    }
}
