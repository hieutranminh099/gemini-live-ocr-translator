package com.gemini.ocr.batch

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BatchHybridStartTest {
    @Test fun changingFilmContextReopensOnlyUnapprovedHybridTranslationsAndKeepsTheirPreviousText()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val videoStore=VideoStore(context);val store=BatchStore(context)
        val approved=VideoCue("approved",0,800,"你好","Bản đã duyệt",approved=true,refinementState="done")
        val draft=VideoCue("draft",1000,1800,"再见","Bản nháp cũ",refinementState="done",polishedVi="Bản nháp cũ")
        var pipeline=VideoPipelineCheckpoint()
        BatchPipelineStage.entries.forEach{pipeline=pipeline.record(it,"COMPLETE",2000)}
        val p=videoStore.save(VideoProject(UUID.randomUUID().toString(),"Synthetic context","file:///unused.mp4",2000,1280,720,
            translationMode=VideoTranslationMode.HYBRID,cues=listOf(approved,draft),scanMs=2000,pipeline=pipeline))
        val original=store.create("Synthetic context batch",BatchKind.SERIES)
        val b=store.save(original.copy(items=listOf(BatchItem(p.id,p.title)),translationMode=VideoTranslationMode.HYBRID))
        val changed=BatchJobs(context,VideoJobs(context)).setContextProfile(b.id,"A gọi B là chị")
        val saved=videoStore.load(p.id)
        assertEquals(approved,saved.cues.first());assertEquals(draft.translation,saved.cues.last().translation)
        assertEquals("pending",saved.cues.last().refinementState);assertEquals("",saved.cues.last().polishedVi)
        assertEquals("PENDING",saved.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.EXTRACT).status)
        assertEquals("A gọi B là chị",saved.pipeline.contextProfile);assertEquals(changed.contextRevision,saved.pipeline.contextRevision)
        assertEquals(listOf("draft"),VideoFullDialoguePlan.next(saved.cues,saved.durationMs,saved.pipeline.contextProfile)!!.cueIds)
    }
    @Test fun savedCloudCooldownAllowsLocalExtractionButStillBlocksTranslationWithoutResettingDeadline()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        SessionState.running.value=false
        val source="1\n00:00:00,000 --> 00:00:00,900\n你好\n"
        val uri=Uri.parse("content://hybrid-batch/${UUID.randomUUID()}/source.srt")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri){ByteArrayInputStream(source.toByteArray())}
        val videoStore=VideoStore(context)
        val p=videoStore.save(VideoProject(UUID.randomUUID().toString(),"Synthetic source","file:///unused.mp4",2000,1280,720,
            translationMode=VideoTranslationMode.HYBRID,sourceMode=VideoSourceMode.SIDECAR,
            subtitleUri=uri.toString(),subtitleName="source.srt"))
        val store=BatchStore(context)
        val original=store.create("Synthetic hybrid batch",BatchKind.SERIES)
        val deadline=System.currentTimeMillis()+120_000
        val b=store.save(original.copy(items=listOf(BatchItem(p.id,p.title)),translationMode=VideoTranslationMode.HYBRID,
            sourceMode=VideoSourceMode.SIDECAR,retryAfterMs=deadline))
        File(videoStore.folder(p.id),"batch-owner").writeText(b.id)
        val videos=VideoJobs(context);val jobs=BatchJobs(context,videos)
        val oldNetwork=StandaloneTranslator.beforeNetworkCallForTest
        val oldResponse=VideoTranslationClient.overrideResponseForTest
        var calls=0
        try {
            StandaloneTranslator.beforeNetworkCallForTest={calls++;error("Quota must be respected")}
            VideoTranslationClient.overrideResponseForTest={calls++;error("Local stage must not dispatch Gemini")}
            jobs.start(b.id)
            val done=CompletableDeferred<Unit>();jobs.execute(b.id){done.complete(Unit)}
            withTimeout(15_000){done.await()}
            val saved=videoStore.load(p.id);val paused=store.load(b.id)
            assertEquals(2000L,saved.scanMs);assertEquals(1,saved.cues.size);assertEquals(0,saved.cloudCalls)
            assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.EXTRACT).status)
            assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.CLEAN).status)
            assertEquals("PAUSED",paused.state);assertEquals(deadline,paused.retryAfterMs)
            assertEquals(BatchPipelineStage.TRANSLATE,paused.currentStage);assertEquals(0,calls)
            assertTrue(File(videoStore.folder(p.id),"source.zh.srt").isFile)
            assertNull(jobs.busy.value);assertNull(videos.batchBusy.value)
            assertThrows(IllegalStateException::class.java){jobs.start(b.id)}
            assertEquals(deadline,store.load(b.id).retryAfterMs)
        }finally{
            StandaloneTranslator.beforeNetworkCallForTest=oldNetwork
            VideoTranslationClient.overrideResponseForTest=oldResponse
            jobs.cancel()
        }
    }
}
