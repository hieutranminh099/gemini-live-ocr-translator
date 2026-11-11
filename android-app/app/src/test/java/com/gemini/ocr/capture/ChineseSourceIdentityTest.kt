package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class ChineseSourceIdentityTest {
    private fun key(source: String) = SubtitleText.sourceIdentity(source, "zh")
    private fun signature(source: String) = StableText.signature(listOf(key(source)))

    @Test fun observedChineseScriptFlickerDoesNotCancelTheSameSubtitleRequest() {
        val pipeline = SubtitlePipeline("film-roi")
        pipeline.observe(signature("快快快 进来进来"))
        val token = pipeline.request("first")!!
        assertFalse(pipeline.observe(signature("快快快進來进来")))
        assertTrue(pipeline.accepts(token))
        assertNull(pipeline.request("duplicate"))
    }

    @Test fun missingGlyphOrChangedMeaningStillCreatesADifferentGeneration() {
        for (different in listOf("快快快来进来", "不要进来", "快快快 进来进来？", "2人进来", "3人进来")) {
            assertNotEquals(key("快快快进来进来"), key(different))
        }
        assertNotEquals(key("2人进来"), key("3人进来"))
        assertNotEquals(key("可以进来"), key("不可以进来"))
        assertNotEquals(key("侯先生进来"), key("候先生进来"))
    }

    @Test fun returningSubtitleCannotAcceptTheFirstOccurrencesLateResponse() {
        val pipeline = SubtitlePipeline("film-roi")
        pipeline.observe(signature("进来")); val first = pipeline.request("one")!!
        pipeline.observe(signature("不要进来")); pipeline.request("two")!!
        pipeline.observe(signature("進來")); val last = pipeline.request("three")!!
        assertFalse(pipeline.accepts(first)); assertTrue(pipeline.accepts(last))
    }

    @Test fun otherLanguagesAndAmbiguousTraditionalCharactersAreNotRewritten() {
        assertNotEquals(SubtitleText.sourceIdentity("進来", "ja"), SubtitleText.sourceIdentity("进来", "ja"))
        assertNotEquals(key("發現"), key("髮現"))
        assertNotEquals(key("now here"), key("nowhere"))
    }
}
