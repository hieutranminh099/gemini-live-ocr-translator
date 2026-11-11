package com.gemini.ocr.batch

/** Process-local playback ownership. A brief recovery hold avoids flapping during seeks/buffering. */
internal class BatchPlaybackPriority(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val owners = mutableSetOf<Any>()
    private var resumeAtMs: Long? = null

    @Synchronized fun setPlaying(owner: Any, playing: Boolean) {
        if (playing) owners.add(owner)
        else if (owners.remove(owner) && owners.isEmpty()) resumeAtMs = nowMs() + RECOVERY_HOLD_MS
    }

    @Synchronized fun restricted(): Boolean = owners.isNotEmpty() || resumeAtMs?.let { nowMs() < it } == true

    companion object {
        internal const val RECOVERY_HOLD_MS = 2_000L
        val current = BatchPlaybackPriority()
    }
}
