package com.gemini.ocr.video

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchProject
import com.gemini.ocr.batch.BatchStore
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

/** Real public decoder/OCR, with explicitly injected native faults. Not a private-video repro. */
@RunWith(AndroidJUnit4::class)
class DecodeRecoveryNativeTest {
    @Test fun decoderReopensFromRealCheckpointsAndPersistentFailureCanResume():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeDecodeRecovery")=="true")
        val context=instrument.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.decodefix")
        val folder=File(context.getExternalFilesDir(null),"decode-public").apply{mkdirs()}
        val source=File(folder,"cee-interview-chinese-cc-by-sa.mp4")
        val hash=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",hash)
        val jobs=VideoJobs.get(context);val batches=BatchJobs.get(context);val store=VideoStore(context)
        val priorNetwork=StandaloneTranslator.beforeNetworkCallForTest
        val priorNative=VideoCodecBoundary.beforeOperationForTest
        var cloudCalls=0;var reserved=""
        val started=SystemClock.elapsedRealtime()
        val report=JSONObject().put("completed",false).put("source_sha256",hash)
            .put("scope","Real local decoder/OCR on public media with injected blank native state failure and decoder timeout. No private user media or real Gemini translation.")
            .put("license","CC BY-SA 4.0; VideoCome adaptation / Wikimedia Eesti / Amadvr / Misosoof")
        val device=UiDevice.getInstance(instrument)
        StandaloneTranslator.beforeNetworkCallForTest={cloudCalls++;error("Native recovery test forbids cloud requests")}
        suspend fun create(title:String):Pair<BatchProject,String> {
            var b=batches.create(title,BatchKind.SERIES)
            b=batches.add(b.id,listOf(Uri.fromFile(source)))
            val id=b.items.single().projectId
            jobs.save(store.load(id).copy(sourceMode=VideoSourceMode.OCR,translationMode=VideoTranslationMode.GEMINI,
                roi=listOf(.25f,.8f,.75f,.95f)))
            return b to id
        }
        suspend fun waitBatch(){withTimeout(240_000){while(batches.busy.value!=null)delay(100)}}
        fun assertPrefix(before:List<VideoCue>,after:List<VideoCue>){
            assertEquals(before.map{listOf(it.id,it.startMs,it.endMs,it.rawZh)},
                after.take(before.size).map{listOf(it.id,it.startMs,it.endMs,it.rawZh)})
            assertEquals(after.size,after.map{it.id}.distinct().size)
        }
        try {
            val (first,id)=create("Khôi phục bộ đọc video")
            reserved=first.id;jobs.reserveBatch(reserved)
            var creations=0;var blankInjected=false;var timeoutInjected=false
            val checkpoints=JSONArray();var prefix=emptyList<VideoCue>()
            VideoCodecBoundary.beforeOperationForTest={operation,ms->
                if(operation=="create_codec"){
                    creations++
                    if(creations>1){val saved=store.load(id)
                        assertTrue(saved.scanMs>3_000);assertTrue(saved.cues.isNotEmpty())
                        if(prefix.isEmpty())prefix=saved.cues else assertPrefix(prefix,saved.cues)
                        checkpoints.put(JSONObject().put("resumeMs",saved.scanMs).put("cueCount",saved.cues.size)
                            .put("pendingRaw",saved.carry.optJSONObject("draftRun")?.optString("raw")))
                    }
                }
                if(operation=="dequeue_output" && ms>=3_500 && !blankInjected){blankInjected=true;throw IllegalStateException()}
                if(operation=="dequeue_output" && ms>=7_000 && !timeoutInjected){timeoutInjected=true
                    throw VideoDecodeException("FRAME_DECODE_TIMEOUT","dequeue_output",ms)}
            }
            val extracted=withTimeout(240_000){jobs.runStageForBatch(reserved,id,BatchPipelineStage.EXTRACT)}
            report.put("automatic_recovery",JSONObject().put("creations",creations).put("checkpoints",checkpoints)
                .put("task",extracted.pipeline.task(BatchPipelineStage.EXTRACT).json()).put("message",extracted.message))
            assertTrue(extracted.message,VideoStagedPipeline.completed(extracted,BatchPipelineStage.EXTRACT))
            assertEquals(3,creations);assertTrue(blankInjected && timeoutInjected)
            assertPrefix(prefix,extracted.cues)
            assertEquals(3,extracted.pipeline.task(BatchPipelineStage.EXTRACT).attempts)
            val actual=extracted.cues.joinToString(" "){it.rawZh}
            for(text in listOf("见到","不知不觉","就是它"))assertTrue("Missing actual public caption: $text",actual.contains(text))
            val srt=VideoStagedPipeline.sourceSrt(extracted.cues,true)
            File(folder,"recovered-source.zh.srt").writeText(srt)
            val parsed=VideoSubtitles.parse("source.srt",srt.toByteArray(),extracted.durationMs)
            assertEquals(extracted.cues.map{it.startMs to it.endMs},parsed.map{it.startMs to it.endMs})
            assertEquals(extracted.cues,VideoStore(context).load(id).cues)
            jobs.releaseBatch(reserved);reserved=""

            val (second,secondId)=create("Lỗi bộ đọc có thể tiếp tục")
            var persistentCreations=0
            VideoCodecBoundary.beforeOperationForTest={operation,ms->
                if(operation=="create_codec")persistentCreations++
                if(operation=="dequeue_output" && ms>=3_500)throw IllegalStateException()
            }
            batches.start(second.id);waitBatch()
            val failed=store.load(secondId);val failedBatch=BatchStore(context).load(second.id)
            assertEquals(3,persistentCreations)
            assertEquals("NEEDS_REVIEW",failed.pipeline.task(BatchPipelineStage.EXTRACT).status)
            assertEquals("FRAME_CODEC_STATE",failed.pipeline.task(BatchPipelineStage.EXTRACT).errorCode)
            assertTrue(failed.scanMs in 3_000 until failed.durationMs);assertTrue(failed.cues.isNotEmpty())
            assertTrue(failed.message.contains("dequeue_output"))
            assertTrue(failedBatch.items.single().error.contains("FRAME_CODEC_STATE"))
            report.put("persistent_failure",JSONObject().put("creations",persistentCreations).put("scanMs",failed.scanMs)
                .put("cueCount",failed.cues.size).put("message",failed.message).put("batchState",failedBatch.state))
            instrument.runOnMainSync{context.startActivity(Intent(context,BatchActivity::class.java)
                .putExtra("batch",second.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)),10_000)
            var visible=device.hasObject(By.text("Đọc video bị gián đoạn"))
            repeat(6){if(!visible){device.swipe(device.displayWidth*2/5,device.displayHeight*4/5,
                device.displayWidth*2/5,device.displayHeight/3,25);device.waitForIdle()
                visible=device.hasObject(By.text("Đọc video bị gián đoạn"))}}
            assertTrue("Local decoder error was not visible",visible)
            assertFalse(device.hasObject(By.text("Kết nối đang gián đoạn")))
            assertTrue(device.takeScreenshot(File(folder,"local-decoder-error.png")))
            VideoCodecBoundary.beforeOperationForTest=null
            batches.start(second.id);waitBatch()
            val resumed=store.load(secondId)
            assertTrue(resumed.message,VideoStagedPipeline.completed(resumed,BatchPipelineStage.EXTRACT))
            assertPrefix(failed.cues,resumed.cues)
            assertEquals(resumed.cues,VideoStore(context).load(secondId).cues)
            report.put("explicit_batch_resume",JSONObject().put("scanMs",resumed.scanMs).put("cueCount",resumed.cues.size)
                .put("extractTask",resumed.pipeline.task(BatchPipelineStage.EXTRACT).json())
                .put("downstreamState",resumed.pipeline.currentStage.name))
            assertEquals(0,cloudCalls)
            report.put("completed",true).put("prefix_ids_timestamps_raw_preserved",true)
                .put("srt_mapping_preserved",true).put("local_error_not_network",true)
        } finally {
            VideoCodecBoundary.beforeOperationForTest=priorNative
            StandaloneTranslator.beforeNetworkCallForTest=priorNetwork
            if(reserved.isNotBlank())jobs.releaseBatch(reserved)
            if(batches.busy.value!=null)batches.pause()
            File(folder,"report.json").writeText(report.put("cloud_calls",cloudCalls)
                .put("elapsed_ms",SystemClock.elapsedRealtime()-started).toString(2))
        }
    }
}
