package com.gemini.ocr

import android.graphics.Rect

/** Re-read after layout animation; the existence of watch_player alone is not fullscreen. */
internal object YouTubeViewport {
    fun coversDisplay(bounds: Rect?, width: Int, height: Int): Boolean =
        bounds != null && width > 0 && height > 0 &&
            bounds.width() >= width * .95 && bounds.height() >= height * .85 &&
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height

    fun isFullscreen(playerBounds: Rect?, videoBounds: Rect?, actionDescription: String?, width: Int, height: Int): Boolean =
        coversDisplay(playerBounds, width, height) && coversDisplay(videoBounds, width, height) &&
            actionDescription in setOf("Thoát chế độ toàn màn hình", "Exit full screen", "Exit fullscreen")
}
