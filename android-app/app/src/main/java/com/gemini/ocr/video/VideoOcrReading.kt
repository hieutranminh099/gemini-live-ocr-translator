package com.gemini.ocr.video

import com.gemini.ocr.ocr.OcrRecoveryPolicy.Bounds
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** A retained OCR reading. A null reading means unknown, while empty text means an observed blank. */
internal class VideoOcrReading(
    val raw: String,
    val bounds: Bounds? = null,
    val confidence: Float? = null,
    val flags: List<String> = emptyList()
) {
    init { require(raw.length <= 2000) { "Một quan sát OCR vượt giới hạn; checkpoint trước được giữ." } }

    val displayText = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .lineSequence()
        .map { line ->
            line.filterNot { it in ignoredFormatting || it.isISOControl() && it != '\t' }
                .replace(spaces, " ").trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    // Fold only ASCII-width differences. Compatibility normalization would also equate e.g. ① and 1.
    val normalizedText = buildString(displayText.length) {
        for (char in displayText) append(when (char) {
            in '\uFF01'..'\uFF5E' -> (char.code - 0xFEE0).toChar()
            '\n' -> ' '
            else -> char
        })
    }.let { text ->
        val stem = text.removeSuffix("。")
        if (stem != text && stem.isHanOnly()) stem else text
    }

    // These suffixes are hypotheses for comparison only. Never remove them from an isolated reading.
    val hanStem: String? = if ('\n' in displayText) null else {
        trailingLetters.matchEntire(normalizedText)?.groupValues?.get(1)
            ?.takeIf { it.isHanOnly(minLength = 3) }
            ?: normalizedText.takeIf { it.isHanOnly(minLength = 3) }
    }
    val isHanAnchor get() = hanStem != null && hanStem == normalizedText

    // A single border stroke can be recognized as a pipe. Keep the literal reading unless
    // the same nearby caption is also actually recognized without the stroke.
    val edgeStrokeStem: String? = when {
        normalizedText.firstOrNull() in edgeStrokes -> normalizedText.drop(1).trimStart()
        normalizedText.lastOrNull() in edgeStrokes -> normalizedText.dropLast(1).trimEnd()
        else -> null
    }?.takeIf { it.isHanOnly(minLength = 2) }

    fun json() = JSONObject().put("raw", raw)
        .put("box", bounds?.toJson() ?: JSONObject.NULL)
        .put("confidence", confidence ?: JSONObject.NULL)

    companion object {
        private val ignoredFormatting = setOf('\u200B', '\u2060', '\uFEFF')
        private val edgeStrokes = setOf<Char?>('|', '¦', '│')
        private val spaces = Regex("[\\s\\p{Zs}]+")
        private val trailingLetters = Regex("(.+?)[ 。.]*[a-z]{1,2}")

        fun from(json: JSONObject) = VideoOcrReading(
            json.getString("raw"), readBounds(json.optJSONArray("box")),
            json.optDouble("confidence").takeIf { it.isFinite() && it in 0.0..1.0 }?.toFloat()
        )

        fun readBounds(json: JSONArray?): Bounds? = json?.let {
            Bounds(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3))
                .takeIf { box -> box.width > 0 && box.height > 0 }
        }
    }
}

internal fun Bounds.toJson() = JSONArray(listOf(left, top, right, bottom))

private fun String.isHanOnly(minLength: Int = 1): Boolean =
    codePointCount(0, length) >= minLength && codePoints().allMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }

internal class VideoOcrContinuity(
    val windowMs: Long = 260L,
    private val horizontalOverlap: Float = .70f,
    private val verticalOverlap: Float = .75f,
    private val confidenceMargin: Float = .20f
) {
    fun sameBox(a: Bounds?, b: Bounds?): Boolean {
        if (a == null || b == null) return false
        val x = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val y = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return x >= horizontalOverlap * maxOf(a.width, b.width) &&
            y >= verticalOverlap * maxOf(a.height, b.height)
    }

    fun possibleVariant(anchor: VideoOcrReading, changed: VideoOcrReading): Boolean =
        anchor.hanStem != null && anchor.hanStem == changed.hanStem ||
            weakSingleSubstitution(anchor, changed)

    fun edgeStrokeAnchor(first: VideoOcrReading, next: VideoOcrReading): VideoOcrReading? {
        return when {
            first.edgeStrokeStem == next.normalizedText && first.confidence?.let { it < .75f } == true -> next
            next.edgeStrokeStem == first.normalizedText && next.confidence?.let { it < .75f } == true -> first
            else -> null
        }
    }

    fun representative(first: VideoOcrReading, middle: VideoOcrReading, last: VideoOcrReading,
        firstAlreadyConfirmed: Boolean): VideoOcrReading? {
        if (first.normalizedText == last.normalizedText) {
            return first.takeIf { it.isHanAnchor && it.hanStem == middle.hanStem && allowsSuffix(it, middle) ||
                weakSingleSubstitution(it, middle) }
        }
        if (last.normalizedText == middle.normalizedText || firstAlreadyConfirmed && !first.isHanAnchor) return null
        // Three distinct readings with one actually observed Han-only anchor and two short
        // trailing variants. A sustained mixed-script caption wins as a separate occurrence.
        val anchor = listOf(first, middle, last).singleOrNull { it.isHanAnchor } ?: return null
        return anchor.takeIf {
            first.hanStem == anchor.hanStem && middle.hanStem == anchor.hanStem && last.hanStem == anchor.hanStem &&
                allowsSuffix(anchor, first) && allowsSuffix(anchor, middle) && allowsSuffix(anchor, last)
        }
    }

    private fun allowsSuffix(anchor: VideoOcrReading, other: VideoOcrReading): Boolean {
        if (other === anchor || other.confidence == null) return true
        // A high-confidence mixed-script line with no stronger anchor remains independently reviewable.
        return other.confidence < .75f ||
            anchor.confidence?.let { it - other.confidence >= confidenceMargin } == true
    }

    private fun weakSingleSubstitution(anchor: VideoOcrReading, other: VideoOcrReading): Boolean {
        val confidence = anchor.confidence ?: return false
        val otherConfidence = other.confidence ?: return false
        if (confidence - otherConfidence < confidenceMargin ||
            !anchor.normalizedText.isHanOnly(minLength = 8) || !other.normalizedText.isHanOnly(minLength = 8)) return false
        val left = anchor.normalizedText.codePoints().toArray()
        val right = other.normalizedText.codePoints().toArray()
        if (left.size != right.size || left.first() != right.first() || left.last() != right.last()) return false
        val index = left.indices.singleOrNull { left[it] != right[it] } ?: return false
        return left[index] !in negations && right[index] !in negations
    }

    private companion object {
        val negations = "不没沒無无非未勿莫别別否甭毋休".codePoints().toArray()
    }
}
