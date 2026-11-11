package com.gemini.ocr.video

import android.graphics.Color
import androidx.media3.common.util.Size
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleBackdropTest {
    private val cue = VideoCue("one", 100, 900, "你好", "Xin chào", origin = "OCR")
    private fun project() = VideoProject("fixture", "Fixture", "content://fixture/video", 1000, 1280, 720,
        sourceMode = VideoSourceMode.OCR, cues = listOf(cue))

    @Test fun defaultIsTransparentOutlinedYellowAndBlurIsLimitedToOriginalCaptionHeight() {
        val project = project()
        val plan = requireNotNull(subtitleBackdropPlan(project))
        assertFalse(project.style.background)
        assertTrue(project.style.outline)
        assertTrue(project.style.yellowText)
        assertTrue(project.style.blurOriginal)
        assertEquals(.09f, plan.bottom - plan.top, .00001f)
        assertEquals(.84f, (plan.bottom + plan.top) / 2, .00001f)
        assertFalse(plan.activeAt(99_999))
        assertTrue(plan.activeAt(100_000))
        assertTrue(plan.activeAt(899_999))
        assertFalse(plan.activeAt(900_000))
        val renderer = TimelineBitmapOverlay(project.cues, project.style)
        renderer.configure(Size(1280, 720))
        try {
            val image = renderer.getBitmap(500_000)
            assertEquals(0, Color.alpha(image.getPixel(0, 0)))
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            assertTrue(pixels.any { Color.alpha(it) > 200 && Color.red(it) > 240 && Color.green(it) > 220 && Color.blue(it) < 80 })
        } finally { renderer.release() }
        assertTrue(VideoTimeline.ass(project).contains("&H003BEBFF"))
    }

    @Test fun narrowRoiIsExactAndSpeechOrBlankRowsNeverBlurThePicture() {
        val project = project().copy(roi = listOf(.2f, .8f, .8f, .85f))
        val plan = requireNotNull(subtitleBackdropPlan(project))
        assertEquals(.2f, plan.left, 0f)
        assertEquals(.8f, plan.top, .00001f)
        assertEquals(.8f, plan.right, 0f)
        assertEquals(.85f, plan.bottom, .00001f)
        assertNull(subtitleBackdropPlan(project.copy(sourceMode = VideoSourceMode.ASR)))
        assertNull(subtitleBackdropPlan(project.copy(cues = listOf(cue.copy(origin = "ASR")))))
        assertNull(subtitleBackdropPlan(project.copy(cues = listOf(cue.copy(translation = "")))))
        assertNull(subtitleBackdropPlan(project.copy(style = project.style.copy(blurOriginal = false))))
    }

    @Test fun resumedPlaybackAndBackwardSeekUseAbsoluteCueBoundaries() {
        val plan = requireNotNull(subtitleBackdropPlan(project()))
        assertTrue(plan.activeAt(0, playbackStartMs = 500))
        assertFalse(plan.activeAt(-400_001, playbackStartMs = 500))
        assertTrue(plan.activeAt(-400_000, playbackStartMs = 500))
        assertTrue(plan.activeAt(399_999, playbackStartMs = 500))
        assertFalse(plan.activeAt(400_000, playbackStartMs = 500))
    }

    @Test fun legacyStockGetsDisplayUpgradeWhileExplicitBlackAndCustomStylesRoundTrip() {
        val old = JSONObject("""{"position":0.86,"sizeFraction":0.045,"background":true,"outline":true}""")
        val legacy = VideoSubtitleStyle.from(old)
        assertTrue(legacy.background)
        assertTrue(legacy.legacyDefault)
        assertFalse(legacy.json().has("blurOriginal"))
        assertEquals(VideoSubtitleStyle(), legacy.displayStyle())
        assertEquals(legacy, VideoSubtitleStyle.from(legacy.json()))
        val explicitBlack = VideoSubtitleStyle(background = true, blurOriginal = false, yellowText = false)
        val restored = VideoSubtitleStyle.from(explicitBlack.json())
        assertEquals(explicitBlack, restored)
        assertEquals(explicitBlack, restored.displayStyle())
        assertNull(subtitleBackdropPlan(project().copy(style = restored)))
        val custom = VideoSubtitleStyle.from(old.put("position", .7))
        assertEquals(custom, custom.displayStyle())
    }
}
