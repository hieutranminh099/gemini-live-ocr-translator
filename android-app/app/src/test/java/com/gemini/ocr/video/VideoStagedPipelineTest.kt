package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoStagedPipelineTest {
    private val workerFailures=CopyOnWriteArrayList<Throwable>()
    @Before fun observeSyntheticWorkerFailures(){VideoJobs.workerFailureForTest={workerFailures.add(it)}}
    @After fun clearSyntheticWorkerObserver(){VideoJobs.workerFailureForTest=null}
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private fun cue(id:String="line",source:String="  你好  \n 世界  ")=VideoCue(id,100,900,source)
    private fun project(cues:List<VideoCue> = emptyList())=VideoProject(UUID.randomUUID().toString(),"Synthetic unit fixture",
        "file:///unused.mp4",2000,640,360,sourceMode=VideoSourceMode.SIDECAR,translationMode=VideoTranslationMode.GEMINI,
        cues=cues,scanMs=if(cues.isEmpty())0 else 2000,resumeStage=if(cues.isEmpty())VideoStage.EXTRACTING else VideoStage.TRANSLATING)
    @Test fun oldSchemaRoundTripsWithoutDiscardingTimelineAndNewCheckpointIsAtomic() {
        val old=project(listOf(cue())).json().apply{remove("pipeline");getJSONArray("cues").getJSONObject(0).remove("rawZh")}
        val loaded=VideoProject.from(old)
        assertEquals(0,loaded.pipeline.version);assertEquals("",loaded.cues.single().rawZh)
        val next=loaded.copy(pipeline=loaded.pipeline.record(BatchPipelineStage.EXTRACT,"COMPLETE",2000))
        val store=VideoStore(context);store.save(next)
        assertEquals("COMPLETE",store.load(next.id).pipeline.task(BatchPipelineStage.EXTRACT).status)
        assertEquals(loaded.cues,store.load(next.id).cues)
    }
    @Test fun cleaningPreservesIdsTimingOriginalAndDistinctSimilarDialogue() {
        val original=listOf(cue(),cue("other","你好啊").copy(startMs=1000,endMs=1400))
        val cleaned=VideoStagedPipeline.clean(original)
        assertEquals(2,cleaned.size);assertEquals(original.map{it.id},cleaned.map{it.id})
        assertEquals(original.map{it.startMs to it.endMs},cleaned.map{it.startMs to it.endMs})
        assertEquals(original[0].source,cleaned[0].rawZh);assertEquals("你好\n世界",cleaned[0].correctedZh)
        assertEquals("你好啊",cleaned[1].source);assertEquals(cleaned,VideoStagedPipeline.clean(cleaned))
        assertTrue(VideoStagedPipeline.sourceSrt(cleaned).contains("你好\n世界"))
    }
    @Test fun unavailableAsrDoesNotInventSpeechOrOverrideApprovedWork()=runBlocking {
        val approved=cue().copy(approved=true,translation="Đã duyệt",model="user-edited")
        assertFalse(UnavailableChineseAsr.available);assertNull(UnavailableChineseAsr.recognize(project(),0,100))
        assertEquals(approved.source,VideoStagedPipeline.clean(listOf(approved)).single().source)
        assertEquals("Đã duyệt",VideoStagedPipeline.clean(listOf(approved)).single().translation)
    }
    @Test fun completedStagesSurviveLaterFailureAndInvalidateOnlyDownstream() {
        var state=VideoPipelineCheckpoint()
        for(stage in BatchPipelineStage.entries.take(3))state=state.record(stage,"COMPLETE",1)
        state=state.record(BatchPipelineStage.POLISH,"RUNNING").record(BatchPipelineStage.POLISH,"NEEDS_REVIEW",1,"API_AUTH")
        val reloaded=VideoPipelineCheckpoint.from(state.json())
        assertEquals("COMPLETE",reloaded.task(BatchPipelineStage.EXTRACT).status)
        assertEquals("COMPLETE",reloaded.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals(1,reloaded.task(BatchPipelineStage.POLISH).attempts)
        val changed=reloaded.invalidateFrom(BatchPipelineStage.TRANSLATE)
        assertEquals("COMPLETE",changed.task(BatchPipelineStage.CLEAN).status)
        assertEquals("PENDING",changed.task(BatchPipelineStage.TRANSLATE).status)
    }
    @Test fun explicitChineseEpisodeNumeralsAndUncertainNumericOrderDoNotMerge() {
        assertEquals("12",BatchOrder.identify("影片第十二集.mp4").episode)
        assertEquals("102",BatchOrder.identify("第 一百零二 集.mp4").episode)
        assertNull(BatchOrder.identify("12.mp4").episode)
        val inputs=listOf(BatchItem("a","10.mp4"),BatchItem("b","2.mp4"))
        assertEquals(listOf("b","a"),BatchOrder.uncertainNumericOrder(inputs)!!.map{it.projectId})
    }
    private fun fakeKey(){context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
        .putString("standalone_api_key","synthetic-invalid-ciphertext")
        .putString("standalone_api_key_plain_fallback","fixture-no-network").commit()
        assertEquals("fixture-no-network",com.gemini.ocr.settings.AppSettings(context).effectiveStandaloneApiKey())
    }
    private fun diagnostic(batchId:String,projects:List<VideoProject>):String =
        "Worker errors: "+workerFailures.joinToString{it.stackTraceToString()}+BatchStore(context).load(batchId).let{b->"Batch ${b.state}: ${b.message}; items=${b.items.map{"${it.stage}/${it.stageStatus}: ${it.error}"}}"}+
            projects.joinToString{p->VideoStore(context).load(p.id).let{saved->
                " | ${saved.title} stage=${saved.stage} resume=${saved.resumeStage}: ${saved.message}; pipeline=${saved.pipeline.json()}"}}

    private fun response(ids:List<String>)=JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason","STOP")
        .put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",JSONObject().put("translations",
            JSONArray(ids.map{JSONObject().put("id",it).put("translation","Xin chào")})).toString())))))).toString()
    private fun sidecar(name:String):VideoProject {
        val store=VideoStore(context);val p=project()
        val file=File(store.folder(p.id),"test.srt").apply{writeText("1\n00:00:00,100 --> 00:00:00,900\n你好\n")}
        // Android file:// semantics do not round-trip Windows drive paths under Robolectric.
        // Exercise the same document-provider route as the product, with a fresh stream per read.
        val uri=android.net.Uri.parse("content://staged-fixture/${p.id}/test.srt")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri){file.inputStream()}
        val saved=store.save(p.copy(title=name,subtitleUri=uri.toString(),subtitleName="test.srt"))
        assertEquals("你好",VideoSubtitles.readSidecar(context,saved).single().source)
        return saved
    }
    @Test fun firstSourceIsReadyBeforeTranslationAndRestartDoesNotRepeatCompletedStages()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val videoStore=VideoStore(context);val batchStore=BatchStore(context)
        val projects=listOf(sidecar("EP02"),sidecar("EP01"))
        val batch=batchStore.create("Synthetic batch",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=projects.mapIndexed{i,p->BatchItem(p.id,p.title,episode=(i+1).toString())},delivery=BatchDelivery.SOURCE_LIBRARY))}
        var requests=0
        VideoTranslationClient.overrideResponseForTest={b->
            assertTrue(VideoStagedPipeline.extracted(videoStore.load(projects.first().id)))
            assertEquals("COMPLETE", videoStore.load(projects.first().id).pipeline.task(BatchPipelineStage.CLEAN).status)
            requests++;response(b.cueIds)
        }
        try {
            val videos=VideoJobs(context);val jobs=BatchJobs(context,videos)
            suspend fun run(){jobs.start(batch.id);val done=CompletableDeferred<Unit>();jobs.execute(batch.id){done.complete(Unit)};withTimeout(15_000){done.await()}}
            run();assertEquals(diagnostic(batch.id,projects),"REVIEW",batchStore.load(batch.id).state);assertEquals(diagnostic(batch.id,projects),2,requests)
            val checkpoints=projects.map{videoStore.load(it.id).pipeline}
            run();assertEquals(2,requests)
            assertEquals(checkpoints,projects.map{videoStore.load(it.id).pipeline})
            assertTrue(projects.all{File(videoStore.folder(it.id),"source.zh.srt").readText().contains("你好")})
            assertTrue(projects.all{videoStore.load(it.id).cues.single().draftVi=="Xin chào"})
        } finally{VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun authenticationStopsSharedApiQueueAfterExtractionWithoutDiscardingAnySource()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val videoStore=VideoStore(context);val batchStore=BatchStore(context);val projects=listOf(sidecar("1"),sidecar("2"))
        val batch=batchStore.create("Auth fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=projects.map{p->BatchItem(p.id,p.title)},delivery=BatchDelivery.SOURCE_LIBRARY))}
        var requests=0;VideoTranslationClient.overrideResponseForTest={requests++;throw VideoApiException("API_AUTH",detail="Không có quyền")}
        try {
            val jobs=BatchJobs(context,VideoJobs(context));jobs.start(batch.id);val done=CompletableDeferred<Unit>()
            jobs.execute(batch.id){done.complete(Unit)};withTimeout(15_000){done.await()}
            assertEquals(diagnostic(batch.id,projects),1,requests);assertEquals(diagnostic(batch.id,projects),"PAUSED",batchStore.load(batch.id).state)
            assertTrue(VideoStagedPipeline.extracted(videoStore.load(projects.first().id)))
            assertEquals("API_AUTH",videoStore.load(projects.first().id).pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
            assertTrue(videoStore.load(projects.last().id).cues.all{it.translation.isBlank()})
        } finally{VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun perVideoRoiOverrideSurvivesBatchStartAfterSharedRevisionWasApplied()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val store=VideoStore(context);val batchStore=BatchStore(context);val p=sidecar("ROI fixture")
        val batch=batchStore.create("ROI fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=listOf(BatchItem(p.id,p.title)),delivery=BatchDelivery.SOURCE_LIBRARY))}
        val videos=VideoJobs(context);val jobs=BatchJobs(context,videos)
        jobs.applySharedRoi(batch.id,listOf(.1f,.7f,.9f,.95f))
        val custom=listOf(.2f,.6f,.8f,.9f)
        videos.save(store.load(p.id).copy(roi=custom))
        // The fixture uses a text sidecar. Shared OCR regions do not change its revision.
        assertEquals(0,store.load(p.id).pipeline.sharedRoiRevision)
        VideoTranslationClient.overrideResponseForTest={b->response(b.cueIds)}
        try {
            jobs.start(batch.id);val done=CompletableDeferred<Unit>();jobs.execute(batch.id){done.complete(Unit)}
            withTimeout(15_000){done.await()}
            assertEquals(custom,store.load(p.id).roi)
            assertEquals(diagnostic(batch.id,listOf(p)),"REVIEW",batchStore.load(batch.id).state)
        }finally{VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun exhaustedTypedRateLimitStopsAllVideosAndPersistsCooldown()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val store=VideoStore(context);val batchStore=BatchStore(context)
        val projects=listOf(sidecar("Rate limit first"),sidecar("Rate limit next"))
        val batch=batchStore.create("Rate limit fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=projects.map{p->BatchItem(p.id,p.title)},delivery=BatchDelivery.SOURCE_LIBRARY))}
        var requests=0
        // Simulate the typed failure after the client's bounded retries have been exhausted.
        // Retry count/backoff themselves are exercised by VideoApiRecoveryTest.
        VideoTranslationClient.overrideResponseForTest={
            requests++
            throw VideoApiException("API_RATE_LIMIT",transient=true,
                detail="Dịch vụ đang giới hạn lượt gọi (429). Tiến độ đã lưu.")
        }
        val before=System.currentTimeMillis()
        val waiting = CompletableDeferred<Long>()
        val jobs = BatchJobs(context, VideoJobs(context), waitBeforeRetry = { millis ->
            waiting.complete(millis)
            kotlinx.coroutines.awaitCancellation()
        })
        try {
            jobs.start(batch.id)
            val done=CompletableDeferred<Unit>();jobs.execute(batch.id){done.complete(Unit)}
            assertTrue(withTimeout(15_000) { waiting.await() } >= 60_000L)
            assertEquals("RETRY_WAIT", batchStore.load(batch.id).state)
            assertEquals(1, requests)
            jobs.pause()
            withTimeout(15_000){done.await()}
            val saved=batchStore.load(batch.id)
            assertEquals(diagnostic(batch.id,projects),1,requests);assertEquals(diagnostic(batch.id,projects),"PAUSED",saved.state)
            assertTrue(saved.retryAfterMs>=before+61_000L)
            assertEquals("API_RATE_LIMIT",store.load(projects.first().id).pipeline.task(BatchPipelineStage.TRANSLATE).errorCode)
            assertEquals(0,store.load(projects.last().id).cloudCalls)
            assertTrue(VideoStagedPipeline.extracted(store.load(projects.first().id)))
            assertEquals("API_RATE_LIMIT",VideoStagedPipeline.errorCode(VideoApiException("API_RATE_LIMIT")))
        }finally{jobs.cancel();VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun pauseDuringTranslationKeepsExtractionAndCleaningCheckpointsComplete()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val store=VideoStore(context);val batchStore=BatchStore(context);val p=sidecar("Pause fixture")
        val batch=batchStore.create("Pause fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=listOf(BatchItem(p.id,p.title)),delivery=BatchDelivery.SOURCE_LIBRARY))}
        val entered=CompletableDeferred<Unit>();val never=CompletableDeferred<Unit>()
        VideoTranslationClient.overrideResponseForTest={entered.complete(Unit);never.await();error("unreachable")}
        try {
            val jobs=BatchJobs(context,VideoJobs(context));jobs.start(batch.id);val done=CompletableDeferred<Unit>()
            jobs.execute(batch.id){done.complete(Unit)}
            val reached=withTimeout(15_000){select<Boolean>{entered.onAwait{true};done.onAwait{false}}}
            assertTrue(diagnostic(batch.id,listOf(p)),reached);jobs.pause()
            withTimeout(15_000){done.await()}
            val saved=store.load(p.id)
            assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.EXTRACT).status)
            assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.CLEAN).status)
            assertEquals("PAUSED",saved.pipeline.task(BatchPipelineStage.TRANSLATE).status)
            assertTrue(saved.cues.isNotEmpty());assertEquals("PAUSED",batchStore.load(batch.id).state)
        }finally{VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun restartingPausedTranslationSkipsCompletedExtractionAndCleaningWithoutReopeningSource()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val store=VideoStore(context);val batchStore=BatchStore(context)
        val extracted=VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT,"COMPLETE",2000)
            .record(BatchPipelineStage.CLEAN,"COMPLETE",1)
            .record(BatchPipelineStage.TRANSLATE,"PAUSED",0)
        // No readable video or sidecar is supplied: completed extraction must never reopen it.
        val p=store.save(project(listOf(cue(source="你好").copy(rawZh="你好",correctedZh="你好")))
            .copy(stage=VideoStage.PAUSED,resumeStage=VideoStage.TRANSLATING,pipeline=extracted))
        val batch=batchStore.create("Resume fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=listOf(BatchItem(p.id,p.title)),delivery=BatchDelivery.SOURCE_LIBRARY))}
        var calls=0
        VideoTranslationClient.overrideResponseForTest={b->calls++;response(b.cueIds)}
        try {
            val jobs=BatchJobs(context,VideoJobs(context));jobs.start(batch.id)
            val done=CompletableDeferred<Unit>();jobs.execute(batch.id){done.complete(Unit)}
            withTimeout(15_000){done.await()}
            val saved=store.load(p.id)
            assertEquals(diagnostic(batch.id,listOf(p)),"REVIEW",batchStore.load(batch.id).state)
            assertEquals(1,calls)
            assertEquals(extracted.task(BatchPipelineStage.EXTRACT),saved.pipeline.task(BatchPipelineStage.EXTRACT))
            assertEquals(extracted.task(BatchPipelineStage.CLEAN),saved.pipeline.task(BatchPipelineStage.CLEAN))
            assertEquals("COMPLETE",saved.pipeline.task(BatchPipelineStage.TRANSLATE).status)
            assertEquals(p.cues.map{Triple(it.id,it.startMs,it.endMs)},saved.cues.map{Triple(it.id,it.startMs,it.endMs)})
            assertEquals("Xin chào",saved.cues.single().draftVi)
            assertTrue(workerFailures.isEmpty())
        }finally{VideoTranslationClient.overrideResponseForTest=null}
    }
    @Test fun restartWaitsForSavedRetryDeadlineAndPauseKeepsItWithoutAnyEarlyApiCall()=runBlocking {
        SessionState.running.value=false;fakeKey()
        val store=VideoStore(context);val batchStore=BatchStore(context)
        val deadline=System.currentTimeMillis()+60_000L
        val waiting=VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT,"COMPLETE",2000)
            .record(BatchPipelineStage.CLEAN,"COMPLETE",1)
            .record(BatchPipelineStage.TRANSLATE,"RETRY_WAIT",0,"API_RATE_LIMIT",deadline)
        val p=store.save(project(listOf(cue(source="你好").copy(rawZh="你好",correctedZh="你好")))
            .copy(stage=VideoStage.PAUSED,resumeStage=VideoStage.TRANSLATING,pipeline=waiting))
        val batch=batchStore.create("Saved retry fixture",BatchKind.SERIES).let{batchStore.save(it.copy(
            items=listOf(BatchItem(p.id,p.title)),delivery=BatchDelivery.SOURCE_LIBRARY,retryAfterMs=0))}
        var calls=0;VideoTranslationClient.overrideResponseForTest={b->calls++;response(b.cueIds)}
        val jobs=BatchJobs(context,VideoJobs(context));val done=CompletableDeferred<Unit>()
        try {
            jobs.start(batch.id);jobs.execute(batch.id){done.complete(Unit)}
            withTimeout(5_000){while(batchStore.load(batch.id).currentStage!=BatchPipelineStage.TRANSLATE && !done.isCompleted)delay(10)}
            assertFalse(diagnostic(batch.id,listOf(p)),done.isCompleted)
            // Let a prematurely cleared deadline reach the test provider, without waiting a minute.
            delay(150)
            assertEquals(diagnostic(batch.id,listOf(p)),0,calls)
            assertEquals(deadline,store.load(p.id).pipeline.task(BatchPipelineStage.TRANSLATE).nextRetryAt)
            jobs.pause();withTimeout(5_000){done.await()}
            val saved=store.load(p.id).pipeline.task(BatchPipelineStage.TRANSLATE)
            assertEquals("RETRY_WAIT",saved.status);assertEquals(deadline,saved.nextRetryAt)
            assertEquals(0,calls);assertEquals("PAUSED",batchStore.load(batch.id).state)
        }finally{jobs.pause();VideoTranslationClient.overrideResponseForTest=null}
    }
}
