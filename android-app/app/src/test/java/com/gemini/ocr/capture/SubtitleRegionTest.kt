package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubtitleRegionTest {
    @Test fun regionIsClippedAndScalesAfterRotation() {
        val region = RectF(.08f, .78f, .92f, .96f)
        assertEquals(Rect(128, 702, 1472, 864), SubtitleRegion.pixels(region, 1600, 900))
        assertEquals(Rect(72, 1248, 828, 1536), SubtitleRegion.pixels(region, 900, 1600))
        assertEquals(Rect(0, 0, 1600, 900), SubtitleRegion.pixels(RectF(-1f, -1f, 2f, 2f), 1600, 900))
    }
    @Test fun splitSubtitleBecomesOneOrderedSentenceWithScreenCoordinates() {
        val crop = Rect(100, 700, 1500, 880)
        val merged = SubtitleRegion.merge(listOf(OcrBlock("second", "world", Rect(30, 50, 190, 80)),
            OcrBlock("first", "Hello", Rect(20, 5, 200, 40))), crop).single()
        assertEquals("Hello world", merged.text)
        assertEquals(Rect(120, 705, 300, 780), merged.rect)
        assertTrue(SubtitleRegion.merge(emptyList(), crop).isEmpty())
    }
    @Test fun presetPreservesPairingAndDoesNotResetCustomRegion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        settings.host = "saved-computer:8765"; settings.subtitleRegion = RectF(.1f, .7f, .9f, .9f)
        settings.useSubtitlePreset()
        assertTrue(settings.subtitleFocus); assertEquals("frame", settings.subtitleCapture)
        assertEquals("cover", settings.overlay); assertEquals("fast", settings.mode)
        assertEquals("saved-computer:8765", settings.host)
        assertEquals(RectF(.1f, .7f, .9f, .9f), settings.subtitleRegion)
    }
}
