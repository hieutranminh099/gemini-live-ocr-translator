package com.gemini.ocr

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativePerformancePanelTest {
    @Test fun performanceTabShowsUnknownBeforeActualSamplingAndSwitchIsUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.gemini.ocr.codexbench" && !SessionState.running.value)
        AndroidPerformanceTelemetry.counters.setEnabled(false)
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val navigation = device.wait(Until.findObject(By.text("Hiệu năng")), 10000)
        assertNotNull(navigation); navigation!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("FPS nhận ảnh")), 5000))
        val toggle = device.findObject(By.desc("Đo hiệu năng"))
        assertNotNull(toggle)
        try {
            toggle!!.click()
            assertTrue(AndroidPerformanceTelemetry.snapshot.value.enabled)
            assertTrue(device.wait(Until.hasObject(By.text("Đang chờ mẫu đo từ phiên dịch.")), 3000))
            // Capture only after verifying this is the performance panel; never capture settings/key UI.
            val file = File(context.getExternalFilesDir(null), "verification/performance-panel-v1.png")
            check(!file.exists()); file.parentFile?.mkdirs()
            assertTrue(device.takeScreenshot(file))
            device.findObject(By.desc("Đo hiệu năng")).click()
            assertFalse(AndroidPerformanceTelemetry.snapshot.value.enabled)
        } finally { AndroidPerformanceTelemetry.counters.setEnabled(false) }
    }
}
