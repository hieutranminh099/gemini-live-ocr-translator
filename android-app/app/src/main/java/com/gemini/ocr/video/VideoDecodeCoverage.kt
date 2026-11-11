package com.gemini.ocr.video

/** An EOS callback alone cannot certify that the decoder reached the selected video's tail. */
internal object VideoDecodeCoverage {
    fun requireComplete(lastInputMs:Long,maxOutputMs:Long,videoDurationMs:Long) {
        require(videoDurationMs>0)
        if(!(lastInputMs>=0 && maxOutputMs>=0 && maxOutputMs>=lastInputMs-1_000 &&
            maxOutputMs>=videoDurationMs-2_000))
            throw VideoDecodeException("FRAME_DECODE_INCOMPLETE","drain",maxOutputMs.coerceAtLeast(0))
    }
}
