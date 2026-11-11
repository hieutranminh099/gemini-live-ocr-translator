package com.gemini.ocr.batch

import com.gemini.ocr.SessionState
import com.gemini.ocr.capture.ScreenCaptureService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchVisibilityTest {
    @Test fun documentListRemainsExcludedFromLiveWhilePausedAndDoesNotClearVideoVisibility() {
        SessionState.activityVisible.value = false
        SessionState.videoActivityVisible.value = false
        SessionState.batchActivityVisible.value = false
        val controller = Robolectric.buildActivity(BatchActivity::class.java).create().start().resume()
        try {
            controller.pause()
            assertTrue("A picker/transition can pause the private list while it is still visible", SessionState.batchActivityVisible.value)
            val blocked = ScreenCaptureService::class.java.getDeclaredMethod("blocked").apply { isAccessible = true }
            assertTrue("Live must not capture filenames from this app's private project list", blocked.invoke(ScreenCaptureService()) as Boolean)
            SessionState.videoActivityVisible.value = true
            controller.stop()
            assertFalse(SessionState.batchActivityVisible.value)
            assertTrue("Stopping the batch list cannot expose a newly opened video screen to Live", SessionState.videoActivityVisible.value)
        } finally {
            controller.destroy()
            SessionState.batchActivityVisible.value = false
            SessionState.videoActivityVisible.value = false
        }
    }
}
