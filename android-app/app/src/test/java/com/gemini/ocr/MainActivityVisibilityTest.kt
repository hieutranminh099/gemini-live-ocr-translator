package com.gemini.ocr

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityVisibilityTest {
    @Test fun pausedButVisibleAppStaysExcludedUntilItHasActuallyStopped() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            assertTrue("Started activities are already visible before they gain focus", SessionState.activityVisible.value)
            controller.resume()
            assertTrue(SessionState.activityVisible.value)
            controller.pause()
            assertTrue("Permission dialogs/transitions can pause the app while its pixels remain visible",
                SessionState.activityVisible.value)
            controller.stop()
            assertFalse(SessionState.activityVisible.value)
            controller.restart().start().resume()
            assertTrue(SessionState.activityVisible.value)
        } finally {
            controller.pause().stop().destroy()
            SessionState.activityVisible.value = false
        }
    }
}
