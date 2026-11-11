package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SmartSourceBatchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun oldJsonKeepsRenderChoiceAndNewBatchDefaultsToSourceLibrary() = runBlocking {
        val json = BatchProject(UUID.randomUUID().toString(), "old", BatchKind.SERIES).json().apply { remove("delivery") }
        assertEquals(BatchDelivery.MP4_ONLY, BatchProject.from(json).delivery)
        val jobs = BatchJobs(context, VideoJobs(context))
        assertEquals(BatchDelivery.SOURCE_LIBRARY, jobs.create("new", BatchKind.SERIES).delivery)
    }
    @Test fun completedSourceTimelineDoesNotDispatchEncoderOrMergeAndPreservesOrder(): Unit = runBlocking {
        SessionState.running.value = false
        val store = VideoStore(context); val batchStore = BatchStore(context)
        val projects = (1..3).map { index ->
            val id = UUID.randomUUID().toString()
            store.save(VideoProject(id, "Part $index", "file:///not-opened-original.mp4", 4000, 1920, 1080,
                sourceMode=VideoSourceMode.SIDECAR, cues=listOf(VideoCue("$index",0,1000,"你好","Xin chào",true)),
                stage=VideoStage.REVIEW,scanMs=4000,resumeStage=VideoStage.TRANSLATING))
        }
        val batch = batchStore.save(BatchProject(UUID.randomUUID().toString(), "Source", BatchKind.MOVIE_PARTS,
            items=projects.map { BatchItem(it.id,it.title,episode="1") },delivery=BatchDelivery.SOURCE_LIBRARY))
        projects.forEach { File(store.folder(it.id),"batch-owner").writeText(batch.id) }
        val before = projects.map { store.load(it.id).json().toString() }
        val jobs = BatchJobs(context,VideoJobs(context)); val done=CompletableDeferred<Unit>()
        jobs.start(batch.id); jobs.execute(batch.id) { done.complete(Unit) }; withTimeout(10000) { done.await() }
        val saved=batchStore.load(batch.id)
        assertEquals("REVIEW",saved.state); assertTrue(saved.outputs.isEmpty()); assertTrue(saved.items.all { it.state=="DONE" })
        val inputs=jobs.validatedSources(batch.id)
        assertEquals(projects.map { it.id },inputs.map { it.project.id })
        assertEquals(before,projects.map { store.load(it.id).json().toString() })
        assertTrue(projects.all { store.load(it.id).exportedMp4.isEmpty() })
        val changed=store.load(projects.last().id)
        store.save(changed.copy(cues=changed.cues.map { it.copy(translation="",approved=false) }))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { jobs.validatedSources(batch.id) } }
    }
}
