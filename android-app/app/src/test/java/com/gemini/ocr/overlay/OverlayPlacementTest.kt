package com.gemini.ocr.overlay

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OverlayPlacementTest {
    private val screen = Rect(0, 0, 1600, 900)

    @Test fun bottomSubtitleGetsAWindowAboveTheWholeOcrRegion() {
        val avoid = Rect(360, 790, 1140, 850)
        assertEquals(Rect(360, 722, 1140, 782),
            OverlayPlacement.outside(screen, avoid, 780, 60, 8))
    }

    @Test fun topSubtitleUsesSpaceBelowInsteadOfClampingOverSource() {
        val avoid = Rect(100, 0, 900, 70)
        assertEquals(Rect(200, 78, 800, 138),
            OverlayPlacement.outside(screen, avoid, 600, 60, 8))
    }

    @Test fun tallRegionUsesLeftThenRightWhenNoVerticalSpaceExists() {
        assertEquals(Rect(138, 400, 338, 500),
            OverlayPlacement.outside(screen, Rect(350, 0, 650, 900), 200, 100, 12))
        assertEquals(Rect(508, 400, 808, 500),
            OverlayPlacement.outside(screen, Rect(0, 0, 500, 900), 300, 100, 8))
    }

    @Test fun wideWindowStaysOnScreenNearEitherHorizontalEdge() {
        assertEquals(Rect(0, 640, 300, 690),
            OverlayPlacement.outside(screen, Rect(2, 700, 102, 800), 300, 50, 10))
        assertEquals(Rect(1300, 640, 1600, 690),
            OverlayPlacement.outside(screen, Rect(1498, 700, 1598, 800), 300, 50, 10))
    }

    @Test fun nonzeroScreenOriginIsPreserved() {
        assertEquals(Rect(800, 690, 1100, 790), OverlayPlacement.outside(
            Rect(100, 200, 1100, 1000), Rect(900, 800, 1080, 950), 300, 100, 10))
    }

    @Test fun fullScreenRegionAndOversizedWindowsHaveNoSafePlacement() {
        assertNull(OverlayPlacement.outside(screen, Rect(screen), 400, 100, 8))
        assertNull(OverlayPlacement.outside(screen, Rect(100, 100, 200, 200), 1601, 60, 8))
        assertNull(OverlayPlacement.outside(screen, Rect(100, 100, 200, 200), 60, 901, 8))
    }

    @Test fun invalidSizesDoNotProduceEmptyOrInvertedWindows() {
        val avoid = Rect(100, 100, 200, 200)
        assertNull(OverlayPlacement.outside(screen, avoid, 0, 50, 8))
        assertNull(OverlayPlacement.outside(screen, avoid, 50, -1, 8))
        assertNull(OverlayPlacement.outside(Rect(), avoid, 50, 50, 8))
    }

    @Test fun zeroOrNegativeGapAllowsTouchingEdgesWithoutOverlap() {
        val avoid = Rect(300, 100, 500, 300)
        assertEquals(Rect(300, 40, 500, 100), OverlayPlacement.outside(screen, avoid, 200, 60, 0))
        assertEquals(OverlayPlacement.outside(screen, avoid, 200, 60, 0),
            OverlayPlacement.outside(screen, avoid, 200, 60, -20))
    }

    @Test fun returnedWindowAlwaysKeepsItsSizeBoundsAndRequestedSeparation() {
        val regions = listOf(Rect(0, 0, 1600, 60), Rect(0, 840, 1600, 900),
            Rect(700, 0, 900, 900), Rect(10, 700, 1590, 890), Rect(0, 0, 1600, 900))
        for (avoid in regions) for (width in listOf(48, 400, 1600))
            for (height in listOf(48, 150, 800)) for (gap in listOf(0, 8, 40)) {
                val result = OverlayPlacement.outside(screen, avoid, width, height, gap) ?: continue
                assertEquals(width, result.width()); assertEquals(height, result.height())
                assertTrue(screen.contains(result)); assertFalse(Rect.intersects(result, avoid))
                assertTrue(result.bottom + gap <= avoid.top || result.top - gap >= avoid.bottom ||
                    result.right + gap <= avoid.left || result.left - gap >= avoid.right)
            }
    }
}
