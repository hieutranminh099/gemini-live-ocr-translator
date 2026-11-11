package com.gemini.ocr.video

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoDecodeFailureTest {
    @Test fun emptyNativeStateFailureRetainsOperationAndNeverCopiesPrivateMessage(){
        for(message in listOf(null,"content://private/video.mp4 apiKey=secret subtitle=秘密")){
            val error=assertThrows(VideoDecodeException::class.java){
                VideoCodecBoundary.call("dequeue_output",429_000,"OMX.google.h264.decoder"){throw IllegalStateException(message)}
            }
            assertEquals("FRAME_CODEC_STATE",error.code);assertEquals("dequeue_output",error.operation)
            assertEquals(429_000L,error.sourceMs);assertTrue(error.retryable)
            assertTrue(error.message!!.contains("dequeue_output"));assertTrue(error.message!!.contains("OMX.google.h264.decoder"))
            assertFalse(error.message!!.contains("private"));assertFalse(error.message!!.contains("secret"));assertNull(error.cause)
        }
    }
    @Test fun cancellationAndUnrelatedInputOrStorageErrorsAreNeverNativeStateFailures(){
        for(original in listOf(CancellationException("pause"),IOException("storage failed"),IllegalArgumentException("ROI_INVALID"))){
            val actual=assertThrows(original.javaClass){VideoCodecBoundary.call("read_frame",100){throw original}}
            assertSame(original,actual)
        }
    }
    @Test fun secondaryOutputReleaseFailureCannotReplacePrimaryErrorOrCancellation(){
        for(original in listOf(IOException("FRAME_INVALID"),CancellationException("pause"))){
            var released=false
            val actual=assertThrows(original.javaClass){VideoCodecBoundary.withRelease(
                read={throw original},release={released=true;throw IllegalStateException()})}
            assertSame(original,actual);assertTrue(released)
        }
        val releaseFailure=IllegalStateException()
        assertSame(releaseFailure,assertThrows(IllegalStateException::class.java){
            VideoCodecBoundary.withRelease(read={42},release={throw releaseFailure})})
    }
    @Test fun allCleanupActionsRunAndCannotReplaceACompletedResult(){
        val calls=mutableListOf<Int>()
        val failed=VideoCodecBoundary.releaseAll({calls+=1;throw IllegalStateException()},
            {calls+=2;throw IOException("release")},{calls+=3})
        assertEquals(listOf(1,2,3),calls);assertEquals(2,failed)
    }
    @Test fun watchdogExcludesLiveGateAndOwnedOcrWorkButBoundsActualDecoderIdle(){
        var time=0L;val guard=VideoDecodeWatchdog{time}
        time=90_000;guard.excludeWait(90_000);guard.check(100)
        time+=29_999;guard.check(200)
        time++
        val error=assertThrows(VideoDecodeException::class.java){guard.check(200)}
        assertEquals("FRAME_DECODE_TIMEOUT",error.code);assertEquals(200L,error.sourceMs)
        guard.progress();time+=100_000;guard.progress() // ROI/OCR is not decoder idle.
        guard.check(250)
    }
    @Test fun diagnosticMetadataRejectsUriOrContentInsteadOfPersistingIt(){
        val error=VideoDecodeException("FRAME_CODEC_ERROR","read_frame",500,true,-1100,
            "content://private/secret","codec/private")
        assertTrue(error.message!!.contains("-1100"));assertFalse(error.message!!.contains("private"))
        assertFalse(error.message!!.contains("content://"))
    }
}
