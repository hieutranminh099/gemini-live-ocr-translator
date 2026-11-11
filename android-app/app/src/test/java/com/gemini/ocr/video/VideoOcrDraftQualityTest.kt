package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoOcrDraftQualityTest {
    @Test fun draftKeepsRealLowConfidenceRawLinesAndDoesNotMergeNegationOrOccurrences(){
        val q=VideoOcrDraftQuality()
        q.record("不是三个\n快走",.2f,listOf("LOW_OCR_CONFIDENCE"))
        q.record("不是三个\n快走",.1f,emptyList())
        q.record("是三个\n快走",.9f,emptyList())
        val cue=q.close(VideoCue("a",0,500,"不是三个 快走"))
        assertEquals("不是三个\n快走",cue.rawZh);assertEquals(.1f,cue.ocrConfidence!!,0f)
        assertTrue("LOW_OCR_CONFIDENCE" in cue.qualityFlags)
        assertEquals(.9f,q.close(VideoCue("b",500,900,"是三个 快走")).ocrConfidence!!,0f)
        q.record("不是三个\n快走",.8f,emptyList())
        assertEquals(.8f,q.close(VideoCue("c",1200,1500,"不是三个 快走")).ocrConfidence!!,0f)
    }
    @Test fun currentQuorumMetadataSurvivesCheckpointWithNoInventedConfidence(){
        val q=VideoOcrDraftQuality();q.record("你好",null,listOf("OCR_CONFIDENCE_UNKNOWN"))
        val resumed=VideoOcrDraftQuality(q.snapshot("你好"))
        val cue=resumed.close(VideoCue("a",0,500,"你好"))
        assertNull(cue.ocrConfidence);assertTrue("OCR_CONFIDENCE_UNKNOWN" in cue.qualityFlags)
        assertEquals("你好",cue.rawZh)
    }
    @Test fun rawTwoFrameEvidenceIsFlaggedAndCannotMergeChangedNegation(){
        val s=VideoOcrStabilizer(requiredObservations=2)
        s.add(0,"是三个");s.add(65,"是三个")
        s.add(130,"不是三个");s.add(195,"不是三个")
        assertEquals(listOf("是三个","不是三个"),s.finish(300).map{it.source})
        val q=VideoOcrDraftQuality()
        q.record("是三个",.8f,emptyList(),frameMs=0)
        q.record("是三个",.8f,emptyList(),frameMs=65)
        assertTrue("OCR_SHORT_QUORUM" in q.close(VideoCue("a",0,130,"是三个")).qualityFlags)
    }
    @Test fun expiredCandidateCannotRaiseLaterOccurrenceToThreeFrameConfidence(){
        val q=VideoOcrDraftQuality()
        q.record("你好",.1f,listOf("OLD_CANDIDATE"),frameMs=0)
        q.record("你好",.8f,emptyList(),frameMs=10_000)
        q.record("你好",.9f,emptyList(),frameMs=10_065)
        val cue=q.close(VideoCue("later",10_000,10_200,"你好"))
        assertEquals(.8f,cue.ocrConfidence!!,0f)
        assertTrue("OCR_SHORT_QUORUM" in cue.qualityFlags)
        assertFalse("OLD_CANDIDATE" in cue.qualityFlags)
    }
    @Test fun closingEarlierOccurrencePreservesAlreadyReadFutureEvidenceAcrossCheckpoint(){
        val q=VideoOcrDraftQuality()
        q.record("不是三个\n快走",.2f,listOf("LOW_OCR_CONFIDENCE"),pin="不是三个 快走",frameMs=0)
        q.record("不是三个\n快走",.3f,emptyList(),pin="不是三个 快走",frameMs=65)
        // The decoder reads the current frame before refining older ring frames: future evidence
        // can already exist when the earlier same-text occurrence is finally closed.
        q.record("不是三个\n快走",.8f,emptyList(),pin="不是三个 快走",frameMs=300)
        q.record("不是三个\n快走",.9f,emptyList(),pin="不是三个 快走",frameMs=365)
        val first=q.close(VideoCue("first",0,130,"不是三个 快走"))
        assertEquals(.2f,first.ocrConfidence!!,0f)
        assertTrue("LOW_OCR_CONFIDENCE" in first.qualityFlags)
        val resumed=VideoOcrDraftQuality(q.snapshot("不是三个 快走"))
        val later=resumed.close(VideoCue("later",300,450,"不是三个 快走"))
        assertEquals("不是三个\n快走",later.rawZh)
        assertEquals(.8f,later.ocrConfidence!!,0f)
        assertFalse("LOW_OCR_CONFIDENCE" in later.qualityFlags)
        assertTrue("OCR_SHORT_QUORUM" in later.qualityFlags)
    }
    @Test fun repeatedTimestampCannotHideShortQuorumAndMissingMetadataIsExplicit(){
        val q=VideoOcrDraftQuality()
        repeat(3){q.record("实际文字",.8f,emptyList(),frameMs=100)}
        assertTrue("OCR_SHORT_QUORUM" in q.close(VideoCue("a",100,200,"实际文字")).qualityFlags)
        val missing=q.close(VideoCue("b",300,500,"没有元数据"))
        assertNull(missing.ocrConfidence)
        assertEquals("没有元数据",missing.rawZh)
        assertTrue("OCR_CONFIDENCE_UNKNOWN" in missing.qualityFlags)
    }
}
