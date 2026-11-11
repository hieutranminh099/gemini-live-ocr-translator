package com.gemini.ocr

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class YouTubeViewportTest {
    @Test fun observedTabletSplitLayoutCannotAuthorizeFullScreenSubtitleCoordinates() {
        assertFalse(YouTubeViewport.coversDisplay(Rect(0, 36, 1040, 621), 1600, 900))
        assertTrue(YouTubeViewport.coversDisplay(Rect(0, 0, 1600, 900), 1600, 900))
        assertFalse(YouTubeViewport.coversDisplay(null, 1600, 900))
        assertFalse(YouTubeViewport.coversDisplay(Rect(0, 0, 1600, 900), 900, 1600))
    }

    @Test fun fullSizeRootOrAnEnterFullscreenButtonCannotAuthorizeTheSubtitleRoi() {
        val screen = Rect(0, 0, 1600, 900)
        val splitVideo = Rect(0, 36, 1040, 621)
        assertFalse(YouTubeViewport.isFullscreen(screen, splitVideo, "Thoát chế độ toàn màn hình", 1600, 900))
        assertFalse(YouTubeViewport.isFullscreen(screen, screen, "Chuyển sang chế độ toàn màn hình", 1600, 900))
        assertFalse(YouTubeViewport.isFullscreen(screen, screen, null, 1600, 900))
        assertFalse(YouTubeViewport.isFullscreen(null, screen, "Exit full screen", 1600, 900))
        assertTrue(YouTubeViewport.isFullscreen(screen, screen, "Thoát chế độ toàn màn hình", 1600, 900))
        assertTrue(YouTubeViewport.isFullscreen(screen, screen, "Exit full screen", 1600, 900))
    }
}
