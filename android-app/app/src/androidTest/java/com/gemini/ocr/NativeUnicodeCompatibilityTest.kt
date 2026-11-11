package com.gemini.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gemini.ocr.network.CostOptimizationEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the real Android regex/runtime, which differs from the JVM used by Robolectric. */
@RunWith(AndroidJUnit4::class)
class NativeUnicodeCompatibilityTest {
    @Test fun hanAndLatinWordCountingLoadsOnTheAndroidRuntime() {
        assertEquals(2, CostOptimizationEngine.estimateWordCount("你好 Alice"))
        assertFalse(CostOptimizationEngine.isOcrNoise("关键时候掉链子", "zh", true))
        val supplementaryHan = String(Character.toChars(0x20000)).repeat(3)
        assertEquals(3, CostOptimizationEngine.estimateWordCount("$supplementaryHan Alice"))
    }
}
