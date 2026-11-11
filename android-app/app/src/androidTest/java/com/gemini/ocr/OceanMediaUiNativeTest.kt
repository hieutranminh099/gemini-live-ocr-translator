package com.gemini.ocr

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.movie.MovieEpisode
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.*
import com.gemini.ocr.video.sync.VideoCloudSync
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
import java.util.regex.Pattern

/** UI-only witness on explicit generated fixtures in a disposable package, never product data. */
@RunWith(AndroidJUnit4::class)
class OceanMediaUiNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }

    private fun top() {
        repeat(7) {
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 3,
                device.displayWidth * 4 / 5, device.displayHeight * 4 / 5, 16)
        }
        device.waitForIdle()
    }

    private fun findText(text: String) = device.findObject(By.pkg(context.packageName).text(text))

    private fun click(text: String) {
        repeat(10) {
            val found = findText(text)
            if (found != null) { found.click(); device.waitForIdle(); return }
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight * 4 / 5,
                device.displayWidth * 4 / 5, device.displayHeight / 3, 18)
        }
        fail("Visible app action: $text")
    }

    private fun step(index: Int) {
        val labels = listOf("Chọn video", "Thiết lập", "Xử lý", "Kiểm tra", "Xuất")
        val description = "Bước ${index + 1} · ${labels[index]}"
        repeat(8) {
            val control = device.findObject(By.desc(description))
            if (control != null && !control.visibleBounds.isEmpty) {
                control.click(); device.waitForIdle(); return
            }
            val visible = device.findObjects(By.desc(Pattern.compile("Bước [1-5] · .*")))
                .filter { !it.visibleBounds.isEmpty }
            assertTrue("Workflow bar has visible steps", visible.isNotEmpty())
            val minimum = visible.minOf { it.contentDescription.orEmpty().removePrefix("Bước ").substringBefore(' ').toInt() - 1 }
            val y = visible.first().visibleBounds.centerY()
            if (index < minimum) device.swipe(device.displayWidth / 5, y, device.displayWidth * 4 / 5, y, 20)
            else device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 20)
            device.waitForIdle()
        }
        fail("Reachable workflow step: $description")
    }

    private fun <T : Activity> orient(scenario: ActivityScenario<T>, portrait: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor("Requested viewport") { (device.displayHeight > device.displayWidth) == portrait }
        device.waitForIdle()
    }

    @Test(timeout = 300_000L)
    fun libraryAndFiveVideoStepsKeepExistingDataAndCallbacks(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeOceanMedia") == "true")
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.oceanmedia")
        require(!SessionState.running.value && VideoJobs.get(context).busy.value == null && BatchJobs.get(context).busy.value == null)
        require(AppSettings(context).effectiveStandaloneApiKey().isBlank())
        require(VideoCloudSync.get(context).state.value.accountEmail.isBlank())
        require(StandaloneTranslator.beforeNetworkCallForTest == null && StandaloneTranslator.overrideGeminiTranslator == null)
        val folder = File(context.getExternalFilesDir(null), "ocean-media-ui-fixtures").apply { mkdirs() }
        val runLabel = UUID.randomUUID().toString().take(8)
        val reviewTitle = "OceanUI · Video kiểm thử · $runLabel"
        val pausedTitle = "OceanUI · Checkpoint tạm dừng · $runLabel"
        val batchTitle = "OceanUI · Dự án đang chuẩn bị · $runLabel"
        val movieTitle = "OceanUI · Phim đã xác nhận · $runLabel"
        val report = JSONObject().put("completed", false)
            .put("runLabel", runLabel)
            .put("scope", "Actual Compose/UI controls on generated test-only movie/project checkpoints; no live worker, OCR, translation, Drive consent, rendering or user data")
        val screenshots = JSONArray(); report.put("screenshots", screenshots)
        val cloudAttempts = AtomicInteger()
        StandaloneTranslator.beforeNetworkCallForTest = { cloudAttempts.incrementAndGet(); error("Cloud forbidden in Ocean Media UI witness") }
        fun shot(name: String) {
            device.waitForIdle(); SystemClock.sleep(400)
            val file = File(folder, "$name.png")
            assertTrue(device.takeScreenshot(file)); screenshots.put(file.name)
        }
        try {
            val source = File(folder, "generated-source.mp4")
            if (!source.exists()) instrumentation.context.assets.open("stability-source.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val jobs = VideoJobs.get(context)
            val store = VideoStore(context)
            val imported = jobs.create(Uri.fromFile(source), reviewTitle)
            require(imported.durationMs >= 5000)
            val cues = listOf(VideoCue("ocean-one", 500, 2000, "你好", "Xin chào", approved = true),
                VideoCue("ocean-two", 2500, 4500, "再见", "Hẹn gặp lại"))
            // Persisted test fixture only: never claim the translator produced these captions.
            val review = store.save(imported.copy(cues = cues, scanMs = imported.durationMs,
                stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, progress = 100,
                message = "Timeline tổng hợp cho kiểm thử giao diện; không gọi AI."))
            val processBase = jobs.create(Uri.fromFile(source), pausedTitle)
            val paused = store.save(processBase.copy(stage = VideoStage.PAUSED, resumeStage = VideoStage.EXTRACTING,
                scanMs = processBase.durationMs / 3, progress = 33,
                message = "Checkpoint tổng hợp cho kiểm thử bố cục; không có worker đang chạy."))
            jobs.refresh()
            val batch = BatchJobs.get(context).create(batchTitle, BatchKind.SERIES)
            val batchFiles = listOf("OceanUI_EP01.mp4", "OceanUI_EP02.mp4").map { name ->
                File(folder, name).also { if (!it.exists()) source.copyTo(it) }
            }
            BatchJobs.get(context).add(batch.id, batchFiles.map(Uri::fromFile))
            BatchJobs.get(context).configure(batch.id, VideoTranslationMode.OFFLINE, VideoSourceMode.OCR)
            val movieId = UUID.randomUUID().toString()
            val library = MovieLibrary.get(context)
            library.confirm(movieId, movieTitle, listOf(
                MovieEpisode("1", "Tập 1", source.path, imported.durationMs),
                MovieEpisode("2", "Tập 2", source.path, imported.durationMs)))
            val beforeProject = store.load(review.id).json().toString()
            val beforePaused = store.load(paused.id).json().toString()
            val beforeBatch = BatchStore(context).load(batch.id).json().toString()
            val beforeMovie = library.movies.value.single { it.id == movieId }.json().toString()
            report.put("reviewProjectId", review.id).put("pausedProjectId", paused.id).put("batchId", batch.id).put("movieId", movieId)

            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("ux_tab", 2)).use { scenario ->
                orient(scenario, false); top()
                waitFor("Movie search present") { device.findObject(By.clazz("android.widget.EditText")) != null }
                shot("ocean2-library-landscape")
                val search = device.findObject(By.clazz("android.widget.EditText"))
                search.text = movieTitle; device.waitForIdle()
                waitFor("Existing movie search") { findText(movieTitle) != null }
                shot("ocean2-library-search-complete")
                device.findObject(By.desc("Sắp xếp phim")).click(); device.waitForIdle()
                click("Tên phim")
                assertNotNull(findText(movieTitle))
                search.text = runLabel; device.waitForIdle(); top()
                click("Google Drive")
                assertTrue(device.wait(Until.hasObject(By.text("Chưa kết nối")), 4_000))
                shot("ocean2-library-drive-disconnected")
                click("Google Drive")
                click(batchTitle)
                assertTrue(device.wait(Until.hasObject(By.text(batchTitle)), 5_000))
                assertNull(jobs.busy.value)
                device.pressBack(); device.waitForIdle()
                orient(scenario, true); top(); shot("ocean2-library-portrait")
                device.pressBack(); device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.text("Bắt đầu dịch")), 5_000))
                shot("ocean2-live-unchanged")
            }
            assertEquals(beforeMovie, library.movies.value.single { it.id == movieId }.json().toString())
            assertEquals(beforeBatch, BatchStore(context).load(batch.id).json().toString())
            report.put("movieAndBatchPreservedByNavigation", true).put("searchSortAndOpenBatch", true)

            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java)).use { scenario ->
                orient(scenario, false); top(); shot("ocean2-video-select-landscape")
                click("Chọn video")
                waitFor("Android video picker opened") { device.currentPackageName != context.packageName }
                device.pressBack(); device.waitForIdle()
                assertNull(jobs.busy.value)
                orient(scenario, true); top(); shot("ocean2-video-select-portrait")
                device.findObject(By.desc("Thiết lập dịch")).click(); device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.text("Thiết lập cho thiết bị này")), 5_000))
                device.pressBack(); device.waitForIdle()
                report.put("pickerOpenedAndCancelled", true).put("settingsBackToVideo", true)
            }

            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", paused.id)).use { scenario ->
                orient(scenario, false); top(); shot("ocean2-video-process-landscape")
                orient(scenario, true); top(); shot("ocean2-video-process-portrait")
                assertNull(jobs.busy.value)
            }
            assertEquals(beforePaused, store.load(paused.id).json().toString())
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", review.id)).use { scenario ->
                orient(scenario, false); top(); shot("ocean2-video-review-landscape")
                step(1); top(); shot("ocean2-video-setup-landscape")
                orient(scenario, true); top(); shot("ocean2-video-setup-portrait")
                step(3); top(); shot("ocean2-video-review-portrait")
                step(4); top(); shot("ocean2-video-export-portrait")
                orient(scenario, false); top(); shot("ocean2-video-export-landscape")
                assertNull(jobs.busy.value)
                assertEquals(beforeProject, store.load(review.id).json().toString())
                report.put("videoNavigationPreservedCheckpoint", true)
                step(3)
                repeat(8) {
                    if (findText("Hẹn gặp lại") == null) device.swipe(device.displayWidth * 4 / 5,
                        device.displayHeight * 4 / 5, device.displayWidth * 4 / 5, device.displayHeight / 3, 18)
                }
                val target = findText("Hẹn gặp lại")
                assertNotNull("Second caption displayed", target)
                val edit = device.findObjects(By.text("Sửa / duyệt")).firstOrNull { it.visibleBounds.top > target!!.visibleBounds.top }
                assertNotNull("Edit second cue", edit); edit!!.click(); device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.text("再见")), 5_000))
                val fields = device.findObjects(By.clazz("android.widget.EditText"))
                assertTrue(fields.size >= 2); fields.last().text = "Bản dịch kiểm thử đã sửa"
                shot("ocean2-video-cue-editor")
                click("Lưu")
                waitFor("Caption saved by existing updateCue callback") { store.load(review.id).cues.last().translation == "Bản dịch kiểm thử đã sửa" }
                val saved = store.load(review.id)
                assertEquals(cues.first(), saved.cues.first())
                assertFalse(saved.cues.last().approved)
                assertEquals(review.roi, saved.roi)
                assertEquals(review.translationMode, saved.translationMode)
                assertEquals(review.style, saved.style)
                assertEquals(0, saved.cloudCalls)
                report.put("captionEditPreservedOtherCueRoiModeStyle", true)
            }
            assertFalse(SessionState.running.value)
            assertNull(jobs.busy.value)
            assertNull(BatchJobs.get(context).busy.value)
            assertEquals(0, cloudAttempts.get())
            report.put("completed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName).put("failure", failure.message)
            throw failure
        } finally {
            StandaloneTranslator.beforeNetworkCallForTest = null
            report.put("cloudDispatches", cloudAttempts.get())
            File(folder, "ocean-media-ui-result.json").writeText(report.toString(2))
        }
    }
}
