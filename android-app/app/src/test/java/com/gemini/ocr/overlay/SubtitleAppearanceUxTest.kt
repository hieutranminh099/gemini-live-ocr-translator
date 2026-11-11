package com.gemini.ocr.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleAppearanceUxTest {
    @Test fun backgroundAndOutlineDefaultsAreCompatibleAndPersistIndependently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = AppSettings(context)
        assertTrue(settings.subtitleBackground); assertFalse(settings.subtitleOutline)
        val region = settings.subtitleRegion
        settings.subtitleBackground = false; settings.subtitleOutline = true
        assertFalse(AppSettings(context).subtitleBackground); assertTrue(AppSettings(context).subtitleOutline)
        assertEquals(region, settings.subtitleRegion)
    }
    @Test fun coverTransparencyAndBackgroundSwitchChangeActualCanvasPixels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context).apply { useSubtitlePreset(); subtitleBackground = true; subtitleOutline = false; opacity = .5f }
        val overlay = TranslationOverlay(context, settings, {}, {})
        try {
            val blocks = JSONArray().put(JSONObject().put("text", "Bản dịch xem trước").put("box",
                JSONObject().put("x", .1).put("y", .7).put("width", .8).put("height", .1)))
            overlay.show(blocks)
            val renderer = ReflectionHelpers.getField<View>(overlay, "renderer")
            renderer.layout(0, 0, 800, 160)
            fun corner(): Int { val bitmap = Bitmap.createBitmap(800, 160, Bitmap.Config.ARGB_8888)
                return try { renderer.draw(Canvas(bitmap)); Color.alpha(bitmap.getPixel(30, 30)) } finally { bitmap.recycle() } }
            val half = corner()
            assertTrue(half in 90..140)
            settings.opacity = 1f; assertTrue(corner() > half)
            settings.subtitleBackground = false; assertEquals(0, corner())
        } finally { overlay.close() }
    }
}
