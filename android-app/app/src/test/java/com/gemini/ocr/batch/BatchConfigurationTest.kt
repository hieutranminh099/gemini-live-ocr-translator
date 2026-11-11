package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPipelineCheckpoint
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchConfigurationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val approved = VideoCue("approved", 100, 900, "你好", "Chào bạn.", approved = true)

    private fun fixture(source: VideoSourceMode) = VideoProject(
        UUID.randomUUID().toString(), "Synthetic fixture", "file:///unused.mp4", 2_000, 640, 360,
        sourceMode = source, translationMode = VideoTranslationMode.GEMINI,
        trackId = if (source == VideoSourceMode.EMBEDDED) 4 else -1,
        subtitleUri = if (source == VideoSourceMode.SIDECAR) "content://batch-fixture/subtitles.srt" else ""
    )

    private fun completedSource() = VideoPipelineCheckpoint()
        .record(BatchPipelineStage.EXTRACT, "COMPLETE", 2_000)
        .record(BatchPipelineStage.CLEAN, "COMPLETE", 2)

    @Test fun changingTranslationPreservesAutomaticDetectionTracksAndTextSources() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val draft = VideoCue("draft", 1_000, 1_900, "再见", "Hẹn gặp lại.")
        val members = listOf(VideoSourceMode.EMBEDDED, VideoSourceMode.SIDECAR).map { source ->
            store.save(fixture(source).copy(cues = listOf(approved, draft), scanMs = 2_000,
                pipeline = completedSource(), resumeStage = VideoStage.TRANSLATING))
        }
        val batch = batches.save(jobs.create("Series", BatchKind.SERIES)
            .copy(items = members.map { BatchItem(it.id, it.title) }))

        val changed = jobs.configureTranslation(batch.id, VideoTranslationMode.OFFLINE)
        assertTrue(changed.detectNewSources)
        for (before in members) {
            val after = store.load(before.id)
            assertEquals(VideoTranslationMode.OFFLINE, after.translationMode)
            assertEquals(before.sourceMode, after.sourceMode)
            assertEquals(before.trackId, after.trackId)
            assertEquals(before.subtitleUri, after.subtitleUri)
            assertEquals(before.scanMs, after.scanMs)
            assertEquals("COMPLETE", after.pipeline.task(BatchPipelineStage.EXTRACT).status)
            assertEquals(approved, after.cues.first())
            assertEquals(draft.copy(translation = ""), after.cues.last())
        }
    }

    @Test fun applyingSharedRoiLeavesCompletedTextTracksUntouchedAndReconcilesNewOcrInputs() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val ocr = store.save(fixture(VideoSourceMode.OCR))
        val text = store.save(fixture(VideoSourceMode.SIDECAR).copy(cues = listOf(approved),
            scanMs = 2_000, pipeline = completedSource(), resumeStage = VideoStage.TRANSLATING))
        val textItem = BatchItem(text.id, text.title, state = "DONE", stage = BatchPipelineStage.FINISH, stageStatus = "COMPLETE")
        val batch = batches.save(jobs.create("Series", BatchKind.SERIES).copy(
            items = listOf(BatchItem(ocr.id, ocr.title), textItem)))

        val roi = listOf(.1f, .7f, .9f, .95f)
        val saved = jobs.applySharedRoi(batch.id, roi)
        assertEquals(text.json().toString(), store.load(text.id).json().toString())
        assertEquals(textItem, saved.items.last())
        assertEquals(roi, store.load(ocr.id).roi)
        assertEquals(saved.sharedRoiRevision, store.load(ocr.id).pipeline.sharedRoiRevision)
        assertEquals(roi, BatchRoiValidation.withSharedRoi(fixture(VideoSourceMode.OCR), saved).roi)
        assertEquals(text, BatchRoiValidation.withSharedRoi(text, saved))
    }

    @Test fun offlinePolishIsOptInAndCanBeDisabledWithoutLosingDraftOrCooldown() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val draft = VideoCue("draft", 1_000, 1_900, "再见", "Hẹn gặp lại.", localDraft = "Hẹn gặp lại.")
        val pipeline = completedSource().record(BatchPipelineStage.TRANSLATE, "COMPLETE", 2)
            .record(BatchPipelineStage.POLISH, "COMPLETE", 2)
        val project = store.save(fixture(VideoSourceMode.SIDECAR).copy(
            translationMode = VideoTranslationMode.OFFLINE, scanMs = 2_000, pipeline = pipeline, cues = listOf(approved, draft)))
        val deadline = System.currentTimeMillis() + 60_000
        val batch = batches.save(jobs.create("Series", BatchKind.SERIES).copy(
            items = listOf(BatchItem(project.id, project.title)), translationMode = VideoTranslationMode.OFFLINE, retryAfterMs = deadline))
        assertEquals(VideoPolishPolicy.NONE, BatchProject.from(batch.json()).offlinePolishPolicy)
        assertEquals(VideoPolishPolicy.NONE, BatchProject.from(batch.json().put("offlinePolishPolicy", "UNKNOWN")).offlinePolishPolicy)

        jobs.setOfflinePolishPolicy(batch.id, VideoPolishPolicy.FLAGGED)
        val enabled = store.load(project.id)
        assertEquals(VideoPolishPolicy.FLAGGED, batches.load(batch.id).offlinePolishPolicy)
        assertEquals(VideoPolishPolicy.FLAGGED, enabled.offlinePolishPolicy)
        assertEquals("PENDING", enabled.pipeline.task(BatchPipelineStage.POLISH).status)
        assertEquals("COMPLETE", enabled.pipeline.task(BatchPipelineStage.TRANSLATE).status)
        assertEquals(listOf(approved, draft), enabled.cues)

        val disabled = jobs.setOfflinePolishPolicy(batch.id, VideoPolishPolicy.NONE)
        assertEquals(deadline, disabled.retryAfterMs)
        assertEquals(VideoPolishPolicy.NONE, store.load(project.id).offlinePolishPolicy)
        assertEquals(listOf(approved, draft), store.load(project.id).cues)
    }

    @Test fun savedOcrCheckpointAvoidsNewSamplesOnlyForTheSameUsableRegion() {
        val partial = fixture(VideoSourceMode.OCR).copy(scanMs = 500,
            pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "PAUSED", 500))
        val batch = BatchProject("fixture", "Series", BatchKind.SERIES,
            sharedRoi = listOf(.1f, .7f, .9f, .95f), sharedRoiRevision = 1)
        assertTrue(BatchRoiValidation.hasSavedOcrProgress(partial))
        assertFalse(BatchRoiValidation.hasSavedOcrProgress(partial.copy(
            pipeline = partial.pipeline.record(BatchPipelineStage.EXTRACT, "NEEDS_REVIEW", 500, errorCode = "ROI_INVALID"))))
        assertFalse(BatchRoiValidation.hasSavedOcrProgress(BatchRoiValidation.withSharedRoi(partial, batch)))
    }

    @Test fun newBatchesDefaultToAutomaticButLegacyMissingSourceKeepsOcr() = runBlocking {
        val jobs = BatchJobs(context, VideoJobs(context))
        val batch = jobs.create("New source policy", BatchKind.SERIES)
        assertEquals(VideoSourceMode.AUTO, batch.sourceMode)
        assertTrue(batch.detectNewSources)
        assertEquals(VideoSourceMode.AUTO, BatchProject.from(batch.json()).sourceMode)
        val legacy = batch.json().also { it.remove("sourceMode") }
        assertEquals(VideoSourceMode.OCR, BatchProject.from(legacy).sourceMode)
    }

    @Test fun translationChangesPreserveManualSpeechSourceAndRecognitionCheckpoint() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val project = store.save(fixture(VideoSourceMode.ASR).copy(cues = listOf(approved), scanMs = 2_000,
            resumeStage = VideoStage.TRANSLATING, pipeline = completedSource(),
            speech = JSONObject().put("resolvedSource", "ASR").put("sourceSha", "fixture-fingerprint")
                .put("modelFingerprint", "fixture-model").put("checkpointMs", 2_000).put("complete", true)))
        val batch = batches.save(jobs.create("Speech source", BatchKind.SERIES).copy(items = listOf(BatchItem(project.id, project.title))))
        jobs.configureSource(batch.id, VideoSourceMode.ASR)
        val changed = jobs.configureTranslation(batch.id, VideoTranslationMode.OFFLINE)
        val after = store.load(project.id)
        assertFalse(changed.detectNewSources)
        assertEquals(VideoSourceMode.ASR, after.sourceMode)
        assertEquals(project.speech.toString(), after.speech.toString())
        assertEquals(project.cues, after.cues)
        assertEquals("COMPLETE", after.pipeline.task(BatchPipelineStage.EXTRACT).status)
    }

    @Test fun sharedRoiUpdatesAutomaticInputsButNeverResetsSpeechInputs() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val automatic = store.save(fixture(VideoSourceMode.AUTO))
        val speech = store.save(fixture(VideoSourceMode.ASR).copy(cues = listOf(approved), scanMs = 2_000,
            pipeline = completedSource(), speech = JSONObject().put("checkpointMs", 2_000)))
        val batch = batches.save(jobs.create("Mixed source", BatchKind.SERIES).copy(
            items = listOf(BatchItem(automatic.id, automatic.title), BatchItem(speech.id, speech.title))))
        val roi = listOf(.1f, .7f, .9f, .95f)
        jobs.applySharedRoi(batch.id, roi)
        assertEquals(roi, store.load(automatic.id).roi)
        assertEquals(speech.json().toString(), store.load(speech.id).json().toString())
        assertFalse(BatchRoiValidation.hasSavedOcrProgress(speech))
    }

    @Test fun sourceChangeCannotPartiallyInvalidateBatchContainingApprovedText() = runBlocking {
        val store = VideoStore(context)
        val batches = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val project = store.save(fixture(VideoSourceMode.OCR).copy(cues = listOf(approved), scanMs = 2_000,
            pipeline = completedSource()))
        val batch = batches.save(jobs.create("Approved source", BatchKind.SERIES).copy(items = listOf(BatchItem(project.id, project.title))))
        val beforeBatch = batch.json().toString()
        val beforeVideo = project.json().toString()
        try { jobs.configureSource(batch.id, VideoSourceMode.ASR); fail("Approved source must remain protected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(beforeBatch, batches.load(batch.id).json().toString())
        assertEquals(beforeVideo, store.load(project.id).json().toString())
        try { jobs.applySharedRoi(batch.id, listOf(.1f, .7f, .9f, .95f), resetConfirmed = true); fail("Approved region must remain protected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(beforeBatch, batches.load(batch.id).json().toString())
        assertEquals(beforeVideo, store.load(project.id).json().toString())
    }

    @Test fun automaticAudioContinuationDoesNotRepeatOcrPreflightAfterMissingModel() = runBlocking {
        val partial = fixture(VideoSourceMode.AUTO).copy(scanMs = 2_000,
            speech = JSONObject().put("ocrComplete", true).put("asrActive", true).put("complete", false),
            pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.EXTRACT, "NEEDS_REVIEW", 2_000,
                errorCode = "ASR_MODEL_MISSING"))
        assertTrue(BatchRoiValidation.hasSavedOcrProgress(partial))
        // There is no readable media at the fixture URI: this succeeds only by using the saved OCR evidence.
        assertTrue(BatchRoiValidation.check(context, listOf(partial)).single().confirmed)
        val changedRegion = BatchRoiValidation.withSharedRoi(partial, BatchProject("batch", "Series", BatchKind.SERIES,
            sharedRoi = listOf(.1f, .7f, .9f, .95f), sharedRoiRevision = 1))
        assertFalse(changedRegion.speech.optBoolean("ocrComplete"))
        assertFalse(BatchRoiValidation.hasSavedOcrProgress(changedRegion))
        assertFalse(BatchRoiValidation.hasSavedOcrProgress(partial.copy(speech = JSONObject().put("asrActive", true))))
    }
}
