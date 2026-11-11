@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Opt-in acceptance on generated media in the disposable app; no production projects or network. */
@RunWith(AndroidJUnit4::class)
class VideoFullscreenNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val reports get() = File(requireNotNull(context.getExternalFilesDir(null)), "fullscreen-fixtures/reports")
        .apply { mkdirs() }

    /** ADB stages only generated media in this disposable package's external files folder. */
    private fun fixture(name: String): File {
        require(name in setOf("portrait", "landscape"))
        val external = File(requireNotNull(context.getExternalFilesDir(null)), "fullscreen-fixtures/$name.mp4")
        require(external.isFile) { "Push generated $name.mp4 into this test app's external fullscreen-fixtures folder first." }
        val internal = File(context.filesDir, "fullscreen-fixtures/$name.mp4")
        require(internal.parentFile!!.mkdirs() || internal.parentFile!!.isDirectory)
        external.copyTo(internal, overwrite = true)
        return internal
    }

    private fun guard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeVideoFullscreen") == "true")
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        require(!SessionState.running.value)
        assertNull(VideoJobs.get(context).busy.value)
    }

    @Test(timeout = 120000) fun portrait720x1280KeepsPlaybackAndMappedCaptions() {
        exercise("portrait", 720, 1280)
    }

    @Test(timeout = 120000) fun landscape1920x1080KeepsPlaybackAndMappedCaptions() {
        exercise("landscape", 1920, 1080)
    }

    @Test(timeout = 90000) fun chineseSourceRoiRemainsRecognizableForTheFullscreenFixture(): Unit = runBlocking {
        guard()
        verifySourceRoi(fixture("portrait"), listOf(.06f, .72f, .94f, .96f), "source-ocr.json", fullscreen = false)
    }

    /** Decode source media directly while the foreground fullscreen player retains its own surface. */
    private suspend fun verifySourceRoi(input: File, roi: List<Float>, filename: String, fullscreen: Boolean) {
        val preview = requireNotNull(VideoDecoder.preview(context, Uri.fromFile(input), 2000))
        val left = (preview.width * roi[0]).roundToInt()
        val top = (preview.height * roi[1]).roundToInt()
        val crop = Bitmap.createBitmap(preview, left, top,
            (preview.width * roi[2]).roundToInt() - left,
            (preview.height * roi[3]).roundToInt() - top)
        preview.recycle()
        val ocr = OcrProcessor("zh")
        try {
            val recognized = ocr.process(crop).joinToString("") { it.text }.replace(Regex("\\s+"), "")
            File(reports, filename).writeText(JSONObject().put("completed", recognized == "欢迎")
                .put("recognized", recognized).put("roi", org.json.JSONArray(roi))
                .put("fullscreen_active", fullscreen)
                .put("scope", "Actual ML Kit OCR on a decoded source ROI; no translator or cloud call")
                .toString(2))
            assertEquals("Synthetic Chinese subtitle must be read exactly", "欢迎", recognized)
        } finally { ocr.close(); crop.recycle() }
    }

    private fun exercise(name: String, width: Int, height: Int) {
        guard()
        val input = fixture(name)
        val metadata = VideoDecoder.metadata(context, Uri.fromFile(input))
        assertEquals(width, metadata.width); assertEquals(height, metadata.height)
        require(metadata.durationMs >= 8000)
        val store = VideoStore(context)
        val project = store.save(VideoProject(UUID.randomUUID().toString(), "Verification · Fullscreen $name",
            Uri.fromFile(input).toString(), metadata.durationMs, width, height,
            sourceMode = VideoSourceMode.OCR,
            style = VideoSubtitleStyle(frame = VideoSubtitleFrame(.10f, .75f, .90f, .93f), blurOriginal = false),
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
            scanMs = metadata.durationMs, progress = 100,
            cues = listOf(VideoCue("fullscreen-cue", 500, metadata.durationMs - 100,
                "欢迎", "Chào mừng.", approved = true, model = "user-edited"))))
        val jobs = VideoJobs.get(context)
        jobs.refresh()
        val oldHook = StandaloneTranslator.beforeNetworkCallForTest
        val attempts = AtomicInteger()
        StandaloneTranslator.beforeNetworkCallForTest = { attempts.incrementAndGet(); error("No network in fullscreen acceptance") }
        val report = JSONObject().put("completed", false).put("width", width).put("height", height)
        var scenario: ActivityScenario<VideoActivity>? = null
        try {
            val launched = ActivityScenario.launch(VideoActivity::class.java)
            scenario = launched
            findControl("Toàn màn hình", scroll = true)
            val originalActivity = AtomicReference<VideoActivity>()
            val originalPlayer = AtomicReference<Player>()
            val originalOrientation = AtomicInteger()
            launched.onActivity { activity ->
                originalActivity.set(activity)
                originalOrientation.set(activity.requestedOrientation)
                originalPlayer.set(requireNotNull(findPlayer(activity.window.decorView)?.player))
                originalPlayer.get().seekTo(2000)
                originalPlayer.get().play()
            }
            awaitPlayer(launched, "Decoder ready") { it.playbackState == Player.STATE_READY && it.currentPosition >= 2200 }
            launched.onActivity { findPlayer(it.window.decorView)!!.player!!.pause() }
            val positionBeforeFullscreen = AtomicReference<Long>()
            launched.onActivity { positionBeforeFullscreen.set(originalPlayer.get().currentPosition) }
            clickControl("Toàn màn hình")
            findControl("Thoát toàn màn hình")
            assertSamePlayer(launched, originalPlayer.get())
            launched.onActivity {
                assertTrue("Entering fullscreen preserves playback time", abs(originalPlayer.get().currentPosition - positionBeforeFullscreen.get()) < 500)
            }
            awaitPlayer(launched, "Pause") { !it.isPlaying }
            runBlocking { verifySourceRoi(input, project.roi, "$name-fullscreen-ocr.json", fullscreen = true) }
            assertSamePlayer(launched, originalPlayer.get())
            assertMappedCaption(launched, project, fill = false, filename = "$name-fit.png")
            copyOwnWindow(launched, "$name-fullscreen-fit.png")
            copyOwnVideoSurface(launched, "$name-video.png")
            require(device.currentPackageName == context.packageName) { "Only the owned fullscreen fixture can be captured." }
            assertTrue("Capture the composited fixture video and Vietnamese overlay",
                device.takeScreenshot(File(reports, "$name-device-fullscreen-fit.png")))

            // The button changes mode, rather than creating another media item/player.
            clickScaleMode()
            assertSamePlayer(launched, originalPlayer.get())
            assertMappedCaption(launched, project, fill = true, filename = "$name-fill.png")
            clickScaleMode()

            // Actual configuration changes must not recreate the Activity/player or jump to zero.
            launched.onActivity { originalPlayer.get().play() }
            awaitPlayer(launched, "Playing before rotation") { it.isPlaying }
            val positionBeforeRotation = AtomicReference<Long>()
            launched.onActivity { activity ->
                positionBeforeRotation.set(originalPlayer.get().currentPosition)
                activity.requestedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            SystemClock.sleep(1200)
            launched.onActivity { activity ->
                assertSame("Rotation must preserve the Activity", originalActivity.get(), activity)
                assertSame("Rotation must preserve the player", originalPlayer.get(), findPlayer(activity.window.decorView)?.player)
                val elapsed = originalPlayer.get().currentPosition - positionBeforeRotation.get()
                assertTrue("Rotation must continue from the same playback time: $elapsed", elapsed in 500L..3500L)
                assertTrue("Playback must continue during rotation", originalPlayer.get().isPlaying)
                originalPlayer.get().pause()
            }
            assertMappedCaption(launched, project, fill = false, filename = "$name-rotated-fit.png")

            verifyTapControlsAndDoubleTapExit(launched, originalPlayer.get(), name)

            // Editing uses the same source-coordinate mapping as caption rendering after rotation.
            val editedStyle = dragFullscreenFrame(launched, project, store)

            // Seek and continue in fullscreen, then exit with Android Back.
            launched.onActivity { originalPlayer.get().seekTo(5000); originalPlayer.get().play() }
            awaitPlayer(launched, "Seek and continue") { it.currentPosition >= 5200 && it.isPlaying }
            launched.onActivity { originalPlayer.get().pause() }
            device.pressBack()
            SystemClock.sleep(900)
            assertSamePlayer(launched, originalPlayer.get())
            launched.onActivity {
                assertFalse("Back exits fullscreen without finishing the screen", it.isFinishing)
                assertEquals("Restore the caller's orientation policy", originalOrientation.get(), it.requestedOrientation)
            }
            clickControl("Toàn màn hình", scroll = true)
            clickControl("Thoát toàn màn hình")
            SystemClock.sleep(700)
            assertSamePlayer(launched, originalPlayer.get())

            val restored = store.load(project.id)
            assertEquals(project.cues, restored.cues)
            assertEquals(project.roi, restored.roi)
            assertEquals(editedStyle, restored.style)
            assertEquals(0, restored.cloudCalls)
            assertEquals(0, attempts.get())
            assertNull(jobs.busy.value)
            report.put("completed", true).put("same_player", true).put("same_activity_on_rotation", true)
                .put("rotation_while_playing", true)
                .put("seek_resume", true).put("back_exit", true).put("button_exit", true)
                .put("captions_checked", true).put("frame_drag_checked", true).put("ocr_during_fullscreen", true)
                .put("controls_tap_checked", true).put("double_tap_exit", true)
                .put("roi_unchanged", true).put("gemini_calls", attempts.get())
        } catch (failure: Throwable) {
            report.put("error", failure.message ?: failure.javaClass.name)
            throw failure
        } finally {
            scenario?.close()
            StandaloneTranslator.beforeNetworkCallForTest = oldHook
            File(reports, "$name.json").writeText(report.toString(2))
        }
    }

    private fun clickControl(description: String, scroll: Boolean = false) {
        findControl(description, scroll).click()
        SystemClock.sleep(300)
    }

    private fun findControl(description: String, scroll: Boolean = false): UiObject2 {
        repeat(if (scroll) 12 else 2) {
            val found = device.findObject(By.desc(description)) ?: device.findObject(By.text(description))
            if (found != null) return found
            if (scroll) device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 25)
            else device.click(device.displayWidth / 2, device.displayHeight / 2)
            SystemClock.sleep(250)
        }
        error("Missing control: $description")
    }

    private fun clickScaleMode() {
        val options = listOf("Fit", "Fill", "Fit · Toàn bộ", "Fill · Lấp đầy", "Chuyển Fit / Fill")
        repeat(3) {
            for (option in options) {
                val found = device.findObject(By.desc(option)) ?: device.findObject(By.text(option))
                if (found != null) { found.click(); SystemClock.sleep(400); return }
            }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            SystemClock.sleep(200)
        }
        fail("Missing Fit/Fill control")
    }

    private fun assertSamePlayer(scenario: ActivityScenario<VideoActivity>, expected: Player) {
        scenario.onActivity { assertSame("Mode changes must retain the player", expected, findPlayer(it.window.decorView)?.player) }
    }

    private fun fullscreenExitVisible() = device.hasObject(By.desc("Thoát toàn màn hình")) ||
        device.hasObject(By.text("Thoát toàn màn hình"))

    /** Touch the movie away from the center play button and the top fullscreen toolbar. */
    private fun verifyTapControlsAndDoubleTapExit(scenario: ActivityScenario<VideoActivity>,
        player: Player, name: String) {
        val timeBefore = AtomicReference<Long>()
        scenario.onActivity { activity ->
            assertFalse("Gesture checks start from paused playback", player.isPlaying)
            timeBefore.set(player.currentPosition)
            requireNotNull(findPlayer(activity.window.decorView)).showController()
        }
        await("Visible controls before tap") { fullscreenExitVisible() }
        var x = device.displayWidth / 2
        var y = device.displayHeight / 4
        assertTrue("Single tap reaches the video", device.click(x, y))
        SystemClock.sleep(400) // GestureDetector confirms a single tap after the double-tap timeout.
        await("Single tap hides fullscreen controls") { !fullscreenExitVisible() }
        assertSamePlayer(scenario, player)
        require(device.currentPackageName == context.packageName) { "Only the owned fullscreen fixture can be captured." }
        assertTrue("Capture the owned fullscreen fixture with controls hidden",
            device.takeScreenshot(File(reports, "$name-device-fullscreen-controls-hidden.png")))

        assertTrue("Second single tap reaches the video", device.click(x, y))
        SystemClock.sleep(400)
        await("Single tap restores fullscreen controls") { fullscreenExitVisible() }
        assertSamePlayer(scenario, player)

        assertTrue("First double-tap event reaches the video", device.click(x, y))
        SystemClock.sleep(80)
        assertTrue("Second double-tap event reaches the video", device.click(x, y))
        await("Double tap exits fullscreen") { !fullscreenExitVisible() &&
            (device.hasObject(By.desc("Toàn màn hình")) || device.hasObject(By.text("Toàn màn hình"))) }
        assertSamePlayer(scenario, player)
        scenario.onActivity {
            assertFalse("Double tap must not finish the video screen", it.isFinishing)
            assertTrue("Double tap preserves playback time", abs(player.currentPosition - timeBefore.get()) < 500)
            assertFalse("Gesture toggles preserve paused playback", player.isPlaying)
        }

        // The inline preview can be partially clipped; re-enter using its explicit control.
        clickControl("Toàn màn hình", scroll = true)
        await("Re-enter fullscreen after double tap") { fullscreenExitVisible() }
        SystemClock.sleep(500)
        assertSamePlayer(scenario, player)
        scenario.onActivity {
            assertTrue("Re-entering after double tap preserves playback time", abs(player.currentPosition - timeBefore.get()) < 500)
            assertFalse("Re-entering preserves paused playback", player.isPlaying)
        }
    }

    private fun dragFullscreenFrame(scenario: ActivityScenario<VideoActivity>, project: VideoProject,
        store: VideoStore): VideoSubtitleStyle {
        clickControl("Chỉnh phụ đề")
        val points = IntArray(4)
        scenario.onActivity { activity ->
            val captions = requireNotNull(findCaption(requireNotNull(findPlayer(activity.window.decorView))))
            val scale = minOf(captions.width.toFloat() / project.width, captions.height.toFloat() / project.height)
            val contentWidth = project.width * scale
            val contentHeight = project.height * scale
            val origin = IntArray(2)
            captions.getLocationOnScreen(origin)
            val left = origin[0] + (captions.width - contentWidth) / 2f
            val top = origin[1] + (captions.height - contentHeight) / 2f
            points[0] = (left + contentWidth * .50f).roundToInt()
            points[1] = (top + contentHeight * .84f).roundToInt()
            points[2] = (left + contentWidth * .55f).roundToInt()
            points[3] = (top + contentHeight * .74f).roundToInt()
        }
        assertTrue("Frame gesture must reach the editor", device.drag(points[0], points[1], points[2], points[3], 35))
        await("Persist fullscreen frame drag") { store.load(project.id).style.frame != project.style.frame }
        val edited = store.load(project.id).style
        val frame = requireNotNull(edited.frame)
        val old = requireNotNull(project.style.frame)
        assertEquals("Horizontal drag maps to normalized source coordinates", .05f, frame.left - old.left, .025f)
        assertEquals("Vertical drag maps to normalized source coordinates", -.10f, frame.top - old.top, .025f)
        assertEquals(old.width, frame.width, .001f)
        assertEquals(old.height, frame.height, .001f)
        clickControl("Chỉnh phụ đề")
        return edited
    }

    private fun awaitPlayer(scenario: ActivityScenario<VideoActivity>, label: String, condition: (Player) -> Boolean) {
        await(label) {
            var met = false
            scenario.onActivity { activity -> findPlayer(activity.window.decorView)?.player?.let { met = condition(it) } }
            met
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        fail("Timed out: $label")
    }

    /** Draw this app's caption layer onto transparent pixels, independently of surface screenshots. */
    private fun assertMappedCaption(scenario: ActivityScenario<VideoActivity>, project: VideoProject,
        fill: Boolean, filename: String) {
        SystemClock.sleep(350) // Saved timeline position is refreshed on its ordinary 100 ms clock.
        scenario.onActivity { activity ->
            val playerView = requireNotNull(findPlayer(activity.window.decorView))
            val captions = requireNotNull(findCaption(playerView))
            require(captions.width > 0 && captions.height > 0)
            val bitmap = Bitmap.createBitmap(captions.width, captions.height, Bitmap.Config.ARGB_8888)
            try {
                captions.draw(Canvas(bitmap))
                val scale = if (fill) maxOf(captions.width.toFloat() / project.width, captions.height.toFloat() / project.height)
                    else minOf(captions.width.toFloat() / project.width, captions.height.toFloat() / project.height)
                val dx = (captions.width - project.width * scale) / 2f
                val dy = (captions.height - project.height * scale) / 2f
                val frame = requireNotNull(project.style.frame)
                val expected = RectF(dx + (frame.left * project.width).roundToInt() * scale,
                    dy + (frame.top * project.height).roundToInt() * scale,
                    dx + (frame.right * project.width).roundToInt() * scale,
                    dy + (frame.bottom * project.height).roundToInt() * scale)
                val clipped = RectF(expected)
                val visible = clipped.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                var pixels = 0
                var white = 0
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) > 32) {
                        assertTrue("Caption pixel outside mapped source frame ($x,$y) expected=$expected fill=$fill",
                            x >= expected.left - 2 && x <= expected.right + 2 && y >= expected.top - 2 && y <= expected.bottom + 2)
                        pixels++
                        if (Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) > 180) white++
                    }
                }
                if (visible && clipped.width() > 10 && clipped.height() > 10) {
                    assertTrue("Vietnamese caption/mask must render in the mapped video frame", pixels > 10)
                    // Fit always shows the entire translated text. Fill can crop source text as it crops the video.
                    if (!fill) assertTrue("Visible Vietnamese glyphs must be present", white > 5)
                } else assertEquals("Fully cropped source captions must not leak into letterboxing", 0, pixels)
                File(reports, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    /** Optional UI evidence: PixelCopy is scoped to this fixture Activity's window, never the display. */
    private fun copyOwnWindow(scenario: ActivityScenario<VideoActivity>, filename: String) {
        val latch = CountDownLatch(1)
        val status = AtomicInteger(-1)
        val copied = AtomicReference<Bitmap?>()
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            if (root.width <= 0 || root.height <= 0) {
                status.set(-3); latch.countDown(); return@onActivity
            }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            copied.set(bitmap)
            runCatching {
                PixelCopy.request(activity.window, bitmap, { status.set(it); latch.countDown() }, Handler(Looper.getMainLooper()))
            }.onFailure { status.set(-2); latch.countDown() }
        }
        val finished = latch.await(5, TimeUnit.SECONDS)
        if (finished && status.get() == PixelCopy.SUCCESS) copied.get()?.let { bitmap ->
            File(reports, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // PixelCopy may still own the destination after a timeout.
        if (finished) copied.get()?.recycle()
        File(reports, "$filename.json").writeText(JSONObject().put("finished", finished)
            .put("pixelcopy_status", status.get())
            .put("scope", "Owned fullscreen fixture Activity window; its video surface is captured separately")
            .toString(2))
    }

    private fun copyOwnVideoSurface(scenario: ActivityScenario<VideoActivity>, filename: String) {
        val latch = CountDownLatch(1)
        val status = AtomicInteger(-1)
        val copied = AtomicReference<Bitmap>()
        scenario.onActivity { activity ->
            val surface = findPlayer(activity.window.decorView)?.videoSurfaceView
                ?: error("Expected the player's own video surface")
            require(surface.width > 0 && surface.height > 0)
            val width = minOf(640, surface.width)
            val height = (surface.height.toLong() * width / surface.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            copied.set(bitmap)
            when (surface) {
                is TextureView -> {
                    val frame = surface.getBitmap(bitmap)
                    status.set(if (frame != null) PixelCopy.SUCCESS else PixelCopy.ERROR_SOURCE_NO_DATA)
                    latch.countDown()
                }
                is SurfaceView -> PixelCopy.request(surface, bitmap,
                    { status.set(it); latch.countDown() }, Handler(Looper.getMainLooper()))
                else -> error("Unsupported owned video surface: ${surface.javaClass.simpleName}")
            }
        }
        val finished = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Capture of the owned video surface timed out", finished)
        assertEquals(PixelCopy.SUCCESS, status.get())
        val bitmap = copied.get()
        try {
            var nonblack = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 20) nonblack++
            }
            assertTrue("Decoded fullscreen movie pixels must reach the video surface", nonblack > 10)
            File(reports, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findCaption(view: View): View? {
        if (view.javaClass.simpleName == "SavedTimelineSubtitleView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCaption(view.getChildAt(index))?.let { return it }
        return null
    }
}
