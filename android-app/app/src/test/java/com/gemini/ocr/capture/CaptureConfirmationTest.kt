package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** The real result handler must request fresh OCR, rather than render an unconfirmed reply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureConfirmationTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private lateinit var pipeline: SubtitlePipeline
    private lateinit var wakeup: CaptureWakeup

    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun message(value: JSONObject) = ScreenCaptureService::class.java.getDeclaredMethod("onMessage", JSONObject::class.java)
        .apply { isAccessible = true }.invoke(service, value)
    private fun response() = JSONObject().put("type", "result").put("request_id", "1").put("latency_ms", 200)
        .put("blocks", JSONArray().put(JSONObject().put("id", "b0").put("text", "Xin chào")))

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).apply { region = "subtitle"; subtitleCapture = "auto"; excludedApps = "" }
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        SessionState.paused.value = false; SessionState.activityVisible.value = false
        SessionState.connected.value = false; SessionState.lastTranslation.value = ""
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        pipeline = field("pipeline").get(service) as SubtitlePipeline
        wakeup = field("captureWakeup").get(service) as CaptureWakeup
        pipeline.observe("source-A")
        field("candidate").set(service, "source-A")
        field("pendingToken").set(service, pipeline.request("1"))
        field("pendingId").set(service, "1")
        field("pendingSignature").set(service, "source-A")
        field("pendingTexts").set(service, mapOf("b0" to "你好"))
        field("pendingSince").setLong(service, SystemClock.elapsedRealtime() - 200)
        field("lastBlocks").set(service, listOf(OcrBlock("b0", "你好", Rect(0, 0, 50, 30))))
    }

    @After fun cleanup() { controller.destroy(); SessionState.paused.value = false; SessionState.connected.value = false }

    @Test fun acceptedReplyWakesFreshCaptureWithoutRenderingBeforeOcr() = runBlocking {
        message(response())
        assertEquals(CaptureWakeup.Reason.RESPONSE, withTimeout(1000) { wakeup.awaitNext(5000) })
        assertEquals("", SessionState.lastTranslation.value)
        assertTrue(field("replyAt").getLong(service) > 0)
    }

    @Test fun obsoleteReplyCannotWakeOrRenderThePreviousSource() = runBlocking {
        pipeline.observe("source-B")
        field("candidate").set(service, "source-B")
        message(response())
        assertEquals("", SessionState.lastTranslation.value)
        assertEquals(0L, field("replyAt").getLong(service))
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { wakeup.awaitNext(5000); fail("Obsolete response woke capture") }
        assertTrue(waiting.isActive)
        waiting.cancelAndJoin()
    }
}
