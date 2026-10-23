package com.gemini.ocr.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.TranslationBackendState
import com.gemini.ocr.network.TranslationClient
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Real service handlers and client.send, with a recording socket and no connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CapturePriorityStateTest {
    private lateinit var controller: ServiceController<ScreenCaptureService>
    private lateinit var service: ScreenCaptureService
    private lateinit var settings: AppSettings
    private lateinit var client: TranslationClient
    private lateinit var clientScope: CoroutineScope
    private lateinit var socket: RecordingSocket

    private class RecordingSocket : WebSocket {
        val sent = mutableListOf<JSONObject>()
        override fun request(): Request = Request.Builder().url("https://127.0.0.1:8765").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(name: String) = ScreenCaptureService::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(service)
    private fun message(json: JSONObject) = ScreenCaptureService::class.java.getDeclaredMethod("onMessage", JSONObject::class.java)
        .apply { isAccessible = true }.invoke(service, json)
    private fun hello(vararg capabilities: String) = message(JSONObject().put("type", "hello")
        .put("provider", "api").put("cache_enabled", true).put("capabilities", JSONArray(capabilities.toList())))
    private fun captureStates() = socket.sent.filter { it.optString("type") == "capture_state" }
        .map { it.getBoolean("active") }

    @Before fun createServiceWithRecordingSocketAndNoNetworkConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettings(context).apply {
            pin = "0".repeat(64); host = "127.0.0.1:8765"
            region = "subtitle"; subtitleCapture = "auto"; excludedApps = ""
        }
        SessionState.paused.value = false
        SessionState.connected.value = true
        SessionState.activityVisible.value = false
        SessionState.backend.value = TranslationBackendState()
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller.get()
        clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        client = TranslationClient(settings, clientScope, {}, {})
        socket = RecordingSocket()
        TranslationClient::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
        TranslationClient::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(client, true)
        field("network").set(service, client)
    }

    @After fun releaseServiceAndClientResources() {
        controller.destroy()
        clientScope.cancel()
        SessionState.paused.value = false
        SessionState.connected.value = false
        SessionState.backend.value = TranslationBackendState()
    }

    @Test fun legacyServerNeverReceivesUnsupportedCaptureStatePackets() {
        invoke("toggle")
        invoke("toggle")
        hello("latest_pending")
        invoke("requestFrame")
        invoke("cancelPending")
        assertTrue(captureStates().isEmpty())
        assertTrue(socket.sent.any { it.optString("type") == "cancel" })
    }

    @Test fun capableHelloAdvertisesCurrentPausedStateInsteadOfActivatingCapture() {
        SessionState.paused.value = true
        hello("latest_pending", "capture_state_v1")
        assertEquals(listOf(false), captureStates())
        assertTrue(SessionState.paused.value)
    }

    @Test fun activeHelloThenPauseAndResumeSendsTheActualCaptureState() {
        hello("capture_state_v1")
        invoke("toggle")
        assertTrue(SessionState.paused.value)
        invoke("toggle")
        assertFalse(SessionState.paused.value)
        assertEquals(listOf(true, false, true), captureStates())
    }

    @Test fun explicitFrameFromPausedStateReactivatesLivePriority() {
        SessionState.paused.value = true
        hello("capture_state_v1")
        invoke("requestFrame")
        assertFalse(SessionState.paused.value)
        assertEquals(listOf(false, true), captureStates())
        assertEquals(true, field("frameRequested").get(service))
    }

    @Test fun cancellingAutomaticTranslationPausesCaptureAndReleasesVideoGate() {
        hello("capture_state_v1")
        invoke("cancelPending")
        assertTrue(SessionState.paused.value)
        assertEquals(listOf(true, false), captureStates())
    }

    @Test fun sourceDisappearanceCancelsOnlyTranslationWhileCaptureKeepsObserving() {
        hello("capture_state_v1")
        socket.sent.clear()
        val pipeline = field("pipeline").get(service) as SubtitlePipeline
        pipeline.observe("visible-source")
        pipeline.observe("")
        invoke("cancelFrame")
        assertFalse(SessionState.paused.value)
        assertTrue(socket.sent.any { it.optString("type") == "cancel" })
        assertTrue(captureStates().isEmpty())
    }

    @Test fun oneFrameCancelDoesNotPretendTheCaptureWasPaused() {
        settings.subtitleCapture = "frame"
        hello("capture_state_v1")
        invoke("cancelPending")
        assertFalse(SessionState.paused.value)
        assertEquals(listOf(true, true), captureStates())
    }

    @Test fun reconnectingToLegacyServerRemovesPreviouslyNegotiatedCapability() {
        hello("capture_state_v1")
        socket.sent.clear()
        hello("latest_pending")
        invoke("toggle")
        assertTrue(captureStates().isEmpty())
    }
}
