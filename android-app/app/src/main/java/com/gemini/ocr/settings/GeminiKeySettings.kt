package com.gemini.ocr.settings

enum class GeminiKeyMode(val label: String) {
    PRIMARY_ONLY("Chỉ khóa chính"),
    FAILOVER("Dự phòng"),
    BALANCED("Dùng đồng thời");

    companion object {
        fun fromPersisted(value: String): GeminiKeyMode = entries.firstOrNull { it.name == value } ?: PRIMARY_ONLY
    }
}

/** The secret is never included in diagnostics or collection logging. */
data class GeminiKeySlot(val slot: Int, val key: String) {
    init { require(slot in 1..3) }
    override fun toString(): String = "GeminiKeySlot(slot=$slot)"
}
