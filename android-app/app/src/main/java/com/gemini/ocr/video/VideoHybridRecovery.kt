package com.gemini.ocr.video

/** Per-worker target sizing, independent of HTTP/auth/quota retry policy.
 * Call only after validation: this class never accepts, remaps or manufactures translations. */
internal class VideoHybridRecovery(savedTargetLimit: Int = 0, previousStageError: String = "") {
    companion object {
        const val INITIAL_TARGETS = 16
        const val MIN_TARGETS = 8
        const val MAX_TARGETS = 64
        const val SUCCESSES_TO_GROW = 3
        const val MAX_INVALID_REPLIES = 2
    }

    var targetLimit: Int = savedTargetLimit.takeIf { it in 1..MAX_TARGETS }
        ?.coerceAtLeast(MIN_TARGETS) ?: INITIAL_TARGETS
        private set
    var invalidReplies: Int = 0
        private set
    private var completeReplies = 0

    init {
        // An older malformed-response failure must not replay its very large target set.
        if (previousStageError == "TEXT_INVALID_RESPONSE") targetLimit = minOf(targetLimit, INITIAL_TARGETS)
    }

    fun completeReply(): Int {
        completeReplies++
        if (completeReplies >= SUCCESSES_TO_GROW) {
            completeReplies = 0
            targetLimit = minOf(MAX_TARGETS, targetLimit * 2)
        }
        return targetLimit
    }

    fun partialReply(requestedTargets: Int): Int {
        require(requestedTargets > 0)
        completeReplies = 0
        targetLimit = minOf(MAX_TARGETS, maxOf(MIN_TARGETS, requestedTargets / 2))
        return targetLimit
    }

    /** True admits one smaller retry. A second invalid reply stops this worker, even
     * when a successful reply intervened, so malformed output cannot create a retry loop. */
    fun invalidReply(): Boolean {
        invalidReplies++
        completeReplies = 0
        targetLimit = maxOf(MIN_TARGETS, targetLimit / 2)
        return invalidReplies < MAX_INVALID_REPLIES
    }
}
