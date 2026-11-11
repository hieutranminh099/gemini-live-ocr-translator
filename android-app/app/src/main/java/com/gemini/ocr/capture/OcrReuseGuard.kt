package com.gemini.ocr.capture

/**
 * Metadata-only permission to reuse a previously accepted native OCR result. The caller owns
 * the corresponding OCR blocks and must call [recordAcceptedOcr] only after all capture,
 * region, generation and pixel-freshness guards accept a real native OCR completion.
 *
 * CRC32 equality is a practical fingerprint, not a mathematical proof that two images are
 * identical. It must cover every byte of the same tightly packed ROI; a sparse sample or an
 * unrelated whole-screen hash is not sufficient for this policy.
 */
class OcrReuseGuard {
    private companion object {
        // An empty recognition can be a transient miss, even on a paused subtitle.
        const val EMPTY_REUSE_MAX_AGE_MS = 300L
    }

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Stamp(
        val captureEpoch: Long,
        val regionId: String,
        val screenGeneration: Long,
        val sourceIdentity: String,
        val frameWidth: Int,
        val frameHeight: Int,
        val crop: Bounds,
        val crc32: Long,
        val acquiredAtMs: Long,
        val sourceAtMs: Long,
    )

    private var accepted: Stamp? = null

    /** Every accepted native result replaces the reference; invalid metadata clears it. */
    fun recordAcceptedOcr(stamp: Stamp): Boolean {
        accepted = stamp.takeIf(::valid)
        return accepted != null
    }

    /**
     * A response can be confirmed without another native OCR only when a hardware image
     * arriving at/after that response has the same ROI pixels as the accepted OCR image.
     * Re-snapshotting an old retained hardware image after the response is insufficient.
     */
    fun canReuse(candidate: Stamp, responseAtMs: Long? = null, allowEmpty: Boolean = false): Boolean {
        val previous = accepted ?: return false
        if (!valid(candidate) || (responseAtMs != null && responseAtMs < 0)) return false
        // An identical known blank ROI is optional work-saving only. It can never confirm a
        // translation response, and the caller must explicitly enable blank-frame reuse.
        if (previous.sourceIdentity.isEmpty() && (!allowEmpty || responseAtMs != null)) return false
        if (candidate.captureEpoch != previous.captureEpoch || candidate.regionId != previous.regionId ||
            candidate.screenGeneration != previous.screenGeneration || candidate.sourceIdentity != previous.sourceIdentity ||
            candidate.frameWidth != previous.frameWidth || candidate.frameHeight != previous.frameHeight ||
            candidate.crop != previous.crop || candidate.crc32 != previous.crc32) return false
        if (candidate.acquiredAtMs < previous.acquiredAtMs || candidate.sourceAtMs < previous.sourceAtMs) return false
        // Measure from the original accepted native OCR frame, not the latest reuse check
        // or hardware arrival. Otherwise one false empty can suppress native OCR forever.
        if (previous.sourceIdentity.isEmpty() &&
            candidate.acquiredAtMs - previous.acquiredAtMs >= EMPTY_REUSE_MAX_AGE_MS) return false
        return responseAtMs == null || candidate.sourceAtMs >= responseAtMs
    }

    fun clear() { accepted = null }

    private fun valid(stamp: Stamp): Boolean =
        stamp.captureEpoch >= 0 && stamp.screenGeneration >= 0 && stamp.regionId.isNotEmpty() &&
            stamp.frameWidth > 0 && stamp.frameHeight > 0 &&
            stamp.crop.left >= 0 && stamp.crop.top >= 0 && stamp.crop.right <= stamp.frameWidth &&
            stamp.crop.bottom <= stamp.frameHeight && stamp.crop.right > stamp.crop.left && stamp.crop.bottom > stamp.crop.top &&
            stamp.crc32 in 0L..0xffff_ffffL && stamp.sourceAtMs >= 0 && stamp.sourceAtMs <= stamp.acquiredAtMs
}
