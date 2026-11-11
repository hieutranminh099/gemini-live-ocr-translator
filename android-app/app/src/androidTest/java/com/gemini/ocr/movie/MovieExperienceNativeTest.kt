@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.FullscreenPlayerView
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoExporter
import com.gemini.ocr.video.VideoPreviewDiagnostics
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoSubtitleFrame
import com.gemini.ocr.video.VideoSubtitleStyle
import com.gemini.ocr.video.subtitleBackdropPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Native graphics and playback on a generated test clip in a separate, network-denied app. */
@RunWith(AndroidJUnit4::class)
class MovieExperienceNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 240_000L)
    fun blurExportMergeAndFullscreenEpisodeSwipe(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeMovieExperience") == "true")
        val context = instrumentation.targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.movie189")
        require(BuildConfig.VERIFICATION_OFFLINE_NETWORK && BuildConfig.GEMINI_API_KEY.isEmpty())
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value)
        val folder = File(checkNotNull(context.getExternalFilesDir(null)), "movie-fixtures").apply { mkdirs() }
        val source = File(folder, "source.mp4")
        require(source.isFile && source.length() > 0) { "Stage the generated synthetic fixture in this test app only." }
        val beforeHash = sha256(source)
        val metadata = VideoDecoder.metadata(context, Uri.fromFile(source))
        require(metadata.width == 320 && metadata.height == 576 && metadata.durationMs in 3500L..4500L)
        val report = JSONObject().put("completed", false).put("fixture", "generated-testsrc2-and-sine")
            .put("sourceSha256", beforeHash).put("networkPermissionDenied", true)
        val reportFile = File(folder, "movie-experience-report.json")
        val movieId = UUID.randomUUID().toString()
        val output = File(folder, "blurred-$movieId.mp4")
        val merged = File(folder, "combined-$movieId.mp4")
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val library = MovieLibrary.get(context)
        var scenario: ActivityScenario<MoviePlaybackActivity>? = null
        val active = AtomicReference<Pair<String, Player>?>(null)
        val renderedPlayers = ConcurrentHashMap.newKeySet<Player>()
        StandaloneTranslator.beforeNetworkCallForTest = { error("Offline movie verification must never call Gemini") }
        try {
            val project = VideoProject(UUID.randomUUID().toString(), "Synthetic blur", Uri.fromFile(source).toString(),
                metadata.durationMs, metadata.width, metadata.height,
                roi = listOf(.04f, .77f, .96f, .88f),
                style = VideoSubtitleStyle(frame = VideoSubtitleFrame(.04f, .77f, .96f, .88f)),
                cues = listOf(VideoCue("caption", 500, 3500, "合成字幕", "Việt Nam", approved = true)),
                stage = VideoStage.REVIEW)
            withTimeout(100_000) { VideoExporter.export(context, project, output) }
            val rendered = VideoDecoder.metadata(context, Uri.fromFile(output))
            assertTrue(output.length() > 0 && rendered.hasAudio)
            assertTrue(abs(rendered.durationMs - metadata.durationMs) <= 500)
            val input = checkNotNull(VideoDecoder.preview(context, Uri.fromFile(source), 2000))
            val result = checkNotNull(VideoDecoder.preview(context, Uri.fromFile(output), 2000))
            try {
                input.save(File(folder, "source-frame.png")); result.save(File(folder, "blur-frame.png"))
                val plan = requireNotNull(subtitleBackdropPlan(project))
                val left = (plan.left * input.width).toInt() + 4
                val right = left + (input.width * .2f).toInt()
                val top = (plan.top * input.height).toInt() + 4
                val bottom = (plan.bottom * input.height).toInt() - 4
                val beforeEdges = edgeEnergy(input, left, top, right, bottom)
                val afterEdges = edgeEnergy(result, left, top, right, bottom)
                report.put("sourceEdgeEnergy", beforeEdges).put("blurEdgeEnergy", afterEdges)
                assertTrue("Original caption area must be blurred", afterEdges < beforeEdges * .85)
                var dark = 0; var count = 0
                for (y in top until bottom) for (x in left until right) {
                    val pixel = result.getPixel(x, y)
                    if (Color.red(pixel) < 15 && Color.green(pixel) < 15 && Color.blue(pixel) < 15) dark++
                    count++
                }
                val darkFraction = dark.toDouble() / count
                report.put("nearBlackFraction", darkFraction)
                assertTrue("Default subtitles must not draw a solid black slab", darkFraction < .35)
                var outsideDelta = 0.0; var samples = 0
                for (y in 80 until 330 step 4) for (x in 20 until input.width - 20 step 4) {
                    outsideDelta += abs(luma(input.getPixel(x, y)) - luma(result.getPixel(x, y))); samples++
                }
                report.put("outsideMeanLumaDelta", outsideDelta / samples)
                assertTrue("Blur must remain local to the caption band", outsideDelta / samples < 28)
            } finally { input.recycle(); result.recycle() }
            assertEquals(beforeHash, sha256(source))
            report.put("sourcePreserved", true)

            val ready = project.copy(scanMs = metadata.durationMs,
                resumeStage = VideoStage.TRANSLATING)
            library.confirmSources(movieId, "Synthetic two-episode movie", listOf(
                MovieSourceInput(firstId, "Tập 1", ready.copy(id = firstId)),
                MovieSourceInput(secondId, "Tập 2", ready.copy(id = secondId))), copySources = true)
            VideoPreviewDiagnostics.onPlayerForTest = { id, player ->
                if (id == firstId || id == secondId) {
                    if (player != null) {
                        active.set(id to player)
                        player.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { renderedPlayers.add(player) }
                        })
                    }
                    else if (active.get()?.first == id) active.set(null)
                }
            }
            scenario = ActivityScenario.launch(Intent(context, MoviePlaybackActivity::class.java)
                .putExtra("movie", movieId).putExtra("episode", firstId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("first episode player") { active.get()?.first == firstId }
            await("source-quality player rendered a native GPU frame") { active.get()?.second?.let(renderedPlayers::contains) == true }
            instrumentation.runOnMainSync {
                assertNull(active.get()!!.second.playerError)
                active.get()!!.second.pause(); active.get()!!.second.seekTo(2000)
            }
            delay(300)
            val device = UiDevice.getInstance(instrumentation)
            val enter = checkNotNull(device.wait(Until.findObject(By.desc("Toàn màn hình")), 10_000)) {
                "Fullscreen button must be reachable"
            }
            enter.click()
            delay(3300)
            await("fullscreen has rendered its paused frame") { active.get()?.second?.let(renderedPlayers::contains) == true }
            val pausedPlayer = requireNotNull(active.get()?.second)
            instrumentation.runOnMainSync {
                assertFalse("Fullscreen must preserve paused playback", pausedPlayer.playWhenReady)
                assertTrue("Fullscreen must preserve the exact paused position", pausedPlayer.currentPosition in 1980L..2020L)
                report.put("pausedPositionAfterFullscreenMs", pausedPlayer.currentPosition)
                    .put("fullscreenPreservesPausedPlayer", true)
                    .put("pausedPlayerState", pausedPlayer.playbackState)
                    .put("pausedPlayerError", pausedPlayer.playerError?.errorCodeName ?: "none")
            }
            assertFalse(device.hasObject(By.text("Về Kho phim")))
            assertFalse(device.hasObject(By.text("Thu nhỏ")))
            assertFalse(device.hasObject(By.text("Fit · đủ hình")))
            scenario.onActivity { activity -> report.put("playerHierarchy", describeView(activity.window.decorView)) }
            val screenshotFile = File(folder, "fullscreen.png")
            assertEquals(context.packageName, device.currentPackageName)
            assertTrue(device.takeScreenshot(screenshotFile))
            val screen = checkNotNull(BitmapFactory.decodeFile(screenshotFile.absolutePath))
            try {
                var dark = 0; var sampled = 0
                for (y in (screen.height * .55f).toInt() until (screen.height * .74f).toInt() step 4)
                    for (x in (screen.width * .2f).toInt() until (screen.width * .8f).toInt() step 4) {
                        val pixel = screen.getPixel(x, y)
                        if (Color.red(pixel) < 15 && Color.green(pixel) < 15 && Color.blue(pixel) < 15) dark++
                        sampled++
                    }
                val fraction = dark.toDouble() / sampled
                report.put("fullscreenLowerBlackFraction", fraction)
                if (fraction >= .35) {
                    instrumentation.runOnMainSync { active.get()!!.second.play() }
                    delay(300)
                    instrumentation.runOnMainSync { active.get()!!.second.pause() }
                    delay(300)
                    instrumentation.runOnMainSync {
                        report.put("diagnosticResumePositionMs", pausedPlayer.currentPosition)
                            .put("diagnosticResumePlayerState", pausedPlayer.playbackState)
                            .put("diagnosticResumePlayerError", pausedPlayer.playerError?.errorCodeName ?: "none")
                    }
                    device.takeScreenshot(File(folder, "fullscreen-fresh-frame.png"))
                    scenario.onActivity { activity -> report.put("afterFreshFrameHierarchy", describeView(activity.window.decorView)) }
                }
                assertTrue("Native video pixels must fill the portrait frame after rotation, not only its View", fraction < .35)
                val band = requireNotNull(subtitleBackdropPlan(project))
                val left = (screen.width * .075).toInt()
                val right = (screen.width * .24).toInt()
                val bandTop = (screen.height * (band.top + .012f)).toInt()
                val bandBottom = (screen.height * (band.bottom - .012f)).toInt()
                val outsideBottom = (screen.height * (band.top - .012f)).toInt()
                val outsideTop = outsideBottom - (bandBottom - bandTop)
                val insideEdges = verticalEdgeEnergy(screen, left, bandTop, right, bandBottom)
                val outsideEdges = verticalEdgeEnergy(screen, left, outsideTop, right, outsideBottom)
                report.put("pausedFullscreenBlurEdgeRatio", insideEdges / outsideEdges)
                assertTrue("Caption band must remain blurred on the paused fullscreen frame", insideEdges < outsideEdges * .85)
            } finally { screen.recycle() }
            scenario.onActivity { activity ->
                val view = requireNotNull(findPlayer(activity.window.decorView))
                assertTrue("Fullscreen player must fill the app width", view.width >= activity.window.decorView.width - 4)
                assertTrue("Fullscreen player must fill the app height", view.height >= activity.window.decorView.height - 4)
                view.player!!.pause(); view.player!!.seekTo(1200)
            }
            delay(300)
            swipe(scenario, downward = true)
            await("downward swipe selects next saved episode") { active.get()?.first == secondId }
            await("next source-quality episode renders") { active.get()?.second?.let(renderedPlayers::contains) == true }
            instrumentation.runOnMainSync { active.get()!!.second.pause() }
            delay(300)
            scenario.onActivity { activity ->
                val view = requireNotNull(findPlayer(activity.window.decorView))
                assertTrue("Next episode must remain fullscreen", view.height >= activity.window.decorView.height - 4)
            }
            assertFalse(device.hasObject(By.text("Về Kho phim")))
            await("outgoing episode position retained") {
                library.movies.value.first { it.id == movieId }.episodes.first { it.id == firstId }.positionMs in 1100..1500
            }
            delay(2800)
            swipe(scenario, downward = false)
            await("upward swipe returns to previous episode") { active.get()?.first == firstId }
            report.put("fullscreenFillsWindow", true).put("downNextUpPrevious", true)
                .put("outgoingProgressSaved", true).put("sourceQualityGpuPlayerRendered", true)
            instrumentation.runOnMainSync { active.get()!!.second.pause() }
            withTimeout(80_000) { VideoExporter.concatenate(context, listOf(output, output), merged) }
            val joined = VideoDecoder.metadata(context, Uri.fromFile(merged))
            assertTrue(joined.hasAudio && abs(joined.durationMs - 2 * rendered.durationMs) <= 700)
            report.put("mergedDurationMs", joined.durationMs).put("completed", true)
        } catch (error: Throwable) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message.orEmpty().take(500))
            throw error
        } finally {
            scenario?.close()
            VideoPreviewDiagnostics.onPlayerForTest = null
            StandaloneTranslator.beforeNetworkCallForTest = null
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private fun swipe(scenario: ActivityScenario<MoviePlaybackActivity>, downward: Boolean) {
        scenario.onActivity { activity ->
            val view = requireNotNull(findPlayer(activity.window.decorView))
            view.hideController()
            val density = view.resources.displayMetrics.density
            val x = view.width / 2f
            val from = if (downward) maxOf(70 * density, view.height * .30f) else view.height * .65f
            val to = if (downward) view.height * .65f else maxOf(70 * density, view.height * .30f)
            val start = SystemClock.uptimeMillis()
            for (i in 0..10) {
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                MotionEvent.obtain(start, start + i * 25L, action, x, from + (to - from) * i / 10, 0).also {
                    view.dispatchTouchEvent(it); it.recycle()
                }
            }
        }
    }

    private fun findPlayer(view: View): FullscreenPlayerView? {
        if (view is FullscreenPlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun describeView(view: View): JSONObject {
        val location = IntArray(2); view.getLocationOnScreen(location)
        val rect = android.graphics.Rect(); val visible = view.getGlobalVisibleRect(rect)
        return JSONObject().put("class", view.javaClass.simpleName)
            .put("id", runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("none"))
            .put("width", view.width).put("height", view.height)
            .put("measuredWidth", view.measuredWidth).put("measuredHeight", view.measuredHeight)
            .put("screenX", location[0]).put("screenY", location[1])
            .put("translationX", view.translationX).put("translationY", view.translationY)
            .put("scaleX", view.scaleX).put("scaleY", view.scaleY)
            .put("clipBounds", view.clipBounds?.toShortString() ?: "none")
            .put("globalVisible", if (visible) rect.toShortString() else "none")
            .put("textureTransform", if (view is android.view.TextureView)
                view.getTransform(android.graphics.Matrix()).toShortString() else "none")
            .apply { if (view is ViewGroup) {
                put("clipChildren", view.clipChildren).put("clipToPadding", view.clipToPadding)
                put("children", JSONArray((0 until view.childCount).map { describeView(view.getChildAt(it)) }))
            } }
    }
    private suspend fun await(label: String, condition: () -> Boolean) = withTimeout(12_000) {
        while (!condition()) delay(50)
    }
    private fun luma(color: Int) = (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114)
    private fun verticalEdgeEnergy(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Double {
        var sum = 0.0
        for (y in top until bottom - 1) for (x in left until right) {
            sum += abs(luma(bitmap.getPixel(x, y)) - luma(bitmap.getPixel(x, y + 1)))
        }
        return sum / ((bottom - top - 1) * (right - left))
    }
    private fun edgeEnergy(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Double {
        var sum = 0.0; var samples = 0
        for (y in top until bottom - 1) for (x in left until right - 1) {
            val value = luma(bitmap.getPixel(x, y))
            sum += abs(value - luma(bitmap.getPixel(x + 1, y))) + abs(value - luma(bitmap.getPixel(x, y + 1)))
            samples++
        }
        return sum / samples
    }
    private fun Bitmap.save(file: File) = file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
