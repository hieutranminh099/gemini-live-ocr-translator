package com.gemini.ocr.movie

/** Numeric-only, opt-in debug observation. No file names, titles or media are logged. */
internal object MoviePlaybackTrace {
    @Volatile var observer: ((String, Long) -> Unit)? = null
    fun emit(stage: String, startedNs: Long) {
        if (com.gemini.ocr.BuildConfig.DEBUG)
            observer?.invoke(stage, android.os.SystemClock.elapsedRealtimeNanos() - startedNs)
    }
}
