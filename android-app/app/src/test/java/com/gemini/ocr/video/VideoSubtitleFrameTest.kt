@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.graphics.Color
import androidx.media3.common.util.Size
import org.json.JSONArray
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
class VideoSubtitleFrameTest {
    private val cue = VideoCue("one", 1000, 2500, "一百人", "Đừng đi: 100 người, không phải 10 người.")

    @Test fun legacyStyleRemainsUnframedAndOutputFrameRoundTripsWithoutChangingOcrRoi() {
        val old = VideoSubtitleStyle.from(JSONObject("""{"position":0.8,"sizeFraction":0.05,"background":true,"outline":true}"""))
        assertNull(old.frame)
        val inputRoi = listOf(.05f, .85f, .95f, .96f)
        val frame = VideoSubtitleFrame(.1f, .15f, .65f, .35f)
        val project = VideoProject("project", "video", "file:///video.mp4", 3000, 1280, 720,
            roi = inputRoi, style = old.copy(frame = frame), cues = listOf(cue))
        val restored = VideoProject.from(JSONObject(project.json().toString()))
        assertEquals(inputRoi, restored.roi)
        assertEquals(frame, restored.style.frame)
        assertEquals(old.position, restored.style.position, 0f)
        assertEquals(project.cues, restored.cues)
        assertEquals(VideoTimeline.srt(project.cues), VideoTimeline.srt(restored.cues))
    }

    @Test fun draggingAndResizingCannotLeaveVideoOrInvertTheRectangle() {
        val frame = VideoSubtitleFrame(.2f, .3f, .7f, .6f)
        val moved = frame.move(9f, -9f)
        assertEquals(1f, moved.right, .00001f)
        assertEquals(0f, moved.top, .00001f)
        assertEquals(frame.width, moved.width, .00001f)
        assertEquals(frame.height, moved.height, .00001f)
        val resized = frame.resize(SubtitleFrameHandle.TOP_LEFT, 9f, 9f)
        assertEquals(frame.right, resized.right, 0f)
        assertEquals(frame.bottom, resized.bottom, 0f)
        assertEquals(VideoSubtitleFrame.MIN_WIDTH, resized.width, .00001f)
        assertEquals(VideoSubtitleFrame.MIN_HEIGHT, resized.height, .00001f)
        val drawn = VideoSubtitleFrame.between(.8f, .9f, .3f, .2f)
        assertEquals(VideoSubtitleFrame(.3f, .2f, .8f, .9f), drawn)
        assertThrows(IllegalArgumentException::class.java) { VideoSubtitleFrame(Float.NaN, .1f, .9f, .2f) }
        assertThrows(IllegalArgumentException::class.java) { VideoSubtitleFrame.from(JSONArray("[-0.1,0.1,0.9,0.2]")) }
    }

    @Test fun customFrameMasksExactlySelectedRectangleAndAnchorsBothAxes() {
        val style = VideoSubtitleStyle(background = true, frame = VideoSubtitleFrame(.1f, .64f, .9f, .82f))
        val renderer = TimelineBitmapOverlay(listOf(cue), style)
        renderer.configure(Size(1280, 720))
        try {
            val bitmap = renderer.getBitmap(1_500_000)
            assertEquals(1024, bitmap.width)
            assertEquals(129, bitmap.height)
            assertEquals(Color.BLACK, bitmap.getPixel(0, 0))
            val bounds = renderer.bitmapBounds()
            assertEquals(128f, bounds.left, 0f)
            assertEquals(461f, bounds.top, 0f)
            assertEquals(1152f, bounds.right, 0f)
            assertEquals(590f, bounds.bottom, 0f)
            val settings = renderer.getOverlaySettings(1_500_000)
            assertEquals(2 * bounds.centerX() / 1280 - 1, settings.backgroundFrameAnchor.first, .00001f)
            assertEquals(1 - 2 * bounds.centerY() / 720, settings.backgroundFrameAnchor.second, .00001f)
            assertEquals(1, renderer.getBitmap(2_500_000).width)
            assertTrue(bitmap.isRecycled)
        } finally { renderer.release() }
    }

    @Test fun transparentFrameFitsTextAndAssUsesSameRectangleWithoutOverrideInjection() {
        val style = VideoSubtitleStyle(sizeFraction = .09f, background = false, outline = false,
            frame = VideoSubtitleFrame(.15f, .1f, .75f, .3f))
        val text = "Không đồng ý {\\pos(0,0)} với 250 người.\nGiữ tên Quân và số -3.5."
        val translated = cue.copy(translation = text)
        val measured = measureSubtitleFrame(text, style, 1280, 720)
        assertTrue(measured.paint.textSize < 720 * .09f)
        assertTrue(measured.layout.height + measured.padding * 2 <= measured.height)
        val renderer = TimelineBitmapOverlay(listOf(translated), style)
        renderer.configure(Size(1280, 720))
        try {
            val bitmap = renderer.getBitmap(1_500_000)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { Color.alpha(it) > 0 && Color.red(it) > 150 })
        } finally { renderer.release() }
        val project = VideoProject("project", "video", "file:///video.mp4", 3000, 1280, 720,
            style = style, cues = listOf(translated))
        val ass = VideoTimeline.ass(project)
        assertTrue(ass.contains("\\clip(192,72,960,216)"))
        assertTrue(ass.contains("｛＼pos(0,0)｝"))
        assertFalse(ass.contains("{\\pos(0,0)}"))
        assertEquals(1, ass.lineSequence().count { it.startsWith("Dialogue:") })
        val opaque = VideoTimeline.ass(project.copy(style = style.copy(background = true)))
        assertTrue(opaque.contains("m 0 0 l 768 0 768 144 0 144"))
        assertEquals(2, opaque.lineSequence().count { it.startsWith("Dialogue:") })
        assertEquals(VideoTimeline.srt(listOf(translated)), VideoTimeline.srt(project.cues))
    }
}
