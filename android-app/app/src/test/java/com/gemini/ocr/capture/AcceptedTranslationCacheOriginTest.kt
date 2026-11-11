package com.gemini.ocr.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.AndroidPerformanceTelemetry
import com.gemini.ocr.PerformanceCacheMetric
import com.gemini.ocr.PerformanceCacheTier
import com.gemini.ocr.PerformanceTranslationSource
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** A fresh accepted model answer is current frame state, not a subsequent phone-cache hit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AcceptedTranslationCacheOriginTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private val source = "扶住他"
    private val accepted = "Đỡ lấy anh ấy."
    private val olderCache = "Chống hắn lại."
    private val namespace = "accepted-origin-regression"
    private val telemetry get() = AndroidPerformanceTelemetry.counters
    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(service)
    private fun set(name: String, value: Any?) = field(name).set(service, value)
    @Suppress("UNCHECKED_CAST")
    private val translations get() = get("currentTranslations") as MutableMap<String, String>
    @Suppress("UNCHECKED_CAST")
    private val origins get() = get("translationSources") as MutableMap<String, PerformanceTranslationSource>
    private fun sourceKey(): String = ScreenCaptureService::class.java.getDeclaredMethod("textKey", String::class.java)
        .apply { isAccessible = true }.invoke(service, source) as String
    private fun memoryKey(): String = ScreenCaptureService::class.java.getDeclaredMethod("memoryKey", String::class.java)
        .apply { isAccessible = true }.invoke(service, source) as String
    private fun lookup(): String? = ScreenCaptureService::class.java.getDeclaredMethod("lookupCachedTranslation", String::class.java)
        .apply { isAccessible = true }.invoke(service, source) as String?

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).apply {
            region = "subtitle"; subtitleCapture = "auto"
            translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT
        }
        StandaloneTranslator.clearDirectCache()
        SessionState.running.value = true; SessionState.connected.value = false
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        set("standaloneSession", true); set("sourceCacheNamespace", namespace)
        telemetry.setEnabled(false); telemetry.setEnabled(true)
    }

    @After fun cleanup() {
        controller.destroy()
        StandaloneTranslator.clearDirectCache()
        telemetry.setEnabled(false)
        SessionState.running.value = false; SessionState.connected.value = false
    }

    private fun acceptCurrentAnswer() {
        translations[sourceKey()] = accepted
        origins[sourceKey()] = PerformanceTranslationSource.ON_DEVICE
    }

    private fun assertNoCacheAttempt() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
        assertTrue(telemetry.sampleIfDue())
        val snapshot = telemetry.snapshot.value
        assertEquals(PerformanceCacheMetric(0, 0), snapshot.caches[PerformanceCacheTier.PHONE_EXACT])
        assertEquals(PerformanceCacheMetric(0, 0), snapshot.caches[PerformanceCacheTier.DIRECT_LRU])
    }

    @Test fun acceptedOnDeviceAnswerWinsOverAnOlderRamCacheWithoutRelabelingOrCounting() {
        val memory = get("memory") as TranslationMemory
        memory.put(memoryKey(), olderCache)
        StandaloneTranslator.putDirectLru(source, "Another older LRU translation.", namespace)
        acceptCurrentAnswer()
        repeat(3) { assertEquals(accepted, lookup()) }
        assertEquals(accepted, translations[sourceKey()])
        assertEquals(PerformanceTranslationSource.ON_DEVICE, origins[sourceKey()])
        assertEquals("The old cache row is preserved rather than rewritten by a lookup", olderCache, memory[memoryKey()])
        assertNoCacheAttempt()
    }

    @Test fun acceptedOnDeviceAnswerSkipsAnOlderDirectLruEvenWithoutARamEntry() {
        StandaloneTranslator.putDirectLru(source, olderCache, namespace)
        acceptCurrentAnswer()
        assertEquals(accepted, lookup())
        assertEquals(accepted, translations[sourceKey()])
        assertEquals(PerformanceTranslationSource.ON_DEVICE, origins[sourceKey()])
        assertEquals(0, (get("memory") as TranslationMemory).size)
        assertNoCacheAttempt()
    }

    @Test fun aSourceWithoutAnAcceptedAnswerStillPerformsAndAttributesARealCacheLookup() {
        StandaloneTranslator.putDirectLru(source, olderCache, namespace)
        assertEquals(olderCache, lookup())
        assertEquals(PerformanceTranslationSource.PHONE_CACHE, origins[sourceKey()])
        ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
        assertTrue(telemetry.sampleIfDue())
        val snapshot = telemetry.snapshot.value
        assertEquals(PerformanceCacheMetric(1, 0), snapshot.caches[PerformanceCacheTier.PHONE_EXACT])
        assertEquals(PerformanceCacheMetric(1, 0), snapshot.caches[PerformanceCacheTier.DIRECT_LRU])
    }
}
