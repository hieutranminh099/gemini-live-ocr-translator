package com.gemini.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.Normalizer

data class OcrBlock(val id: String, val text: String, val rect: Rect) {
    fun json(width: Int, height: Int): JSONObject = JSONObject().put("id", id).put("text", text.take(2000))
        .put("box", JSONObject().put("x", rect.left.toDouble() / width).put("y", rect.top.toDouble() / height)
            .put("width", rect.width().toDouble() / width).put("height", rect.height().toDouble() / height))
}

class OcrProcessor(language: String) : AutoCloseable {
    private val recognizer: TextRecognizer = when (language) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    suspend fun process(bitmap: Bitmap, retainSourceFragments: Boolean = false): List<OcrBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        var total = 0
        return result.textBlocks.flatMap { it.lines }.mapNotNull { block ->
            val rect = block.boundingBox ?: return@mapNotNull null
            val text = Normalizer.normalize(block.text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
            // ML Kit sometimes does not supply confidence, so reject only known low-confidence lines.
            val confidence = block.confidence?.toDouble() ?: Double.NaN
            val singleCjk = text.length == 1 && Character.UnicodeScript.of(text[0].code) in setOf(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
            val fragment = retainSourceFragments && text.any { it.isDigit() || it == '_' } &&
                text.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
            if ((text.length < 2 && !singleCjk && !fragment) || (text.none { it.isLetter() } && !fragment) || text.length > 2000 || (!confidence.isNaN() && confidence < .35)) return@mapNotNull null
            val clipped = Rect(rect)
            if (!clipped.intersect(0, 0, bitmap.width, bitmap.height) || clipped.width() < 3 || clipped.height() < 3) return@mapNotNull null
            total += text.length
            if (total > 12000) return@mapNotNull null
            OcrBlock("b${block.hashCode().toUInt().toString(16)}", text, clipped)
        }.sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left })).take(40)
            .mapIndexed { i, block -> block.copy(id = "b$i") }
    }
    override fun close() { recognizer.close() }
}
