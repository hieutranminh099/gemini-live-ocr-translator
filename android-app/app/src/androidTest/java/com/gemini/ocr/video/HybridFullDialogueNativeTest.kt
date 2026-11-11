package com.gemini.ocr.video

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
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
import java.util.UUID

/** Public media/native OCR and real worker; Gemini responses are labelled controlled stimuli. */
@RunWith(AndroidJUnit4::class)
class HybridFullDialogueNativeTest {
    @Test fun localExtractionCompletesBeforeOneFullDialogueRequestAndResumesOldVisionCheckpoint():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeHybridFullDialogue")=="true")
        val context=instrument.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.hybridtest")
        require(!SessionState.running.value)
        val directory=File(context.getExternalFilesDir(null),"hybrid-public").apply{mkdirs()}
        val source=File(directory,"source.mp4")
        val hash=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",hash)
        val metadata=VideoDecoder.metadata(context,Uri.fromFile(source))
        require(metadata.durationMs in 12_000L..15_000L && metadata.width==1280 && metadata.height==720)
        val settings=AppSettings(context)
        settings.standaloneApiKey=""
        require(settings.effectiveStandaloneApiKey().isBlank()) {"Only an empty synthetic test application is allowed"}
        val store=VideoStore(context)
        fun project()=VideoProject(UUID.randomUUID().toString(),"Public OCR fixture · controlled cloud response",
            Uri.fromFile(source).toString(),metadata.durationMs,metadata.width,metadata.height,
            sourceMode=VideoSourceMode.OCR,translationMode=VideoTranslationMode.HYBRID,
            roi=listOf(.05f,.68f,.95f,.99f))
        suspend fun execute(p:VideoProject):VideoProject {
            val done=CompletableDeferred<Unit>();val jobs=VideoJobs(context)
            jobs.execute(p.id,false){done.complete(Unit)}
            withTimeout(240_000){done.await()}
            assertNull(jobs.busy.value)
            return store.load(p.id)
        }
        fun snapshot(p:VideoProject,name:String){
            VideoJobs.get(context).refresh()
            val intent=Intent(context,VideoActivity::class.java).putExtra("project",p.id)
            val scenario=ActivityScenario.launch<VideoActivity>(intent)
            try {
                instrument.waitForIdleSync();instrument.uiAutomation.waitForIdle(700,5_000)
                val output=File(directory,name)
                ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                    "screencap -p ${output.absolutePath}")).use{it.readBytes()}
                assertTrue(output.length()>1000)
            }finally{scenario.close()}
        }
        val oldNetwork=StandaloneTranslator.beforeNetworkCallForTest
        val oldResponse=VideoTranslationClient.overrideResponseForTest
        var networkCalls=0;var requests=0
        val report=JSONObject().put("completed",false).put("source_sha256",hash)
            .put("scope","Public CC BY-SA video, real local OCR and worker. Controlled Vietnamese response fixtures are not real Gemini translation or accuracy evidence.")
        try {
            StandaloneTranslator.beforeNetworkCallForTest={networkCalls++;error("This native verification must never call a cloud API")}
            VideoTranslationClient.overrideResponseForTest=null
            val initial=store.save(project())
            val extracted=execute(initial)
            assertEquals(VideoStage.FAILED,extracted.stage)
            assertEquals(metadata.durationMs,extracted.scanMs);assertEquals(0,extracted.carry.length())
            assertTrue(extracted.cues.isNotEmpty());assertEquals(0,extracted.cloudCalls);assertEquals(0,networkCalls)
            assertTrue(extracted.message.contains("100%"))
            val sourceSrt=File(store.folder(initial.id),"source.zh.srt")
            assertTrue(sourceSrt.isFile)
            val parsed=VideoSubtitles.parse("source.srt",sourceSrt.readBytes(),metadata.durationMs)
            assertEquals(extracted.cues.map{it.startMs to it.endMs},parsed.map{it.startMs to it.endMs})
            File(directory,"extracted-source.zh.srt").writeBytes(sourceSrt.readBytes())
            snapshot(extracted,"source-100-before-cloud.png")
            report.put("source_finished_without_key_or_nmt_preparation",true).put("source_cues",extracted.cues.size)
                .put("source_scan_ms",extracted.scanMs).put("cloud_calls_at_extraction_end",extracted.cloudCalls)
            settings.standaloneApiKey="fixture-no-network"
            VideoTranslationClient.overrideResponseForTest={batch->
                requests++;assertTrue(batch.fullDialogue)
                val persisted=store.load(initial.id)
                assertEquals(metadata.durationMs,persisted.scanMs);assertEquals(0,persisted.carry.length())
                assertTrue(sourceSrt.isFile)
                val dialogue=JSONObject(batch.videoContext).getJSONArray("dialogue")
                assertEquals(extracted.cues.size,dialogue.length())
                assertEquals(extracted.cues.map{it.id},batch.cueIds)
                val rows=JSONArray(batch.cueIds.mapIndexed{index,id->
                    val digits=Regex("[0-9]+(?:[.,][0-9]+)*").findAll(batch.texts[index]).map{it.value}.joinToString(" ")
                    JSONObject().put("id",id).put("translation","Bản kiểm thử có kiểm soát. $digits".trim())
                })
                JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason","STOP")
                    .put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",
                        JSONObject().put("translations",rows).toString())))))).toString()
            }
            val translated=execute(extracted)
            assertEquals(1,requests);assertEquals(1,translated.cloudCalls);assertEquals(VideoStage.REVIEW,translated.stage)
            assertTrue(translated.cues.all{VideoTranslationPlan.isComplete(it,true) && !it.approved})
            assertEquals(extracted.cues.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)},
                translated.cues.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)})
            report.put("full_dialogue_logical_requests",requests).put("ids_source_timestamps_preserved",true)
            snapshot(translated,"whole-dialogue-complete.png")

            // A legacy Vision prefix is kept, then the remaining frames are read locally.
            settings.standaloneApiKey="";VideoTranslationClient.overrideResponseForTest=null
            val scan=metadata.durationMs/2
            val approved=VideoCue("existing-approved",0,500,"已保存","Bản đã duyệt",approved=true,model="user-reviewed")
            val old=store.save(project().copy(scanMs=scan,cues=listOf(approved),stage=VideoStage.FAILED,
                resumeStage=VideoStage.EXTRACTING,carry=JSONObject().put("visualScan",true)
                    .put("source","保留来源").put("start",1000).put("last",scan-250)
                    .put("blank",-1).put("blanks",0)))
            val resumed=execute(old)
            assertEquals(metadata.durationMs,resumed.scanMs);assertEquals(0,resumed.carry.length())
            assertEquals(approved,resumed.cues.first());assertEquals(0,resumed.cloudCalls)
            assertTrue(resumed.cues.any{it.rawZh=="保留来源" && it.startMs==1000L && it.endMs>=scan-250})
            VideoTimeline.validate(resumed.cues,metadata.durationMs)
            assertEquals(0,networkCalls)
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)})
            report.put("legacy_visual_prefix_resumed_locally",true).put("legacy_approved_unchanged",true)
                .put("legacy_source_scan_ms",resumed.scanMs).put("completed",true)
        }catch(error:Throwable){report.put("failure",error.message?:error.javaClass.simpleName);throw error}
        finally {
            VideoTranslationClient.overrideResponseForTest=oldResponse
            StandaloneTranslator.beforeNetworkCallForTest=oldNetwork
            report.put("network_calls",networkCalls).put("controlled_response_requests",requests)
            File(directory,"report.json").writeText(report.toString(2))
        }
    }
}
