package com.gemini.ocr

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.SecureConnection
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern
import java.text.Normalizer

@RunWith(AndroidJUnit4::class)
class NativeCaptureTest {
    @Test fun screenCaptureOcrAndOverlayWorkWhileHudChanges() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val settings=AppSettings(context)
        settings.unpair()
        SecureConnection.pair(settings,requireNotNull(args.getString("serverHost")),requireNotNull(args.getString("serverPin")),requireNotNull(args.getString("pairCode")),"Android 15 capture test")
        settings.language="en"; settings.mode="smart"; settings.profile="game"; settings.overlay="subtitle"
        settings.region="full"; settings.fullScreen=true; settings.interval=600
        val device=UiDevice.getInstance(instrumentation)
        val directory=File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() } }
        shell("appops set com.gemini.ocr SYSTEM_ALERT_WINDOW allow")
        shell("pm grant com.gemini.ocr android.permission.POST_NOTIFICATIONS")
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val start=device.wait(Until.findObject(By.text("Bật dịch màn hình")),10000)
            assertNotNull("App did not show the capture start button",start)
            start.click()
            val consent=device.wait(Until.findObject(By.res("android:id/button1").pkg("com.android.systemui")
                .text(Pattern.compile("Start now|Start recording|Start sharing|Share screen|Start",Pattern.CASE_INSENSITIVE))),10000)
            if(consent==null) {
                device.dumpWindowHierarchy(File(directory,"capture-permission.xml"))
                device.takeScreenshot(File(directory,"capture-permission.png"))
                fail("System projection consent was not found; see capture-permission.xml")
            }
            consent!!.click()
            withTimeout(15000) { while(!SessionState.running.value) delay(100) }
            context.startActivity(Intent().setComponent(ComponentName("com.gemini.ocr.test","com.gemini.ocr.TestSceneActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                withTimeout(30000) { while(SessionState.blocks.value==0) delay(100) }
            } catch(error: TimeoutCancellationException) {
                device.takeScreenshot(File(directory,"capture-failure.png"))
                throw AssertionError("No translation displayed: "+SessionState.status.value,error)
            }
            assertTrue(SessionState.running.value)
            assertTrue(SessionState.cacheHits.value>0)
            withTimeout(10000) { while(!SessionState.overlayVisible.value) delay(20) }
            delay(60)
            val screenshot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory,"native-overlay.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                val processor=OcrProcessor("en")
                val text=try { processor.process(screenshot).joinToString("") { it.text } } finally { processor.close() }
                val normalized=Normalizer.normalize(text,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").replace(Regex("[^a-zA-Z]"),"").lowercase()
                assertTrue("Vietnamese subtitle was not present in the actual screenshot: $text",normalized.contains("xinchaolukhach"))
            } finally { screenshot.recycle() }
            // A changing timer must not cause repeated translation of an unchanged dialogue.
            val initial=SessionState.blocks.value
            context.startActivity(Intent().setComponent(ComponentName("com.gemini.ocr.test","com.gemini.ocr.TestSceneActivity"))
                .putExtra("animate",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            delay(2000)
            assertEquals(initial,SessionState.blocks.value)
            val description="Chạm để tạm dừng hoặc tiếp tục; giữ để dừng; kéo để di chuyển"
            val bubble=device.wait(Until.findObject(By.desc(description)),5000)
            assertNotNull(bubble); bubble.click()
            withTimeout(5000) { while(!SessionState.paused.value) delay(50) }
            assertFalse(SessionState.overlayVisible.value)
            val pausedCount=SessionState.blocks.value
            delay(1200); assertEquals(pausedCount,SessionState.blocks.value)
            device.findObject(By.desc(description)).click()
            withTimeout(10000) { while(SessionState.blocks.value<=pausedCount) delay(50) }
            assertTrue(SessionState.status.value.contains("cache trên điện thoại"))
            val beforeRotation=SessionState.blocks.value
            device.setOrientationLeft()
            withTimeout(15000) { while(SessionState.blocks.value<=beforeRotation || !SessionState.overlayVisible.value) delay(50) }
            delay(60)
            assertTrue(device.takeScreenshot(File(directory,"native-landscape.png")))
            shell("appops set com.gemini.ocr GET_USAGE_STATS allow")
            settings.excludedApps="com.gemini.ocr.test"
            val excludedCount=SessionState.blocks.value
            delay(1600)
            assertFalse(SessionState.overlayVisible.value)
            assertEquals(excludedCount,SessionState.blocks.value)
            settings.excludedApps=""
            withTimeout(10000) { while(SessionState.blocks.value<=excludedCount || !SessionState.overlayVisible.value) delay(50) }
            val beforeLock=SessionState.blocks.value
            device.sleep(); delay(1600)
            assertFalse(SessionState.overlayVisible.value)
            assertEquals(beforeLock,SessionState.blocks.value)
        } finally {
            device.wakeUp(); device.setOrientationNatural(); device.unfreezeRotation()
            context.stopService(Intent(context,ScreenCaptureService::class.java))
            withContext(NonCancellable) {
                withTimeout(10000) { while(SessionState.running.value) delay(100) }
                settings.excludedApps=""; settings.unpair()
            }
        }
    }
}
