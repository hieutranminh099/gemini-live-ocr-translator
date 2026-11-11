package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercise the real standalone result handler without MediaProjection, credentials or API calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StandaloneAcceptanceTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private val sourceA = "明天下午四点请在东门等我"
    private val sourceB = "计划改变了我们改到西门见面"
    private val translationA = "Chiều mai lúc bốn giờ hãy đợi tôi ở cổng đông."
    private val history = listOf("请不要迟到" to "Đừng đến muộn.")
    private lateinit var namespace: String
    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(service)
    private fun set(name: String, value: Any?) = field(name).set(service, value)
    private val pipeline: SubtitlePipeline get() = get("pipeline") as SubtitlePipeline
    @Suppress("UNCHECKED_CAST")
    private val translations: MutableMap<String, String> get() = get("currentTranslations") as MutableMap<String, String>

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        PhoneTranslationCache.clear(context)
        AppSettings(context).apply {
            region = "subtitle"; subtitleCapture = "auto"; profile = "manga"; mode = "fast"
            translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT; excludedApps = ""
        }
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        SessionState.running.value = true; SessionState.connected.value = false
        SessionState.paused.value = false; SessionState.activityVisible.value = false
        SessionState.lastTranslation.value = ""; SessionState.lastOcr.value = ""
        SessionState.frameToDraw.value = 0; SessionState.timings.value = ""
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        set("standaloneSession", true)
        set("lastSize", Pair(1600, 900))
        pipeline.updateRegion("test-standalone-roi")
        namespace = StandaloneTranslator.cacheNamespace("manga", "", history)
        set("sourceContextItems", history); set("sourceCacheNamespace", namespace)
        StandaloneTranslator.overrideGeminiTranslator = null
    }

    @After fun cleanup() {
        StandaloneTranslator.overrideGeminiTranslator = null
        controller.destroy()
        SessionState.running.value = false; SessionState.paused.value = false
        SessionState.connected.value = false
        PhoneTranslationCache.clear(null)
    }

    private fun pending(source: String, id: String): SubtitlePipeline.RequestToken {
        val signature = StableText.signature(listOf(SubtitleText.identity(source)))
        pipeline.observe(signature)
        val token = pipeline.request(id)!!
        set("candidate", signature); set("pendingSignature", signature)
        set("pendingId", id); set("pendingToken", token)
        set("pendingSince", SystemClock.elapsedRealtime())
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
                    namespace, history, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

    private fun uncertainSource() = ScreenCaptureService::class.java.getDeclaredMethod("deferUncertainSource")
        .apply { isAccessible = true }.invoke(service)

    @Test fun rateLimitReleasesCurrentRequestAndKeepsProviderCooldownAcrossNewSources():Unit=runBlocking {
        StandaloneTranslator.overrideGeminiTranslator={_,_,_,_,_->throw IOException("Đạt giới hạn lượt gọi Gemini API (429).")}
        translate(pending(sourceA,"quota"),sourceA)
        assertEquals("",get("pendingId"));assertTrue(translations.isEmpty())
        val retries=get("retries") as TranslationRetryState
        val now=SystemClock.elapsedRealtime()
        assertEquals("rate_limited",retries.waiting("different-source",now)?.code)
        assertTrue(retries.waiting("different-source",now)!!.retryAt!!-now>=60_000)
    }

    @Test fun resultWhileOwnActivityIsVisibleReleasesItsSlotWithoutDrawing():Unit=runBlocking {
        StandaloneTranslator.overrideGeminiTranslator={_,_,_,_,_->SessionState.activityVisible.value=true;listOf(translationA)}
        translate(pending(sourceA,"hidden"),sourceA)
        assertEquals("",get("pendingId"));assertNull(get("pendingToken"));assertTrue(translations.isEmpty())
        SessionState.activityVisible.value=false
        assertNotNull(pipeline.request("visible-again"))
    }

    @Test fun currentCoroutineCancellationDoesNotLeaveTheNextSubtitleWedged():Unit=runBlocking {
        StandaloneTranslator.overrideGeminiTranslator={_,_,_,_,_->throw kotlinx.coroutines.CancellationException("Synthetic cancellation")}
        try{translate(pending(sourceA,"cancelled"),sourceA);fail("Cancellation must propagate")}
        catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals("",get("pendingId"));assertNull(get("pendingToken"))
        assertNotNull(pipeline.request("after-cancellation"))
    }

    @Test fun untranslatedHistoricalModelOutputCannotDrawLearnOrRetryEveryOcrPass() = runBlocking {
        val source = "意图吞并我顾家"
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf("吞 吞 家 家") }
        val token = pending(source, "untranslated-han")
        translate(token, source)
        assertEquals("", SessionState.lastTranslation.value)
        assertTrue(translations.isEmpty())
        assertNull(StandaloneTranslator.lookupDirectLru(source, namespace))
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertEquals("", get("pendingId")); assertEquals(0L, get("pendingSince"))
        assertEquals(token.signature, get("sentSignature"))
        assertNull("Unchanged OCR must not issue the same failed model output repeatedly", pipeline.request("retry"))
        assertTrue(SessionState.status.value.contains("Chưa dịch được"))
        assertTrue(pipeline.accepts(pending(sourceB, "new-source-after-rejection")))
    }

    @Test fun staleUntranslatedOutputCannotClearTheNextSourcesPendingRequest() = runBlocking {
        val old = pending(sourceA, "old-untranslated")
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ ->
            pending(sourceB, "new-valid-pending")
            listOf("吞 吞 家 家")
        }
        translate(old, sourceA)
        assertEquals("new-valid-pending", get("pendingId"))
        assertEquals(sourceB, SessionState.lastOcr.value)
        assertTrue(translations.isEmpty())
        assertNull(StandaloneTranslator.lookupDirectLru(sourceA, namespace))
    }

    @Test fun nativeContractRejectionCannotRetryEveryOcrOrEnterCacheAndHistory() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ ->
            throw com.gemini.ocr.network.TranslationOutputGuard.RejectedOutput(
                com.gemini.ocr.network.TranslationOutputGuard.Reason.NATIVE_MODEL_CONTRACT)
        }
        val token = pending(sourceA, "native-contract-rejected")
        translate(token, sourceA)
        assertEquals("", get("pendingId"))
        assertTrue(translations.isEmpty())
        assertNull(StandaloneTranslator.lookupDirectLru(sourceA, namespace))
        assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
        assertNull(pipeline.request("same-source-native-retry"))
        assertEquals(token.signature, get("sentSignature"))
        assertTrue(pipeline.accepts(pending(sourceB, "next-source-native")))
    }

    @Test fun selectedFilmCannotSkipReviewedMemoryUsingAnOldGlobalMachineCache() {
        AppSettings(service).memoryFilmId = "fictional-reviewed-film"
        StandaloneTranslator.putDirectLru(sourceA, "Old unreviewed answer", namespace)
        val method = ScreenCaptureService::class.java.getDeclaredMethod("lookupCachedTranslation", String::class.java)
            .apply { isAccessible = true }
        assertNull(method.invoke(service, sourceA))
        assertEquals("Old unreviewed answer", StandaloneTranslator.lookupDirectLru(sourceA, namespace))
    }

    @Test fun confidenceUncertaintyHidesTheOldResultWithoutCancelingItsPendingToken() {
        val token = pending(sourceA, "confidence-uncertain")
        val job = Job(); set("standaloneJob", job)
        set("replyAt", 73L); set("lastFrameSourceAt", 81L)
        set("lastNativeOcr", get("lastBlocks"))
        SessionState.lastTranslation.value = translationA
        uncertainSource()
        assertEquals(true, get("sourceObservationUncertain"))
        assertEquals(0L, get("lastFrameSourceAt"))
        assertEquals("An uncertain image must not reset the response freshness deadline", 73L, get("replyAt"))
        assertEquals("", SessionState.lastTranslation.value)
        assertEquals("", SessionState.lastOcr.value)
        assertNull(get("lastNativeOcr")); assertNull(get("translatedFrame"))
        assertFalse(job.isCancelled); assertTrue(pipeline.accepts(token))
        assertEquals(token.requestId, get("pendingId"))
    }

    @Test fun cachedOldSourceCannotRenderOrDispatchDuringUncertaintyEvenWithoutAResponseDeadline() {
        pending(sourceA, "cached-uncertain")
        translations[SubtitleText.identity(sourceA)] = translationA
        set("replyAt", 0L); set("lastFrameSourceAt", Long.MAX_VALUE)
        uncertainSource()
        // A snapshot timestamp or a cache hit cannot act as a reliable source observation.
        set("lastFrameSourceAt", Long.MAX_VALUE)
        ScreenCaptureService::class.java.getDeclaredMethod("renderCurrent").apply { isAccessible = true }.invoke(service)
        ScreenCaptureService::class.java.getDeclaredMethod("sendLatest").apply { isAccessible = true }.invoke(service)
        assertEquals("", SessionState.lastTranslation.value)
        assertEquals("cached-uncertain", get("pendingId"))
        assertEquals(true, get("sourceObservationUncertain"))
    }

    @Test fun pendingReplyDuringUncertaintyCannotDrawOrPopulateTheNextSourceCache() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translationA) }
        val token = pending(sourceA, "reply-while-uncertain")
        uncertainSource()
        translate(token, sourceA)
        assertEquals("", SessionState.lastTranslation.value)
        assertTrue((get("replyAt") as Long) > 0)
        assertEquals("The accepted answer remains attached to its frozen source namespace", translationA,
            StandaloneTranslator.lookupDirectLru(sourceA, namespace))
        assertNull(StandaloneTranslator.lookupDirectLru(sourceB, namespace))
        // A later reliable B observation invalidates A. It cannot select A's cached translation.
        pending(sourceB, "reliable-next")
        set("sourceObservationUncertain", false); set("lastFrameSourceAt", Long.MAX_VALUE)
        ScreenCaptureService::class.java.getDeclaredMethod("renderCurrent").apply { isAccessible = true }.invoke(service)
        assertEquals("", SessionState.lastTranslation.value)
        assertFalse(pipeline.accepts(token))
        assertNull(StandaloneTranslator.lookupDirectLru(sourceB,
            StandaloneTranslator.cacheNamespace("manga", "", listOf(sourceA to translationA))))
    }

    @Test fun aReliableBlankAfterUncertaintyStillInvalidatesThePendingResult() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translationA) }
        val token = pending(sourceA, "uncertain-then-blank")
        uncertainSource()
        pipeline.observe("")
        ScreenCaptureService::class.java.getDeclaredMethod("cancelSupersededSourceRequest")
            .apply { isAccessible = true }.invoke(service)
        set("sourceObservationUncertain", false)
        translate(token, sourceA)
        assertEquals("", SessionState.lastTranslation.value)
        assertNull(StandaloneTranslator.lookupDirectLru(sourceA, namespace))
        assertFalse(pipeline.accepts(token)); assertEquals("", get("pendingId"))
    }

    @Test fun newerStandaloneSourceCancelsTheOldQueuedJobAndReleasesPending() {
        pending(sourceA, "old-queued")
        val oldJob = Job()
        set("standaloneJob", oldJob)
        pipeline.observe(StableText.signature(listOf(SubtitleText.identity(sourceB))))
        ScreenCaptureService::class.java.getDeclaredMethod("cancelSupersededSourceRequest")
            .apply { isAccessible = true }.invoke(service)
        assertTrue(oldJob.isCancelled)
        assertEquals("", get("pendingId"))
        assertNull(get("pendingToken"))
        assertTrue(pipeline.accepts(pending(sourceB, "new-current")))
    }

    @Test fun connectedAutoKeepsItsExistingServerSchedulingPolicy() {
        SessionState.connected.value = true
        set("standaloneSession", false)
        pending(sourceA, "server-queued")
        val oldJob = Job()
        set("standaloneJob", oldJob)
        ScreenCaptureService::class.java.getDeclaredMethod("cancelSupersededSourceRequest")
            .apply { isAccessible = true }.invoke(service)
        assertFalse(oldJob.isCancelled)
        assertEquals("server-queued", get("pendingId"))
    }

    @Test fun acceptedAutoOutcomeWaitsForFreshOcrAndUsesOnlyItsFrozenCacheNamespace() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translationA) }
        val token = pending(sourceA, "accepted")
        translate(token, sourceA)
        assertEquals("Auto response must wait for a post-response OCR frame", "", SessionState.lastTranslation.value)
        val replyAt = get("replyAt") as Long
        assertTrue(replyAt > 0)
        val wakeup = get("captureWakeup") as CaptureWakeup
        assertEquals(CaptureWakeup.Reason.RESPONSE, withTimeout(1000) { wakeup.awaitNext(5000) })
        // The OCR consumer updates lastFrameAt only after it accepts that fresh frame.
        set("lastFrameAt", replyAt)
        set("lastFrameSourceAt", replyAt)
        ScreenCaptureService::class.java.getDeclaredMethod("renderCurrent")
            .apply { isAccessible = true }.invoke(service)
        assertEquals(translationA, SessionState.lastTranslation.value)
        assertEquals(translationA, translations[SubtitleText.identity(sourceA)])
        assertEquals(translationA, StandaloneTranslator.lookupDirectLru(sourceA, namespace))
        assertNull(StandaloneTranslator.lookupDirectLru(sourceA))
        assertNull(StandaloneTranslator.lookupDirectLru(sourceA,
            StandaloneTranslator.cacheNamespace("manga", "", emptyList())))
        assertTrue("Preparing text without an actual Canvas must not commit dialogue",
            StandaloneTranslator.getRecentContextSnapshot().isEmpty())
    }

    @Test fun explicitOneFrameOutcomeCanRenderItsSelectedFrameImmediately() = runBlocking {
        AppSettings(ApplicationProvider.getApplicationContext<Context>()).subtitleCapture = "frame"
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translationA) }
        val token = pending(sourceA, "explicit-frame")
        translate(token, sourceA)
        assertEquals(translationA, SessionState.lastTranslation.value)
        assertEquals(0L, get("replyAt"))
    }
    @Test fun newSnapshotTimestampDoesNotMakeARetainedOldImageFresh() = runBlocking {
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ -> listOf(translationA) }
        val token = pending(sourceA, "hardware-freshness")
        translate(token, sourceA)
        val replyAt = get("replyAt") as Long
        set("lastFrameAt", replyAt + 10)
        set("lastFrameSourceAt", replyAt - 1)
        val render = ScreenCaptureService::class.java.getDeclaredMethod("renderCurrent").apply { isAccessible = true }
        render.invoke(service)
        assertEquals("", SessionState.lastTranslation.value)
        set("lastFrameSourceAt", replyAt)
        render.invoke(service)
        assertEquals(translationA, SessionState.lastTranslation.value)
    }

    private suspend fun lateCompletionCannotChangeTheNextSource(fails: Boolean) {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        StandaloneTranslator.overrideGeminiTranslator = { _, _, _, _, _ ->
            started.complete(Unit); release.await()
            if (fails) throw IOException("fake old request failed")
            listOf(translationA)
        }
        kotlinx.coroutines.coroutineScope {
            val first = pending(sourceA, "old")
            val completion = async(start = CoroutineStart.UNDISPATCHED) { translate(first, sourceA) }
            withTimeout(3000) { started.await() }
            val next = pending(sourceB, "new")
            SessionState.lastTranslation.value = "Câu mới đang chờ"
            release.complete(Unit)
            withTimeout(3000) { completion.await() }
            assertEquals("new", get("pendingId"))
            assertTrue(pipeline.accepts(next))
            assertFalse(pipeline.accepts(first))
            assertEquals("Câu mới đang chờ", SessionState.lastTranslation.value)
            assertTrue(translations.isEmpty())
            assertTrue(StandaloneTranslator.getRecentContextSnapshot().isEmpty())
            assertNull(StandaloneTranslator.lookupDirectLru(sourceA, namespace))
        }
    }

    @Test fun oldSuccessCannotWriteAfterTheSubtitleChanges() = runBlocking {
        lateCompletionCannotChangeTheNextSource(fails = false)
    }

    @Test fun oldFailureCannotClearANewerPendingRequest() = runBlocking {
        lateCompletionCannotChangeTheNextSource(fails = true)
    }
    @Test fun connectedPhoneCacheCannotReuseAContextFreeTranslation() {
        set("standaloneSession", false); SessionState.connected.value = true
        val key = ScreenCaptureService::class.java.getDeclaredMethod("memoryKey", String::class.java)
            .apply { isAccessible = true }.invoke(service, sourceA) as String
        @Suppress("UNCHECKED_CAST")
        val memory = get("memory") as TranslationMemory
        memory.put(key, "A translation from a different dialogue")
        val lookup = ScreenCaptureService::class.java.getDeclaredMethod("lookupCachedTranslation", String::class.java)
            .apply { isAccessible = true }
        assertNull(lookup.invoke(service, sourceA))
    }

    private fun connectedReplyMayNotPopulateThePhoneCache(stale: Boolean) {
        set("standaloneSession", false); SessionState.connected.value = true
        set("memoryAllowed", true); set("latestPendingSupported", true)
        val token = pending(sourceA, "connected-dialogue")
        if (stale) pipeline.observe(StableText.signature(listOf(SubtitleText.identity(sourceB))))
        val result = org.json.JSONObject().put("type", "result").put("request_id", token.requestId)
            .put("region_id", token.regionId).put("screen_generation", token.screenGeneration)
            .put("latency_ms", 200).put("blocks", org.json.JSONArray().put(org.json.JSONObject()
                .put("id", "b0").put("text", translationA)))
        ScreenCaptureService::class.java.getDeclaredMethod("onMessage", org.json.JSONObject::class.java)
            .apply { isAccessible = true }.invoke(service, result)
        @Suppress("UNCHECKED_CAST")
        val memory = get("memory") as TranslationMemory
        assertEquals(0, memory.size)
        if (stale) assertTrue(translations.isEmpty())
        else assertEquals(translationA, translations[SubtitleText.identity(sourceA)])
    }

    @Test fun acceptedConnectedResultStaysInTheCurrentFrameInsteadOfAnUnscopedCache() {
        connectedReplyMayNotPopulateThePhoneCache(stale = false)
    }

    @Test fun obsoleteConnectedResultCannotPoisonTheNextDialoguesPhoneCache() {
        connectedReplyMayNotPopulateThePhoneCache(stale = true)
    }

}
