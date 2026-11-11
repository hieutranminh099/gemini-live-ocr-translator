package com.gemini.ocr.network

import java.io.IOException

/** Rejects missing lexical content and untranslated Han, not semantic correctness or confidence. */
object TranslationOutputGuard {
    enum class Reason(val traceCode: String) {
        UNTRANSLATED_HAN("untranslated_han"), NATIVE_MODEL_CONTRACT("native_model_contract"),
        NO_LEXICAL_CONTENT("no_lexical_content"),
    }
    class RejectedOutput(val reason: Reason = Reason.UNTRANSLATED_HAN) :
        IOException("Chưa dịch được câu này sang tiếng Việt.")

    fun validateModelOutput(language: String, sources: List<String>, translations: List<String>,
                            exactGlossary: Map<String, String> = emptyMap()) {
        if (!language.substringBefore('-').substringBefore('_').equals("zh", true)) return
        for ((index, target) in translations.withIndex()) {
            val source = sources.getOrNull(index) ?: throw RejectedOutput()
            if (source.codePoints().anyMatch { Character.isLetterOrDigit(it) } &&
                target.codePoints().noneMatch { Character.isLetterOrDigit(it) }) {
                throw RejectedOutput(Reason.NO_LEXICAL_CONTENT)
            }
            val hasHan = target.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            val hasLatin = target.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
            if (!hasHan || hasLatin) continue
            // An explicit whole-source user glossary can intentionally preserve a Chinese name.
            // A name appearing elsewhere in the glossary cannot license a missing translation.
            val explicit = exactGlossary[PhoneTranslationCache.normalizeText(source)]
            if (explicit == null || explicit.trim() != target.trim()) throw RejectedOutput()
        }
    }
}
