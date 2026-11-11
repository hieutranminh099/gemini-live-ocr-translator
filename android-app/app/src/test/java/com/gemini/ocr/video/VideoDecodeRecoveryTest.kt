package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoDecodeRecoveryTest {
    private fun failure(code:String="FRAME_CODEC_FAILED",retryable:Boolean=true)=VideoDecodeException(
        code=code,operation="dequeue_output",sourceMs=1200,retryable=retryable)

    @Test fun reopenUsesLatestAtomicCheckpointAndKeepsPrefixIdsPendingRawAndApprovedText():Unit=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=VideoStore(context)
        val approved=VideoCue("keep-user-id",0,800,"甲","Đã duyệt",approved=true,model="user-edited")
        var project=store.save(VideoProject(UUID.randomUUID().toString(),"Synthetic decoder recovery",
            "file:///unused.mp4",2000,640,360,sourceMode=VideoSourceMode.OCR,
            translationMode=VideoTranslationMode.GEMINI,cues=listOf(approved),scanMs=1001,
            resumeStage=VideoStage.EXTRACTING,carry=JSONObject().put("scanLast",1000).put("last",1000),
            glossary="Tên giữ nguyên",profile="manga"))
        val startingCheckpoints=mutableListOf<Long>()
        val retryCheckpoints=mutableListOf<Long>()
        val waits=mutableListOf<Long>()
        var scans=0
        val result=VideoDecodeRecovery.run(onRetry={_,_->
            val reopened=VideoStore(context).load(project.id)
            retryCheckpoints+=reopened.scanMs
            assertEquals(project.cues,reopened.cues)
            assertEquals(project.carry.toString(),reopened.carry.toString())
        },wait={waits+=it}){
            scans++
            // This is the same latest-project capture used by the worker, not its initial input.
            startingCheckpoints+=project.scanMs
            val timeline=VideoDraftTimeline(project.cues,project.carry)
            when(scans){
                1->{timeline.add(1100,"乙");timeline.add(1165,"乙");timeline.add(1200,"丙\n保留原文")}
                2->{
                    assertEquals("丙\n保留原文",project.carry.getJSONObject("draftRun").getString("raw"))
                    timeline.add(1265,"丙\n保留原文");timeline.add(1300,"丁")
                }
                else->{timeline.add(1365,"丁")}
            }
            if(scans<3){
                val at=if(scans==1)1201L else 1301L
                project=store.save(project.copy(scanMs=at,cues=timeline.cues.toList(),carry=timeline.snapshot()))
                throw failure(if(scans==1)"FRAME_CODEC_FAILED" else "FRAME_DECODE_TIMEOUT")
            }
            val rows=timeline.finish(1600)
            project=store.save(project.copy(cues=rows,scanMs=2000,carry=JSONObject()))
            rows
        }
        assertEquals(listOf(1001L,1201L,1301L),startingCheckpoints)
        assertEquals(listOf(1201L,1301L),retryCheckpoints)
        assertEquals(listOf(250L,500L),waits)
        assertEquals(listOf("甲","乙","丙\n保留原文","丁"),result.map{it.source})
        assertEquals(approved,result.first())
        assertEquals(4,result.map{it.id}.distinct().size)
        assertEquals("Tên giữ nguyên",project.glossary)
        assertEquals("manga",project.profile)
        assertEquals(VideoTranslationMode.GEMINI,project.translationMode)
        assertEquals(result,VideoStore(context).load(project.id).cues)
        VideoTimeline.validate(result,project.durationMs)
    }

    @Test fun repeatedDecoderFailureStopsAfterExactlyTwoReopens():Unit=runBlocking {
        val terminal=failure("FRAME_DECODE_TIMEOUT")
        val retries=mutableListOf<Int>();val waits=mutableListOf<Long>();var scans=0
        try {
            VideoDecodeRecovery.run(onRetry={attempt,error->retries+=attempt;assertSame(terminal,error)},
                wait={waits+=it}){scans++;throw terminal}
            fail("A stalled decoder must stop after the bounded reopen budget")
        }catch(actual:VideoDecodeException){assertSame(terminal,actual)}
        assertEquals(3,scans);assertEquals(listOf(1,2),retries);assertEquals(listOf(250L,500L),waits)
    }

    @Test fun unsupportedDecoderAndUnrelatedErrorsNeverReopen():Unit=runBlocking {
        for(terminal in listOf(failure(retryable=false),IOException("Synthetic disk write failure"),
            IllegalArgumentException("ROI_INVALID: synthetic validation"),
            IllegalStateException("Synthetic OCR state failure"),CancellationException("User paused"))){
            var scans=0;var retries=0;var waits=0
            try {
                VideoDecodeRecovery.run(onRetry={_,_->retries++},wait={waits++}){scans++;throw terminal}
                fail("Only retryable typed native decoder errors may reopen")
            }catch(actual:Exception){assertSame(terminal,actual)}
            assertEquals(1,scans);assertEquals(0,retries);assertEquals(0,waits)
        }
    }

    @Test fun recoveryCheckpointFailureEscapesWithoutAnotherNativeAttempt():Unit=runBlocking {
        val diskFailure=IOException("Synthetic checkpoint failure, not a decoder error")
        var scans=0;var callbacks=0;var waits=0
        try {
            VideoDecodeRecovery.run(onRetry={_,_->callbacks++;throw diskFailure},wait={waits++}){
                scans++;throw failure()
            }
            fail("A failed checkpoint must not replay source work")
        }catch(actual:IOException){assertSame(diskFailure,actual)}
        assertEquals(1,scans);assertEquals(1,callbacks);assertEquals(0,waits)
    }

    @Test fun cancellationDuringBackoffStopsBeforeAnotherScan():Unit=runBlocking {
        val waiting=CompletableDeferred<Unit>();var scans=0;var retries=0
        val worker=launch {
            VideoDecodeRecovery.run(onRetry={_,_->retries++},wait={waiting.complete(Unit);delay(Long.MAX_VALUE)}){
                scans++;throw failure()
            }
        }
        withTimeout(1000){waiting.await()}
        worker.cancelAndJoin()
        assertEquals(1,scans);assertEquals(1,retries)
    }
}
