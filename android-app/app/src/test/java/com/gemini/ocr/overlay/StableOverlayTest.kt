package com.gemini.ocr.overlay

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.capture.SubtitleRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp-mdpi")
class StableOverlayTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun blocks(y: Double = .84) = JSONArray().put(JSONObject().put("id", "b0").put("text", "Sáng mai gặp lại nhé.")
        .put("box", JSONObject().put("x", .15).put("y", y).put("width", .7).put("height", .05)))
    private fun params(o: TranslationOverlay, name: String) = ReflectionHelpers.getField<WindowManager.LayoutParams>(o, name)
    private fun rect(p: WindowManager.LayoutParams, height: Int = p.height) = Rect(p.x, p.y, p.x+p.width, p.y+height)
    private fun roi(s: AppSettings): Rect = context.resources.displayMetrics.let { SubtitleRegion.pixels(s.subtitleRegion,it.widthPixels,it.heightPixels) }

    @Test fun automaticSamplingKeepsCaptionAndControlsVisibleAndOutsideTheOcrStrip() {
        val settings = AppSettings(context).apply { useAutoSubtitlePreset() }
        val overlay = TranslationOverlay(context,settings,{}, {})
        try {
            overlay.show(blocks())
            val renderer=ReflectionHelpers.getField<View>(overlay,"renderer")
            val controls=ReflectionHelpers.getField<LinearLayout>(overlay,"controls")
            repeat(20) {
                overlay.sampling(true)
                assertEquals(View.VISIBLE, renderer.visibility)
                assertEquals(1f, controls.alpha)
                overlay.sampling(false)
            }
            assertFalse(Rect.intersects(roi(settings),rect(params(overlay,"layerParams"))))
            assertTrue(overlay.prepareForCapture())
        } finally { overlay.close() }
    }

    @Test fun identicalCaptionDoesNotMoveOrReplaceItsRenderDataWhenOcrBoxesJitter() {
        val settings=AppSettings(context).apply { useAutoSubtitlePreset() }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            val first=blocks();overlay.show(first)
            val initial=Rect(rect(params(overlay,"layerParams")))
            repeat(20) { overlay.show(blocks(.84+it*.0001)) }
            assertEquals(initial,rect(params(overlay,"layerParams")))
            val renderer=ReflectionHelpers.getField<View>(overlay,"renderer")
            assertSame(first,ReflectionHelpers.getField<JSONArray>(renderer,"blocks"))
        } finally { overlay.close() }
    }

    @Test fun manualFrameRetainsInPlaceCoverAndItsSingleCaptureCanHide() {
        val settings=AppSettings(context).apply { useSubtitlePreset() }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            overlay.show(blocks())
            assertTrue(Rect.intersects(roi(settings),rect(params(overlay,"layerParams"))))
            val renderer=ReflectionHelpers.getField<View>(overlay,"renderer")
            overlay.sampling(true);assertEquals(View.INVISIBLE,renderer.visibility)
            overlay.sampling(false);assertEquals(View.VISIBLE,renderer.visibility)
        } finally { overlay.close() }
    }

    @Test fun draggedControlsAndLongStatusAreMovedOutOfTheSourceRegion() {
        val settings=AppSettings(context).apply { useAutoSubtitlePreset() }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            overlay.feedback("OCR: "+"long text ".repeat(30))
            val controls=ReflectionHelpers.getField<LinearLayout>(overlay,"controls")
            val p=params(overlay,"bubbleParams");p.y=roi(settings).top;p.x=roi(settings).left
            controls.measure(View.MeasureSpec.makeMeasureSpec(p.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            controls.layout(0,0,p.width,controls.measuredHeight)
            assertTrue(overlay.prepareForCapture())
            assertFalse(Rect.intersects(roi(settings),rect(p,controls.measuredHeight)))
        } finally { overlay.close() }
    }

    @Test fun impossibleFullScreenRegionDoesNotSilentlyOcrItsOwnControls() {
        val settings=AppSettings(context).apply { useAutoSubtitlePreset();subtitleRegion=RectF(0f,0f,1f,1f) }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            assertFalse(overlay.prepareForCapture())
            val label=ReflectionHelpers.getField<TextView>(overlay,"statusLabel")
            assertTrue(label.text.contains("Thu hẹp VÙNG"))
        } finally { overlay.close() }
    }

    @Test fun captionIsNotShownWhenItWouldLeaveNoSafeSpaceForControls() {
        val settings=AppSettings(context).apply { useAutoSubtitlePreset();subtitleRegion=RectF(0f,.16f,1f,1f) }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            overlay.show(blocks())
            assertEquals(View.INVISIBLE,ReflectionHelpers.getField<View>(overlay,"renderer").visibility)
            overlay.feedback("Đã dịch")
            assertTrue(ReflectionHelpers.getField<TextView>(overlay,"statusLabel").text.contains("Thu hẹp VÙNG"))
        } finally { overlay.close() }
    }

    @Test fun relayoutAlsoClearsCaptionWhenTheNewRegionHasNoSafeSpace() {
        val settings=AppSettings(context).apply { useAutoSubtitlePreset() }
        val overlay=TranslationOverlay(context,settings,{}, {})
        try {
            overlay.show(blocks())
            assertEquals(View.VISIBLE,ReflectionHelpers.getField<View>(overlay,"renderer").visibility)
            settings.subtitleRegion=RectF(0f,.16f,1f,1f)
            overlay.updateBounds()
            assertEquals(View.INVISIBLE,ReflectionHelpers.getField<View>(overlay,"renderer").visibility)
        } finally { overlay.close() }
    }
}
