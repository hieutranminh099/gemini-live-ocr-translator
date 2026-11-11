package com.gemini.ocr.capture

/** Monotonic timestamps scoped to the source and request; never stores text or credentials. */
class FrameTimeline {
    data class Source(val regionId: String, val generation: Long, val frameId: Long,
                      val acquiredAt: Long, val captureMs: Long, val queueMs: Long,
                      val ocrMs: Long, val detectedAt: Long)
    data class Request(val token: SubtitlePipeline.RequestToken, val at: Long)
    data class Draw(val source: Source, val request: Request?, val receivedAt: Long?, val readyAt: Long) {
        fun fields(drawnAt: Long): Array<Pair<String, Any?>> = arrayOf(
            "source_frame_id" to source.frameId,
            "capture_ms" to source.captureMs, "ocr_queue_ms" to source.queueMs, "ocr_ms" to source.ocrMs,
            "pre_request_ms" to request?.let { it.at - source.acquiredAt - source.captureMs - source.queueMs - source.ocrMs },
            "observed_to_send_ms" to request?.let { it.at - source.acquiredAt },
            "stabilization_ms" to request?.let { it.at - source.detectedAt },
            "request_to_result_ms" to receivedAt?.let { it - checkNotNull(request).at },
            "result_to_ready_ms" to receivedAt?.let { readyAt - it },
            "received_to_draw_ms" to receivedAt?.let { drawnAt - it },
            "overlay_ms" to drawnAt - readyAt,
            "observed_to_draw_ms" to drawnAt - source.acquiredAt,
        )
    }

    private var source: Source? = null
    private var request: Request? = null
    private var receivedAt: Long? = null

    fun clear() { source = null; request = null; receivedAt = null }
    fun observed(value: Source) { source = value; request = null; receivedAt = null }
    fun requested(token: SubtitlePipeline.RequestToken, at: Long): Boolean {
        val current = source ?: return false
        if (token.regionId != current.regionId || token.screenGeneration != current.generation) return false
        request = Request(token, at); receivedAt = null
        return true
    }
    fun received(token: SubtitlePipeline.RequestToken, at: Long): Boolean {
        if (request?.token != token) return false
        receivedAt = at
        return true
    }
    fun drawing(at: Long): Draw? = source?.let { Draw(it, request, receivedAt, at) }
}
