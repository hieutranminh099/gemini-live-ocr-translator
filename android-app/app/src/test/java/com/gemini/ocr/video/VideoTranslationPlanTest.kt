package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

/** No model, network, clock or filesystem: source/draft ordering and checkpoint acceptance only. */
class VideoTranslationPlanTest{
    private fun cue(index:Int,source:String="第${index}句",draft:String="Nháp $index",state:String="pending",approved:Boolean=false)=
        VideoCue("cue-$index",index*1000L,index*1000L+900,source,draft,approved,localDraft=draft,refinementState=state)

    @Test fun approvedAndDoneCuesAreNeverSentForRefinementButRemainContext(){
        val rows=listOf(cue(0,approved=true),cue(1),cue(2,state="done"),cue(3))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertEquals(listOf(1,3),batch.indices)
        assertEquals(listOf(rows[1].source,rows[3].source),batch.texts)
        assertTrue(batch.videoContext.contains("\"previous\":[{\"index\":0"))
        assertTrue(batch.videoContext.contains("\"inside\":[{\"index\":2"))
        assertFalse(VideoTranslationPlan.needsCloudRefinement(rows[0]))
        assertFalse(VideoTranslationPlan.needsCloudRefinement(rows[2]))
        assertFalse(VideoTranslationPlan.isComplete(rows[1],true))
        assertTrue(VideoTranslationPlan.isComplete(rows[2],true))
    }
    @Test fun batchesUseTwelveCuesRatherThanFourWhileKeepingACharBound(){
        val rows=(0 until 40).map{cue(it)}
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertEquals((0..11).toList(),batch.indices)
        assertTrue(batch.maxOutputTokens in 1024..8192)
        val long=(0 until 20).map{cue(it,"中".repeat(250),"Việt".repeat(10))}
        assertEquals(4,VideoTranslationPlan.nextCloudBatch(long,true)!!.indices.size)
    }
    @Test fun draftsHaveTheirOwnBudgetAndCannotDisplaceOriginalSource(){
        val rows=(0 until 10).map{cue(it,"不卖250匹马","Sai nháp ".repeat(170))}
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertEquals(2,batch.indices.size)
        assertTrue(batch.texts.all{it=="不卖250匹马"})
        assertTrue(batch.offlineDrafts.all{it==rows[0].localDraft})
        assertTrue(batch.videoContext.contains("\"source\":\"不卖250匹马\""))
        assertTrue(batch.videoContext.contains("\"localDraft\":\"Sai nháp"))
    }
    @Test fun contextNamesBeforeAndAfterTruthfullyAndEscapesSubtitleData(){
        val rows=listOf(cue(0,"前句",state="done"),cue(1,"不卖\"250\"匹马\n第二行\\路径"),cue(2,"后句",state="done"))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true,maxCues=1)!!
        assertEquals(listOf(rows[1].source),batch.texts)
        assertTrue(batch.videoContext.contains("\"previous\":[{\"index\":0"))
        assertTrue(batch.videoContext.contains("\"following\":[{\"index\":2"))
        assertTrue(batch.videoContext.contains("不卖\\\"250\\\"匹马\\n第二行\\\\路径"))
        assertFalse(batch.videoContext.contains("匹马\n第二行"))
    }
    @Test fun successfulResponseUpdatesOnlyMappedDraftsAndRetainsChineseTimestampsAndLocalDraft(){
        val rows=listOf(cue(0,approved=true),cue(1,"不卖250匹马","Bán 250 con ngựa"),cue(2,state="done"))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        val result=VideoTranslationPlan.applyCloudResult(rows,batch,listOf("Không bán 250 con ngựa"),"gemini-model",true)
        assertEquals(rows[0],result[0]);assertEquals(rows[2],result[2])
        assertEquals(rows[1].source,result[1].source)
        assertEquals(rows[1].startMs,result[1].startMs);assertEquals(rows[1].endMs,result[1].endMs)
        assertEquals("Bán 250 con ngựa",result[1].localDraft)
        assertEquals("Không bán 250 con ngựa",result[1].translation)
        assertEquals("done",result[1].refinementState);assertFalse(result[1].approved)
        assertTrue(VideoTranslationPlan.isComplete(result[1],true))
        assertNull(VideoTranslationPlan.nextCloudBatch(result,true))
    }
    @Test fun failureKeepsLocalOnlyTextPendingAndSameCheckpointCanBeRetried(){
        val rows=listOf(cue(0),cue(1,approved=true))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        val failed=VideoTranslationPlan.markCloudFailure(rows,batch,"Hết hạn 45 giây",true)
        assertEquals(rows[0].translation,failed[0].translation)
        assertEquals(rows[0].localDraft,failed[0].localDraft)
        assertEquals("pending",failed[0].refinementState)
        assertTrue(failed[0].error.contains("45"))
        assertEquals(rows[1],failed[1])
        assertFalse(VideoTranslationPlan.isComplete(failed[0],true))
        assertEquals(batch.indices,VideoTranslationPlan.nextCloudBatch(failed,true)!!.indices)
        assertFalse(VideoTranslationPlan.needsLocalDraft(failed[0],true))
    }
    @Test fun countMismatchOrChangedSourceRejectsTheWholeBatch(){
        val rows=listOf(cue(0),cue(1));val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        try{VideoTranslationPlan.applyCloudResult(rows,batch,listOf("Only one"),"model",true);fail("must reject mismatchedcount")}
        catch(_:IllegalArgumentException){}
        assertEquals("Nháp 0",rows[0].translation)
        val edited=rows.mapIndexed{i,c->if(i==1)c.copy(source="不一样") else c}
        try{VideoTranslationPlan.applyCloudResult(edited,batch,listOf("A","B"),"model",true);fail("must reject changedsource")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun approvedWhileRequestWasInFlightRemainsUntouched(){
        val rows=listOf(cue(0));val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        val reviewed=listOf(rows[0].copy(translation="Bản tôi duyệt",approved=true))
        assertEquals(reviewed,VideoTranslationPlan.applyCloudResult(reviewed,batch,listOf("Máy sửa"),"model",true))
        assertEquals(reviewed,VideoTranslationPlan.markCloudFailure(reviewed,batch,"Error",true))
    }
    @Test fun geminiOnlyBatchesHaveNoLocalDraftAndDoNotRepeatCompletedTranslations(){
        val rows=listOf(cue(0),cue(1,draft="",state=""),cue(2,draft="",state=""))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,false)!!
        assertEquals(listOf(1,2),batch.indices)
        assertEquals(listOf("",""),batch.offlineDrafts)
        assertTrue(batch.videoContext.contains("translate_original_chinese"))
        assertTrue(VideoTranslationPlan.isComplete(rows[0],false))
        assertFalse(VideoTranslationPlan.isComplete(rows[1],false))
    }
    @Test fun oneLargeValidCueIsKeptWholeWithoutUnboundedCombinedRequest(){
        val source="中".repeat(2000);val draft="V".repeat(8000)
        val batch=VideoTranslationPlan.nextCloudBatch(listOf(cue(0,source,draft),cue(1)),true)!!
        assertEquals(listOf(0),batch.indices)
        assertEquals(source,batch.texts.single());assertEquals(draft,batch.offlineDrafts.single())
        assertEquals(8192,batch.maxOutputTokens)
        assertTrue(batch.videoContext.length<=24000)
    }
    @Test fun hybridCannotSkipItsExplicitLocalDraftPhase(){
        try{VideoTranslationPlan.nextCloudBatch(listOf(cue(0,draft="")),true);fail("must not send original without requested localphase")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun foreignCyrillicArtifactsAreRejectedBeforeReplacingAnyDraftUnlessUserSpecifiedThem(){
        val rows=listOf(cue(0,"维基人","Người Wiki"));val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertThrows(IllegalArgumentException::class.java){
            VideoTranslationPlan.applyCloudResult(rows,batch,listOf("Người Вики"),"model",true)
        }
        assertEquals("Người Wiki",rows.single().translation)
        val manualTerm=VideoTranslationPlan.applyCloudResult(rows,batch,listOf("Người Вики"),"model",true,"维基人=Вики")
        assertEquals("Người Вики",manualTerm.single().translation)
    }

    @Test fun unexpectedCyrillicPredicateMatchesTheAtomicAcceptanceGuardAndExplicitGlossary(){
        assertFalse(VideoTranslationPlan.hasUnexpectedCyrillic("Chào bạn, người Wiki 250."))
        assertTrue(VideoTranslationPlan.hasUnexpectedCyrillic("Chào người Вики."))
        assertFalse(VideoTranslationPlan.hasUnexpectedCyrillic("Chào người Вики.","维基人=Вики"))
        assertTrue(VideoTranslationPlan.hasUnexpectedCyrillic("Вики Б", "维基人=Вики"))
        val rows=listOf(cue(0),cue(1));val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertThrows(IllegalArgumentException::class.java){
            VideoTranslationPlan.applyCloudResult(rows,batch,listOf("Bản đúng","Bản Вики"),"model",true)
        }
        assertEquals(listOf("Nháp 0","Nháp 1"),rows.map{it.translation})
        assertTrue(rows.all{it.refinementState=="pending"})
    }

    @Test fun selectingBatchPositionsKeepsOriginalMappingAndRejectsAmbiguousOrOutOfRangePositions(){
        val rows=listOf(cue(0,approved=true),cue(1),cue(2),cue(3,state="done"),cue(4))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        assertEquals(listOf(1,2,4),batch.indices)
        val selected=VideoTranslationPlan.selectBatch(batch,listOf(0,2))
        assertEquals(listOf(1,4),selected.indices)
        assertEquals(listOf(rows[1].id,rows[4].id),selected.cueIds)
        assertEquals(listOf(rows[1].source,rows[4].source),selected.texts)
        assertEquals(listOf(rows[1].localDraft,rows[4].localDraft),selected.offlineDrafts)
        assertEquals(batch.videoContext,selected.videoContext)
        assertEquals(batch.maxOutputTokens,selected.maxOutputTokens)
        for(positions in listOf(emptyList(),listOf(1,1),listOf(2,0),listOf(-1),listOf(3))){
            assertThrows(IllegalArgumentException::class.java){VideoTranslationPlan.selectBatch(batch,positions)}
        }
        assertThrows(IllegalArgumentException::class.java){VideoTranslationPlan.selectBatch(batch.copy(texts=emptyList()),listOf(0))}
        assertEquals(listOf(1,2,4),batch.indices)
    }

    @Test fun acceptingValidSubsetAndMarkingOnlyBadRowRetainsDraftAndRetriesOnlyThatRow(){
        val rows=listOf(cue(0,approved=true),cue(1),cue(2),cue(3,state="done"),cue(4))
        val batch=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        val accepted=VideoTranslationPlan.applyCloudResult(rows,VideoTranslationPlan.selectBatch(batch,listOf(0,2)),
            listOf("Bản dịch thứ nhất","Bản dịch cuối"),"model",true)
        val feedback="Có từ Kirill \"Вики\".\nHãy dùng tiếng Việt theo nguồn Trung."
        val marked=VideoTranslationPlan.markCloudFailure(accepted,VideoTranslationPlan.selectBatch(batch,listOf(1)),feedback,true)
        assertEquals(rows[0],marked[0]);assertEquals(rows[3],marked[3])
        assertEquals("Bản dịch thứ nhất",marked[1].translation);assertEquals("done",marked[1].refinementState)
        assertEquals("Bản dịch cuối",marked[4].translation);assertEquals("done",marked[4].refinementState)
        assertEquals(rows[2].translation,marked[2].translation);assertEquals(rows[2].localDraft,marked[2].localDraft)
        assertEquals("pending",marked[2].refinementState);assertEquals(feedback,marked[2].error)
        val retry=VideoTranslationPlan.nextCloudBatch(marked,true)!!
        assertEquals(listOf(2),retry.indices)
        assertEquals(listOf(rows[2].source),retry.texts)
        assertTrue(retry.videoContext.contains("\"qualityIssue\":\"Có từ Kirill \\\"Вики\\\".\\nHãy dùng tiếng Việt"))
        val completed=VideoTranslationPlan.applyCloudResult(marked,retry,listOf("Bản đã sửa bằng tiếng Việt"),"model",true)
        assertEquals(marked[1],completed[1]);assertEquals(marked[4],completed[4])
        assertEquals("",completed[2].error);assertEquals("done",completed[2].refinementState)
        assertNull(VideoTranslationPlan.nextCloudBatch(completed,true))
        assertEquals(rows.map{Triple(it.source,it.startMs,it.endMs)},completed.map{Triple(it.source,it.startMs,it.endMs)})
    }
    @Test fun boundedRepairTargetsOnlyRejectedRowsAndLaterFailureCannotUndoAcceptedRows(){
        val rows=listOf(cue(0),cue(1),cue(2))
        val dispatched=VideoTranslationPlan.nextCloudBatch(rows,true)!!
        val accepted=VideoTranslationPlan.applyCloudResult(rows,VideoTranslationPlan.selectBatch(dispatched,listOf(1,2)),
            listOf("Câu tốt hai","Câu tốt ba"),"model",true)
        val failed=VideoTranslationPlan.markCloudFailure(accepted,dispatched,"Chỉ câu đầu lỗi",true)
        assertEquals(accepted[1],failed[1]);assertEquals(accepted[2],failed[2])
        val repair=VideoTranslationPlan.repairBatch(failed,dispatched,listOf(0),true)
        assertEquals(listOf(0),repair.indices)
        assertTrue(repair.videoContext.contains("\"qualityRepair\":true"))
        assertTrue(repair.videoContext.contains("Câu tốt hai"))
        assertThrows(IllegalArgumentException::class.java){VideoTranslationPlan.repairBatch(failed,dispatched,listOf(1),true)}
        val cloudRows=rows.map{it.copy(translation="",localDraft="",refinementState="")}
        val cloudBatch=VideoTranslationPlan.nextCloudBatch(cloudRows,false)!!
        val cloudAccepted=VideoTranslationPlan.applyCloudResult(cloudRows,VideoTranslationPlan.selectBatch(cloudBatch,listOf(1)),
            listOf("Đã xong"),"model",false)
        assertEquals(cloudAccepted[1],VideoTranslationPlan.markCloudFailure(cloudAccepted,cloudBatch,"Lỗi",false)[1])
    }
}
