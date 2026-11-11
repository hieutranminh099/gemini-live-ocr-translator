package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

/** Floating-point edge coordinates must not turn a valid next drag into an empty clamp range. */
class VideoFrameGeometryRegressionTest {
    @Test fun minimumFrameMovedAgainstTopLeftCanStillResizeBothLeadingEdges() {
        // 0.9f - 0.8f rounds below 0.1f; the persisted frame validator permits that float tolerance.
        val moved = VideoSubtitleFrame(.8f, .8f, .9f, .84f).move(-9f, -9f)
        assertEquals(0f, moved.left, 0f)
        assertEquals(0f, moved.top, 0f)
        val resized = moved.resize(SubtitleFrameHandle.TOP_LEFT, .01f, .01f)
        assertNormalized(resized)
        assertEquals(moved.right, resized.right, 0f)
        assertEquals(moved.bottom, resized.bottom, 0f)
    }

    @Test fun validToleranceAtBottomRightDoesNotInvertTrailingEdgeLimits() {
        val frame = VideoSubtitleFrame(.900005f, .960005f, 1f, 1f)
        val resized = frame.resize(SubtitleFrameHandle.BOTTOM_RIGHT, -.01f, -.01f)
        assertNormalized(resized)
        assertEquals(frame.left, resized.left, 0f)
        assertEquals(frame.top, resized.top, 0f)
    }

    @Test fun repeatedMinimumResizesMovesAndCornerChangesKeepAUsableNormalizedFrame() {
        var frame = VideoSubtitleFrame(.13f, .71f, .91f, .94f)
        repeat(30) { index ->
            frame = frame.resize(SubtitleFrameHandle.BOTTOM_RIGHT, -9f, -9f)
            frame = frame.move(if(index % 2 == 0) -9f else 9f, if(index % 3 == 0) -9f else 9f)
            for(handle in SubtitleFrameHandle.entries) {
                frame = frame.resize(handle, if(handle.left) 9f else -9f, if(handle.top) 9f else -9f)
                assertNormalized(frame)
            }
            frame = frame.resize(SubtitleFrameHandle.BOTTOM_RIGHT, 9f, 9f)
            assertNormalized(frame)
        }
    }

    private fun assertNormalized(frame: VideoSubtitleFrame) {
        assertTrue(listOf(frame.left, frame.top, frame.right, frame.bottom).all { it.isFinite() && it in 0f..1f })
        assertTrue(frame.width >= VideoSubtitleFrame.MIN_WIDTH - .00001f)
        assertTrue(frame.height >= VideoSubtitleFrame.MIN_HEIGHT - .00001f)
    }
}
