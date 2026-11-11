@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.video.*
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Real decoder/player/timeline witness. It does not call OCR, translation, render or Google. */
@RunWith(AndroidJUnit4::class)
class SmartSourceLibraryNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test(timeout = 180_000L)
    fun originalQualityLibraryKeepsTimelineAndSupportsReviewWithoutRendering():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeSmartSource")=="true")
        val context=instrumentation.targetContext
        require(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK && context.packageName=="com.gemini.ocr.codexbench")
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value && VideoJobs.get(context).busy.value==null && BatchJobs.get(context).busy.value==null)
        require(VideoCloudSync.get(context).state.value.accountEmail.isBlank())
        val runId=UUID.randomUUID().toString()
        val folder=File(context.getExternalFilesDir(null),"smart-source-fixtures").apply{mkdirs()}
        val source=File(folder,"source-$runId.mp4")
        instrumentation.context.assets.open("stability-source.mp4").use{input->source.outputStream().use{input.copyTo(it)}}
        val beforeHash=sha(source)
        val metadata=VideoDecoder.metadata(context,Uri.fromFile(source))
        val project=VideoProject(UUID.randomUUID().toString(),"Smart source native fixture",Uri.fromFile(source).toString(),
            metadata.durationMs,metadata.width,metadata.height,
            cues=listOf(VideoCue("approved",1000,3000,"你好","Xin chào Việt Nam",approved=true),
                VideoCue("draft",4000,6500,"再见","Hẹn gặp lại",approved=false)),
            style=VideoSubtitleStyle(position=.8f,sizeFraction=.055f),scanMs=metadata.durationMs,
            stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING)
        val library=MovieLibrary.get(context)
        val movieId=UUID.randomUUID().toString();val episodeId="original-1"
        val report=JSONObject().put("schema",1).put("completed",false).put("cloudCalls",0)
            .put("scope","Native generated source: original bytes, timeline overlay pixels, seek/pause/resume/fullscreen, explicit edit snapshot; no OCR/translation/render/cloud")
            .put("sourceSha256",beforeHash).put("sourceWidth",metadata.width).put("sourceHeight",metadata.height)
        val began=SystemClock.elapsedRealtime()
        try {
            assertFalse(library.movies.value.any{it.id==movieId})
            val saved=library.confirmSources(movieId,"Smart original source",listOf(MovieSourceInput(episodeId,"EP01",project)))
            val repeated=library.confirmSources(movieId,"Duplicate",listOf(MovieSourceInput(episodeId,"EP01",project)))
            assertEquals(saved.episodes,repeated.episodes)
            assertEquals(1,library.movies.value.count{it.id==movieId})
            val episode=saved.episodes.single()
            assertEquals(project.uri,episode.mediaUri)
            assertEquals(beforeHash,episode.source!!.sha256)
            assertEquals(project.cues,episode.source.cues)
            assertFalse(library.folder(movieId).listFiles().orEmpty().any{it.extension in setOf("mp4","input")})
            assertEquals("",project.exportedMp4)
            library.progress(movieId,episodeId,1500)
            val firstPixels=exercisePlayback(movieId,episodeId,1500,File(folder,"before-$runId.png"),true)

            val editor=library.createSourceEditor(movieId,episodeId)
            val changed=editor.copy(cues=editor.cues.map{if(it.id=="approved")it.copy(translation="PHỤ ĐỀ ĐÃ SỬA",approved=false,model="user-edited") else it})
            val progress=library.find(movieId)!!.episodes.single().positionMs
            val updated=library.refreshSourceSnapshot(movieId,episodeId,changed,episode.source.revision)
            assertEquals(progress,updated.episodes.single().positionMs)
            assertFalse(updated.episodes.single().source!!.cues.first().approved)
            assertEquals(project.cues.last(),updated.episodes.single().source!!.cues.last())
            library.progress(movieId,episodeId,1500)
            val secondPixels=exercisePlayback(movieId,episodeId,1500,File(folder,"after-$runId.png"),false)
            assertNotEquals("Edited timeline must change visible overlay pixels",firstPixels,secondPixels)
            assertEquals(beforeHash,sha(source))
            assertFalse(library.folder(movieId).listFiles().orEmpty().any{it.extension in setOf("mp4","input")})
            val moved=File(folder,"missing-$runId.mp4")
            check(source.renameTo(moved))
            try {
                assertFalse(library.isAvailable(updated.episodes.single()))
                assertEquals(changed.cues,library.find(movieId)!!.episodes.single().source!!.cues)
            } finally {check(moved.renameTo(source))}
            report.put("completed",true).put("sourcePreserved",true).put("renderedFiles",0)
                .put("timelineOverlayBefore",firstPixels).put("timelineOverlayAfter",secondPixels)
                .put("missingSourcePreservesTimeline",true).put("movieId",movieId)
        } catch(e:Throwable) {
            report.put("failure",e.javaClass.simpleName+": "+e.message.orEmpty().take(300));throw e
        } finally {
            report.put("elapsedMs",SystemClock.elapsedRealtime()-began)
            File(folder,"result-$runId.json").writeText(report.toString(2))
        }
    }

    private fun exercisePlayback(movie:String,episode:String,resume:Long,screenshot:File,fullscreen:Boolean):String {
        val context=instrumentation.targetContext
        val intent=Intent(context,MoviePlaybackActivity::class.java).putExtra("movie",movie).putExtra("episode",episode)
        ActivityScenario.launch<MoviePlaybackActivity>(intent).use{scenario->
            scenario.onActivity { activity -> activity.startActivity(Intent(activity,MoviePlaybackActivity::class.java)
                .putExtra("movie",movie).putExtra("episode",episode).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            val ready=AtomicBoolean(false);val restored=AtomicLong(-1)
            waitFor("decoded original video") {
                scenario.onActivity{activity->findPlayer(activity.window.decorView)?.player?.let{player->
                    assertNull(player.playerError)
                    if(player.playbackState==Player.STATE_READY){restored.set(player.currentPosition);ready.set(true)}
                }};ready.get()
            }
            assertTrue("Saved watch position must restore",abs(restored.get()-resume)<=300)
            scenario.onActivity{activity->findPlayer(activity.window.decorView)!!.player!!.apply{seekTo(1500);play()}}
            SystemClock.sleep(450)
            scenario.onActivity{activity->findPlayer(activity.window.decorView)!!.player!!.apply{
                assertTrue(currentPosition>1550);pause();seekTo(1500)
            }}
            SystemClock.sleep(250)
            var pixels=""
            waitFor("translated timeline overlay") {
                scenario.onActivity{activity->
                    findCaptions(activity.window.decorView)?.let{caption->
                        if(caption.width>0 && caption.height>0){
                            val bitmap=Bitmap.createBitmap(caption.width,caption.height,Bitmap.Config.ARGB_8888)
                            caption.draw(Canvas(bitmap))
                            val values=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(values,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                            if(values.count{(it ushr 24)>0}>30){
                                val digest=MessageDigest.getInstance("SHA-256")
                                val bytes=java.nio.ByteBuffer.allocate(values.size*4)
                                bytes.asIntBuffer().put(values);digest.update(bytes.array())
                                pixels=digest.digest().joinToString(""){"%02x".format(it)}
                            }
                            bitmap.recycle()
                        }
                    }
                };pixels.isNotBlank()
            }
            assertTrue("Benchmark playback must be the foreground app",device.wait(Until.hasObject(By.pkg("com.gemini.ocr.codexbench")),5000))
            assertTrue(device.takeScreenshot(screenshot))
            if(fullscreen){
                assertTrue(device.wait(Until.hasObject(By.desc("Toàn màn hình")),5000))
                device.findObject(By.desc("Toàn màn hình")).click();device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.desc("Thoát toàn màn hình")),5000))
                device.pressBack();device.waitForIdle()
                scenario.onActivity{assertFalse(it.isFinishing)}
            }
            return pixels
        }
    }
    private fun waitFor(label:String,check:()->Boolean){
        val deadline=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<deadline){if(check())return;SystemClock.sleep(100)}
        fail("Timed out waiting for $label")
    }
    private fun findPlayer(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findPlayer(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun findCaptions(view:View):View? {
        if(view.javaClass.simpleName=="SavedTimelineSubtitleView")return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findCaptions(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun sha(file:File):String=file.inputStream().use{input->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(128*1024)
        while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
}
