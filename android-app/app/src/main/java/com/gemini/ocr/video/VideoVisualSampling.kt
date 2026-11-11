package com.gemini.ocr.video

/** Source-time sampling arithmetic only; this helper reads no media and sends no data. */
internal object VideoVisualSampling {
    const val INTERVAL_MS=250L
    private const val MAX_TIME_MS=24*60*60*1000L
    fun firstGridAtOrAfter(ms:Long,intervalMs:Long=INTERVAL_MS):Long {
        require(ms in 0..MAX_TIME_MS)
        require(intervalMs in setOf(250L,500L))
        return ((ms+intervalMs-1)/intervalMs)*intervalMs
    }
    fun firstGridStrictlyAfter(ms:Long,intervalMs:Long=INTERVAL_MS):Long {
        require(ms in 0..MAX_TIME_MS)
        require(intervalMs in setOf(250L,500L))
        return (ms/intervalMs+1)*intervalMs
    }
}
