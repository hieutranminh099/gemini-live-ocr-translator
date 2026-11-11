package com.gemini.ocr.batch

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPipelineCheckpoint
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchEpisodeExportTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun translated() = VideoProject(UUID.randomUUID().toString(), "Synthetic episode", "content://fixtures/source.mp4",
        2_000, 640, 360, scanMs = 2_000, stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
        translationMode = VideoTranslationMode.OFFLINE,
        cues = listOf(VideoCue("cue-1", 100, 950, "你好", "Xin chào.")),
        pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "COMPLETE", 2_000)
            .record(BatchPipelineStage.CLEAN, "COMPLETE", 1).record(BatchPipelineStage.TRANSLATE, "COMPLETE", 1))

    private fun destination(output: ByteArrayOutputStream = ByteArrayOutputStream()): Pair<Uri, ByteArrayOutputStream> {
        val uri = Uri.parse("content://batch-export-fixture/document/${UUID.randomUUID()}")
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        return uri to output
    }

    private fun createBatch(project: VideoProject, state: String): BatchProject {
        val batches = BatchStore(context)
        val batch = batches.create("Synthetic batch", BatchKind.SERIES)
        return batches.save(batch.copy(state = state,
            items = listOf(BatchItem(project.id, project.title),
                BatchItem(UUID.randomUUID().toString(), "Other episode", state = "FAILED"))))
    }

    @Test fun finishedTranslationIsExportableWhileAnotherEpisodeIsStillRunning() {
        val episode = translated()
        assertTrue(BatchEpisodeExport.canSave(episode, "other-active-video"))
        assertFalse(BatchEpisodeExport.canSave(episode, episode.id))
        assertFalse(BatchEpisodeExport.canSave(episode.copy(scanMs = 1_500, resumeStage = VideoStage.EXTRACTING), null))
        assertFalse(BatchEpisodeExport.canSave(episode.copy(progress = 95, pipeline = episode.pipeline
            .record(BatchPipelineStage.TRANSLATE, "RUNNING", 1)), null))
    }

    @Test fun optInPolishMustBeCompleteBeforeItsPendingRowsCanBeSaved() {
        val draft = translated().copy(offlinePolishPolicy = VideoPolishPolicy.ALL)
        assertFalse(BatchEpisodeExport.canSave(draft, null))
        assertTrue(BatchEpisodeExport.canSave(draft.copy(
            cues = draft.cues.map { it.copy(polishedVi = it.translation) }), null))
        val flagged = draft.copy(offlinePolishPolicy = VideoPolishPolicy.FLAGGED,
            cues = draft.cues.map { it.copy(qualityFlags = listOf("OCR_UNCERTAIN")) })
        assertFalse(BatchEpisodeExport.canSave(flagged, null))
        assertTrue(BatchEpisodeExport.canSave(flagged.copy(pipeline = flagged.pipeline
            .record(BatchPipelineStage.POLISH, "COMPLETE", 1)), null))
    }

    @Test fun translatedSrtUsesPersistedSnapshotEvenWhenBatchFailedElsewhere() = runBlocking {
        val store = VideoStore(context)
        val episode = store.save(translated())
        val batch = createBatch(episode, "FAILED")
        val (uri, output) = destination()
        val jobs = VideoJobs(context)

        BatchEpisodeExport.save(context, jobs, batch.id, episode.id, BatchEpisodeFile.SRT, uri)

        val srt = output.toString(Charsets.UTF_8.name())
        assertTrue(srt.contains("00:00:00,100 --> 00:00:00,950"))
        assertTrue(srt.contains("Xin chào."))
        assertFalse(srt.contains("你好"))

        store.save(episode.copy(pipeline = episode.pipeline.invalidateFrom(BatchPipelineStage.TRANSLATE)))
        val (staleUri, staleOutput) = destination()
        val stale = runCatching { BatchEpisodeExport.save(context, jobs, batch.id, episode.id, BatchEpisodeFile.SRT, staleUri) }
        assertTrue(stale.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, staleOutput.size())
    }

    @Test fun readyMp4CopiesOnlyOwnedCompleteOutputAndDoesNotOverwriteSource() = runBlocking {
        val store = VideoStore(context)
        val episode = translated()
        val owned = store.exportFile(episode.id, "mp4").apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
        store.save(episode.copy(exportedMp4 = owned.absolutePath))
        val batch = createBatch(episode, "RUNNING")
        val jobs = VideoJobs(context)
        val (uri, output) = destination()
        BatchEpisodeExport.save(context, jobs, batch.id, episode.id, BatchEpisodeFile.MP4, uri)
        assertArrayEquals(owned.readBytes(), output.toByteArray())

        val overwrittenSource = ByteArrayOutputStream()
        val originalUri = Uri.parse(episode.uri)
        shadowOf(context.contentResolver).registerOutputStream(originalUri, overwrittenSource)
        val sameDestination = runCatching {
            BatchEpisodeExport.save(context, jobs, batch.id, episode.id, BatchEpisodeFile.MP4, originalUri)
        }
        assertTrue(sameDestination.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, overwrittenSource.size())

        val unowned = File(context.cacheDir, "foreign-${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(8, 9)) }
        store.save(episode.copy(exportedMp4 = unowned.absolutePath))
        val (badUri, badOutput) = destination()
        val unsafe = runCatching { BatchEpisodeExport.save(context, jobs, batch.id, episode.id, BatchEpisodeFile.MP4, badUri) }
        assertTrue(unsafe.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, badOutput.size())
    }
}
