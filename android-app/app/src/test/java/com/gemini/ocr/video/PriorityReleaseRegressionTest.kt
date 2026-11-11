package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PriorityReleaseRegressionTest {
    @Test fun legacyManualTranslationIsNeverResubmittedOrOverwrittenByAnInflightBatch() {
        val original=VideoCue("one",100,2000,"别走",translation="Nháp",localDraft="Nháp",refinementState="pending")
        val batch=requireNotNull(VideoTranslationPlan.nextCloudBatch(listOf(original),true))
        val manual=original.copy(translation="Đừng đi, anh Quân.",model="user-edited")
        assertTrue(manual.protectedText)
        assertFalse(VideoTranslationPlan.needsLocalDraft(manual,true))
        assertFalse(VideoTranslationPlan.needsCloudRefinement(manual))
        assertTrue(VideoTranslationPlan.isComplete(manual,true))
        assertNull(VideoTranslationPlan.nextCloudBatch(listOf(manual),true))
        assertEquals(manual,VideoTranslationPlan.applyCloudResult(listOf(manual),batch,listOf("Bản máy mới"),"test-model",true).single())
        assertEquals(manual,VideoTranslationPlan.markCloudFailure(listOf(manual),batch,"Mất mạng",true).single())
        assertEquals(manual,VideoCue.from(manual.json()))
    }
    @Test fun portraitTextKeepsTheLargestReadableSizeInsteadOfCollapsingToMinimum() {
        val text="Tôi không phải Trần Minh. Tôi có 250 nhân dân tệ, đừng viết mười hai thành hai mươi."
        val style=VideoSubtitleStyle(sizeFraction=.09f,frame=VideoSubtitleFrame(.03f,.75f,.97f,.93f))
        val measured=measureSubtitleFrame(text,style,720,1280)
        assertTrue("Portrait subtitles should remain readable",measured.paint.textSize>20f)
        assertTrue(measured.layout.height+measured.padding*2<=measured.height)
    }
}
