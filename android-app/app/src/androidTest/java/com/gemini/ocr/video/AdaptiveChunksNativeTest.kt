package com.gemini.ocr.video

import android.content.Intent
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
import java.util.Collections
import java.util.UUID

/** Real worker/storage/UI with1512 controlled cues; no video decoding or paid provider call. */
@RunWith(AndroidJUnit4::class)
class AdaptiveChunksNativeTest {
    @Test fun oversizedDialogueUsesTwoPartsAndKeepsEveryIdTimestampAndApprovedRow():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeAdaptiveChunks")=="true")
        val context=instrument.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.chunktest")
        require(!SessionState.running.value)
        val directory=File(context.getExternalFilesDir(null),"chunks-fixture").apply{mkdirs()}
        AppSettings(context).standaloneApiKey="fixture-no-network"
        val source="你好"+"中".repeat(14)
        val rows=List(1512){VideoCue("cue-$it",it*300L,it*300L+250,source,rawZh=source)}
            .mapIndexed{i,cue->if(i==0)cue.copy(translation="Bản đã duyệt",approved=true,model="fixture-approved")else cue}
        val store=VideoStore(context)
        val project=store.save(VideoProject(UUID.randomUUID().toString(),"Controlled 1,512-cue fixture · simulated Gemini",
            "file:///not-decoded.mp4",1512*300L,1280,720,translationMode=VideoTranslationMode.HYBRID,
            sourceMode=VideoSourceMode.OCR,cues=rows,scanMs=1512*300L,stage=VideoStage.PAUSED,resumeStage=VideoStage.TRANSLATING))
        val jobs=VideoJobs.get(context)
        val secondEntered=CompletableDeferred<Unit>();val releaseSecond=CompletableDeferred<Unit>()
        val finished=CompletableDeferred<Unit>()
        val requests=Collections.synchronizedList(mutableListOf<List<String>>())
        val oldResponse=VideoTranslationClient.overrideResponseForTest
        val oldNetwork=StandaloneTranslator.beforeNetworkCallForTest
        var network=0
        val report=JSONObject().put("completed",false).put("scope",
            "1512 controlled Chinese cues and simulated Vietnamese provider responses; actual worker, storage and UI. Not a real translated film or OCR accuracy measurement.")
        try {
            StandaloneTranslator.beforeNetworkCallForTest={network++;error("This native test must not contact Gemini")}
            VideoTranslationClient.overrideResponseForTest={batch->
                requests.add(batch.cueIds.toList())
                assertTrue(batch.fullDialogue);assertFalse(batch.cueIds.contains("cue-0"))
                assertEquals(1512,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
                assertEquals(project.durationMs,store.load(project.id).scanMs)
                assertTrue(File(store.folder(project.id),"source.zh.srt").isFile)
                if(requests.size==2){secondEntered.complete(Unit);releaseSecond.await()}
                val translated=JSONArray(batch.cueIds.reversed().map{
                    JSONObject().put("id",it).put("translation","Dữ liệu kiểm thử có kiểm soát.")})
                JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason","STOP")
                    .put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",
                        JSONObject().put("translations",translated).toString())))))).toString()
            }
            jobs.execute(project.id,false){finished.complete(Unit)}
            withTimeout(60_000){secondEntered.await()}
            val checkpoint=store.load(project.id)
            assertEquals(rows.first(),checkpoint.cues.first())
            assertTrue(requests.first().all{id->checkpoint.cues.first{it.id==id}.refinementState=="done"})
            assertTrue(requests[1].none{it in requests.first()})
            report.put("first_part_durable_before_second",true)
            val scenario=ActivityScenario.launch<VideoActivity>(Intent(context,VideoActivity::class.java).putExtra("project",project.id))
            try {
                instrument.waitForIdleSync();instrument.uiAutomation.waitForIdle(700,5_000)
                val image=File(directory,"between-parts.png")
                ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                    "screencap -p ${image.absolutePath}")).use{it.readBytes()}
                assertTrue(image.length()>1000)
            }finally{scenario.close()}
            releaseSecond.complete(Unit)
            withTimeout(60_000){finished.await()}
            val result=store.load(project.id)
            assertEquals(VideoStage.REVIEW,result.stage);assertEquals(2,requests.size)
            assertEquals(listOf(1133,378),requests.map{it.size})
            assertEquals(rows.drop(1).map{it.id},requests.flatten())
            assertEquals(rows.first(),result.cues.first())
            assertTrue(result.cues.all{VideoTranslationPlan.isComplete(it,true)})
            assertEquals(rows.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)},
                result.cues.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)})
            assertEquals(0,network);assertNull(jobs.busy.value)
            val srt=store.exportFile(project.id,"srt")
            val parsed=VideoSubtitles.parse("result.srt",srt.readBytes(),project.durationMs)
            assertEquals(1512,parsed.size);assertEquals(rows.map{it.startMs to it.endMs},parsed.map{it.startMs to it.endMs})
            File(directory,"controlled-result.srt").writeBytes(srt.readBytes())
            report.put("completed",true).put("total_cues",1512).put("approved_context_rows",1)
                .put("request_sizes",JSONArray(requests.map{it.size})).put("logical_requests",requests.size)
                .put("all_ids_timestamps_sources_preserved",true).put("approved_unchanged",true)
                .put("srt_rows",parsed.size)
        }catch(error:Throwable){report.put("failure",error.message?:error.javaClass.simpleName);throw error}
        finally {
            releaseSecond.complete(Unit)
            if(!finished.isCompleted){jobs.pause();runCatching{withTimeout(10_000){finished.await()}}}
            VideoTranslationClient.overrideResponseForTest=oldResponse
            StandaloneTranslator.beforeNetworkCallForTest=oldNetwork
            report.put("provider_network_calls",network)
            File(directory,"report.json").writeText(report.toString(2))
        }
    }
}
