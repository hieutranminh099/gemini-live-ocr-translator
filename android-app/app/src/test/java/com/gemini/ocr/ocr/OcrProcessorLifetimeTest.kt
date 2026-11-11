package com.gemini.ocr.ocr

import android.graphics.Bitmap
import com.gemini.ocr.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrProcessorLifetimeTest {
    @Test fun closeSignalsOnlyOnceAndClosedProcessorNeverEntersNative() = runBlocking {
        var closed = 0; var calls = 0
        val processor = OcrProcessor(recognize = { calls++; emptyList() }, onClosed = { closed++ })
        processor.close(); processor.close()
        val bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(runCatching { processor.process(bitmap) }.exceptionOrNull() is IllegalStateException)
            assertEquals(0, calls); assertEquals(1, closed)
        } finally { bitmap.recycle() }
        Unit
    }
    @Test fun factoryWorksOnlyInExplicitVerificationPackage() {
        var created = 0
        OcrProcessor.factoryForVerification = { created++; OcrProcessor { emptyList() } }
        try {
            val result = runCatching { OcrProcessor.create("zh") }
            if (BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench") {
                result.getOrThrow().close(); assertEquals(1, created)
            } else {
                assertTrue(result.exceptionOrNull() is IllegalStateException); assertEquals(0, created)
            }
        } finally { OcrProcessor.factoryForVerification = null }
    }
}
