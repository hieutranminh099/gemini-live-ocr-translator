package com.gemini.ocr.capture

import java.text.Normalizer
import java.util.Locale

object SubtitleText {
    // OCR may insert spaces between Chinese/Japanese characters. Latin and Korean word
    // boundaries carry meaning: "now here" and "nowhere" must never share a cache entry.
    // Preserve gaps/underscores and question/exclamation marks rather than invent missing text.
    fun identity(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        val compact = buildString {
            normalized.forEachIndexed { index, character ->
                if (character != ' ' || index == 0 || index == normalized.lastIndex ||
                    !unspacedScript(normalized[index - 1]) || !unspacedScript(normalized[index + 1])) {
                    append(character)
                }
            }
        }
        // A single final full stop is a frequent OCR variation; keep ellipses/incomplete clauses.
        return if ((compact.endsWith('.') && !compact.endsWith("..")) ||
            (compact.endsWith('。') && !compact.endsWith("。。"))) compact.dropLast(1) else compact
    }

    private fun unspacedScript(character: Char): Boolean =
        character in '\u3400'..'\u9fff' || character in '\uf900'..'\ufaff' ||
            character in '\u3040'..'\u30ff'

    fun isRenderedCopy(recognized: String, rendered: String): Boolean {
        fun visualKey(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }.replace('đ', 'd')
        val key = visualKey(rendered)
        return key.isNotEmpty() && key == visualKey(recognized)
    }
}
