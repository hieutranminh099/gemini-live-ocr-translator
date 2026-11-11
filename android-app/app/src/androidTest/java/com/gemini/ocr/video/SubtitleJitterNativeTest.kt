@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual player/export on public media. Caption rows are controlled timing stimuli, not AI output. */
@RunWith(AndroidJUnit4::class)
class SubtitleJitterNativeTest {
    @Test fun savedSubtitlesFollowPlayerFramesThroughTailSeekPauseEndAndReopen():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeSubtitleJitter")=="true")
        val context=instrument.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.jittertest")
        require(!SessionState.running.value)
        val directory=File(context.getExternalFilesDir(null),"jitter-public").apply{mkdirs()}
        val source=File(directory,"source-65s.mp4")
        val expectedHash=requireNotNull(args.getString("fixtureSha256")).lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        fun sourceHash()=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals(expectedHash,sourceHash())
        val metadata=VideoDecoder.metadata(context,Uri.fromFile(source))
        require(metadata.durationMs in 64_000L..66_000L && metadata.width==1280 && metadata.height==720)
        val end=metadata.durationMs
        val a="见到维基人，就像见到家人和朋友。"
        val b="不知不觉就熟悉了每个人，\n聊个不停，舍不得离开。"
        val c="这次聚会唯一让人遗憾的，\n就是它终究会结束。"
        fun cue(id:String,start:Long,until:Long,text:String)=VideoCue(id,start,until,text,text,
            approved=true,origin="CONTROLLED_PUBLIC_TIMING_FIXTURE",model="user-edited",rawZh=text,correctedZh=text)
        // Deliberate controlled boundaries cover the requested cases. They are not a transcript
        // of the looped video and must not be presented as translation/OCR accuracy evidence.
        val cues=listOf(cue("start",0,8_000,a),cue("second",8_000,16_000,b),
            cue("repeat",16_000,24_000,a),cue("same-text-next-id",24_000,28_000,a),
            cue("short-65ms",28_000,28_065,"就是它终究会结束。"),cue("after-short",28_065,34_000,b),
            cue("after-gap",35_000,45_000,c),cue("distant-repeat",45_000,50_000,a),
            cue("before-tail",50_000,end-200,b),cue("tail",end-200,end,c))
        val store=VideoStore(context)
        val project=store.save(VideoProject(UUID.randomUUID().toString(),"Public timing fixture · not a translated film",
            Uri.fromFile(source).toString(),end,metadata.width,metadata.height,
            stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING,sourceMode=VideoSourceMode.SIDECAR,
            scanMs=end,progress=100,cues=cues,
            style=VideoSubtitleStyle(frame=VideoSubtitleFrame(.08f,.04f,.92f,.23f),background=true)))
        VideoJobs.get(context).refresh()
        val intent=Intent(context,VideoActivity::class.java).putExtra("project",project.id).putExtra("ux_step","review")
        val player=AtomicReference<Player?>()
        val frame=AtomicReference<VideoPreviewFrame?>()
        val frames=Collections.synchronizedList(mutableListOf<VideoPreviewFrame>())
        val attaches=AtomicInteger();val detaches=AtomicInteger();val cloud=AtomicInteger()
        val oldPlayerHook=VideoPreviewDiagnostics.onPlayerForTest
        val oldFrameHook=VideoPreviewDiagnostics.onFrameForTest
        val oldNetwork=StandaloneTranslator.beforeNetworkCallForTest
        var scenario:ActivityScenario<VideoActivity>?=null
        val report=JSONObject().put("completed",false).put("source_sha256",expectedHash)
            .put("scope","Actual ExoPlayer on a 65s loop of public CC BY-SA media; controlled Chinese timing stimuli, not OCR/translation accuracy or the user's private project.")
            .put("caption_rows_are_controlled_stimuli",true).put("source_duration_ms",end)
            .put("license","CC BY-SA 4.0; VideoCome adaptation / Wikimedia Eesti / Amadvr / Misosoof")
        val started=SystemClock.elapsedRealtime()
        fun main(action:()->Unit){instrument.runOnMainSync(action)}
        fun screenshot(name:String){
            // BlueStacks API28 crashes its UiAutomation screenshot Binder. The Android
            // screencap binary works on the same display and includes the video surface.
            instrument.waitForIdleSync()
            instrument.uiAutomation.waitForIdle(500,3_000)
            val target=File(directory,name)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                "screencap -p ${target.absolutePath}")).use{it.readBytes()}
            assertTrue("Android screencap did not create $name",target.length()>1000)
        }
        suspend fun await(label:String,timeout:Long=12_000,predicate:()->Boolean){
            withTimeout(timeout){while(!predicate())delay(15)}
            report.put("last_completed_wait",label)
        }
        fun observed()=synchronized(frames){frames.toList()}
        fun expectedAt(ms:Long)=cues.firstOrNull{ms>=it.startMs && ms<it.endMs}
        suspend fun seek(ms:Long):VideoPreviewFrame {
            frame.set(null)
            main{requireNotNull(player.get()).apply{pause();seekTo(ms)}}
            await("seek $ms"){
                var ready=false
                main{ready=player.get()?.playbackState==Player.STATE_READY}
                val f=frame.get();f!=null && abs(f.positionMs-ms)<=2 && f.cueId==expectedAt(ms)?.id && !f.ended
                    && ready
            }
            return requireNotNull(frame.get()).also{assertEquals(expectedAt(ms)?.translation.orEmpty(),it.text)}
        }
        VideoPreviewDiagnostics.onPlayerForTest={id,actual->if(id==project.id){
            player.set(actual);if(actual==null)detaches.incrementAndGet()else attaches.incrementAndGet()
        }}
        VideoPreviewDiagnostics.onFrameForTest={actual->if(actual.projectId==project.id){
            frame.set(actual);if(frames.size<20_000)frames.add(actual)
        }}
        StandaloneTranslator.beforeNetworkCallForTest={cloud.incrementAndGet();error("Saved subtitle playback must not call Gemini")}
        try {
            scenario=ActivityScenario.launch(intent)
            await("player attached"){player.get()!=null}
            await("initial frame"){frame.get()!=null}
            val seeks=JSONArray()
            for(ms in listOf(6_000L,end-3_000,38_000L,15_000L,56_000L,28_032L,34_500L,47_000L,end-100,31_000L)){
                val f=seek(ms)
                seeks.put(JSONObject().put("requested_ms",ms).put("actual_ms",f.positionMs)
                    .put("cue_id",f.cueId?:JSONObject.NULL).put("expected_id",expectedAt(ms)?.id?:JSONObject.NULL))
            }
            report.put("tail_forward_backward_seeks",seeks)

            // Different IDs with identical visible text must not clear/rebuild the subtitle.
            val stable=seek(22_000)
            val stableStart=observed().size
            main{player.get()!!.apply{setPlaybackSpeed(1f);play()}}
            await("same-text ID boundary",6_000){(frame.get()?.positionMs?:0)>=24_800}
            main{player.get()!!.pause()}
            val stableRows=observed().drop(stableStart).filter{it.positionMs in 22_000L..25_000L && !it.ended}
            assertTrue(stableRows.isNotEmpty())
            assertTrue(stableRows.all{it.text==a})
            assertTrue("Unchanged subtitle was invalidated repeatedly",stableRows.all{it.invalidationCount==stable.invalidationCount})
            repeat(3){main{player.get()!!.play()};delay(100);main{player.get()!!.pause()};delay(60)}
            assertEquals(stable.invalidationCount,frame.get()!!.invalidationCount)
            report.put("stable_same_text_no_extra_invalidations",true)

            for(speed in listOf(1f,.25f)){
                seek(27_900)
                val from=observed().size
                main{player.get()!!.apply{setPlaybackSpeed(speed);play()}}
                await("short cue at $speed",6_000){(frame.get()?.positionMs?:0)>=28_150}
                main{player.get()!!.pause()}
                assertTrue("65ms source cue was skipped at speed $speed",observed().drop(from).any{it.cueId=="short-65ms"})
            }
            report.put("short_65ms_observed_at_normal_and_quarter_speed",true)

            seek(end-1_000)
            main{player.get()!!.apply{setPlaybackSpeed(2f);play()}}
            await("ended clears caption",8_000){frame.get()?.let{it.ended && it.cueId==null && it.text.isEmpty()}==true}
            delay(250)
            val ended=frame.get()!!;val endedCount=observed().size
            delay(500)
            assertEquals("Ended player still scheduled subtitle frames",endedCount,observed().size)
            assertEquals(ended.invalidationCount,frame.get()!!.invalidationCount)
            screenshot("tail-ended.png")
            main{player.get()!!.apply{seekTo(0);setPlaybackSpeed(1f);play()}}
            await("replay initial caption",5_000){frame.get()?.let{!it.ended && it.cueId=="start" && it.positionMs in 1L..2_000L}==true}
            main{player.get()!!.pause()}
            report.put("ended_cleared_and_replay_correct",true)

            val firstPlayer=player.get()
            scenario?.close();scenario=null
            await("player detached"){player.get()==null}
            delay(150);val closedEvents=observed().size;delay(400)
            assertEquals("Disposed preview emitted stale frames",closedEvents,observed().size)
            scenario=ActivityScenario.launch(intent)
            await("new player attached"){player.get()!=null && player.get()!==firstPlayer}
            seek(end-100)
            assertEquals("tail",frame.get()!!.cueId)
            screenshot("tail-preview.png")
            scenario?.close();scenario=null
            await("second player detached"){player.get()==null}
            report.put("closed_callbacks_stopped_and_reopen_new_player",true)

            val srt=VideoTimeline.srt(cues)
            File(directory,"controlled-timing.zh.srt").writeText(srt)
            val parsed=VideoSubtitles.parse("controlled.srt",srt.toByteArray(Charsets.UTF_8),end)
            assertEquals(cues.map{it.startMs to it.endMs},parsed.map{it.startMs to it.endMs})
            assertEquals(cues.map{it.translation},parsed.map{it.source})
            val srtRows=parsed.map{it.copy(translation=it.source)}
            val originalOverlay=TimelineBitmapOverlay(cues,project.style)
            val parsedOverlay=TimelineBitmapOverlay(srtRows,project.style)
            originalOverlay.configure(Size(project.width,project.height));parsedOverlay.configure(Size(project.width,project.height))
            try {
                val first=originalOverlay.getBitmap(22_000_000)
                assertSame("Same text across IDs rebuilt the export bitmap",first,originalOverlay.getBitmap(25_000_000))
                for(ms in listOf(20_000L,28_032L,34_500L,end-80,end)){
                    val left=originalOverlay.getBitmap(ms*1000);val right=parsedOverlay.getBitmap(ms*1000)
                    assertTrue("SRT and preview/export raster disagree at $ms",left.sameAs(right))
                }
            }finally{originalOverlay.release();parsedOverlay.release()}
            val output=File(directory,"jitter-export-${UUID.randomUUID()}.mp4")
            withTimeout(300_000){VideoExporter.export(context,project,output)}
            val exported=VideoDecoder.metadata(context,Uri.fromFile(output))
            assertEquals(project.width,exported.width);assertEquals(project.height,exported.height)
            assertTrue(abs(end-exported.durationMs)<1_000);assertEquals(metadata.hasAudio,exported.hasAudio)
            val activeFrame=requireNotNull(VideoDecoder.preview(context,Uri.fromFile(output),20_000))
            val gapFrame=requireNotNull(VideoDecoder.preview(context,Uri.fromFile(output),34_500))
            val tailFrame=requireNotNull(VideoDecoder.preview(context,Uri.fromFile(output),end-80))
            val originalFrame=requireNotNull(VideoDecoder.preview(context,Uri.fromFile(source),20_000))
            try {
                val activeBlack=borderBlackFraction(activeFrame);val originalBlack=borderBlackFraction(originalFrame)
                val gapBlack=borderBlackFraction(gapFrame);val tailBlack=borderBlackFraction(tailFrame)
                report.put("encoded_top_caption_border_black_fraction",JSONObject().put("active",activeBlack)
                    .put("source",originalBlack).put("gap",gapBlack).put("tail",tailBlack))
                assertTrue("Encoded active caption frame absent",activeBlack>.9 && activeBlack-originalBlack>.25)
                assertTrue("Encoded gap incorrectly retained caption",gapBlack<.85)
                assertTrue("Encoded final caption frame absent",tailBlack>.9)
                save(activeFrame,File(directory,"export-active.png"));save(gapFrame,File(directory,"export-gap.png"))
                save(tailFrame,File(directory,"export-tail.png"))
            }finally{activeFrame.recycle();gapFrame.recycle();tailFrame.recycle();originalFrame.recycle()}
            assertEquals(expectedHash,sourceHash())
            assertEquals(cues,store.load(project.id).cues)
            assertNull(VideoJobs.get(context).busy.value);assertEquals(0,cloud.get())
            // Exercise the real staged decoder's last-image OCR, without rescanning a
            // private project or calling a translation provider. Only the public last second.
            val priorDraftObserver=VideoDecoder.draftObserverForTest
            val recognizedPts=mutableListOf<Long>()
            var scanCheckpoint=-1L;var scanCarry=JSONObject()
            val extracted=try {
                VideoDecoder.draftObserverForTest={ms,_->recognizedPts.add(ms)}
                withTimeout(60_000){VideoDecoder.scanDrafts(context,project.copy(cues=emptyList(),
                    scanMs=end-1_000,carry=JSONObject(),roi=listOf(.05f,.68f,.95f,.99f)),gate={}){ms,_,carry->
                    scanCheckpoint=ms;scanCarry=carry
                }}
            }finally{VideoDecoder.draftObserverForTest=priorDraftObserver}
            val scanMetrics=requireNotNull(scanCarry.optJSONObject("metrics"))
            assertEquals(end,scanCheckpoint)
            assertTrue(recognizedPts.isNotEmpty())
            // A stream-copy cut may retain B-frame packets beyond container duration.
            // They cannot authorize drawing/OCR beyond the project's valid interval.
            val sourceTrack=MediaExtractor()
            val finalValidPts=try {
                sourceTrack.setDataSource(context,Uri.fromFile(source),null)
                sourceTrack.selectTrack((0 until sourceTrack.trackCount).first{
                    sourceTrack.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/")==true})
                var last=-1L
                while(sourceTrack.sampleTime>=0){val ms=sourceTrack.sampleTime/1000
                    if(ms<end)last=maxOf(last,ms)
                    if(!sourceTrack.advance())break
                };last
            }finally{sourceTrack.release()}
            assertEquals("The final valid decoded image was not recognized",finalValidPts,recognizedPts.last())
            assertTrue(scanMetrics.getLong("ocr_native_calls")>0)
            VideoTimeline.validate(extracted,end)
            report.put("real_decoder_final_image_ocr",JSONObject().put("scan_start_ms",end-1_000)
                .put("final_ocr_pts_ms",recognizedPts.last()).put("max_decoded_pts_ms",scanMetrics.getLong("max_output_ms"))
                .put("last_valid_source_pts_ms",finalValidPts)
                .put("native_ocr_calls",scanMetrics.getLong("ocr_native_calls")).put("cue_count",extracted.size))
            report.put("completed",true).put("srt_preview_export_timing_parity",true).put("export_path",output.absolutePath)
                .put("export_duration_ms",exported.durationMs).put("export_width",exported.width)
                .put("export_height",exported.height).put("export_has_audio",exported.hasAudio)
                .put("attached_players",attaches.get()).put("detached_players",detaches.get())
        }catch(error:Throwable){report.put("failure",error.message?:error.javaClass.simpleName);throw error}
        finally{
            scenario?.close()
            VideoPreviewDiagnostics.onPlayerForTest=oldPlayerHook
            VideoPreviewDiagnostics.onFrameForTest=oldFrameHook
            StandaloneTranslator.beforeNetworkCallForTest=oldNetwork
            report.put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("cloud_calls",cloud.get())
            File(directory,"report.json").writeText(report.toString(2))
        }
    }

    /** The opaque top frame is disjoint from the public clip's original bottom hardsubs. */
    private fun borderBlackFraction(bitmap:Bitmap):Double {
        var black=0;var count=0
        val left=(bitmap.width*.08).toInt()+4;val right=(bitmap.width*.92).toInt()-5
        val top=(bitmap.height*.04).toInt()+4;val bottom=(bitmap.height*.23).toInt()-4
        for(y in top until bottom step 3)for(x in listOf(left,left+2,right-2,right)){
            val color=bitmap.getPixel(x,y);if(maxOf(Color.red(color),Color.green(color),Color.blue(color))<24)black++
            count++
        }
        return black.toDouble()/count.coerceAtLeast(1)
    }
    private fun save(bitmap:Bitmap,file:File){file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
}
