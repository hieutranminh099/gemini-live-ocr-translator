package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class SubtitleTextTest {
    @Test fun spacingAndFinalPeriodCannotCauseNewSubtitleRequests() {
        assertEquals(SubtitleText.identity("Please  wait for me."), SubtitleText.identity("please wait for me"))
        assertEquals(SubtitleText.identity("一緒に 帰りましょう。"), SubtitleText.identity("一緒に帰りましょう"))
    }
    @Test fun meaningfulWordBoundariesCannotReturnAnotherSentencesCache() {
        assertNotEquals(SubtitleText.identity("now here"), SubtitleText.identity("nowhere"))
        assertNotEquals(SubtitleText.identity("Please wait for me"), SubtitleText.identity("please waitfor me"))
        assertNotEquals(SubtitleText.identity("아버지가 방에"), SubtitleText.identity("아버지 가방에"))
    }
    @Test fun gapsAndIncompleteTextRemainVisibleToTranslation() {
        assertNotEquals(SubtitleText.identity("您 的 ___"), SubtitleText.identity("您的"))
        assertEquals("wait...", SubtitleText.identity("Wait..."))
        assertEquals("你_好", SubtitleText.identity("你_好"))
        assertEquals("你好", SubtitleText.identity("你\u3000好。"))
    }
    @Test fun questionsAndExclamationsRemainDifferentSentences() {
        assertNotEquals(SubtitleText.identity("You are here?"), SubtitleText.identity("You are here!"))
        assertNotEquals(SubtitleText.identity("Wait for me"), SubtitleText.identity("Do not wait for me"))
    }
    @Test fun ocrOfOurVietnameseOverlayCannotBeSentBackToAi() {
        assertTrue(SubtitleText.isRenderedCopy("Lam on hay doi toi", "Làm ơn hãy đợi tôi."))
        assertFalse(SubtitleText.isRenderedCopy("Please wait for me.", "Làm ơn hãy đợi tôi."))
        assertFalse(SubtitleText.isRenderedCopy("", ""))
    }
}
