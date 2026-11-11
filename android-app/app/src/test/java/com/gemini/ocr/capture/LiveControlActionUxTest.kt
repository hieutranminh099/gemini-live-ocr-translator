package com.gemini.ocr.capture

import android.content.Intent
import com.gemini.ocr.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveControlActionUxTest {
    @Test fun inactiveRegionControlCannotStartProjectionWithoutConsent() = inactive("SELECT_REGION")
    @Test fun inactiveFrameControlCannotStartProjectionWithoutConsent() = inactive("CAPTURE_FRAME")
    private fun inactive(action: String) {
        SessionState.running.value = false
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent().setAction(action), 0, 1)
            assertFalse(SessionState.running.value)
            assertNull(ReflectionHelpers.getField<Any?>(service, "projection"))
            assertNull(ReflectionHelpers.getField<Any?>(service, "regionSelector"))
        } finally { controller.destroy() }
    }
}
