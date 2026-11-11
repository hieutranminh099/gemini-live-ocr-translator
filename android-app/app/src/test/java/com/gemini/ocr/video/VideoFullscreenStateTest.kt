@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
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
import java.io.File

/** State/ownership checks only; native tests must separately prove decoded video and caption pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFullscreenStateTest {
    private val compose = createAndroidComposeRule<VideoActivity>()
    private val id = "ddee1122-3344-5566-7788-99aabbccddee"
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            VideoJobs::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            SessionState.running.value = false
            val source = File(context.cacheDir, "fullscreen-state.mp4").apply { writeBytes(byteArrayOf()) }
            VideoStore(context).save(VideoProject(id, "Fullscreen state fixture", source.toURI().toString(),
                6000, 1920, 1080, stage = VideoStage.REVIEW, progress = 100,
                cues = listOf(VideoCue("one", 1000, 3000, "不是三个", "Không phải ba."))))
            VideoJobs.get(context).refresh()
        }

        override fun after() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.filesDir, "video-projects/$id/project.json").delete()
            File(context.cacheDir, "fullscreen-state.mp4").delete()
            VideoJobs.get(context).refresh()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun enteringAndExitingMovesTheExistingPlayerAndRestoresOrientation() {
        openSavedProject()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_fullscreen_enter"))
        lateinit var original: Player
        val saved = VideoStore(compose.activity).load(id)
        compose.runOnIdle {
            original = requireNotNull(findPlayer(compose.activity.window.decorView)?.player)
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
        compose.onNodeWithTag("video_fullscreen_enter").performClick()
        compose.runOnIdle {
            assertSame(original, findPlayer(compose.activity.window.decorView)?.player)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)
            // Fullscreen starts unobstructed; an intentional touch reveals transient controls.
            requireNotNull(findPlayer(compose.activity.window.decorView)).showController()
        }
        compose.onNodeWithTag("video_fullscreen_exit").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(original, findPlayer(compose.activity.window.decorView)?.player)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, compose.activity.requestedOrientation)
            val after = VideoStore(compose.activity).load(id)
            assertEquals(saved.cues, after.cues)
            assertEquals(saved.roi, after.roi)
            assertEquals(saved.style, after.style)
            assertEquals(saved.cloudCalls, after.cloudCalls)
            assertNull(VideoJobs.get(compose.activity).busy.value)
        }
    }

    @Test fun androidBackExitsFullscreenBeforeClosingVideoActivity() {
        openSavedProject()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_fullscreen_enter"))
        lateinit var original: Player
        compose.runOnIdle { original = requireNotNull(findPlayer(compose.activity.window.decorView)?.player) }
        compose.onNodeWithTag("video_fullscreen_enter").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("video_fullscreen_enter").assertExists()
        compose.runOnIdle {
            assertFalse(compose.activity.isFinishing)
            assertSame(original, findPlayer(compose.activity.window.decorView)?.player)
        }
    }

    private fun openSavedProject() {
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_library"))
        compose.onNodeWithTag("video_library").performClick()
        compose.onNodeWithText("Fullscreen state fixture · 100%").performClick()
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
