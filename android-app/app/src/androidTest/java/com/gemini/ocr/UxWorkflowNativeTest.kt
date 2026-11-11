package com.gemini.ocr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.batch.*
import com.gemini.ocr.movie.*
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Screen/UI witness with generated media in the disposable, network-denied package only. */
@RunWith(AndroidJUnit4::class)
class UxWorkflowNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun click(text: String) {
        assertTrue("Visible action: $text", device.wait(Until.hasObject(By.text(text)), 8_000))
        device.findObject(By.text(text)).click(); device.waitForIdle()
    }
    @Test(timeout = 180_000L)
    fun captureAndExerciseActualScreensWithoutCloud(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeUx") == "true")
        val before = args.getString("beforeUx") == "true"
        val label = args.getString("uxLabel").orEmpty().also { require(it.matches(Regex("[a-z0-9-]{1,40}"))) }
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value && VideoJobs.get(context).busy.value == null && BatchJobs.get(context).busy.value == null)
        val folder = File(context.getExternalFilesDir(null), "ux-fixtures").apply { mkdirs() }
        val report = JSONObject().put("label", label).put("before", before).put("completed", false)
            .put("scope", "Real Compose/UiAutomator on generated fixtures; no OCR, translation, Google consent or production user data")
        val screenshots = JSONArray(); report.put("screenshots", screenshots)
        val attempts = AtomicInteger()
        StandaloneTranslator.beforeNetworkCallForTest = { attempts.incrementAndGet(); error("Cloud forbidden in UX smoke") }
        fun shot(name: String) {
            device.waitForIdle(); SystemClock.sleep(350)
            val file = File(folder, "$label-$name.png")
            assertTrue(device.takeScreenshot(file)); screenshots.put(file.name)
        }
        try {
            val source = File(folder, "generated.mp4")
            if (!source.exists()) contextTest().assets.open("stability-source.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val jobs = VideoJobs.get(context)
            val created = jobs.create(Uri.fromFile(source), "UX · Video mẫu")
            val store = VideoStore(context)
            File(store.folder(created.id), "batch-owner").writeText(UUID.randomUUID().toString())
            val cues = listOf(VideoCue("ux-one", 1000, 3000, "你好", "Xin chào", approved = true),
                VideoCue("ux-two", 4000, 6000, "再见", "Hẹn gặp lại"))
            // Synthetic checkpoint fixture: Jobs.save deliberately ignores caller-supplied timelines.
            val project = store.save(created.copy(cues = cues, scanMs = created.durationMs,
                stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING))
            jobs.refresh()
            if (!before) {
                val batch = BatchJobs.get(context).create("UX · Danh sách thử", BatchKind.SERIES)
                val files = listOf("UX_EP10.mp4", "UX_EP02.mp4", "UX_EP01.mp4").map { name -> File(folder, name).also { if (!it.exists()) source.copyTo(it) } }
                BatchJobs.get(context).add(batch.id, files.map(Uri::fromFile))
                BatchJobs.get(context).configure(batch.id, VideoTranslationMode.OFFLINE, VideoSourceMode.OCR)
                report.put("batchId", batch.id)
                val movieId = UUID.randomUUID().toString()
                MovieLibrary.get(context).confirm(movieId, "UX · Bộ phim mẫu", listOf(MovieEpisode("1", "Tập 1", source.path, created.durationMs),
                    MovieEpisode("2", "Tập 2", source.path, created.durationMs)))
                MovieLibrary.get(context).progress(movieId, "1", 2000)
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                click("Dịch trực tiếp"); shot("live")
                click("Kho thoại"); shot("dialogues")
                click("Kho phim"); shot("movies")
                if (!before && args.getString("captureExtras") == "true") {
                    val search = device.findObject(By.clazz("android.widget.EditText"))
                    search.text = "Bộ phim mẫu"; device.waitForIdle(); shot("movies-completed")
                    click("Hiệu năng"); shot("performance")
                }
                click("Tùy chỉnh"); shot("settings")
                device.pressBack(); device.waitForIdle()
                if (!before) assertTrue(device.hasObject(By.text("Bắt đầu dịch")))
            }
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", project.id)).use {
                shot("video-review")
                if (!before) {
                    device.findObject(By.desc("Toàn màn hình")).click(); device.waitForIdle(); shot("video-fullscreen")
                    assertTrue(device.hasObject(By.desc("Thoát toàn màn hình")))
                    device.pressBack(); device.waitForIdle()
                    assertTrue(device.hasObject(By.text("4 · Kiểm tra")))
                    click("5 · Xuất"); assertTrue(device.hasObject(By.text("Tải SRT về máy"))); shot("video-export")
                    click("4 · Kiểm tra")
                    repeat(5) {
                        if (!device.hasObject(By.text("Hẹn gặp lại"))) device.swipe(device.displayWidth * 4 / 5,
                            device.displayHeight * 3 / 4, device.displayWidth * 4 / 5, device.displayHeight / 3, 25)
                    }
                    assertTrue(device.hasObject(By.text("Hẹn gặp lại")))
                    var edit = device.findObjects(By.text("Sửa / duyệt")).firstOrNull {
                        it.visibleBounds.top > device.findObject(By.text("Hẹn gặp lại")).visibleBounds.top
                    }
                    repeat(5) {
                        if (edit == null) {
                            device.swipe(device.displayWidth * 4 / 5, device.displayHeight * 3 / 4,
                                device.displayWidth * 4 / 5, device.displayHeight / 2, 25)
                            val line = device.findObject(By.text("Hẹn gặp lại"))
                            edit = device.findObjects(By.text("Sửa / duyệt")).firstOrNull { line != null && it.visibleBounds.top > line.visibleBounds.top }
                        }
                    }
                    assertNotNull("Edit the second cue, rather than the first visible action", edit)
                    edit!!.click(); device.waitForIdle()
                    assertTrue(device.hasObject(By.text("再见")))
                    val fields = device.findObjects(By.clazz("android.widget.EditText"))
                    assertTrue(fields.size >= 2); fields.last().click(); fields.last().text = "Ban dich UX da sua"
                    device.waitForIdle(); shot("cue-keyboard")
                    // The save action remains reachable while the keyboard is open.
                    click("Lưu")
                    val saved = store.load(project.id)
                    assertEquals("Ban dich UX da sua", saved.cues.last().translation)
                    assertFalse(saved.cues.last().approved)
                    assertEquals(cues.first(), saved.cues.first())
                    report.put("inlineEditPreservedOtherApprovedCue", true)
                }
            }
            if (!before) ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("Chọn video để bắt đầu")), 8_000)); shot("video-start")
                device.findObject(By.desc("Thiết lập dịch")).click(); device.waitForIdle()
                assertTrue(device.hasObject(By.text("Thiết lập cho thiết bị này")))
                device.pressBack(); device.waitForIdle()
                assertTrue(device.hasObject(By.text("Chọn video để bắt đầu")))
                report.put("settingsBackReturnedToVideo", true)
            }
            if (!before) {
                ActivityScenario.launch<BatchActivity>(Intent(context, BatchActivity::class.java).putExtra("batch", report.getString("batchId"))).use {
                    click("1. Chọn phim"); shot("batch-list-and-order")
                    click("2. Thiết lập phụ đề"); shot("batch-setup")
                    click("3. Tiến độ"); shot("batch-process")
                    click("4. Kiểm tra và xuất"); shot("batch-review-export")
                }
            }
            assertEquals(0, attempts.get()); report.put("cloudDispatches", attempts.get()).put("completed", true)
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            report.put("cloudDispatches", attempts.get())
            File(folder, "$label-result.json").writeText(report.toString(2))
        }
    }
    private fun contextTest(): Context = instrumentation.context
}
