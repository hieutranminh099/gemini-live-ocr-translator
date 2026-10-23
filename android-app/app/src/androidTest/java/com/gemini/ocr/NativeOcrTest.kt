package com.gemini.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOcrTest {
    private fun recognize(language: String, text: String): String = runBlocking {
        val bitmap = Bitmap.createBitmap(1400, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 88f }
        canvas.drawText(text, 50f, 180f, paint)
        val processor = OcrProcessor(language)
        try {
            val blocks = processor.process(bitmap)
            blocks.forEach { block ->
                val box = block.json(bitmap.width, bitmap.height).getJSONObject("box")
                assertTrue(box.getDouble("x") >= 0 && box.getDouble("y") >= 0)
                assertTrue(box.getDouble("x") + box.getDouble("width") <= 1.001)
                assertTrue(box.getDouble("y") + box.getDouble("height") <= 1.001)
            }
            blocks.joinToString("") { it.text }.replace(Regex("\\s+"), "")
        } finally { processor.close(); bitmap.recycle() }
    }
    @Test fun englishBundledModel() { assertEquals("Welcometraveler", recognize("en", "Welcome traveler")) }
    @Test fun japaneseBundledModel() { assertEquals("こんにちは世界", recognize("ja", "こんにちは世界")) }
    @Test fun chineseBundledModel() { assertEquals("欢迎来到世界", recognize("zh", "欢迎来到世界")) }
    @Test fun koreanBundledModel() { assertEquals("안녕하세요세계", recognize("ko", "안녕하세요 세계")) }
    @Test fun numericHudIsNotSentForTranslation() { assertEquals("", recognize("en", "1234567890")) }
}
