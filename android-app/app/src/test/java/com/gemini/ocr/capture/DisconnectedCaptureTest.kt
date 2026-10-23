package com.gemini.ocr.capture

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DisconnectedCaptureTest {
    private fun verify(manual: Boolean, connected: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        settings.region = "subtitle"; settings.subtitleCapture = if (manual) "frame" else "auto"
        settings.excludedApps = ""
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        SessionState.paused.value = false; SessionState.activityVisible.value = false
        SessionState.connected.value = connected; SessionState.status.value = "Đang đọc một khung phụ đề…"
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        val service = controller.get()
        fun field(name: String) = ScreenCaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("lastBlocks").set(service, listOf(OcrBlock("b0", "你好", Rect(0,0,50,30))))
        field("candidate").set(service, "2:你好")
        (field("pipeline").get(service) as SubtitlePipeline).observe("2:你好")
        try {
            ScreenCaptureService::class.java.getDeclaredMethod("sendLatest").apply { isAccessible = true }.invoke(service)
            assertFalse(SessionState.status.value.contains("Đang đọc"))
            assertTrue(SessionState.status.value.contains(if (connected) "Không gửi được" else "Chưa kết nối Windows"))
            assertEquals("", field("pendingId").get(service))
            if (connected) assertNotNull((field("pipeline").get(service) as SubtitlePipeline).request("retry"))
        } finally { controller.destroy(); SessionState.connected.value = false }
    }
    @Test fun autoFrameWithOfflineWindowsDoesNotKeepReadingLabel() = verify(false, false)
    @Test fun manualFrameWithOfflineWindowsReportsConnectionFailure() = verify(true, false)
    @Test fun failedSocketSendReportsFailureAndAllowsRetry() = verify(false, true)
}
