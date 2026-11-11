@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoEpisodeSwipeTest {
    @Test fun rotatedFullscreenUsesTheSameTextureInTheViewHierarchy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = android.view.LayoutInflater.from(context).inflate(
            com.gemini.ocr.R.layout.video_fullscreen_player, null, false) as FullscreenPlayerView
        val texture = view.videoSurfaceView
        assertTrue(texture is android.view.TextureView)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1600, 900)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 900, 1600)
        assertSame(texture, view.videoSurfaceView)
        assertEquals(900, texture?.width)
        assertEquals(1600, texture?.height)
    }

    @Test fun downwardAndUpwardBodyDragsRequestAdjacentEpisodeWithoutDoubleTap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var doubles = 0
        val view = FullscreenPlayerView(context) { doubles++ }
        val density = context.resources.displayMetrics.density
        view.layout(0, 0, (400 * density).toInt(), (800 * density).toInt())
        val requests = mutableListOf<Int>()
        view.onEpisodeSwipe = { requests += it; true }
        drag(view, 200f, 300f, 200f, 420f, density)
        drag(view, 200f, 500f, 200f, 350f, density)
        assertEquals(listOf(1, -1), requests)
        assertEquals(0, doubles)
    }

    @Test fun horizontalSeekSmallDragsAndSystemOrControllerEdgesNeverSwitchEpisode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = FullscreenPlayerView(context) {}
        val density = context.resources.displayMetrics.density
        view.layout(0, 0, (400 * density).toInt(), (800 * density).toInt())
        var requests = 0
        view.onEpisodeSwipe = { requests++; true }
        drag(view, 100f, 300f, 300f, 330f, density)
        drag(view, 200f, 300f, 200f, 330f, density)
        drag(view, 10f, 300f, 10f, 450f, density)
        drag(view, 200f, 30f, 200f, 180f, density)
        drag(view, 200f, 740f, 200f, 580f, density)
        assertEquals(0, requests)
    }

    private fun drag(view: FullscreenPlayerView, x1: Float, y1: Float, x2: Float, y2: Float, density: Float) {
        val start = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x1 * density, y1 * density, 0)
        val move = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_MOVE, x2 * density, y2 * density, 0)
        val up = MotionEvent.obtain(start, start + 200, MotionEvent.ACTION_UP, x2 * density, y2 * density, 0)
        try { view.onTouchEvent(down); view.onTouchEvent(move); view.onTouchEvent(up) }
        finally { down.recycle(); move.recycle(); up.recycle() }
    }
}
