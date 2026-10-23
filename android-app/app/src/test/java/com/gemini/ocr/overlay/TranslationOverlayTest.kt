package com.gemini.ocr.overlay

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranslationOverlayTest {
    @Test fun waitingFrameCanBeCancelledAndRetriedInsteadOfBecomingDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context).apply { useSubtitlePreset() }
        var captured = 0; var cancelled = 0
        val overlay = TranslationOverlay(context, settings, {}, {}, { captured++ }, {}, { cancelled++ })
        try {
            val button = ReflectionHelpers.getField<TextView>(overlay, "frameButton")
            assertEquals("1 KHUNG", button.text.toString())
            assertEquals("Dịch 1 khung phụ đề", button.contentDescription)
            button.performClick(); assertEquals(1, captured)
            overlay.busy(true); overlay.progress(12, "亏你还是韩国人")
            assertTrue(button.isEnabled); assertEquals("12s · HỦY", button.text.toString())
            button.performClick(); assertEquals(1, cancelled); assertEquals(1, captured)
            overlay.busy(false); button.performClick(); assertEquals(2, captured)
        } finally { overlay.close() }
    }

    @Test fun automaticModeOffersRescanWithoutImplyingThatEverySentenceNeedsATap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context).apply { useAutoSubtitlePreset() }
        var rescans = 0; var cancelled = 0
        val overlay = TranslationOverlay(context, settings, {}, {}, { rescans++ }, {}, { cancelled++ })
        try {
            val button = ReflectionHelpers.getField<TextView>(overlay, "frameButton")
            assertEquals("QUÉT LẠI", button.text.toString())
            assertTrue(button.contentDescription.toString().contains("tự động"))
            button.performClick()
            assertEquals(1, rescans)
            overlay.busy(true)
            assertEquals("HỦY AI", button.text.toString())
            button.performClick()
            assertEquals(1, cancelled)
            overlay.progress(2, "你好")
            assertEquals("2s · HỦY", button.text.toString())
            overlay.busy(false)
            assertEquals("QUÉT LẠI", button.text.toString())
            overlay.paused(true)
            val label = ReflectionHelpers.getField<TextView>(overlay, "statusLabel")
            assertTrue(label.text.contains("QUÉT LẠI"))
        } finally { overlay.close() }
    }
    @Test fun errorAndRecognizedTextAreVisibleWithoutOpeningMainScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context).apply { useSubtitlePreset() }
        val overlay = TranslationOverlay(context, settings, {}, {})
        try {
            val label = ReflectionHelpers.getField<TextView>(overlay, "statusLabel")
            overlay.busy(true); overlay.progress(3, "亏你还是韩国人")
            assertTrue(label.text.contains("OCR: 亏你还是韩国人")); assertNotNull(label.parent)
            overlay.busy(false); overlay.feedback("AI quá lâu • Chạm 1 KHUNG để thử lại", true)
            assertTrue(label.text.contains("thử lại"))
            assertTrue(ReflectionHelpers.getField<TextView>(overlay, "frameButton").isEnabled)
        } finally { overlay.close() }
    }
}
