package com.gemini.ocr.batch

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic migration fixtures. Dummy output bytes intentionally cannot be encoded or decoded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BatchLegacyFingerprintTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private data class Fixture(val batch:BatchProject,val projects:List<VideoProject>,val merged:File)
    private fun fixture(mergeEpisodes: Boolean = false):Fixture {
        val store=VideoStore(context);val batchStore=BatchStore(context)
        val batch=batchStore.create("Legacy migration fixture",BatchKind.MOVIE_PARTS)
        val projects=List(2){index->
            val id=UUID.randomUUID().toString()
            val mp4=File(store.folder(id),"translated.mp4").apply{writeBytes(byteArrayOf(1,2,3,index.toByte()))}
            val source = if (mergeEpisodes) "content://batch-merge-fixture/document/source-$index.mp4" else "file:///unreadable-source-$index.mp4"
            store.save(VideoProject(id,"Legacy part $index",source,2000,640,360,
                sourceMode=VideoSourceMode.OCR,translationMode=VideoTranslationMode.GEMINI,
                cues=listOf(VideoCue("ocr-1",100,900,"你好","Xin chào",approved=true)),
                stage=VideoStage.COMPLETE,resumeStage=VideoStage.EXPORTING,scanMs=2000,
                exportedMp4=mp4.absolutePath))
        }
        val merged=File(batchStore.folder(batch.id),"merged-${UUID.randomUUID()}.mp4").apply{writeBytes(byteArrayOf(8,7,6,5))}
        val items=projects.map{BatchItem(it.id,it.title,episode="1",state="DONE")}
        val output=BatchOutput(if (mergeEpisodes) BatchPlan.ALL_EPISODES else "1",batch.title,merged.absolutePath,4000,oldFingerprint(projects))
        return Fixture(batchStore.save(batch.copy(items=items,outputs=listOf(output),state="REVIEW",
            delivery=BatchDelivery.MP4_ONLY, mergeEpisodes = mergeEpisodes)),projects,merged)
    }

    /** The explicit field order below is the archived 1.7.9 VideoProject/VideoCue.json contract. */
    private fun oldFingerprint(projects:List<VideoProject>):String {
        val projectKeys=listOf("schema","id","title","uri","durationMs","width","height","sourceMode","translationMode",
            "subtitleUri","subtitleName","trackId","roi","style","stage","resumeStage","progress","message","cues","scanMs","carry",
            "glossary","profile","model","elapsedMs","maxPssKiB","exportedMp4","cloudCalls","originalSourceUri","originalSubtitleUri",
            "extractionElapsedMs","translationElapsedMs","exportElapsedMs","ocrMetrics","visualSampleMs")
        val cueKeys=listOf("id","startMs","endMs","source","translation","approved","origin","model","error","localDraft","refinementState")
        val digest=MessageDigest.getInstance("SHA-256")
        for(project in projects){
            val current=project.json();val old=JSONObject()
            for(key in projectKeys)old.put(key,if(key=="cues")JSONArray(project.cues.map{cue->
                JSONObject().also{row->val value=cue.json();cueKeys.forEach{row.put(it,value.get(it))}}
            })else current.get(key))
            val payload=old.toString().toByteArray(Charsets.UTF_8)
            digest.update(payload.size.toString().toByteArray(Charsets.UTF_8));digest.update(':'.code.toByte());digest.update(payload)
            val file=File(project.exportedMp4)
            digest.update("|${file.length()}:${file.lastModified()}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }

    @Test fun legacyMergedOutputIsAcceptedAndRetainedOnResumeWithoutRenderingOrRewritingProjects()=runBlocking {
        SessionState.running.value=false
        val fixture=fixture();val store=VideoStore(context);val batchStore=BatchStore(context)
        val before=fixture.projects.map{store.load(it.id).json().toString()};val bytes=fixture.merged.readBytes()
        val jobs=BatchJobs(context,VideoJobs(context))
        assertEquals(fixture.batch.outputs,jobs.validatedOutputs(fixture.batch.id))
        jobs.start(fixture.batch.id);val done=CompletableDeferred<Unit>();jobs.execute(fixture.batch.id){done.complete(Unit)}
        withTimeout(15_000){done.await()}
        val result=batchStore.load(fixture.batch.id)
        assertEquals(result.message,"REVIEW",result.state)
        assertEquals(fixture.batch.outputs,result.outputs)
        assertArrayEquals(bytes,fixture.merged.readBytes())
        assertEquals(before,fixture.projects.map{store.load(it.id).json().toString()})
    }

    @Test fun legacyFallbackStillRejectsChangedSourceStyleOrOutputIdentityAndAnyStagedInput()=runBlocking {
        val fixture=fixture();val store=VideoStore(context);val jobs=BatchJobs(context,VideoJobs(context))
        val original=fixture.projects.first()
        suspend fun rejected(){assertThrows(IllegalArgumentException::class.java){runBlocking{jobs.validatedOutputs(fixture.batch.id)}}}
        store.save(original.copy(style=original.style.copy(position=.70f)));rejected()
        store.save(original.copy(cues=original.cues.map{it.copy(source="不要走")}));rejected()
        store.save(original.copy(pipeline=VideoPipelineCheckpoint().record(BatchPipelineStage.FINISH,"COMPLETE",1)));rejected()
        store.save(original)
        File(original.exportedMp4).appendBytes(byteArrayOf(9));rejected()
        assertTrue(fixture.merged.isFile)
    }

    @Test fun mergedOutputPickerCopiesTheFrozenResultAndRejectsStaleOutputOrSourceAlias() = runBlocking {
        val fixture = fixture(mergeEpisodes = true)
        val jobs = BatchJobs(context, VideoJobs(context))
        val output = fixture.batch.outputs.single()
        val destination = Uri.parse("content://batch-merge-fixture/document/copied.mp4")
        val saved = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(destination, saved)
        jobs.copyMergedOutput(fixture.batch.id, output.path, output.inputFingerprint, destination)
        assertArrayEquals(fixture.merged.readBytes(), saved.toByteArray())

        val alias = Uri.parse("content://batch-merge-fixture/tree/fixtures/document/source-0.mp4")
        val source = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(alias, source)
        val rejectedSource = runCatching { jobs.copyMergedOutput(fixture.batch.id, output.path, output.inputFingerprint, alias) }
        assertTrue(rejectedSource.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, source.size())

        val project = fixture.projects.first()
        VideoStore(context).save(project.copy(style = project.style.copy(position = .70f)))
        saved.reset()
        val rejectedStale = runCatching { jobs.copyMergedOutput(fixture.batch.id, output.path, output.inputFingerprint, destination) }
        assertTrue(rejectedStale.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, saved.size())
        assertTrue(fixture.merged.isFile)
    }
}
