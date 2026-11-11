package com.gemini.ocr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Opt-in UI witness confined to a separate package; never touches installed product data. */
@RunWith(AndroidJUnit4::class)
class OceanUiNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val editor = By.desc(Pattern.compile("Chọn vùng phụ đề:.*"))

    private fun requireDisposablePackage() {
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.oceanbench")
        require(!SessionState.running.value)
        require(StandaloneTranslator.beforeNetworkCallForTest == null)
        require(StandaloneTranslator.overrideGeminiTranslator == null)
        require(AppSettings(context).effectiveStandaloneApiKey().isBlank())
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(80)
        assertTrue(message, condition())
    }

    private fun top() {
        repeat(7) {
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 3,
                device.displayWidth * 4 / 5, device.displayHeight * 4 / 5, 16)
        }
        device.waitForIdle()
    }

    private fun click(text: String) {
        repeat(8) {
            val found = device.findObject(By.pkg(context.packageName).text(text))
            if (found != null) {
                found.click()
                device.waitForIdle()
                return
            }
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight * 4 / 5,
                device.displayWidth * 4 / 5, device.displayHeight / 3, 20)
        }
        fail("Visible app action: $text")
    }

    private fun capture(folder: File, name: String, screenshots: JSONArray) {
        device.waitForIdle()
        SystemClock.sleep(350)
        val file = File(folder, "$name.png")
        assertTrue(device.takeScreenshot(file))
        screenshots.put(file.name)
    }

    @Test(timeout = 180_000L)
    fun portraitLandscapeAndExistingShortcuts(): Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeOcean") == "true")
        requireDisposablePackage()
        val folder = File(context.getExternalFilesDir(null), "ocean-ui-fixtures").apply { mkdirs() }
        val report = JSONObject().put("completed", false).put("scope", "Actual UI, portrait/landscape, existing settings; no cloud")
        val screenshots = JSONArray()
        report.put("screenshots", screenshots)
        val attempts = AtomicInteger()
        val settings = AppSettings(context)
        val previousCapture = settings.subtitleCapture
        val previousRegion = settings.region
        StandaloneTranslator.beforeNetworkCallForTest = { attempts.incrementAndGet(); error("Cloud forbidden in Ocean UI witness") }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitUntil("Portrait viewport") { device.displayHeight > device.displayWidth }
                top()
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Bắt đầu dịch")), 8_000))
                capture(folder, "ocean-live-portrait", screenshots)
                click("Một lần")
                assertEquals("frame", settings.subtitleCapture)
                click("Tự động")
                assertEquals("auto", settings.subtitleCapture)
                click("Chọn vùng phụ đề")
                assertTrue(device.wait(Until.hasObject(By.text("Toàn màn hình")), 5_000))
                capture(folder, "ocean-region-choices", screenshots)
                click("Toàn màn hình")
                assertEquals("full", settings.region)
                click("Chọn vùng phụ đề")
                click("Dải phụ đề")
                assertEquals("subtitle", settings.region)
                capture(folder, "ocean-live-portrait-controls", screenshots)
                click("Cài đặt")
                assertTrue(device.wait(Until.hasObject(By.text("Thiết lập cho thiết bị này")), 5_000))
                capture(folder, "ocean-existing-settings", screenshots)
                device.pressBack()
                device.waitForIdle()
                click("Kết nối")
                assertTrue(device.wait(Until.hasObject(By.text("Khóa Gemini API")), 5_000))
                capture(folder, "ocean-existing-connection", screenshots)
                device.pressBack()
                device.waitForIdle()
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitUntil("Landscape viewport") { device.displayWidth > device.displayHeight }
                top()
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Bắt đầu dịch")), 5_000))
                capture(folder, "ocean-live-landscape", screenshots)
                click("Một lần")
                assertEquals("frame", settings.subtitleCapture)
                assertFalse(SessionState.running.value)
                report.put("portrait", true).put("landscape", true).put("modeChanges", true)
                    .put("regionChoices", true).put("settingsAndConnection", true)
            }
            assertEquals(0, attempts.get())
            report.put("completed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName).put("failure", failure.message)
            throw failure
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            settings.subtitleCapture = previousCapture
            settings.region = previousRegion
            report.put("cloudDispatches", attempts.get())
            File(folder, "ocean-ui-result.json").writeText(report.toString(2))
        }
    }

    @Test(timeout = 180_000L)
    fun actualProjectionPauseRegionAndStopWithoutTranslation(): Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeOceanCapture") == "true")
        requireDisposablePackage()
        val folder = File(context.getExternalFilesDir(null), "ocean-ui-fixtures").apply { mkdirs() }
        val report = JSONObject().put("completed", false)
            .put("scope", "Real MediaProjection, overlay selector and existing controls; Activity remains visible; no translation claim")
        val screenshots = JSONArray()
        report.put("screenshots", screenshots)
        val attempts = AtomicInteger()
        val settings = AppSettings(context)
        val previousCapture = settings.subtitleCapture
        val previousRegion = settings.region
        val previousRoi = settings.subtitleRegion
        StandaloneTranslator.beforeNetworkCallForTest = { attempts.incrementAndGet(); error("Cloud forbidden in Ocean capture controls") }
        try {
            // Own disposable package only; product permissions and stored credentials are untouched.
            device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitUntil("Landscape viewport") { device.displayWidth > device.displayHeight }
                click("Một lần")
                click("Chọn vùng phụ đề")
                click("Dải phụ đề")
                top()
                click("Bắt đầu dịch")
                val consent = device.wait(Until.findObject(By.pkg("com.android.systemui").res("android:id/button1")
                    .text(Pattern.compile("Start now|Start recording|Start sharing|Share screen|Start|Bắt đầu ngay|Bắt đầu", Pattern.CASE_INSENSITIVE))), 10_000)
                assertNotNull("Android must request MediaProjection consent", consent)
                consent!!.click()
                waitUntil("Existing capture service actually started") { SessionState.running.value }
                capture(folder, "ocean-live-running", screenshots)
                click("Tạm dừng")
                waitUntil("Service pause action") { SessionState.paused.value }
                capture(folder, "ocean-live-paused", screenshots)
                click("Tiếp tục")
                waitUntil("Service resume action") { !SessionState.paused.value }
                click("Chọn vùng phụ đề")
                assertTrue(device.wait(Until.hasObject(editor), 5_000))
                capture(folder, "ocean-live-region-overlay", screenshots)
                // Existing Canvas selector has fixed dp controls above a bottom subtitle region.
                val density = context.resources.displayMetrics.density
                val selectorButtonY = if (previousRoi.bottom > .75f) 76 * density else device.displayHeight - 70 * density
                device.click((74 * density).toInt(), (selectorButtonY + 22 * density).toInt())
                assertTrue("Cancel closes selector", device.wait(Until.gone(editor), 5_000))
                assertEquals(previousRoi, settings.subtitleRegion)
                click("Chọn vùng phụ đề")
                assertTrue(device.wait(Until.hasObject(editor), 5_000))
                device.click((207 * density).toInt(), (selectorButtonY + 22 * density).toInt())
                assertTrue("Save closes selector", device.wait(Until.gone(editor), 5_000))
                assertEquals(previousRoi, settings.subtitleRegion)
                top()
                click("Kết thúc")
                waitUntil("Existing STOP action ended capture") { !SessionState.running.value }
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Bắt đầu dịch")), 5_000))
                capture(folder, "ocean-live-stopped", screenshots)
                report.put("projectionConsent", true).put("actualCaptureStarted", true)
                    .put("pauseResume", true).put("regionCancelAndSave", true).put("stop", true)
            }
            assertEquals(0, attempts.get())
            report.put("completed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName).put("failure", failure.message)
            throw failure
        } finally {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SessionState.running.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(80)
            StandaloneTranslator.beforeNetworkCallForTest = null
            settings.subtitleCapture = previousCapture
            settings.region = previousRegion
            settings.subtitleRegion = previousRoi
            report.put("cloudDispatches", attempts.get()).put("serviceStoppedAfterCleanup", !SessionState.running.value)
            File(folder, "ocean-capture-controls-result.json").writeText(report.toString(2))
        }
    }
}
