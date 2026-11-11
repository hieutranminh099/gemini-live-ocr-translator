package com.gemini.ocr.network

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputGuardTest {
    @Test fun liveVideoPunctuationOnlyAnswersCannotBecomeTranslationsOrDialogue() {
        for (target in listOf(".", "!", "…", "。！？", " ", "", "🙂")) {
            val failure = assertThrows(TranslationOutputGuard.RejectedOutput::class.java) {
                TranslationOutputGuard.validateModelOutput("zh", listOf("我的孩子"), listOf(target))
            }
            assertEquals(TranslationOutputGuard.Reason.NO_LEXICAL_CONTENT, failure.reason)
        }
    }
    @Test fun aPurePunctuationSourceIsNotGivenAnInventedLexicalRequirement() {
        TranslationOutputGuard.validateModelOutput("zh", listOf("……"), listOf("…"))
    }
    @Test fun historicalVideoUntranslatedHanOutputIsRejected() {
        assertThrows(TranslationOutputGuard.RejectedOutput::class.java) {
            TranslationOutputGuard.validateModelOutput("zh", listOf("意图吞并我顾家"), listOf("吞 吞 家 家"))
        }
    }
    @Test fun unchangedChineseWithNumbersAndPunctuationIsNotVietnamese() {
        assertThrows(TranslationOutputGuard.RejectedOutput::class.java) {
            TranslationOutputGuard.validateModelOutput("zh-CN", listOf("还有2分钟"), listOf("还有2分钟。"))
        }
    }
    @Test fun supplementaryHanCannotBypassTheGuard() {
        assertThrows(TranslationOutputGuard.RejectedOutput::class.java) {
            TranslationOutputGuard.validateModelOutput("zh", listOf("名字"), listOf(String(Character.toChars(0x20000))))
        }
    }
    @Test fun vietnameseIncludingAnUntranslatedProperNameIsNotClaimedToBeIncorrect() {
        TranslationOutputGuard.validateModelOutput("zh", listOf("去见李明"), listOf("Đến gặp 李明."))
    }
    @Test fun numericAndLatinProperNameOutputsArePreserved() {
        TranslationOutputGuard.validateModelOutput("zh", listOf("尤素夫", "三"), listOf("Yusufu", "3"))
    }
    @Test fun explicitWholeSourceGlossaryMayPreserveAChineseName() {
        TranslationOutputGuard.validateModelOutput("zh", listOf("李明"), listOf("李明"), mapOf("李明" to "李明"))
    }
    @Test fun aGlossaryNameCannotLicenseAnUntranslatedWholeSentence() {
        assertThrows(TranslationOutputGuard.RejectedOutput::class.java) {
            TranslationOutputGuard.validateModelOutput("zh", listOf("去见李明"), listOf("李明"), mapOf("李明" to "李明"))
        }
    }
    @Test fun otherSourceLanguagesRetainTheirExistingPolicy() {
        TranslationOutputGuard.validateModelOutput("ja", listOf("名前"), listOf("名前"))
    }
}
