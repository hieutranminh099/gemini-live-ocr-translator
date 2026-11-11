package com.gemini.ocr.video

/** One project and one worker run. Entries never reach Live or another film. */
internal class VideoOfflineDraftCache(private val limit: Int = 256) {
    init { require(limit > 0) }

    private data class Key(val previous: String?, val source: String, val following: String?)
    private val drafts = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>): Boolean = size > limit
    }

    private fun key(cues: List<VideoCue>, index: Int) = Key(cues.getOrNull(index - 1)?.source,
        cues[index].source, cues.getOrNull(index + 1)?.source)

    fun lookup(cues: List<VideoCue>, index: Int): String? = drafts[key(cues, index)]

    fun remember(cues: List<VideoCue>, index: Int, draft: String) {
        if (!cues[index].protectedText && draft.isNotBlank()) drafts[key(cues, index)] = draft
    }
}
