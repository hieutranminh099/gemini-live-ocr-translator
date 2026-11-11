package com.gemini.ocr.video

import android.content.Context
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoAdaptiveChunksWorkerTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val store:VideoStore get()=VideoStore(context)
    @Before fun setup(){
        SessionState.running.value=false
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear()
            .putString("standalone_api_key","synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback","fixture-no-network").commit()
        StandaloneTranslator.beforeNetworkCallForTest={error("Offline verification must not call Gemini")}
    }
    @After fun reset(){VideoTranslationClient.overrideResponseForTest=null;StandaloneTranslator.beforeNetworkCallForTest=null}
    private fun project(count:Int=1512):VideoProject {
        val source="你好"+"中".repeat(14)
        val rows=List(count){VideoCue("cue-$it",it*300L,it*300L+250,source,rawZh=source)}
        return store.save(VideoProject(UUID.randomUUID().toString(),"Controlled capacity fixture","file:///unused.mp4",
            count*300L,1280,720,sourceMode=VideoSourceMode.OCR,translationMode=VideoTranslationMode.HYBRID,
            cues=rows,scanMs=count*300L,stage=VideoStage.FAILED,resumeStage=VideoStage.TRANSLATING))
    }
    private fun body(ids:List<String>)=JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason","STOP")
        .put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",JSONObject().put("translations",
            JSONArray(ids.map{JSONObject().put("id",it).put("translation","Bản dịch kiểm thử.")})).toString())))))).toString()
    private suspend fun run(p:VideoProject):VideoProject {
        val done=CompletableDeferred<Unit>();val jobs=VideoJobs(context)
        jobs.execute(p.id,false){done.complete(Unit)}
        withTimeout(30_000){done.await()};assertNull(jobs.busy.value)
        return store.load(p.id)
    }
    @Test fun fifteenHundredTwelveCuesUseTwoMaximalPartsWithAStoredCheckpointBeforeTheSecondRequest()=runBlocking {
        val p=project();val requests=mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest={batch->
            assertTrue(batch.fullDialogue);requests+=batch.cueIds
            assertEquals(p.durationMs,store.load(p.id).scanMs)
            assertEquals(1512,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
            if(requests.size==2){
                val saved=store.load(p.id)
                assertTrue(requests.first().all{id->saved.cues.first{it.id==id}.refinementState=="done"})
                assertTrue(batch.cueIds.none{it in requests.first()})
            }
            body(batch.cueIds.reversed())
        }
        val result=run(p)
        assertEquals(VideoStage.REVIEW,result.stage);assertEquals(2,requests.size);assertEquals(2,result.cloudCalls)
        // Each row costs56 estimated output tokens; base2048 leaves room for1133 rows.
        assertEquals(listOf(1133,379),requests.map{it.size})
        assertEquals(p.cues.map{it.id},requests.flatten());assertTrue(result.cues.all{VideoTranslationPlan.isComplete(it,true)})
        assertEquals(p.cues.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)},result.cues.map{listOf(it.id,it.startMs,it.endMs,it.source,it.rawZh)})
    }
    @Test fun failedSecondPartKeepsTheFirstPartAndResumeNeverSendsThoseIdsAgain()=runBlocking {
        val p=project();val requests=mutableListOf<List<String>>()
        VideoTranslationClient.overrideResponseForTest={batch->
            requests+=batch.cueIds
            if(requests.size==2)throw VideoApiException("API_AUTH",detail="Controlled interruption")
            body(batch.cueIds)
        }
        val failed=run(p)
        assertEquals(VideoStage.FAILED,failed.stage);assertEquals(2,requests.size)
        val doneIds=failed.cues.filter{VideoTranslationPlan.isComplete(it,true)}.map{it.id}
        assertEquals(requests.first(),doneIds);assertEquals(p.durationMs,failed.scanMs)
        val result=run(failed)
        assertEquals(VideoStage.REVIEW,result.stage);assertEquals(3,requests.size)
        assertEquals(requests[1],requests[2]);assertTrue(requests[2].none{it in doneIds})
        assertTrue(result.cues.all{VideoTranslationPlan.isComplete(it,true)})
    }
    @Test fun alreadyFinishedAndApprovedRowsOnlyProvideContextAndDoNotConsumeNewTargets()=runBlocking {
        val p=project();val retained=p.cues.mapIndexed{i,row->if(i<1200)row.copy(translation="Bản trước",refinementState="done",approved=i==0)else row}
        val saved=store.save(p.copy(cues=retained));var calls=0
        VideoTranslationClient.overrideResponseForTest={batch->
            calls++;assertEquals(p.cues.drop(1200).map{it.id},batch.cueIds)
            assertEquals(1512,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
            body(batch.cueIds)
        }
        val result=run(saved)
        assertEquals(1,calls);assertEquals(VideoStage.REVIEW,result.stage)
        assertEquals(retained.take(1200),result.cues.take(1200))
    }
    @Test fun smallDialogueStillUsesOnlyOneRequest()=runBlocking {
        val p=project(70);var calls=0
        VideoTranslationClient.overrideResponseForTest={calls++;body(it.cueIds)}
        val result=run(p)
        assertEquals(1,calls);assertEquals(VideoStage.REVIEW,result.stage)
    }
}
