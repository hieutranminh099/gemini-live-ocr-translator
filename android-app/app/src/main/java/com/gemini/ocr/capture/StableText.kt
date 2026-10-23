package com.gemini.ocr.capture

/** Consecutive observations are tracked per text, so an animated HUD cannot starve a stable dialogue. */
class StableText(private val requiredObservations: Int = 2) {
    private var streaks = emptyMap<String, Int>()
    init { require(requiredObservations >= 1) }
    fun observe(texts: List<String>): Set<String> {
        streaks = texts.toSet().associateWith { ((streaks[it] ?: 0) + 1).coerceAtMost(requiredObservations) }
        return streaks.filterValues { it >= requiredObservations }.keys
    }
    fun reset() { streaks = emptyMap() }
    companion object {
        // Length prefixes avoid ambiguity when OCR itself contains separator characters.
        fun signature(texts: List<String>): String = texts.joinToString("") { "${it.length}:$it" }
    }
}

/** RAM-only session cache. Reconnects and server invalidation clear it before reuse. */
class TranslationMemory(private val capacity: Int = 256) {
    private val items = object : LinkedHashMap<String, String>(capacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > capacity
    }
    init { require(capacity > 0) }
    operator fun get(text: String): String? = items[text]
    fun put(text: String, translated: String) { items[text] = translated }
    fun clear() = items.clear()
    val size get() = items.size
}
