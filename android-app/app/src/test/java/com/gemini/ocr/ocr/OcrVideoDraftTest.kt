package com.gemini.ocr.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OcrVideoDraftTest {
    private fun line(text:String,confidence:Double=.8,top:Int=10,bounds:OcrRecoveryPolicy.Bounds?=
        OcrRecoveryPolicy.Bounds(10,top,150,top+20))=OcrRecoveryPolicy.Line(text,confidence,bounds)

    @Test fun batchDraftRetainsLowConfidenceHanButLiveStillRejectsIt()=runBlocking {
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        var calls=0
        val processor=OcrProcessor{calls++;listOf(line("不知不觉就熟悉了每个人。",.2))}
        try {
            val draft=processor.processVideoDraft(bitmap)
            assertEquals("不知不觉就熟悉了每个人。",draft.text)
            assertEquals(.2f,draft.minConfidence!!,0f);assertTrue(draft.needsReview)
            assertTrue("LOW_OCR_CONFIDENCE" in draft.flags);assertEquals(1,calls)
            val live=processor.processObservation(bitmap,fastDownscale=false,enforceSubtitlePolicy=true)
            assertTrue(live.blocks.isEmpty());assertTrue(live.uncertain)
            assertFalse(bitmap.isRecycled)
        }finally{processor.close();bitmap.recycle()}
    }
    @Test fun multilineDraftKeepsNativeLinesAndKnownOrUnknownConfidence()=runBlocking {
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        val processor=OcrProcessor{listOf(line("聊个不停，舍不得离开。",Double.NaN,50),line("不知不觉就熟悉了每个人。",.7,10))}
        try {
            val draft=processor.processVideoDraft(bitmap)
            assertEquals("不知不觉就熟悉了每个人。\n聊个不停，舍不得离开。",draft.text)
            assertEquals(.7f,draft.minConfidence!!,0f);assertNull(draft.lines[1].confidence)
            assertTrue("OCR_CONFIDENCE_UNKNOWN" in draft.flags);assertTrue(draft.needsReview)
            assertEquals(2,draft.detectedHanLines);assertEquals(0,draft.rejectedHanLines)
        }finally{processor.close();bitmap.recycle()}
    }
    @Test fun missingBoundsAreFlaggedWithoutInventingTextAndNonHanNoiseStaysEmpty() {
        val result=OcrVideoDraft.from(listOf(line("缺少位置",bounds=null),line("!!!"),line("12345")),200,100)
        assertEquals("",result.text);assertNull(result.minConfidence)
        assertTrue(result.needsReview);assertTrue("OCR_GEOMETRY_REJECTED" in result.flags)
        assertEquals(1,result.detectedHanLines);assertEquals(1,result.rejectedHanLines)
        val empty=OcrVideoDraft.from(listOf(line("!!!"),line("12345")),200,100)
        assertEquals("",empty.text);assertFalse(empty.needsReview);assertNull(empty.minConfidence)
    }
    @Test fun digitsNegationsAndDistinctDialogueAreNeverNormalizedIntoOneLine() {
        val lines=listOf("3.5元","35元","-3元","3元","有两本书","没有两本书")
            .mapIndexed{index,text->line(text,top=index*25)}
        val result=OcrVideoDraft.from(lines,200,180)
        assertEquals(lines.map{it.text},result.lines.map{it.text})
        assertEquals(6,result.lines.size);assertFalse(result.needsReview)
        val unknown=OcrVideoDraft.from(listOf(line("未知",Double.NaN)),200,100)
        assertNull(unknown.minConfidence);assertTrue(unknown.needsReview)
    }
    @Test fun budgetRejectionIsVisibleAndDoesNotCreateFakeCharacters() {
        val draft=OcrVideoDraft.from(listOf(line("中".repeat(2001)),line("保留这句",top=40)),200,100)
        assertEquals("保留这句",draft.text);assertEquals(1,draft.rejectedHanLines)
        assertTrue(draft.needsReview);assertTrue("OCR_BUDGET_REJECTED" in draft.flags)
    }
    @Test fun cancellationWaitsForNativeCompletionAndCallerStillOwnsBitmap()=runBlocking {
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val processor=OcrProcessor{assertFalse(bitmap.isRecycled);entered.complete(Unit);release.await()
            assertFalse(bitmap.isRecycled);listOf(line("真实识别"))}
        try {
            val task=launch{processor.processVideoDraft(bitmap)}
            entered.await();task.cancel()
            assertFalse(task.isCompleted);assertFalse(bitmap.isRecycled)
            release.complete(Unit);task.join()
            assertTrue(task.isCancelled);assertFalse(bitmap.isRecycled)
        }finally{release.complete(Unit);processor.close();bitmap.recycle()}
    }
}
