package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoViewportTest {
    @Test fun portrait720x1280FitsWithoutStretchingOrTreatingSideBarsAsVideo() {
        val video = VideoViewport.calculate(720, 1280, 1920, 1080, fill = false)
        assertEquals(656.25f, video.left, .001f)
        assertEquals(0f, video.top, .001f)
        assertEquals(607.5f, video.width, .001f)
        assertEquals(1080f, video.height, .001f)
        assertFalse(video.contains(200f, 540f))
        assertTrue(video.contains(960f, 540f))
        assertEquals(.5f, video.normalizedX(960f), .00001f)
    }

    @Test fun landscape1920x1080FitsPortraitScreenAndFillsWithACenteredHorizontalCrop() {
        val fit = VideoViewport.calculate(1920, 1080, 720, 1280, fill = false)
        assertEquals(0f, fit.left, .001f)
        assertEquals(437.5f, fit.top, .001f)
        assertEquals(720f, fit.width, .001f)
        assertEquals(405f, fit.height, .001f)
        assertFalse(fit.contains(360f, 100f))

        val fill = VideoViewport.calculate(1920, 1080, 720, 1280, fill = true)
        assertEquals(1280f, fill.height, .001f)
        assertEquals(0f, fill.top, .001f)
        assertTrue(fill.left < 0f)
        assertEquals(720f, fill.left + fill.right, .001f)
        assertTrue(fill.normalizedX(0f) > 0f)
        assertTrue(fill.normalizedX(720f) < 1f)
        assertEquals(1920f / 1080f, fill.width / fill.height, .00001f)
    }

    @Test fun portraitFillCropsTopAndBottomWithoutChangingSourceCoordinates() {
        val video = VideoViewport.calculate(720, 1280, 1920, 1080, fill = true)
        assertEquals(1920f, video.width, .001f)
        assertEquals(0f, video.left, .001f)
        assertTrue(video.top < 0f)
        assertEquals(1080f, video.top + video.bottom, .001f)
        assertTrue(video.normalizedY(0f) > 0f)
        assertTrue(video.normalizedY(1080f) < 1f)
        assertEquals(.5f, video.normalizedY(540f), .00001f)
    }

    @Test fun rotatingTheScreenRetainsTheSameNormalizedOcrAndSubtitlePoint() {
        val portrait = VideoViewport.calculate(1920, 1080, 720, 1280, fill = false)
        val landscape = VideoViewport.calculate(1920, 1080, 1280, 720, fill = false)
        assertEquals(0f, landscape.left, .001f)
        assertEquals(0f, landscape.top, .001f)
        assertEquals(1280f, landscape.width, .001f)
        assertEquals(720f, landscape.height, .001f)
        for (video in listOf(portrait, landscape)) {
            assertEquals(.23f, video.normalizedX(video.screenX(.23f)), .00001f)
            assertEquals(.81f, video.normalizedY(video.screenY(.81f)), .00001f)
        }
        // The metadata reader has already exchanged source dimensions for a 90°/270° media rotation.
        val rotatedSource = VideoViewport.calculate(1080, 1920, 720, 1280, fill = false)
        assertEquals(0f, rotatedSource.left, .001f)
        assertEquals(0f, rotatedSource.top, .001f)
        assertEquals(720f, rotatedSource.width, .001f)
        assertEquals(1280f, rotatedSource.height, .001f)
    }

    @Test fun arbitraryAspectRatiosPreserveUniformScaleCenterAndPointRoundTrips() {
        for ((sourceWidth, sourceHeight) in listOf(720 to 1280, 1920 to 1080, 1000 to 1000, 2048 to 858, 640 to 480)) {
            for ((viewWidth, viewHeight) in listOf(720 to 1280, 1920 to 1080, 1137 to 811)) {
                for (fill in listOf(false, true)) {
                    val video = VideoViewport.calculate(sourceWidth, sourceHeight, viewWidth, viewHeight, fill)
                    assertEquals(video.width / sourceWidth, video.height / sourceHeight, .00001f)
                    assertEquals(viewWidth.toFloat(), video.left + video.right, .001f)
                    assertEquals(viewHeight.toFloat(), video.top + video.bottom, .001f)
                    if (fill) {
                        assertTrue(video.width + .001f >= viewWidth && video.height + .001f >= viewHeight)
                    } else {
                        assertTrue(video.width <= viewWidth + .001f && video.height <= viewHeight + .001f)
                    }
                    for (point in listOf(0f, .06f, .5f, .94f, 1f)) {
                        assertEquals(point, video.normalizedX(video.screenX(point)), .00001f)
                        assertEquals(point, video.normalizedY(video.screenY(point)), .00001f)
                    }
                }
            }
        }
    }

    @Test fun invalidDimensionsFailBeforeProducingNonFiniteGeometry() {
        listOf(listOf(0, 1080, 720, 1280), listOf(1920, -1, 720, 1280),
            listOf(1920, 1080, 0, 1280), listOf(1920, 1080, 720, -1)).forEach { dimensions ->
            assertThrows(IllegalArgumentException::class.java) {
                VideoViewport.calculate(dimensions[0], dimensions[1], dimensions[2], dimensions[3], false)
            }
        }
    }
}
