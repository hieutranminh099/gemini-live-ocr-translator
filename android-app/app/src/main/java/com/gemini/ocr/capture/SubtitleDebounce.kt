package com.gemini.ocr.capture

/**
 * Briefly settle a new source before spending a cloud request on it.
 *
 * This gate belongs after the phone-cache lookup. It never delays an empty-source transition,
 * capture, OCR, or rejection of an obsolete translation. The caller keeps scanning and queries
 * [remainingMs] again on later OCR observations; there is no timer or AI work in this class.
 */
class SubtitleDebounce {
    private val changes = ArrayDeque<Long>()
    private var changedAt: Long? = null

    fun changed(now: Long, empty: Boolean) {
        if (empty) { reset(); return }
        if (changedAt?.let { now < it } == true) reset()
        changedAt = now
        while (changes.isNotEmpty() && now - changes.first() > CHANGE_WINDOW_MS) changes.removeFirst()
        changes.addLast(now)
        // Only the three most recent changes are needed to classify a noisy source.
        while (changes.size > RAPID_CHANGE_COUNT) changes.removeFirst()
    }

    fun remainingMs(now: Long): Long = remainingMs(now, turbo = false, standaloneZeroDebounce = false)

    fun remainingMs(now: Long, turbo: Boolean, standaloneZeroDebounce: Boolean = false): Long {
        val changed = changedAt ?: return 0
        val initialSettle = when {
            standaloneZeroDebounce -> STANDALONE_ZERO_SETTLE_MS
            turbo -> TURBO_INITIAL_SETTLE_MS
            else -> INITIAL_SETTLE_MS
        }
        val jitterSettle = when {
            standaloneZeroDebounce -> STANDALONE_JITTER_SETTLE_MS
            turbo -> TURBO_JITTER_SETTLE_MS
            else -> JITTER_SETTLE_MS
        }
        val settling = if (changes.size >= RAPID_CHANGE_COUNT) jitterSettle else initialSettle
        return (settling - (now - changed).coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun reset() { changes.clear(); changedAt = null }

    private companion object {
        const val INITIAL_SETTLE_MS = 90L
        const val JITTER_SETTLE_MS = 180L
        const val TURBO_INITIAL_SETTLE_MS = 25L
        const val TURBO_JITTER_SETTLE_MS = 70L
        const val STANDALONE_ZERO_SETTLE_MS = 0L
        const val STANDALONE_JITTER_SETTLE_MS = 35L
        const val CHANGE_WINDOW_MS = 1000L
        const val RAPID_CHANGE_COUNT = 3
    }
}
