package com.gemini.ocr.video

import android.content.Context
import android.content.pm.ActivityInfo
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/** Fullscreen changes presentation only. The Activity handles rotation without rebuilding playback. */
@Composable
internal fun VideoFullscreenWindow(fullscreen: Boolean, landscape: Boolean) {
    val activity = checkNotNull(LocalActivity.current)
    DisposableEffect(fullscreen, activity, landscape) {
        if (!fullscreen) return@DisposableEffect onDispose { }
        val window = activity.window
        val decor = window.decorView
        val orientation = activity.requestedOrientation
        val flags = decor.systemUiVisibility
        val keepScreenOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val insets = ViewCompat.getRootWindowInsets(decor)
        val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val controller = WindowCompat.getInsetsController(window, decor)
        val behavior = controller.systemBarsBehavior
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose {
            activity.requestedOrientation = orientation
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.systemBarsBehavior = behavior
            if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
            decor.systemUiVisibility = flags
            if (!keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FullscreenPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : PlayerView(context, attrs) {
    constructor(context: Context, onDoubleTap: () -> Unit) : this(context, null) { this.onDoubleTap = onDoubleTap }
    var onDoubleTap: () -> Unit = {}
    init {
        controllerAutoShow = false
        controllerShowTimeoutMs = 2500
        controllerHideOnTouch = true
    }
    /** Body gestures only: the controller's seekbar/buttons keep their own touch dispatch. */
    var onEpisodeSwipe: ((Int) -> Boolean)? = null
    private var downX = 0f
    private var downY = 0f
    private var swipeStarted = false
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()
        override fun onDoubleTap(event: MotionEvent): Boolean { this@FullscreenPlayerView.onDoubleTap(); return true }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!useController) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                val density = resources.displayMetrics.density
                swipeStarted = onEpisodeSwipe != null && event.x in 24 * density..width - 24 * density &&
                    event.y in 64 * density..height - 96 * density
            }
            MotionEvent.ACTION_UP -> {
                val direction = if (swipeStarted) episodeSwipeDirection(
                    event.x - downX, event.y - downY, resources.displayMetrics.density) else 0
                swipeStarted = false
                if (direction != 0 && onEpisodeSwipe?.invoke(direction) == true) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel); cancel.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeStarted = false
        }
        return gestures.onTouchEvent(event)
    }
}

/** A downward swipe means the next saved episode; horizontal scrubs never navigate. */
internal fun episodeSwipeDirection(dx: Float, dy: Float, density: Float): Int =
    if (abs(dy) >= 72 * density && abs(dy) > abs(dx) * 1.6f) if (dy > 0) 1 else -1 else 0
