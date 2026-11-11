package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoDecodeCoverageTest {
    @Test fun reorderedFinalFrameAndShortClipsAreAccepted(){
        VideoDecodeCoverage.requireComplete(13400,13433,13400)
        VideoDecodeCoverage.requireComplete(50,83,100)
    }
    @Test fun prematureInputOrOutputEosCannotCertifyACompleteSource(){
        for((input,output) in listOf(5800L to 5800L,13400L to 5800L,-1L to -1L)){
            val error=assertThrows(VideoDecodeException::class.java){VideoDecodeCoverage.requireComplete(input,output,13400)}
            assertTrue(error.message!!.startsWith("FRAME_DECODE_INCOMPLETE"))
        }
    }
}
