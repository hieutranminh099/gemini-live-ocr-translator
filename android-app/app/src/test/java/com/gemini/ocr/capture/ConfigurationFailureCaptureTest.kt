package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.TranslationBackendState
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Exercise the real error handler and next-OCR send path without any socket or cloud calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigurationFailureCaptureTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private val reason = "Google không hỗ trợ dùng Antigravity CLI gói cá nhân làm backend ứng dụng bên thứ ba. Chọn Gemini API bằng khóa riêng."
    private val sourceA = "你好"
    private val sourceB = "今天休息"
    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun value(name: String): Any? = field(name).get(service)
    private fun set(name: String, value: Any?) = field(name).set(service, value)
    private fun invoke(name: String) = ScreenCaptureService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    private fun message(json: JSONObject) = ScreenCaptureService::class.java.getDeclaredMethod("onMessage", JSONObject::class.java)
        .apply { isAccessible = true }.invoke(service, json)
    private val pipeline: SubtitlePipeline get() = value("pipeline") as SubtitlePipeline
    private val retries: TranslationRetryState get() = value("retries") as TranslationRetryState
    private fun signature(text: String) = StableText.signature(listOf(SubtitleText.identity(text)))
    private fun key(text: String) = "zh|game|fast|${signature(text)}"

    @Before fun prepareServiceWithoutScreenCaptureOrNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings(context).apply {
            language = "zh"; profile = "game"; mode = "fast"
            region = "subtitle"; subtitleCapture = "auto"; excludedApps = ""
        }
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        SessionState.paused.value = false; SessionState.activityVisible.value = false
        SessionState.connected.value = true; SessionState.status.value = "Ready"
        SessionState.backend.value = TranslationBackendState()
        SessionState.lastTranslation.value = ""; SessionState.lastOcr.value = ""
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        set("latestPendingSupported", true)
        set("lastSize", Pair(1600, 900))
        pipeline.updateRegion("test-roi")
    }

    @After fun destroyService() {
        controller.destroy()
        SessionState.connected.value = false
        SessionState.backend.value = TranslationBackendState()
    }

    private fun observe(text: String) {
        pipeline.observe(signature(text))
        set("candidate", signature(text)); set("sentSignature", "")
        set("lastBlocks", listOf(OcrBlock("b0", text, Rect(100, 500, 500, 530))))
        @Suppress("UNCHECKED_CAST")
        (value("currentTranslations") as MutableMap<String, String>).clear()
        set("replyAt", 0L)
        SessionState.lastOcr.value = text
    }

    private fun pending(text: String = sourceA, requestId: String = "1"): SubtitlePipeline.RequestToken {
        observe(text)
        val token = pipeline.request(requestId)!!
        set("pendingToken", token); set("pendingId", requestId)
        set("pendingSignature", signature(text)); set("pendingTexts", mapOf("b0" to text))
        set("pendingSince", SystemClock.elapsedRealtime()); set("sequence", requestId.toLong())
        return token
    }

    private fun error(token: SubtitlePipeline.RequestToken, generation: Long = token.screenGeneration) = JSONObject()
        .put("type", "error").put("request_id", token.requestId).put("region_id", token.regionId)
        .put("screen_generation", generation).put("code", "cli_configuration").put("message", reason)

    @Test fun errorReasonSurvivesNextOcrAndDoesNotRetryAfterSixtySeconds() {
        message(error(pending()))
        assertNull(value("pendingToken"))
        assertEquals("", value("pendingId"))
        val sequence = value("sequence")
        repeat(3) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            invoke("sendLatest")
            assertEquals(sequence, value("sequence"))
            assertTrue(SessionState.status.value.startsWith(reason))
            assertFalse(SessionState.status.value.contains("Đang chờ thử lại Gemini"))
        }
    }

    @Test fun newSentenceCanAttemptServerCacheLookupAfterConfigurationFailure() {
        message(error(pending()))
        observe(sourceB)
        assertNull(retries.waiting(key(sourceB), SystemClock.elapsedRealtime()))
        invoke("sendLatest")
        // Network is deliberately absent: reaching send yields the real socket-failure status.
        assertEquals(2L, value("sequence"))
        assertTrue(SessionState.status.value.contains("Không gửi được sang Windows"))
        assertNotNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
    }

    @Test fun ramCachedSentenceRendersEvenWhileConfigurationFailureIsLatched() {
        message(error(pending()))
        set("memoryAllowed", true)
        (value("memory") as TranslationMemory).put("zh|game|fast|${SubtitleText.identity(sourceB)}", "Hôm nay nghỉ.")
        observe(sourceB)
        invoke("sendLatest")
        assertEquals(1L, value("sequence"))
        assertEquals("Hôm nay nghỉ.", SessionState.lastTranslation.value)
        assertTrue(SessionState.status.value.contains("Dùng cache trên điện thoại"))
        assertNotNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
    }

    @Test fun helloAndInvalidateReleaseConfigurationFailureForFreshLookup() {
        listOf("hello", "invalidate").forEach { type ->
            message(error(pending(requestId = "1")))
            assertNotNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
            message(JSONObject().put("type", type).put("provider", "api").put("cache_enabled", true)
                .put("capabilities", JSONArray().put("latest_pending")))
            assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
            observe(sourceA)
            invoke("sendLatest")
            assertEquals(2L, value("sequence"))
            assertTrue(SessionState.status.value.contains("Không gửi được sang Windows"))
        }
    }

    @Test fun helloAndInvalidateUpdateBackendModeAndReleaseOldPermanentFailure() {
        message(error(pending()))
        message(JSONObject().put("type", "hello").put("provider", "cli").put("backend_mode", "cache_only")
            .put("translation_available", false).put("experimental_cli_enabled", false))
        assertEquals("cache_only", SessionState.backend.value.mode)
        assertEquals(false, SessionState.backend.value.translationAvailable)
        assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
        message(JSONObject().put("type", "invalidate").put("provider", "cli").put("backend_mode", "cli_experimental")
            .put("translation_available", true).put("experimental_cli_enabled", true))
        assertEquals(TranslationBackendState("cli", "cli_experimental", true, true), SessionState.backend.value)
        assertEquals("cli", SessionState.provider.value)
    }

    @Test fun legacyHelloClearsPreviousAvailabilityInsteadOfPreservingExperimentalBadge() {
        SessionState.backend.value = TranslationBackendState("cli", "cli_experimental", true, true)
        message(JSONObject().put("type", "hello").put("provider", "cli"))
        assertEquals(TranslationBackendState(provider = "cli"), SessionState.backend.value)
        assertTrue(SessionState.backend.value.badge.contains("Chưa rõ"))
        assertFalse(SessionState.backend.value.badge.contains("Chỉ dùng cache"))
    }

    @Test fun explicitFrameActionReleasesFailureWhileOrdinaryResetKeepsIt() {
        message(error(pending()))
        ScreenCaptureService::class.java.getDeclaredMethod("resetFrame", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, false)
        observe(sourceA)
        invoke("sendLatest")
        assertEquals(1L, value("sequence"))
        assertTrue(SessionState.status.value.startsWith(reason))
        invoke("requestFrame")
        assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
        observe(sourceA)
        invoke("sendLatest")
        assertEquals(2L, value("sequence"))
    }

    @Test fun staleErrorForAIsDiscardedAndCannotLatchOrReplaceB() {
        val old = pending()
        observe(sourceB)
        SessionState.status.value = "Câu B đang hiện"
        message(error(old))
        assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
        assertNull(retries.waiting(key(sourceB), SystemClock.elapsedRealtime()))
        assertEquals(2L, value("sequence"))
        assertFalse(SessionState.status.value.contains(reason))
        assertEquals(sourceB, SessionState.lastOcr.value)
    }

    @Test fun errorWithWrongGenerationIsIgnoredWithoutReleasingCurrentRequest() {
        val token = pending()
        SessionState.status.value = "Đang dịch câu hiện tại"
        message(error(token, token.screenGeneration + 1))
        assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
        assertEquals(token, value("pendingToken"))
        assertEquals(token.requestId, value("pendingId"))
        assertEquals("Đang dịch câu hiện tại", SessionState.status.value)
        assertEquals(1L, value("sequence"))
    }

    @Test fun quotaRetryInfoUsesServerDeadlineAndBlocksDifferentUncachedSentence() {
        val now = SystemClock.elapsedRealtime()
        message(error(pending()).put("code", "rate_limited").put("message", "Quota reset")
            .put("retry_after_ms", 6000))
        assertEquals(now + 6000L, retries.waiting(key(sourceA), now)?.retryAt)
        observe(sourceB)
        invoke("sendLatest")
        assertEquals(1L, value("sequence"))
        assertTrue(SessionState.status.value.startsWith("Quota reset"))
        assertEquals(sourceB, SessionState.lastOcr.value)
    }

    @Test fun obsoleteSubtitleQuotaErrorStillProtectsCurrentSentenceWithoutReplacingItsText() {
        val old = pending()
        observe(sourceB)
        val now = SystemClock.elapsedRealtime()
        message(error(old).put("code", "rate_limited").put("message", "Provider cooldown")
            .put("retry_after_ms", 25000))
        assertEquals(1L, value("sequence"))
        assertEquals(sourceB, SessionState.lastOcr.value)
        assertEquals("", SessionState.lastTranslation.value)
        assertEquals(now + 25000L, retries.waiting(key(sourceB), now)?.retryAt)
    }

    @Test fun wronglyCorrelatedQuotaErrorCannotCreateProviderCooldown() {
        val token = pending()
        message(error(token, token.screenGeneration + 1).put("code", "rate_limited")
            .put("retry_after_ms", 25000))
        assertNull(retries.waiting(key(sourceA), SystemClock.elapsedRealtime()))
        assertEquals(token, value("pendingToken"))
    }

    @Test fun explicitRescanCannotBypassQuotaCooldown() {
        message(error(pending()).put("code", "rate_limited").put("message", "Quota reset")
            .put("retry_after_ms", 25000))
        invoke("requestFrame")
        observe(sourceB)
        invoke("sendLatest")
        assertEquals(1L, value("sequence"))
        assertNotNull(retries.waiting(key(sourceB), SystemClock.elapsedRealtime()))
        assertTrue(SessionState.status.value.startsWith("Quota reset"))
    }

    @Test fun ramCacheStillRendersDuringProviderCooldown() {
        message(error(pending()).put("code", "rate_limited").put("message", "Quota reset")
            .put("retry_after_ms", 25000))
        set("memoryAllowed", true)
        (value("memory") as TranslationMemory).put("zh|game|fast|${SubtitleText.identity(sourceB)}", "Hôm nay nghỉ.")
        observe(sourceB)
        invoke("sendLatest")
        assertEquals(1L, value("sequence"))
        assertEquals("Hôm nay nghỉ.", SessionState.lastTranslation.value)
        assertTrue(SessionState.status.value.contains("Dùng cache trên điện thoại"))
        assertNotNull(retries.waiting(key(sourceB), SystemClock.elapsedRealtime()))
    }
}
