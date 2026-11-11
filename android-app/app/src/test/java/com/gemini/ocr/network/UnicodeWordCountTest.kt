package com.gemini.ocr.network

import org.junit.Assert.*
import org.junit.Test

class UnicodeWordCountTest {
    @Test fun hanAndLatinKeepTheirSeparateCounts() {
        assertEquals(2, CostOptimizationEngine.estimateWordCount("你好 Alice"))
        assertEquals(2, CostOptimizationEngine.estimateWordCount("  Alice Bob  "))
        assertEquals(0, CostOptimizationEngine.estimateWordCount(""))
    }
    @Test fun supplementaryHanCountsAsOneCodePointRatherThanTwoSurrogates() {
        val source = String(Character.toChars(0x20000)).repeat(3)
        assertEquals(2, CostOptimizationEngine.estimateWordCount(source))
        assertEquals(3, CostOptimizationEngine.estimateWordCount("$source Alice"))
    }
}
