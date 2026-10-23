package com.gemini.ocr.capture

import android.graphics.Rect
import com.gemini.ocr.ocr.OcrBlock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubtitleSourceRowsTest {
    private val crop = Rect(100, 500, 1500, 850)

    @Test fun chineseSourceExcludesPinyinAndVietnameseRows() {
        val merged = SubtitleRegion.merge(listOf(
            OcrBlock("vi", "Xin chào, tôi có thể giúp gì?", Rect(0, 100, 800, 130)),
            OcrBlock("zh", "你好,先生", Rect(20, 10, 350, 45)),
            OcrBlock("pinyin", "Nǐ hǎo, xiānsheng", Rect(10, 60, 600, 90)),
        ), crop, "zh").single()
        assertEquals("你好,先生", merged.text)
        assertEquals(Rect(120, 510, 450, 545), merged.rect)
    }

    @Test fun fragmentsOnSourceRowKeepGapsAndLatinNames() {
        val merged = SubtitleRegion.merge(listOf(
            OcrBlock("zh", "你好", Rect(10, 10, 100, 40)),
            OcrBlock("name", "Alice", Rect(110, 11, 180, 41)),
            OcrBlock("gap", "___", Rect(190, 10, 240, 40)),
            OcrBlock("vi", "Xin chào Alice", Rect(10, 80, 500, 110)),
        ), crop, "zh").single()
        assertEquals("你好 Alice ___", merged.text)
        assertEquals(Rect(110, 510, 340, 541), merged.rect)
    }

    @Test fun japaneseKanaAndKoreanHangulChooseTheirSourceRows() {
        val japanese = SubtitleRegion.merge(listOf(
            OcrBlock("source", "こんにちは", Rect(10, 10, 220, 40)),
            OcrBlock("romaji", "konnichiwa", Rect(10, 60, 250, 90)),
        ), crop, "ja").single()
        assertEquals("こんにちは", japanese.text)
        val korean = SubtitleRegion.merge(listOf(
            OcrBlock("source", "안녕하세요", Rect(10, 10, 220, 40)),
            OcrBlock("vi", "Xin chào", Rect(10, 60, 250, 90)),
        ), crop, "ko").single()
        assertEquals("안녕하세요", korean.text)
    }

    @Test fun oneCharacterSourceSurvivesRowSelection() {
        // Tests row merging after OCR; real native ML Kit recognition is verified on Android.
        val merged = SubtitleRegion.merge(listOf(
            OcrBlock("source", "好", Rect(10, 10, 50, 50)),
            OcrBlock("vi", "Được", Rect(10, 70, 160, 105)),
        ), crop, "zh").single()
        assertEquals("好", merged.text)
        assertEquals(Rect(110, 510, 150, 550), merged.rect)
    }

    @Test fun absentRequestedScriptPreservesRecognizedTextForUserVisibility() {
        val merged = SubtitleRegion.merge(listOf(
            OcrBlock("source", "Please wait", Rect(10, 10, 200, 40)),
        ), crop, "zh").single()
        assertEquals("Please wait", merged.text)
        assertTrue(SubtitleRegion.merge(emptyList(), crop, "zh").isEmpty())
    }
}
