package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoOcrStabilizerTest {
    @Test fun punctuationJitterAndOneFrameFragmentsDoNotCreateFlickeringCues(){
        val s=VideoOcrStabilizer()
        listOf("帮帮我","帮帮我-","帮帮我,","帮帮我","帮帮我").forEachIndexed{i,t->s.add(i*50L,t)}
        s.add(250,"帮我");s.add(300,"帮帮我")
        s.add(350,"扶住他");s.add(400,"扶住他");s.add(450,"扶住他")
        val rows=s.finish(700)
        assertEquals(2,rows.size);assertEquals("帮帮我",rows[0].source)
        assertEquals(0L,rows[0].startMs);assertEquals(350L,rows[0].endMs)
        assertEquals("扶住他",rows[1].source);assertEquals(350L,rows[1].startMs)
    }
    @Test fun confirmedBlankSeparatesRepeatedOccurrencesAndKeepsShortCueOnset(){
        val s=VideoOcrStabilizer()
        s.add(0,"拜托");s.add(50,"拜托");s.add(100,"拜托")
        s.add(150,"");s.add(200,"");s.add(250,"")
        s.add(300,"拜托");s.add(350,"拜托");s.add(400,"拜托")
        val rows=s.finish(500)
        assertEquals(2,rows.size);assertEquals(150L,rows[0].endMs)
        assertEquals(300L,rows[1].startMs);assertNotEquals(rows[0].id,rows[1].id)
    }
    @Test fun pendingQuorumAndBlankStateSurviveCheckpointWithNoDuplicateOrLostOnset(){
        val first=VideoOcrStabilizer();first.add(0,"不是三个");first.add(50,"不是三个")
        val resumed=VideoOcrStabilizer(first.cues,JSONObject(first.snapshot().toString()))
        resumed.add(100,"不是三个");resumed.add(150,null);resumed.add(200,"");resumed.add(250,"")
        val again=VideoOcrStabilizer(resumed.cues,JSONObject(resumed.snapshot().toString()))
        again.add(300,"");val rows=again.finish(350)
        assertEquals(1,rows.size);assertEquals(0L,rows.single().startMs);assertEquals(200L,rows.single().endMs)
        assertEquals("不是三个",rows.single().source)
    }
    @Test fun negationAndNumericPunctuationAreNeverTreatedAsTheSameSource(){
        val s=VideoOcrStabilizer();var ms=0L
        for(text in listOf("是三个","不是三个","3.5","35","-3","3")){
            repeat(3){s.add(ms,text);ms+=50}
        }
        val rows=s.finish(ms)
        assertEquals(listOf("是三个","不是三个","3.5","35","-3","3"),rows.map{it.source})
    }
    @Test fun staticSubtitleRequestsConfirmationUntilItsThirdObservationThenKeepsTheOriginalOnset(){
        val s=VideoOcrStabilizer()
        assertFalse(s.needsConfirmation)
        s.add(0,"现在回家")
        assertTrue("A static frame still needs another observation, rather than waiting 250 ms",s.needsConfirmation)
        s.add(100,"现在回家")
        assertTrue(s.needsConfirmation)
        s.add(200,"现在回家")
        assertFalse(s.needsConfirmation)
        val cue=s.finish(1000).single()
        assertEquals("现在回家",cue.source)
        assertEquals(0L,cue.startMs)
        assertEquals(1000L,cue.endMs)
    }
    @Test fun movingBackgroundQuorumRejectsTheWrongNumberAndConfirmsARealNegatedChange(){
        val s=VideoOcrStabilizer()
        s.add(0,"有3个人")
        s.add(50,"有8个人") // A wrong OCR number must not become another occurrence.
        s.add(100,"有3个人")
        s.add(150,null)
        s.add(200,"有3个人")
        assertFalse(s.needsConfirmation)
        s.add(300,"不是3个人")
        assertTrue(s.needsConfirmation)
        s.add(350,"不是3个人")
        s.add(400,null)
        s.add(450,"不是3个人")
        assertFalse(s.needsConfirmation)
        val rows=s.finish(700)
        assertEquals(listOf("有3个人","不是3个人"),rows.map{it.source})
        assertEquals(listOf(0L to 300L,300L to 700L),rows.map{it.startMs to it.endMs})
    }
    @Test fun uncertainObservationsDoNotConfirmDisappearanceAndReturningTextCancelsPendingBlank(){
        val s=VideoOcrStabilizer()
        s.add(0,"拜托");s.add(50,"拜托");s.add(100,"拜托")
        s.add(150,"")
        assertTrue(s.needsConfirmation)
        s.add(200,null);s.add(250,null)
        assertTrue(s.needsConfirmation)
        s.add(300,"拜托")
        assertFalse(s.needsConfirmation)
        val cue=s.finish(500).single()
        assertEquals(0L,cue.startMs)
        assertEquals(500L,cue.endMs)
    }
    @Test fun oneOrTwoBlankObservationsAtEofCannotTruncateAnOtherwiseVisibleCue(){
        for(blankCount in 1..2){
            val s=VideoOcrStabilizer()
            s.add(0,"别走");s.add(50,"别走");s.add(100,"别走")
            repeat(blankCount){s.add(150+it*50L,"")}
            assertTrue(s.needsConfirmation)
            val cue=s.finish(400).single()
            assertEquals("Unconfirmed blank must not be certified just because the video ended",400L,cue.endMs)
        }
    }
    @Test fun checkpointKeepsTheNeedForStaticConfirmationAndARepeatedOccurrenceAfterBlank(){
        val s=VideoOcrStabilizer()
        s.add(0,"再见");s.add(100,"再见")
        val resumed=VideoOcrStabilizer(s.cues,JSONObject(s.snapshot().toString()))
        assertTrue(resumed.needsConfirmation)
        resumed.add(200,"再见")
        assertFalse(resumed.needsConfirmation)
        resumed.add(300,"");resumed.add(350,"");resumed.add(400,"")
        assertFalse(resumed.needsConfirmation)
        resumed.add(500,"再见")
        assertTrue(resumed.needsConfirmation)
        resumed.add(550,"再见");resumed.add(600,"再见")
        val rows=resumed.finish(800)
        assertEquals(listOf("再见","再见"),rows.map{it.source})
        assertEquals(listOf(0L to 300L,500L to 800L),rows.map{it.startMs to it.endMs})
        assertNotEquals(rows[0].id,rows[1].id)
    }
    @Test fun spacesBetweenDigitsOrLatinTokensCannotMergeDifferentSources(){
        val s=VideoOcrStabilizer();var ms=0L
        val sources=listOf("1 2","12","3 5","35","Li Na","LiNa")
        for(text in sources)repeat(3){s.add(ms,text);ms+=50}
        assertEquals(sources,s.finish(ms).map{it.source})
    }
    @Test fun harmlessSpacingBetweenHanCharactersStillUsesOneExactTextQuorum(){
        val s=VideoOcrStabilizer()
        s.add(0,"帮 帮 我");s.add(100,"帮帮我");s.add(200,"帮 帮 我")
        assertFalse(s.needsConfirmation)
        val cue=s.finish(500).single()
        assertEquals("帮帮我",cue.source)
        assertEquals(0L,cue.startMs)
    }
    @Test fun internalCommaPeriodJitterBetweenIdenticalHanDoesNotSplitThePublicVideoCue(){
        val s=VideoOcrStabilizer()
        s.add(0,"见到维基人，就像见到家人和朋友。")
        s.add(65,"见到维基人。就像见到家人和朋友。")
        s.add(130,"见到维基人,就像见到家人和朋友。")
        assertFalse(s.needsConfirmation)
        s.add(300,"见到维基人。就像见到家人和朋友。")
        s.add(500,"见到维基人，就像见到家人和朋友。")
        val rows=s.finish(800)
        assertEquals(1,rows.size);assertEquals(0L,rows.single().startMs)
    }
    @Test fun punctuationThatSeparatesNegationAndDecimalNumbersRemainsPartOfIdentity(){
        assertNotEquals(VideoOcrStabilizer.identity("不，是三个"),VideoOcrStabilizer.identity("不是三个"))
        assertNotEquals(VideoOcrStabilizer.identity("有3.5个人"),VideoOcrStabilizer.identity("有35个人"))
        assertNotEquals(VideoOcrStabilizer.identity("-3个人"),VideoOcrStabilizer.identity("3个人"))
        assertNotEquals(VideoOcrStabilizer.identity("你去？我去"),VideoOcrStabilizer.identity("你去，我去"))
    }
    @Test fun refinedFiftyMillisecondSamplesRetainAShortCueBetweenCoarseObservations(){
        val s=VideoOcrStabilizer()
        s.add(0,"之前");s.add(65,"之前");s.add(130,"之前")
        s.add(400,"之前")
        // A coarse observation at 600ms sees the short cue. The retained ring is refined first.
        s.add(450,"之前");s.add(500,"快走");s.add(550,"快走");s.add(600,"快走")
        s.add(650,"");s.add(700,"");s.add(750,"")
        val rows=s.finish(800)
        assertEquals(listOf("之前","快走"),rows.map{it.source})
        assertEquals(500L,rows[1].startMs);assertEquals(650L,rows[1].endMs)
    }
    @Test fun twoFrameDraftQuorumDoesNotLetAcceptedTextShadowShortFollowingNegation(){
        val s=VideoOcrStabilizer(requiredObservations=2)
        s.add(0,"是三个");s.add(65,"是三个")
        s.add(130,"不是三个");s.add(195,"不是三个")
        val rows=s.finish(300)
        assertEquals(listOf("是三个","不是三个"),rows.map{it.source})
        assertEquals(listOf(0L to 130L,130L to 300L),rows.map{it.startMs to it.endMs})
    }
    @Test fun twoFrameTransitionCannotReuseEarlierOccurrenceToFlipBackAfterOneFrame(){
        val s=VideoOcrStabilizer(requiredObservations=2)
        s.add(0,"走");s.add(65,"走")
        s.add(130,"不走");s.add(195,"不走")
        s.add(260,"走")
        assertEquals(listOf("走","不走"),s.finish(300).map{it.source})
        val resumed=VideoOcrStabilizer(saved=s.snapshot(),requiredObservations=2)
        // A replay of the same image timestamp cannot supply a second independent observation.
        resumed.add(260,"走")
        assertEquals(0,resumed.cues.size)
    }
    @Test fun draftQuorumRequiresDistinctFrameTimestamps(){
        val s=VideoOcrStabilizer(requiredObservations=2)
        s.add(0,"快走");s.add(0,"快走")
        assertTrue(s.finish(50).isEmpty())
    }
}
