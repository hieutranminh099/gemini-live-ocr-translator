package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/** Real worker/persistence with local SRT input and controlled responses; no provider API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoHybridWorkerTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val store:VideoStore get()=VideoStore(context)
    @Before fun reset(){
        SessionState.running.value=false
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
        StandaloneTranslator.beforeNetworkCallForTest={error("This offline test must not call the provider")}
    }
    @After fun clear(){VideoTranslationClient.overrideResponseForTest=null;StandaloneTranslator.beforeNetworkCallForTest=null}
    private fun key(){context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
        .putString("standalone_api_key","synthetic-invalid-ciphertext")
        .putString("standalone_api_key_plain_fallback","fixture-no-network").commit()}
    private fun source(count:Int=70):VideoProject {
        val duration=count*1000L
        val rows=List(count){VideoCue("row-$it",it*1000L,it*1000L+900,"你好",translation="你好")}
        val srt=File(context.cacheDir,"source-${UUID.randomUUID()}.srt").apply{writeText(VideoTimeline.srt(rows,duration))}
        // Exercise Android's document-provider path without treating a Windows drive
        // path as an Android file URI. Each retry receives a fresh stream.
        val uri=Uri.parse("content://hybrid-fixture/${UUID.randomUUID()}/source.srt")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri){srt.inputStream()}
        return store.save(VideoProject(UUID.randomUUID().toString(),"Synthetic dialogue fixture","file:///not-read.mp4",
            duration,1280,720,translationMode=VideoTranslationMode.HYBRID,sourceMode=VideoSourceMode.SIDECAR,
            subtitleUri=uri.toString(),subtitleName="fixture.srt"))
    }
    private fun full(count:Int=70):VideoProject {
        val p=source(count)
        val rows=VideoSubtitles.readSidecar(context,p)
        return store.save(p.copy(cues=rows,scanMs=p.durationMs,stage=VideoStage.PAUSED,resumeStage=VideoStage.TRANSLATING))
    }
    private fun body(ids:List<String>,reason:String="STOP"):String=JSONObject().put("candidates",JSONArray().put(
        JSONObject().put("finishReason",reason).put("content",JSONObject().put("parts",JSONArray().put(
            JSONObject().put("text",JSONObject().put("translations",JSONArray(ids.map{
                JSONObject().put("id",it).put("translation","Xin chào.")
            })).toString())))))).toString()
    private suspend fun run(p:VideoProject,stage:BatchPipelineStage?=null):VideoProject {
        val done=CompletableDeferred<Unit>();val jobs=VideoJobs(context)
        jobs.execute(p.id,false,stage){done.complete(Unit)}
        withTimeout(15_000){done.await()}
        assertNull(jobs.busy.value)
        return store.load(p.id)
    }
    @Test fun extractionFinishesAndSourceIsSavedWithoutAnyGeminiKeyOrOfflineNmtModel()=runBlocking {
        val p=source();var calls=0
        VideoTranslationClient.overrideResponseForTest={calls++;error("Cloud cannot run before source/key readiness")}
        val result=run(p)
        assertEquals(VideoStage.FAILED,result.stage);assertEquals(p.durationMs,result.scanMs)
        assertEquals(70,result.cues.size);assertEquals(0,result.carry.length())
        assertEquals(0,calls);assertEquals(0,result.cloudCalls)
        assertTrue(result.message.contains("100%"))
        val saved=File(store.folder(p.id),"source.zh.srt")
        assertEquals(70,VideoSubtitles.parse("source.srt",saved.readBytes(),p.durationMs).size)
    }
    @Test fun seventyRowsUseOneFullRequestOnlyAfterTheSourceCheckpointAndSrtExist()=runBlocking {
        key();val p=source();var calls=0
        VideoTranslationClient.overrideResponseForTest={batch->
            calls++;assertTrue(batch.fullDialogue);assertEquals(70,batch.cueIds.size)
            val saved=store.load(p.id)
            assertEquals(p.durationMs,saved.scanMs);assertEquals(0,saved.carry.length())
            assertEquals(70,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
            assertTrue(File(store.folder(p.id),"source.zh.srt").isFile)
            body(batch.cueIds)
        }
        val result=run(p)
        assertEquals(VideoStage.REVIEW,result.stage);assertEquals(1,calls);assertEquals(1,result.cloudCalls)
        assertEquals(70,result.cues.size);assertTrue(result.cues.all{it.translation=="Xin chào." && it.refinementState=="done" && !it.approved})
    }
    @Test fun quotaFailureKeepsFullSourceAndDoesNotSendSmallerRequests()=runBlocking {
        key();val p=source();var calls=0
        VideoTranslationClient.overrideResponseForTest={calls++;throw VideoApiException("API_QUOTA_EXHAUSTED",detail="Fixture quota exhausted")}
        val result=run(p)
        assertEquals(1,calls);assertEquals(1,result.cloudCalls);assertEquals(VideoStage.FAILED,result.stage)
        assertEquals(p.durationMs,result.scanMs);assertEquals(70,result.cues.size)
        assertTrue(result.cues.all{it.source=="你好" && it.translation.isBlank()})
        assertTrue(File(store.folder(p.id),"source.zh.srt").isFile)
    }
    @Test fun partialReplyStopsAfterOneRequestAndExplicitResumeTargetsOnlyMissingIdsWithAllContext()=runBlocking {
        key();val p=full(4);val approved=p.cues.first().copy(translation="Bản đã duyệt",approved=true)
        val saved=store.save(p.copy(cues=listOf(approved)+p.cues.drop(1)))
        val requests=mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest={batch->
            requests+=batch.cueIds;assertEquals(4,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
            body(if(requests.size==1)batch.cueIds.take(2)else batch.cueIds)
        }
        val failed=run(saved)
        assertEquals(1,requests.size);assertEquals(VideoStage.FAILED,failed.stage)
        assertEquals(approved,failed.cues.first());assertEquals("done",failed.cues[1].refinementState)
        assertEquals("pending",failed.cues.last().refinementState)
        val result=run(failed)
        assertEquals(2,requests.size);assertEquals(listOf(p.cues.last().id),requests.last())
        assertEquals(approved,result.cues.first());assertEquals(VideoStage.REVIEW,result.stage)
        assertEquals(p.cues.map{Triple(it.id,it.startMs,it.endMs)},result.cues.map{Triple(it.id,it.startMs,it.endMs)})
    }
    @Test fun tokenLimitedShuffledReplyKeepsTheLastReturnedIdPendingInsteadOfReportingSuccess()=runBlocking {
        key();val p=full(4);var calls=0
        VideoTranslationClient.overrideResponseForTest={batch->calls++;body(batch.cueIds.reversed(),"MAX_TOKENS")}
        val result=run(p)
        assertEquals(1,calls);assertEquals(VideoStage.FAILED,result.stage)
        assertEquals(3,result.cues.count{VideoTranslationPlan.isComplete(it,true)})
        assertEquals("pending",result.cues.first().refinementState)
        assertEquals("done",result.cues.last().refinementState)
        assertTrue(result.message.contains("TEXT_TRUNCATED"));assertEquals(p.durationMs,result.scanMs)
    }
    @Test fun ambiguousDuplicateIdsCannotOverwriteAnySavedCueOrTriggerAutomaticRepair()=runBlocking {
        key();val p=full(3);var calls=0
        VideoTranslationClient.overrideResponseForTest={batch->calls++;body(listOf(batch.cueIds[0],batch.cueIds[0]))}
        val result=run(p)
        assertEquals(1,calls);assertEquals(VideoStage.FAILED,result.stage)
        assertTrue(result.cues.all{it.translation.isBlank()});assertEquals(p.cues.map{it.source},result.cues.map{it.source})
    }
    @Test fun fullSourceWithOnlyApprovedRowsDoesNotRequireKeyOrCallGemini()=runBlocking {
        val p=full(3);val approved=p.cues.map{it.copy(translation="Bản đã duyệt",approved=true)}
        val saved=store.save(p.copy(cues=approved))
        VideoTranslationClient.overrideResponseForTest={error("Approved rows cannot be sent")}
        val result=run(saved)
        assertEquals(VideoStage.REVIEW,result.stage);assertEquals(0,result.cloudCalls);assertEquals(approved,result.cues)
    }
    @Test fun partialSidecarCheckpointKeepsAnApprovedSavedRowWhileCompletingSource()=runBlocking {
        key();val p=full(3);val approved=p.cues.first().copy(translation="Bản đã duyệt",approved=true)
        val partial=store.save(p.copy(cues=listOf(approved),scanMs=1000,resumeStage=VideoStage.TRANSLATING))
        VideoTranslationClient.overrideResponseForTest={body(it.cueIds)}
        val result=run(partial)
        assertEquals(VideoStage.REVIEW,result.stage);assertEquals(p.durationMs,result.scanMs)
        assertEquals(3,result.cues.size);assertEquals(approved,result.cues.first());assertEquals(1,result.cloudCalls)
    }
    @Test fun stagedHybridPolishDoesNotAddAnotherGeminiRequestAfterWholeDialogueTranslation()=runBlocking {
        key();val p=full(3)
        val checkpoint=VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT,"COMPLETE",p.durationMs)
            .record(BatchPipelineStage.CLEAN,"COMPLETE",p.cues.size.toLong())
        val staged=store.save(p.copy(pipeline=checkpoint,cues=p.cues.map{it.copy(qualityFlags=listOf("SHORT_SUBTITLE"))}))
        var calls=0;VideoTranslationClient.overrideResponseForTest={calls++;body(it.cueIds)}
        val translated=run(staged,BatchPipelineStage.TRANSLATE)
        assertEquals("COMPLETE",translated.pipeline.task(BatchPipelineStage.TRANSLATE).status);assertEquals(1,calls)
        val polished=run(translated,BatchPipelineStage.POLISH)
        assertEquals("COMPLETE",polished.pipeline.task(BatchPipelineStage.POLISH).status);assertEquals(1,calls)
        assertEquals(translated.cues.map{it.translation},polished.cues.map{it.translation})
        assertTrue(polished.cues.all{it.polishedVi==it.translation && !it.approved})
    }
}
