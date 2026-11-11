package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class VideoOcrReuseTest {
    private fun frame(ms:Long, bytes:ByteArray=byteArrayOf(1,2,3,4))=VideoOcrFrame(ms,2,2,bytes)
    @Test fun exactUnchangedPixelsReuseButQuorumStillRequiresIndependentObservations() {
        val reuse=VideoOcrReuse();reuse.record(frame(0),"不是三个\n快走")
        assertNull(reuse.lookup(frame(50),true))
        assertEquals("不是三个\n快走",reuse.lookup(frame(200),false))
        assertNull(reuse.lookup(frame(250,byteArrayOf(1,2,3,5)),false))
        reuse.record(frame(250),null)
        assertNull(reuse.lookup(frame(300),false))
    }
    @Test fun blankCanBeReusedButCannotHideChangedTextOrDimensions() {
        val reuse=VideoOcrReuse();reuse.record(frame(0),"")
        assertEquals("",reuse.lookup(frame(200),false))
        assertNull(reuse.lookup(VideoOcrFrame(250,4,1,byteArrayOf(1,2,3,4)),false))
        assertNull(reuse.lookup(frame(250,byteArrayOf(1,2,4,4)),false))
    }
    @Test fun invalidRoiAndTruncatedFrameFailBeforeAnyBitmapOrApiCall() {
        for(roi in listOf(listOf(0f,0f,1.1f,1f),listOf(0f,Float.NaN,1f,1f),listOf(0f,0f,0f,1f),emptyList())) {
            assertThrows(IllegalArgumentException::class.java) { VideoLumaCrop.sample(0,ByteBuffer.wrap(ByteArray(16)),4,1,0,0,4,4,roi,0) }
        }
        assertThrows(IllegalArgumentException::class.java) { VideoLumaCrop.sample(0,ByteBuffer.wrap(ByteArray(15)),4,1,0,0,4,4,listOf(0f,0f,1f,1f),0) }
    }
    @Test fun missingFrameStalledDecoderAndInvalidCropAreDistinctFromApiResponseErrors() {
        for(code in listOf("FRAME_NULL","FRAME_DECODE_TIMEOUT","FRAME_INVALID","ROI_INVALID")) {
            assertEquals(code,VideoStagedPipeline.errorCode(IllegalStateException("$code: không đọc được ảnh")))
        }
    }
}
