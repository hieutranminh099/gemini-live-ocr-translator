package com.gemini.ocr.capture

/**
 * The current screen, rather than the most recently completed network call, owns the subtitle.
 * All methods are called on the capture service's main dispatcher. No Android dependencies make
 * the ordering rules independently testable with delayed OCR and translation completions.
 */
class SubtitlePipeline(initialRegionId: String = "") {
    data class CaptureToken(val regionId: String, val captureEpoch: Long)
    data class RequestToken(
        val requestId: String,
        val regionId: String,
        val screenGeneration: Long,
        val signature: String,
    )

    var regionId: String = initialRegionId
        private set
    var screenGeneration: Long = 0
        private set
    var signature: String = ""
        private set
    private var captureEpoch: Long = 0
    private var submittedGeneration: Long? = null
    private var currentRequest: RequestToken? = null

    fun captureToken(): CaptureToken = CaptureToken(regionId, captureEpoch)

    /** An OCR task started before a crop, rotation, pause, or session reset cannot publish. */
    fun acceptsOcr(token: CaptureToken): Boolean =
        token.regionId == regionId && token.captureEpoch == captureEpoch

    fun updateRegion(id: String): Boolean {
        if (regionId == id) return false
        regionId = id
        reset()
        return true
    }

    /** Empty text is a real screen generation too, so disappearing text invalidates pending AI. */
    fun observe(newSignature: String): Boolean {
        if (newSignature == signature) return false
        signature = newSignature
        screenGeneration++
        submittedGeneration = null
        currentRequest = null
        return true
    }

    /** Repeated OCR of an unchanged sentence can issue at most one successful request. */
    fun request(requestId: String): RequestToken? {
        require(requestId.isNotBlank())
        if (signature.isEmpty() || submittedGeneration == screenGeneration) return null
        return RequestToken(requestId, regionId, screenGeneration, signature).also {
            submittedGeneration = screenGeneration
            currentRequest = it
        }
    }

    /** Include all four fields: A -> B -> A must not accept the first A's late response. */
    fun accepts(token: RequestToken): Boolean =
        currentRequest == token && signature.isNotEmpty() && token.regionId == regionId &&
            token.screenGeneration == screenGeneration && token.signature == signature

    /** Release deduplication after a failed send/error only if that request still owns the screen. */
    fun retry(token: RequestToken) {
        if (!accepts(token)) return
        submittedGeneration = null
        currentRequest = null
    }

    /** Also invalidates work when the current screen was already empty. */
    fun reset() {
        captureEpoch++
        screenGeneration++
        signature = ""
        submittedGeneration = null
        currentRequest = null
    }
}
