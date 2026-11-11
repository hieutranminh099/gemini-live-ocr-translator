package com.gemini.ocr.video

import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoFullDialoguePlanTest {
    private fun cues(count:Int)=List(count){VideoCue("cue-$it",it*1000L,it*1000L+900,"你好")}
    private fun batch(rows:List<VideoCue>)=VideoFullDialoguePlan.next(rows,rows.maxOf{it.endMs})!!
    private fun envelope(raw:String,reason:String="STOP",padding:String="")=JSONObject()
        .put("candidates",JSONArray().put(JSONObject().put("finishReason",reason).put("content",JSONObject()
            .put("parts",JSONArray().put(JSONObject().put("text",raw)))))).put("padding",padding).toString()
    private fun rows(vararg ids:String)=JSONObject().put("translations",JSONArray(ids.map{
        JSONObject().put("id",it).put("translation","Xin chào")
    })).toString()
    private fun parse(raw:String,b:VideoTranslationPlan.Batch,reason:String="STOP")=
        VideoTranslationClient.parseResponse(envelope(raw,reason),b,"","model")

    @Test fun onePlanIncludesAllChronologicalDialogueButTargetsOnlyUnfinishedIdsWithoutLocalNmt(){
        val original=cues(90).mapIndexed{i,cue->when(i){
            1->cue.copy(translation="Tôi đã duyệt",approved=true)
            2->cue.copy(translation="Đã dịch",refinementState="done")
            3->cue.copy(localDraft="Nháp cũ",refinementState="pending")
            else->cue
        }}
        val plan=batch(original);val context=JSONObject(plan.videoContext)
        assertTrue(plan.fullDialogue);assertEquals(88,plan.indices.size)
        assertEquals(90,context.getJSONArray("dialogue").length())
        assertEquals(plan.cueIds,(0 until context.getJSONArray("targetIds").length()).map{context.getJSONArray("targetIds").getString(it)})
        assertFalse(plan.cueIds.contains("cue-1"));assertFalse(plan.cueIds.contains("cue-2"))
        assertEquals("Nháp cũ",context.getJSONArray("dialogue").getJSONObject(3).getString("localDraft"))
        assertFalse(context.getJSONArray("dialogue").getJSONObject(0).has("localDraft"))
        assertEquals(90,original.size);assertTrue(original[1].approved)
    }

    @Test fun unsortedInputKeepsOriginalResultIndicesAndRepeatedLinesStayIndependent(){
        val chronological=cues(3);val original=listOf(chronological[2],chronological[0],chronological[1])
        val plan=batch(original);val dialogue=JSONObject(plan.videoContext).getJSONArray("dialogue")
        assertEquals(listOf(0,1,2),plan.indices)
        assertEquals(listOf("cue-0","cue-1","cue-2"),(0 until dialogue.length()).map{dialogue.getJSONObject(it).getString("id")})
        val result=VideoTranslationPlan.applyCloudResult(original,plan,listOf("Sau","Trước","Giữa"),"model",true)
        assertEquals(original.map{Triple(it.id,it.startMs,it.endMs)},result.map{Triple(it.id,it.startMs,it.endMs)})
        assertEquals("Sau",result[0].translation);assertEquals("Trước",result[1].translation)
    }

    @Test fun thousandShortCuesUseOneBoundedRequestAndLiveLimitsRemainUnchanged(){
        val plan=batch(cues(1000));assertEquals(1000,plan.cueIds.size)
        assertTrue(plan.maxOutputTokens in 8192..65536)
        val request=JSONObject(VideoTranslationClient.buildRequest(plan,"manga","你好=Xin chào"))
        assertEquals(plan.maxOutputTokens,request.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        val item=request.getJSONObject("generationConfig").getJSONObject("responseSchema")
            .getJSONObject("properties").getJSONObject("translations").getJSONObject("items")
        assertFalse(item.getJSONObject("properties").getJSONObject("id").has("enum"))
        val full=VideoTranslationClient.requestClient(true)
        assertEquals(240_000,full.callTimeoutMillis);assertEquals(240_000,full.readTimeoutMillis)
        assertFalse(full.retryOnConnectionFailure);assertFalse(full.followRedirects)
        assertEquals(45_000,VideoTranslationClient.requestClient(false).callTimeoutMillis)
        assertEquals(20_000,StandaloneTranslator.httpClientForRequest(false,true).callTimeoutMillis)
    }

    @Test fun fullPayloadContainsEachSourceOnlyInItsDialogueRowAndKeepsAllSourceCharacters(){
        val original=cues(70).mapIndexed{i,cue->cue.copy(source="唯一原文${i}不卖三匹马")}
        val request=JSONObject(VideoTranslationClient.buildRequest(batch(original),"historical","三匹马=ba con ngựa"))
        val user=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        original.forEach{cue->assertEquals(1,Regex(Regex.escape(cue.source)).findAll(user).count())}
        val data=JSONObject(user.substringAfter('\n'))
        assertFalse(data.has("targets"));assertEquals(70,data.getJSONArray("dialogue").length())
        assertEquals("historical",data.getString("profile"));assertEquals("ba con ngựa",data.getJSONObject("glossary").getString("三匹马"))
    }

    @Test fun approvedAndFinishedContextSurvivesResumeWithoutRetargetingCompletedIds(){
        val original=cues(3).mapIndexed{i,c->if(i==1)c.copy(translation="Đã duyệt",approved=true)else c}
        val plan=batch(original)
        val accepted=VideoTranslationPlan.applyCloudResult(original,VideoTranslationPlan.selectBatch(plan,listOf(0)),listOf("Chào"),"model",true)
        val resumed=batch(accepted)
        assertEquals(listOf("cue-2"),resumed.cueIds)
        assertEquals(3,JSONObject(resumed.videoContext).getJSONArray("dialogue").length())
        assertEquals(original[1],accepted[1])
        assertNull(VideoFullDialoguePlan.next(accepted.map{if(it.id=="cue-2")it.copy(translation="Xong",refinementState="done")else it},3000))
    }

    @Test fun oversizedOutputAndContextBecomeBoundedPartsWithoutDroppingRows(){
        val tooMuchOutput=cues(2000).map{it.copy(source="中".repeat(30))}
        val first=batch(tooMuchOutput)
        assertEquals(755,first.cueIds.size)
        assertTrue(first.maxOutputTokens<=VideoFullDialoguePlan.MAX_OUTPUT_TOKENS)
        val tooMuchInput=cues(300).mapIndexed{i,c->c.copy(source="中".repeat(1500),
            translation=if(i==0)""else "Đã duyệt",approved=i!=0)}
        val window=batch(tooMuchInput)
        assertEquals(listOf("cue-0"),window.cueIds)
        assertFalse(JSONObject(window.videoContext).getBoolean("contextComplete"))
        assertTrue(window.videoContext.toByteArray(Charsets.UTF_8).size<=VideoFullDialoguePlan.MAX_CONTEXT_BYTES)
        assertEquals(2000,tooMuchOutput.size);assertEquals(300,tooMuchInput.size)
    }

    @Test fun fullResponseMapsExplicitIdsAndOnlyMissingRowsRemainPending(){
        val plan=batch(cues(3));val result=parse(rows("cue-2","cue-0"),plan)
        assertEquals(setOf("cue-0","cue-2"),result.accepted.keys)
        assertEquals(listOf("cue-1"),result.missingIds);assertFalse(result.truncated)
        for(raw in listOf(rows("cue-0","cue-0"),rows("cue-0","other"))){
            val error=assertThrows(VideoApiException::class.java){parse(raw,plan)}
            assertFalse(error.splitAllowed)
        }
    }

    @Test fun tokenCutoffKeepsOnlyCompletePrefixObjectsAndMarksTruncated(){
        val plan=batch(cues(3))
        val raw="""{"translations":[{"id":"cue-0","translation":"Xin {chào} \"bạn\""},{"id":"cue-1","translation":"dở"""
        val result=parse(raw,plan,"MAX_TOKENS")
        assertTrue(result.truncated);assertEquals(setOf("cue-0"),result.accepted.keys)
        assertEquals(listOf("cue-1","cue-2"),result.missingIds)
        assertThrows(VideoApiException::class.java){parse(raw,plan,"STOP")}
        assertTrue(parse(rows("cue-0","cue-1","cue-2"),plan,"MAX_TOKENS").truncated)
    }

    @Test fun tokenCutoffDoesNotAllowProseGuessedIdsDuplicateIdsOrMalformedCompleteRows(){
        val plan=batch(cues(2))
        for(raw in listOf("Here: "+rows("cue-0"),
            """{"translations":[{"id":"cue-0","translation":"Chào"},{"id":"other","translation":"Sai"},""",
            """{"translations":[{"id":"cue-0","translation":"Chào"},{"id":"cue-0","translation":"Trùng"},""",
            """{"translations":[{"id":"cue-0","translation":"Chào"},garbage""")){
            assertThrows(VideoApiException::class.java){parse(raw,plan,"MAX_TOKENS")}
        }
    }

    @Test fun largerFullEnvelopeDoesNotRaiseLegacySmallBatchResponseBound(){
        val plan=batch(cues(1));val body=envelope(rows("cue-0"),padding="x".repeat(600_000))
        assertEquals("Xin chào",VideoTranslationClient.parseResponse(body,plan,"","model").accepted["cue-0"])
        val legacy=VideoTranslationPlan.nextCloudBatch(cues(1),false)!!
        assertThrows(VideoApiException::class.java){VideoTranslationClient.parseResponse(body,legacy,"","model")}
    }

    @Test fun mockTransportReceivesTheWholeDialogueInOneDispatch()=runBlocking {
        val plan=batch(cues(70));var calls=0;var dispatches=0
        val old=VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest={b->calls++;assertEquals(plan,b);envelope(rows(*b.cueIds.toTypedArray()))}
            val result=VideoTranslationClient().translate("",plan,"manga",""){dispatches++}
            assertEquals(70,result.accepted.size);assertEquals(1,calls);assertEquals(1,dispatches)
        }finally{VideoTranslationClient.overrideResponseForTest=old}
    }
    @Test fun filmContextIsPreservedInFullPayloadAndChangesThePrivateCacheFingerprint(){
        val rows=cues(3)
        val first=VideoFullDialoguePlan.next(rows,3000,"Nhân vật A gọi nhân vật B là chị")!!
        val changed=VideoFullDialoguePlan.next(rows,3000,"Nhân vật A gọi nhân vật B là em")!!
        assertEquals("Nhân vật A gọi nhân vật B là chị",JSONObject(first.videoContext).getString("filmContext"))
        assertNotEquals(first.videoContext,changed.videoContext)
        val payload=VideoTranslationClient.buildRequest(first,"manga","")
        assertTrue(payload.contains("Nhân vật A gọi nhân vật B là chị"))
    }
}
