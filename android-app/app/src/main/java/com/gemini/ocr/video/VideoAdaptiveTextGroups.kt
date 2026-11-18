package com.gemini.ocr.video

/** Starts conservatively and adapts only to results already validated by the text worker. */
internal class VideoAdaptiveTextGroups(private val maxCues: Int, initialLimit: Int = maxCues) {
    init {
        require(maxCues in 1..50 && initialLimit in 1..maxCues)
    }

    var limit = minOf(initialLimit, 30)
        private set
    private var completeGroups = 0

    fun complete(requested: Int) {
        if (requested != limit || limit == maxCues) {
            completeGroups = 0
            return
        }
        if (++completeGroups >= 3) {
            limit = minOf(maxCues, limit + 10, limit * 2)
            completeGroups = 0
        }
    }

    fun reduce(requested: Int) {
        require(requested in 1..maxCues)
        limit = minOf(limit, (requested / 2).coerceAtLeast(1))
        completeGroups = 0
    }
}
