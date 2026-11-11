package com.paddle.ocr.postprocess

import com.paddle.ocr.model.OCRError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CTCDecoderTest {
    private val characters = listOf("中", "𠮷")
    private fun scores(vararg classes: Int): FloatArray = FloatArray(classes.size * 3) { .01f }.also { data ->
        classes.forEachIndexed { step, selected -> data[step * 3 + selected] = .9f }
    }
    private fun invalid(output: FloatArray, shape: LongArray, dictionary: List<String> = characters) {
        assertThrows(OCRError.DecodeError::class.java) { CTCDecoder.decode(output, shape, dictionary) }
    }

    @Test fun blankSequenceHasEmptyTextAndZeroConfidence() {
        assertEquals(listOf("" to 0f), CTCDecoder.decode(scores(0, 0, 0), longArrayOf(1, 3, 3), characters))
    }

    @Test fun adjacentRepeatsCollapseAndABlankPermitsTheSameCharacterAgain() {
        val result = CTCDecoder.decode(scores(1, 1, 0, 1, 2, 2), longArrayOf(1, 6, 3), characters).single()
        assertEquals("中中𠮷", result.first)
        assertEquals(.9f, result.second, .00001f)
    }

    @Test fun blankResetsPreviousClassIndependentlyForEveryBatch() {
        val result = CTCDecoder.decode(scores(1, 1, 0, 2, 0, 2), longArrayOf(2, 3, 3), characters)
        assertEquals(listOf("中", "𠮷𠮷"), result.map { it.first })
        assertEquals(.9f, result[0].second, .00001f)
        assertEquals(.9f, result[1].second, .00001f)
    }

    @Test fun confidenceAveragesOnlyTheFirstEmissionOfEachCollapsedRun() {
        val data = floatArrayOf(.05f, .80f, .15f, .01f, .98f, .01f, .05f, .25f, .70f)
        val result = CTCDecoder.decode(data, longArrayOf(1, 3, 3), characters).single()
        assertEquals("中𠮷", result.first)
        assertEquals(.75f, result.second, .00001f)
    }

    @Test fun spaceIsADictionaryTokenAndBlankIsNot() {
        assertEquals("中 中", CTCDecoder.decode(scores(1, 2, 1), longArrayOf(1, 3, 3), listOf("中", " ")).single().first)
    }

    @Test fun unexpectedRankFailsWithDecodeErrorBeforeIndexing() {
        for (shape in listOf(longArrayOf(), longArrayOf(1), longArrayOf(1, 3), longArrayOf(1, 1, 3, 1))) {
            invalid(floatArrayOf(), shape)
        }
    }

    @Test fun zeroAndNegativeDimensionsFailWithoutTreatingMissingDataAsValidBlank() {
        for (dimension in 0..2) for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            val shape = longArrayOf(1, 1, 3); shape[dimension] = bad
            invalid(floatArrayOf(), shape)
        }
    }

    @Test fun valuesBeyondIntRangeFailBeforeNarrowing() {
        for (dimension in 0..2) for (bad in listOf(Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            val shape = longArrayOf(1, 1, 3); shape[dimension] = bad
            invalid(floatArrayOf(), shape)
        }
    }

    @Test fun productsExceedingIntOrLongCapacityFailBeforeMultiplicationOrAllocation() {
        invalid(floatArrayOf(), longArrayOf(Int.MAX_VALUE.toLong(), 2, 3))
        invalid(floatArrayOf(), longArrayOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong()))
        invalid(floatArrayOf(), longArrayOf(1, Int.MAX_VALUE.toLong(), 3))
    }

    @Test fun truncatedAndTrailingTensorElementsFailClosed() {
        invalid(FloatArray(5), longArrayOf(1, 2, 3))
        invalid(FloatArray(7), longArrayOf(1, 2, 3))
        invalid(FloatArray(11), longArrayOf(2, 2, 3))
        invalid(FloatArray(13), longArrayOf(2, 2, 3))
    }

    @Test fun recognizerClassCountMustMatchDictionaryPlusBlank() {
        invalid(floatArrayOf(.1f, .2f, .7f), longArrayOf(1, 1, 3), listOf("中"))
        invalid(floatArrayOf(.1f, .9f), longArrayOf(1, 1, 2), characters)
    }

    @Test fun emptyDictionaryIsAnInvalidModelConfiguration() {
        invalid(floatArrayOf(1f), longArrayOf(1, 1, 1), emptyList())
    }
}
