@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The controller runs this only after the offline fixture workflow; it never records other apps. */
@RunWith(AndroidJUnit4::class)
class VideoActivityNativeTest {
    @Test fun savedTimelinePreviewRendersFramesWithoutStartingProcessing(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase=args.getString("videoPhase")
        val permissionDeniedExpected=args.getString("networkPermissionDeniedExpected")=="true"
        assumeTrue(args.getString("executeVideo") == "true" && phase in setOf("offline","local-validation"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(!SessionState.running.value)
        if(phase=="offline")VideoOfflineNetworkWitness.verify(context)
        val fixture = File(args.getString("videoPreviewPath")?:File(context.getExternalFilesDir(null), "verification/video-source-modern.mp4").absolutePath)
        val store = VideoStore(context)
        val requestedId = args.getString("videoProjectId")
        val project = store.list().firstOrNull {
            (requestedId == null || it.id == requestedId) &&
                it.title.startsWith("Verification ·") &&
                android.net.Uri.parse(it.uri) == android.net.Uri.fromFile(fixture) &&
                it.cues.any { cue -> cue.translation.isNotBlank() }
        } ?: error("Run the offline video workflow first; no public verification timeline found.")
        // Bump only this owned fixture so the activity's ordinary recent-project behavior selects it.
        store.save(project)
        val jobs = VideoJobs.get(context)
        jobs.refresh()
        assertNull(jobs.busy.value)
        val reportFolder = File(context.getExternalFilesDir(null), "verification/video-workflow").apply { mkdirs() }
        // These files belong to this test. Never mistake an earlier successful image for this run.
        listOf("saved-timeline-activity.png","saved-timeline-video-surface.png").forEach{File(reportFolder,it).delete()}
        val report = JSONObject().put("project_id", project.id).put("completed", false)
            .put("network_verification_mode",if(permissionDeniedExpected)"APP_INTERNET_PERMISSION_DENIED; physical airplane mode NOT VERIFIED" else "DEVICE_NETWORK_DISCONNECTED")
            .put("physical_offline_acceptance",when {
                permissionDeniedExpected -> "NOT VERIFIED: Android denies this app's INTERNET permission; device networking may remain connected"
                phase=="offline" -> "requires blocked Internet from the disconnected device"
                else -> "NOT VERIFIED by this local-validation phase"
            })
            .put("network_setup",if(permissionDeniedExpected)"controller uses the verification APK without INTERNET permission" else "controller must leave device offline from the workflow test")
        val attempts = AtomicInteger()
        val frames = AtomicInteger()
        val playbackFailure = AtomicReference<String?>(null)
        val beforeNetwork = StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest = { attempts.incrementAndGet(); error("No network allowed while previewing a saved timeline.") }
        var scenario: ActivityScenario<VideoActivity>? = null
        try {
            val launched = ActivityScenario.launch(VideoActivity::class.java)
            scenario = launched
            assertEquals(Lifecycle.State.RESUMED, launched.state)
            val frameListener = object : Player.Listener {
                override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                    playbackFailure.set("${playbackError.errorCodeName}: ${playbackError.message}")
                }
            }
            var attached = false
            withTimeout(30000) {
                while (!attached) {
                    launched.onActivity { activity ->
                        findPlayer(activity.window.decorView)?.player?.let { player ->
                            player.addListener(frameListener)
                            (player as ExoPlayer).setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
                            player.seekTo(project.cues.first { it.translation.isNotBlank() }.startMs + 150)
                            player.play()
                            attached = true
                            report.put("player_attached",true)
                        }
                    }
                    delay(100)
                }
                while (frames.get() == 0 && playbackFailure.get() == null) delay(100)
            }
            assertNull(playbackFailure.get())
            assertTrue("Saved timeline preview must decode a real frame", frames.get() > 0)
            delay(400)
            launched.onActivity { activity -> findPlayer(activity.window.decorView)?.player?.pause() }

            // PixelCopy reads this activity's own window. It never uses UiAutomation's vendor
            // screen-capture implementation or captures the user desktop/another app.
            val latch = CountDownLatch(1)
            val screenshotStatus = AtomicInteger(-1)
            val screenshot = AtomicReference<Bitmap?>()
            launched.onActivity { activity ->
                val root = activity.window.decorView
                if (root.width <= 0 || root.height <= 0) { latch.countDown(); return@onActivity }
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                screenshot.set(bitmap)
                runCatching {
                    PixelCopy.request(activity.window, bitmap, { result -> screenshotStatus.set(result); latch.countDown() }, Handler(Looper.getMainLooper()))
                }.onFailure { screenshotStatus.set(-2); latch.countDown() }
            }
            val screenshotFinished = latch.await(5, TimeUnit.SECONDS)
            if (screenshotStatus.get() == PixelCopy.SUCCESS) screenshot.get()?.let { bitmap ->
                val file = File(reportFolder, "saved-timeline-activity.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                report.put("screenshot", file.absolutePath)
            }
            // PixelCopy may still own its destination after a timeout; let GC reclaim that case.
            if (screenshotFinished) screenshot.get()?.recycle()

            // A SurfaceView is composited separately and can be black in PixelCopy(window), even
            // while its decoder is producing frames. Copy this PlayerView's own video surface too;
            // a decoded-frame callback alone does not prove that visible movie pixels reached it.
            val surfaceLatch = CountDownLatch(1)
            val surfaceStatus = AtomicInteger(-1)
            val surfaceBitmap = AtomicReference<Bitmap?>()
            launched.onActivity { activity ->
                val surface = findPlayer(activity.window.decorView)?.videoSurfaceView as? SurfaceView
                if (surface == null || surface.width <= 0 || surface.height <= 0) {
                    surfaceStatus.set(-3); surfaceLatch.countDown(); return@onActivity
                }
                val width = minOf(640, surface.width)
                val height = (surface.height.toLong() * width / surface.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                surfaceBitmap.set(bitmap)
                runCatching {
                    PixelCopy.request(surface, bitmap, { result -> surfaceStatus.set(result); surfaceLatch.countDown() }, Handler(Looper.getMainLooper()))
                }.onFailure { surfaceStatus.set(-2); surfaceLatch.countDown() }
            }
            val surfaceFinished = surfaceLatch.await(5, TimeUnit.SECONDS)
            report.put("video_surface_pixelcopy_status", surfaceStatus.get())
                .put("window_screenshot_scope", "Activity UI; separately composited SurfaceView may be black in this image")
            var surfaceHasMoviePixels = false
            if (surfaceStatus.get() == PixelCopy.SUCCESS) surfaceBitmap.get()?.let { bitmap ->
                val file = File(reportFolder, "saved-timeline-video-surface.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                var minRed=255; var maxRed=0; var minGreen=255; var maxGreen=0; var minBlue=255; var maxBlue=0
                var nonBlack=0; var samples=0
                for (y in 0 until bitmap.height step maxOf(1, bitmap.height/36)) {
                    for (x in 0 until bitmap.width step maxOf(1, bitmap.width/64)) {
                        val pixel=bitmap.getPixel(x,y)
                        val red=Color.red(pixel); val green=Color.green(pixel); val blue=Color.blue(pixel)
                        minRed=minOf(minRed,red); maxRed=maxOf(maxRed,red)
                        minGreen=minOf(minGreen,green); maxGreen=maxOf(maxGreen,green)
                        minBlue=minOf(minBlue,blue); maxBlue=maxOf(maxBlue,blue)
                        if(maxOf(red,green,blue)>12)nonBlack++
                        samples++
                    }
                }
                val channelRange=maxOf(maxRed-minRed,maxGreen-minGreen,maxBlue-minBlue)
                surfaceHasMoviePixels=channelRange>16 && nonBlack>samples/100
                report.put("video_surface_screenshot",file.absolutePath)
                    .put("video_surface_width",bitmap.width).put("video_surface_height",bitmap.height)
                    .put("video_surface_sample_count",samples).put("video_surface_nonblack_samples",nonBlack)
                    .put("video_surface_max_channel_range",channelRange)
            }
            if(surfaceFinished)surfaceBitmap.get()?.recycle()
            report.put("video_surface_nonblack_nonuniform",surfaceHasMoviePixels)
            assertEquals("The saved video's own surface must be readable",PixelCopy.SUCCESS,surfaceStatus.get())
            assertTrue("Saved preview must contain nonblack, nonuniform movie pixels",surfaceHasMoviePixels)
            assertNull("Viewing must never schedule processing", jobs.busy.value)
            assertEquals(0, attempts.get())
            assertEquals(project.cues, store.load(project.id).cues)
            assertEquals(project.cloudCalls, store.load(project.id).cloudCalls)
            report.put("completed", true).put("rendered_frames", frames.get())
                .put("screenshot_pixelcopy_status", screenshotStatus.get())
                .put("gemini_dispatch_attempts", attempts.get()).put("worker_started", false)
        } catch (failure: Throwable) {
            report.put("error", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            scenario?.close()
            StandaloneTranslator.beforeNetworkCallForTest = beforeNetwork
            File(reportFolder, "activity-preview.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
