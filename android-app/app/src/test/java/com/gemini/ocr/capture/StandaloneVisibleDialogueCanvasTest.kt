package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.overlay.TranslationOverlay
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Staged regression: execute the actual overlay RenderView.draw callback, never invoke a saved lambda. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1600dp-h900dp-mdpi")
class StandaloneVisibleDialogueCanvasTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private lateinit var overlay: TranslationOverlay
    private val first = "明天下午四点请在东门等我"
    private val next = "计划改变了我们改到西门见面"
    private val translated = "Chiều mai lúc bốn giờ hãy đợi tôi ở cổng đông."
    private val acceptedHistory = listOf("请不要迟到" to "Đừng đến muộn.")
    private lateinit var namespace: String
    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(service)
    private fun set(name: String, value: Any?) = field(name).set(service, value)
    private val pipeline get() = get("pipeline") as SubtitlePipeline
    @Suppress("UNCHECKED_CAST")
    private val translations get() = get("currentTranslations") as MutableMap<String, String>

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).apply {
            useAutoSubtitlePreset(); profile = "manga"; mode = "fast"
            translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT; excludedApps = ""
        }
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        StandaloneTranslator.clearDirectCache()
        acceptedHistory.forEach { StandaloneTranslator.recordContextPair(it.first, it.second) }
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translated) }
        SessionState.running.value = true; SessionState.connected.value = false
        SessionState.paused.value = false; SessionState.activityVisible.value = false
        SessionState.lastTranslation.value = ""; SessionState.lastOcr.value = ""
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        set("standaloneSession", true); set("lastSize", Pair(1600, 900))
        pipeline.updateRegion("visible-dialogue-regression")
        namespace = StandaloneTranslator.cacheNamespace("manga", "", acceptedHistory)
        set("sourceContextItems", acceptedHistory); set("sourceCacheNamespace", namespace)
        overlay = TranslationOverlay(service, AppSettings(service), {}, {})
        set("overlay", overlay)
    }

    @After fun cleanup() {
        StandaloneTranslator.overrideGeminiTranslator = null
        controller.destroy() // The service owns the actual overlay and closes it.
        StandaloneTranslator.clearDirectCache()
        SessionState.running.value = false; SessionState.paused.value = false
        SessionState.connected.value = false; SessionState.activityVisible.value = false
    }

    private fun pending(source: String, id: String): SubtitlePipeline.RequestToken {
        val signature = StableText.signature(listOf(SubtitleText.identity(source)))
        pipeline.observe(signature)
        val token = checkNotNull(pipeline.request(id))
        set("candidate", signature); set("pendingSignature", signature)
        set("pendingId", id); set("pendingToken", token); set("pendingSince", SystemClock.elapsedRealtime())
        set("pendingTexts", mapOf("b0" to source))
        set("lastBlocks", listOf(OcrBlock("b0", source, Rect(100, 600, 800, 650))))
        SessionState.lastOcr.value = source
        return token
    }

    private suspend fun translate(token: SubtitlePipeline.RequestToken, source: String): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = ScreenCaptureService::class.java.declaredMethods.single { it.name == "translateStandalone" }
                .apply { isAccessible = true }
            try {
                method.invoke(service, token, token.signature,
                    listOf(OcrBlock("b0", source, Rect(100, 600, 800, 650))), false,
                    namespace, acceptedHistory, continuation)
            } catch (error: InvocationTargetException) { throw error.targetException }
        }

    private fun render() = ScreenCaptureService::class.java.getDeclaredMethod("renderCurrent")
        .apply { isAccessible = true }.invoke(service)

    private fun makeResponseFrameFresh() {
        val received = get("replyAt") as Long
        check(received > 0)
        set("lastFrameAt", received + 1); set("lastFrameSourceAt", received + 1)
    }

    private fun drawActualRenderer() {
        val renderer = ReflectionHelpers.getField<View>(overlay, "renderer")
        assertEquals("A real visible overlay must be prepared before drawing", View.VISIBLE, renderer.visibility)
        assertTrue(ReflectionHelpers.getField<Boolean>(overlay, "rendererAttached"))
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(overlay, "layerParams")
        val width = params.width.takeIf { it > 0 } ?: 1600
        val height = params.height.takeIf { it > 0 } ?: 900
        renderer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        renderer.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { renderer.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    @Test fun acceptedAndFreshPreparedResultDoesNotBecomeDialogueUntilCanvasThenCachesKeepFrozenNamespace() = runBlocking {
        translate(pending(first, "waiting-for-canvas"), first)
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
        assertEquals(translated, StandaloneTranslator.lookupDirectLru(first, namespace))
        assertEquals("", SessionState.lastTranslation.value)
        makeResponseFrameFresh(); render()
        assertEquals("Prepared text is not a draw acknowledgement", acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
        assertEquals(translated, SessionState.lastTranslation.value)
        drawActualRenderer()
        assertEquals(acceptedHistory + (first to translated), StandaloneTranslator.getRecentContextSnapshot(2))
        assertEquals("The current source's request history stays frozen", acceptedHistory, get("sourceContextItems"))
        assertEquals(namespace, get("sourceCacheNamespace"))
        assertNull(StandaloneTranslator.lookupDirectLru(first,
            StandaloneTranslator.cacheNamespace("manga", "", listOf(first to translated))))
    }

    @Test fun anUndrawnFalseSourceCannotBecomeTheNextRequestsAcceptedTargetPrefix() = runBlocking {
        translate(pending("儿", "false-source"), "儿")
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
        // A new reliable source would freeze this snapshot, not the undrawn false line.
        pending(next, "next-source")
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot())
    }

    @Test fun preparedCallbackFromAnOldGenerationCannotCommitHistoryAtActualCanvasDraw() = runBlocking {
        translate(pending(first, "old-generation"), first)
        makeResponseFrameFresh(); render()
        pipeline.observe(StableText.signature(listOf(SubtitleText.identity(next))))
        drawActualRenderer() // Callback is still present, so the generation guard is exercised.
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun preparedCallbackFromAnOldRegionCannotCommitHistoryAtActualCanvasDraw() = runBlocking {
        translate(pending(first, "old-region"), first)
        makeResponseFrameFresh(); render()
        pipeline.updateRegion("different-roi")
        drawActualRenderer()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun repeatRenderAndCanvasDrawCommitTheVisibleDialogueOnlyOnce() = runBlocking {
        translate(pending(first, "draw-once"), first)
        makeResponseFrameFresh(); render(); drawActualRenderer()
        repeat(3) { render(); drawActualRenderer() }
        assertEquals(acceptedHistory + (first to translated), StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun canvasHistoryUsesFrozenDisplayedPairsInsteadOfAChangedMutableTranslationMap() = runBlocking {
        translate(pending(first, "freeze-visible-pairs"), first)
        makeResponseFrameFresh(); render()
        translations[SubtitleText.identity(first)] = "A later replacement has not been drawn."
        drawActualRenderer()
        assertEquals(acceptedHistory + (first to translated), StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun pausedOrUncertainCallbackCannotCommitDialogueAfterPreparation() = runBlocking {
        translate(pending(first, "paused-before-draw"), first)
        makeResponseFrameFresh(); render()
        SessionState.paused.value = true // No reset/clear: directly challenge the callback acceptance guard.
        drawActualRenderer()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun uncertainCallbackCannotCommitDialogueAfterPreparation() = runBlocking {
        translate(pending(first, "uncertain-before-draw"), first)
        makeResponseFrameFresh(); render()
        set("sourceObservationUncertain", true) // Keep callback present to test its own guard.
        drawActualRenderer()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun aVisibleStandaloneCacheOnlyCaptionAlsoBecomesDialogue() {
        pending(first, "cache-only")
        translations[SubtitleText.identity(first)] = translated
        set("replyAt", 0L); set("lastFrameSourceAt", Long.MAX_VALUE)
        render()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
        drawActualRenderer()
        assertEquals(acceptedHistory + (first to translated), StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun connectedServerCanvasCannotPopulateStandalonePhoneDialogueHistory() {
        pending(first, "connected-only")
        set("standaloneSession", false); SessionState.connected.value = true
        translations[SubtitleText.identity(first)] = translated
        set("replyAt", 0L); set("lastFrameSourceAt", Long.MAX_VALUE)
        render(); drawActualRenderer()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun partialCacheThenRemainingRowsCommitEachVisiblePairOnce() {
        pending(first, "partial-cache")
        set("lastBlocks", listOf(OcrBlock("b0", first, Rect(100, 600, 800, 650)),
            OcrBlock("b1", next, Rect(100, 660, 800, 710))))
        translations[SubtitleText.identity(first)] = translated
        set("replyAt", 0L); set("lastFrameSourceAt", Long.MAX_VALUE)
        render(); drawActualRenderer()
        val secondTranslation = "Đổi kế hoạch rồi, gặp nhau ở cổng tây."
        translations[SubtitleText.identity(next)] = secondTranslation
        render(); drawActualRenderer()
        assertEquals(listOf(first to translated, next to secondTranslation),
            StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun correctedSourceMetadataIsFrozenWithTheActualVisibleTarget() = runBlocking {
        translate(pending(first, "corrected-source"), first)
        @Suppress("UNCHECKED_CAST")
        val corrected = get("correctedVisibleSources") as MutableMap<String, String>
        val acceptedCorrection = "明天下午四点请在东门等我。"
        corrected[SubtitleText.identity(first)] = acceptedCorrection
        makeResponseFrameFresh(); render()
        corrected[SubtitleText.identity(first)] = "A later correction was never rendered."
        drawActualRenderer()
        assertEquals(acceptedHistory + (acceptedCorrection to translated),
            StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun replacedAcceptedRequestCannotCommitAnOlderPreparedCallback() = runBlocking {
        translate(pending(first, "old-display-request"), first)
        makeResponseFrameFresh(); render()
        set("displayRequestId", "a-newer-accepted-request")
        drawActualRenderer()
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
    }

    @Test fun explicitSelectedFrameCommitsOnlyAfterItsActualCanvas() = runBlocking {
        AppSettings(service).subtitleCapture = "frame"
        translate(pending(first, "explicit-frame"), first)
        assertEquals(translated, SessionState.lastTranslation.value)
        assertEquals(acceptedHistory, StandaloneTranslator.getRecentContextSnapshot(2))
        drawActualRenderer()
        assertEquals(acceptedHistory + (first to translated), StandaloneTranslator.getRecentContextSnapshot(2))
    }
}
