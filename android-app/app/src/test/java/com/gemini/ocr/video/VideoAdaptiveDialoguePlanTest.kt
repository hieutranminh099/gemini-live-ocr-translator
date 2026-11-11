package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoAdaptiveDialoguePlanTest {
    private fun rows(count:Int,length:Int=20)=List(count){VideoCue("cue-$it",it*1000L,it*1000L+900,"中".repeat(length))}
    private fun next(rows:List<VideoCue>,film:String="",fits:((VideoTranslationPlan.Batch)->Boolean)?=null)=
        VideoFullDialoguePlan.next(rows,rows.maxOf{it.endMs},film,fits)
    private fun accepted(rows:List<VideoCue>,batch:VideoTranslationPlan.Batch)=
        VideoTranslationPlan.applyCloudResult(rows,batch,List(batch.indices.size){"Bản thử"},"model",true)
    private fun assertBounded(rows:List<VideoCue>,batch:VideoTranslationPlan.Batch){
        assertTrue(batch.indices.isNotEmpty());assertEquals(batch.indices.sorted(),batch.indices)
        assertTrue(batch.maxOutputTokens<=65_536)
        assertTrue(batch.videoContext.toByteArray(Charsets.UTF_8).size<=1024*1024)
        val context=JSONObject(batch.videoContext);val dialogue=context.getJSONArray("dialogue")
        val byId=(0 until dialogue.length()).associate{dialogue.getJSONObject(it).getString("id") to dialogue.getJSONObject(it)}
        batch.indices.forEachIndexed{position,index->
            val row=rows[index];val sent=byId.getValue(row.id)
            assertEquals(row.id,batch.cueIds[position]);assertEquals(row.source,batch.texts[position])
            assertEquals(row.source,sent.getString("source"));assertEquals(row.startMs,sent.getLong("startMs"));assertEquals(row.endMs,sent.getLong("endMs"))
        }
    }
    @Test fun fifteenTwelveRowsUseTwoMaximalPartsWithNoLostRepeatedDialogueOrId(){
        val original=rows(1512);var remaining=original;val sent=mutableListOf<String>();val sizes=mutableListOf<Int>()
        while(true){
            val batch=next(remaining)?:break
            assertBounded(remaining,batch);sent+=batch.cueIds;sizes+=batch.indices.size
            assertTrue(JSONObject(batch.videoContext).getBoolean("contextComplete"))
            assertEquals(1512,JSONObject(batch.videoContext).getJSONArray("dialogue").length())
            remaining=accepted(remaining,batch)
        }
        assertEquals(listOf(992,520),sizes)
        assertEquals(original.map{it.id},sent);assertEquals(sent.size,sent.distinct().size)
        assertEquals(original.map{Triple(it.id,it.startMs,it.endMs)},remaining.map{Triple(it.id,it.startMs,it.endMs)})
        assertTrue(original.all{it.translation.isBlank()})
    }
    @Test fun unevenChronologicalCostsMatchTheMinimumContiguousPartitionCount(){
        val original=rows(50).mapIndexed{index,cue->cue.copy(source="中".repeat(if(index%3==0)2000 else 900))}
        fun cost(c:VideoCue)=c.source.length*2+maxOf(8,(c.id.length+1)/2)+16
        val dp=IntArray(original.size+1){1000};dp[0]=0
        for(end in 1..original.size){var sum=2048
            for(start in end-1 downTo 0){sum+=cost(original[start]);if(sum>65_536)break;dp[end]=minOf(dp[end],dp[start]+1)}
        }
        var current=original;var count=0
        while(true){val batch=next(current)?:break;assertBounded(current,batch);count++;current=accepted(current,batch)}
        assertEquals(dp.last(),count)
    }
    @Test fun smallDialogueStillUsesOneRequestAndApprovedOrDoneRowsRemainContextOnly(){
        val original=rows(80).mapIndexed{index,cue->when(index){
            0->cue.copy(translation="Tôi đã duyệt",approved=true)
            1->cue.copy(translation="Đã xong",refinementState="done")
            else->cue
        }}
        val batch=next(original,"Giữ tên và xưng hô")!!
        assertEquals(78,batch.cueIds.size);assertFalse(batch.cueIds.contains("cue-0"));assertFalse(batch.cueIds.contains("cue-1"))
        assertEquals(80,JSONObject(batch.videoContext).getInt("totalCueCount"))
        assertEquals("Giữ tên và xưng hô",JSONObject(batch.videoContext).getString("filmContext"))
        val result=accepted(original,batch)
        assertEquals(original.first(),result.first());assertEquals(original[1],result[1]);assertNull(next(result))
    }
    @Test fun utf8InputBudgetPacksTargetsBeforeAddingContextAndResumesWithoutRetranslatingDoneRows(){
        val original=rows(100,2).map{it.copy(localDraft="ữ".repeat(8000))}
        var current=original;val sent=mutableListOf<String>();var count=0
        while(true){val batch=next(current)?:break
            assertBounded(current,batch)
            if(count==0)assertFalse(JSONObject(batch.videoContext).getBoolean("contextComplete"))
            assertEquals(100,JSONObject(batch.videoContext).getInt("totalCueCount"))
            assertTrue(batch.indices.size>1);sent+=batch.cueIds;count++;current=accepted(current,batch)
        }
        assertEquals(3,count);assertEquals(original.map{it.id},sent)
        assertTrue(original.all{it.localDraft.length==8000 && it.translation.isBlank()})
    }
    @Test fun hugeApprovedContextDoesNotForceExtraTargetRequestsOrDisappearFromTheSavedTimeline(){
        val original=rows(300,1500).mapIndexed{index,cue->if(index==155)cue else cue.copy(translation="Đã duyệt",approved=true)}
        val batch=next(original)!!;val context=JSONObject(batch.videoContext)
        assertEquals(listOf("cue-155"),batch.cueIds);assertEquals("window",context.getString("contextScope"))
        assertEquals(300,context.getInt("totalCueCount"));assertTrue(context.getJSONArray("dialogue").length()<300)
        assertEquals(300,original.size);assertEquals(299,original.count{it.approved})
        assertNull(next(accepted(original,batch)))
    }
    @Test fun unsortedSavedInputStillChoosesEarliestPendingTimesAndKeepsOriginalMappingIndices(){
        val chronological=rows(1512);val reversed=chronological.reversed()
        val before=reversed.map{it.copy()}
        val batch=next(reversed)!!
        assertBounded(reversed,batch);assertEquals(992,batch.indices.size)
        assertEquals(chronological.take(992).map{it.id}.toSet(),batch.cueIds.toSet())
        assertEquals(before,reversed)
    }
    @Test fun exactHttpPreflightCanReduceTargetPrefixWithoutPostingOrSendingAlreadyDoneIds(){
        var current=rows(25);val sizes=mutableListOf<Int>();var preflights=0
        while(true){val batch=next(current,fits={preflights++;it.indices.size<=7})?:break
            assertBounded(current,batch);sizes+=batch.indices.size;current=accepted(current,batch)
        }
        assertEquals(listOf(7,7,7,4),sizes);assertTrue(preflights<60)
    }
    @Test fun impossibleFilmContextOrSingleRequestConfigurationFailsBeforeAnyTargetIsDropped(){
        val original=rows(2)
        assertThrows(IllegalArgumentException::class.java){next(original,"ữ".repeat(400_000))}
        assertThrows(IllegalArgumentException::class.java){next(original,fits={false})}
        assertEquals(2,original.size);assertTrue(original.all{it.translation.isBlank()})
    }
    @Test fun maximumTimelineUsesBoundedNonemptyPartsWithoutChangingAnyOriginalCue(){
        val original=rows(20_000,2);val batch=next(original)!!
        assertBounded(original,batch);assertTrue(batch.cueIds.size>1000)
        assertFalse(JSONObject(batch.videoContext).getBoolean("contextComplete"))
        assertEquals(20_000,JSONObject(batch.videoContext).getInt("totalCueCount"))
        assertEquals(20_000,original.size)
    }
}
