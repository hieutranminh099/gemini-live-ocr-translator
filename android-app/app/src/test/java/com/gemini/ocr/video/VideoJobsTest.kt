package com.gemini.ocr.video

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

/** Local persistence/edit boundaries only: never starts a service, prepares a model or translates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoJobsTest {
    private lateinit var context: Context
    private lateinit var store: VideoStore
    private lateinit var jobs: VideoJobs

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = VideoStore(context)
        jobs = VideoJobs(context)
    }

    @Test fun translationConfigurationInvalidatesDraftsButPreservesApprovedTextAndTimeline() = runBlocking {
        val old = store.save(project())
        val saved = jobs.save(old.copy(translationMode = VideoTranslationMode.GEMINI,
            glossary = "军哥=anh Quân", profile = "historical"))

        assertEquals(old.cues.map { Triple(it.id, it.startMs, it.endMs) },
            saved.cues.map { Triple(it.id, it.startMs, it.endMs) })
        assertEquals(old.cues.first(), saved.cues.first())
        val draft = saved.cues[1]
        assertEquals("再见", draft.source)
        assertEquals("", draft.translation)
        assertEquals("", draft.model)
        assertEquals("", draft.error)
        assertFalse(draft.approved)
        assertEquals(VideoStage.PAUSED, saved.stage)
        assertEquals(VideoStage.TRANSLATING, saved.resumeStage)
        assertEquals("", saved.exportedMp4)
        assertEquals(old.scanMs, saved.scanMs)
        assertEquals(old.carry.toString(), saved.carry.toString())
        assertEquals(saved.cues, store.load(old.id).cues)
    }

    @Test fun glossaryChangeDuringPartialOcrPreservesExtractionCheckpointAndApprovedWork()=runBlocking {
        val old=store.save(partialOcrProject())
        val saved=jobs.save(old.copy(glossary="军哥=anh Quân",profile="historical"))

        assertEquals(VideoStage.PAUSED,saved.stage)
        assertEquals(VideoStage.EXTRACTING,saved.resumeStage)
        assertEquals(3001L,saved.scanMs)
        assertEquals(old.carry.toString(),saved.carry.toString())
        assertEquals(old.cues.first(),saved.cues.first())
        assertEquals(old.cues.map{Triple(it.id,it.startMs,it.endMs)},saved.cues.map{Triple(it.id,it.startMs,it.endMs)})
        assertEquals("",saved.cues[1].translation)
        assertEquals("",saved.cues[1].localDraft)
        assertEquals(VideoStage.EXTRACTING,store.load(old.id).resumeStage)
        assertNull(jobs.busy.value)
    }

    @Test fun changingOcrEngineMidScanRequiresANewRevisionAndCannotMutateTheOldCheckpoint()=runBlocking {
        val old=store.save(partialOcrProject())
        val before=store.load(old.id).json().toString()
        val error=assertThrows(IllegalArgumentException::class.java){runBlocking {
            jobs.save(old.copy(translationMode=VideoTranslationMode.GEMINI))
        }}
        assertTrue(error.message.orEmpty().contains("checkpoint OCR"))
        assertEquals(before,store.load(old.id).json().toString())
        assertEquals(old.cues.first(),store.load(old.id).cues.first())
        assertNull(jobs.busy.value)
    }

    @Test fun freshVisualRevisionKeepsPartialOriginalAndCanChangeEngineBeforeScanningStarts()=runBlocking {
        val source=File.createTempFile("partial-public-rescan-", ".mp4", context.cacheDir).apply{writeBytes(byteArrayOf(4,5,6))}
        val old=store.save(partialOcrProject().copy(uri=Uri.fromFile(source).toString()))
        val before=store.load(old.id).json().toString()
        val revised=jobs.createVisualRevision(old.id)
        assertEquals(VideoTranslationMode.GEMINI,revised.translationMode)
        assertEquals(0L,revised.scanMs)
        assertTrue(revised.cues.isEmpty());assertEquals(0,revised.carry.length())
        assertEquals(VideoStage.EXTRACTING,revised.resumeStage)
        assertEquals(before,store.load(old.id).json().toString())

        val changed=jobs.save(revised.copy(translationMode=VideoTranslationMode.OFFLINE))
        assertEquals(VideoTranslationMode.OFFLINE,changed.translationMode)
        assertEquals(VideoStage.EXTRACTING,changed.resumeStage)
        assertTrue(changed.cues.isEmpty());assertEquals(0L,changed.scanMs)
        assertEquals(before,store.load(old.id).json().toString())
        assertEquals(0,changed.cloudCalls);assertNull(jobs.busy.value)
        assertArrayEquals(byteArrayOf(4,5,6),source.readBytes())
    }
    @Test fun switchingFromLocalTranslationToHybridKeepsTheLocalOcrCheckpointAndApprovedRows()=runBlocking {
        val original=partialOcrProject()
        val draft=JSONObject(original.carry.toString()).put("draftVersion",2)
            .put("draftRun",JSONObject().put("raw","继续说话").put("start",2500).put("last",3000).put("count",2))
        val old=store.save(original.copy(carry=draft))
        val saved=jobs.save(old.copy(translationMode=VideoTranslationMode.HYBRID))
        assertEquals(old.scanMs,saved.scanMs);assertEquals(old.carry.toString(),saved.carry.toString())
        assertEquals(old.cues.first(),saved.cues.first())
        assertEquals(old.cues.map{Triple(it.id,it.startMs,it.endMs)},saved.cues.map{Triple(it.id,it.startMs,it.endMs)})
        assertEquals(VideoStage.EXTRACTING,saved.resumeStage)
    }
    @Test fun switchingALegacyLocalAccumulatorMidScanCannotDiscardItsUnconfirmedObservations()=runBlocking {
        val old=store.save(partialOcrProject())
        val before=store.load(old.id).json().toString()
        assertThrows(IllegalArgumentException::class.java){runBlocking{jobs.save(old.copy(translationMode=VideoTranslationMode.HYBRID))}}
        assertEquals(before,store.load(old.id).json().toString())
    }

    @Test fun timingOnlyEditDuringPartialOcrKeepsScanningTheRestOfTheVideo()=runBlocking {
        val old=store.save(partialOcrProject().copy(translationMode=VideoTranslationMode.HYBRID))
        jobs.updateCue(old.id,old.cues[1].copy(startMs=1200,endMs=2100))
        val saved=store.load(old.id)
        assertEquals(VideoStage.EXTRACTING,saved.resumeStage)
        assertEquals(old.scanMs,saved.scanMs)
        assertEquals(old.carry.toString(),saved.carry.toString())
        assertEquals(1200L,saved.cues[1].startMs);assertEquals(2100L,saved.cues[1].endMs)
        assertEquals(old.cues[1].translation,saved.cues[1].translation)
        assertEquals(old.cues[1].model,saved.cues[1].model)
        assertEquals(old.cues[1].error,saved.cues[1].error)
        assertEquals(old.cues.first(),saved.cues.first())
        assertNull(jobs.busy.value)
    }

    @Test fun changingTheExtractionRegionCannotReuseEvenApprovedCuesFromTheOldSource() = runBlocking {
        val old = store.save(project().copy(sourceMode = VideoSourceMode.OCR))
        val saved = jobs.save(old.copy(roi = listOf(.1f, .8f, .9f, .95f)))

        assertTrue(saved.cues.isEmpty())
        assertEquals(0L, saved.scanMs)
        assertEquals(0, saved.carry.length())
        assertEquals(VideoStage.READY, saved.stage)
        assertEquals(VideoStage.EXTRACTING, saved.resumeStage)
        assertEquals(0, saved.progress)
        assertEquals("", saved.exportedMp4)
        assertEquals(old.uri, saved.uri)
        assertEquals(old.translationMode, saved.translationMode)
        assertTrue(store.load(old.id).cues.isEmpty())
    }
    @Test fun samplingSpeedIsChosenBeforeExtractionAndCannotDiscardAnExistingTimeline()=runBlocking {
        val old=store.save(project().copy(sourceMode=VideoSourceMode.OCR))
        assertThrows(IllegalArgumentException::class.java){runBlocking{jobs.save(old.copy(visualSampleMs=500))}}
        assertEquals(old.cues,store.load(old.id).cues);assertEquals(250L,store.load(old.id).visualSampleMs)
        val fresh=store.save(old.copy(id=UUID.randomUUID().toString(),cues=emptyList(),scanMs=0,
            stage=VideoStage.READY,resumeStage=VideoStage.EXTRACTING,exportedMp4=""))
        val fast=jobs.save(fresh.copy(visualSampleMs=500))
        assertEquals(500L,store.load(fast.id).visualSampleMs)
        assertTrue(fast.cues.isEmpty());assertEquals(VideoStage.EXTRACTING,fast.resumeStage)
        assertThrows(IllegalArgumentException::class.java){runBlocking{jobs.save(fast.copy(visualSampleMs=1000))}}
        assertEquals(500L,store.load(fast.id).visualSampleMs)
    }

    @Test fun visualRescanCreatesSeparateEmptyTimelineAndKeepsTheReviewedOriginal()=runBlocking {
        val source=File.createTempFile("public-rescan-", ".mp4", context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val old=store.save(project().copy(uri=Uri.fromFile(source).toString(),sourceMode=VideoSourceMode.OCR,
            style=VideoSubtitleStyle(frame=VideoSubtitleFrame.BOTTOM),glossary="维基人=thành viên Wikipedia"))
        val revised=jobs.createVisualRevision(old.id)
        assertNotEquals(old.id,revised.id)
        assertTrue(revised.cues.isEmpty());assertEquals(VideoStage.READY,revised.stage)
        assertEquals(VideoTranslationMode.GEMINI,revised.translationMode)
        assertEquals(old.uri,revised.uri);assertEquals(old.roi,revised.roi)
        assertEquals(old.style,revised.style);assertEquals(old.glossary,revised.glossary)
        assertEquals(old.cues,store.load(old.id).cues)
        assertEquals(0,revised.cloudCalls);assertNull(jobs.busy.value)
        assertArrayEquals(byteArrayOf(1,2,3),source.readBytes())
    }

    @Test fun visualStyleChangesKeepReviewedAndDraftTextButInvalidateTheRenderedMp4() = runBlocking {
        val old = store.save(project())
        val saved = jobs.save(old.copy(style = old.style.copy(position = .7f, background = false)))

        assertEquals(old.cues, saved.cues)
        assertEquals(old.scanMs, saved.scanMs)
        assertEquals(old.stage, saved.stage)
        assertEquals(old.resumeStage, saved.resumeStage)
        assertEquals("", saved.exportedMp4)
        assertEquals(.7f, saved.style.position)
        assertFalse(saved.style.background)
    }

    @Test fun switchingToHybridClearsUnreviewedTranslationAndLocalDraftButPreservesApprovedPair()=runBlocking{
        val old=store.save(project().copy(cues=project().cues.map{it.copy(localDraft="Nháp cũ",refinementState="done")}))
        val saved=jobs.save(old.copy(translationMode=VideoTranslationMode.HYBRID))
        assertEquals(old.cues.first(),saved.cues.first())
        val draft=saved.cues[1]
        assertEquals("",draft.translation);assertEquals("",draft.localDraft)
        assertEquals("",draft.refinementState);assertEquals("",draft.model)
        assertFalse(draft.approved)
        assertEquals(old.cues[1].source,draft.source)
        assertEquals(old.cues[1].startMs,draft.startMs);assertEquals(old.cues[1].endMs,draft.endMs)
        assertEquals(VideoStage.PAUSED,saved.stage)
        assertEquals(VideoStage.TRANSLATING,saved.resumeStage)
    }

    @Test fun timingOnlyEditCannotTurnPendingLocalDraftIntoClaimedCloudCompletion()=runBlocking{
        val old=store.save(hybridProject())
        val pending=old.cues[1]
        jobs.updateCue(old.id,pending.copy(startMs=1200,endMs=2100))
        val saved=store.load(old.id)
        val edited=saved.cues[1]
        assertEquals(1200L,edited.startMs);assertEquals(2100L,edited.endMs)
        assertEquals(pending.translation,edited.translation)
        assertEquals(pending.localDraft,edited.localDraft)
        assertEquals("pending",edited.refinementState)
        assertEquals(pending.error,edited.error)
        assertEquals(pending.model,edited.model)
        assertFalse(VideoTranslationPlan.isComplete(edited,true))
        assertEquals(old.cues.first(),saved.cues.first())
        assertEquals("",saved.exportedMp4)
    }

    @Test fun changingOnlyChineseSourceInvalidatesTheOldMeaningInsteadOfSilentlyReusingItsVietnamese()=runBlocking{
        val old=store.save(hybridProject())
        val previous=old.cues[1]
        jobs.updateCue(old.id,previous.copy(source="不要离开"))
        val saved=store.load(old.id).cues[1]
        assertEquals("不要离开",saved.source)
        assertEquals("",saved.translation);assertEquals("",saved.localDraft)
        assertEquals("",saved.refinementState);assertEquals("",saved.model)
        assertFalse(saved.approved)
        assertTrue(VideoTranslationPlan.needsLocalDraft(saved,true))
        assertEquals(old.cues.first(),store.load(old.id).cues.first())
    }

    @Test fun explicitlyEditedVietnameseIsKeptAsManualDraftWithoutClaimingItWasGeminiOutput()=runBlocking{
        val old=store.save(hybridProject())
        val pending=old.cues[1]
        jobs.updateCue(old.id,pending.copy(translation="Đừng rời đi, anh Quân."))
        val edited=store.load(old.id).cues[1]
        assertEquals("Đừng rời đi, anh Quân.",edited.translation)
        assertEquals(pending.localDraft,edited.localDraft)
        assertEquals("done",edited.refinementState)
        assertEquals("user-edited",edited.model)
        assertEquals("",edited.error)
        assertFalse(edited.approved)
        assertTrue(VideoTranslationPlan.isComplete(edited,true))
    }

    @Test fun userCanReplaceChineseAndVietnameseTogetherWithoutKeepingAnUnrelatedLocalDraft()=runBlocking{
        val old=store.save(hybridProject())
        jobs.updateCue(old.id,old.cues[1].copy(source="不要离开",translation="Đừng rời đi.",approved=true))
        val edited=store.load(old.id).cues[1]
        assertEquals("不要离开",edited.source);assertEquals("Đừng rời đi.",edited.translation)
        assertEquals("",edited.localDraft)
        assertEquals("done",edited.refinementState)
        assertEquals("user-edited",edited.model)
        assertTrue(edited.approved)
        assertEquals(old.cues.first(),store.load(old.id).cues.first())
    }

    @Test fun styleChangePreservesHybridPendingStateForCheckpointResume()=runBlocking{
        val old=store.save(hybridProject())
        val saved=jobs.save(old.copy(style=old.style.copy(frame=VideoSubtitleFrame.TOP)))
        assertEquals(old.cues,saved.cues)
        assertEquals("pending",saved.cues[1].refinementState)
        assertEquals(old.cues[1].localDraft,saved.cues[1].localDraft)
        assertEquals(old.cues[1].error,saved.cues[1].error)
        assertEquals("",saved.exportedMp4)
    }

    @Test fun rejectedOverlappingEditCannotOverwriteThePreviouslySavedReview() = runBlocking {
        val old = store.save(project())
        try {
            jobs.updateCue(old.id, old.cues[1].copy(startMs = 999))
            fail("An edit overlapping the previous cue must fail before persistence")
        } catch (_: IllegalArgumentException) { }
        val unchanged = store.load(old.id)
        assertEquals(old.cues, unchanged.cues)
        assertEquals(old.exportedMp4, unchanged.exportedMp4)

        jobs.updateCue(old.id, old.cues[1].copy(startMs = 1200, translation = "Tạm biệt, anh Quân.", approved = true))
        val edited = store.load(old.id)
        assertEquals(old.cues.first(), edited.cues.first())
        assertEquals(1200L, edited.cues[1].startMs)
        assertEquals("Tạm biệt, anh Quân.", edited.cues[1].translation)
        assertTrue(edited.cues[1].approved)
        assertEquals("", edited.cues[1].error)
        assertEquals("", edited.exportedMp4)
        assertEquals(VideoStage.REVIEW, edited.stage)
    }

    @Test fun creatingAndEditingAProjectNeverWritesTheSelectedSourceFile() = runBlocking {
        val source = File.createTempFile("video-source-test-", ".mp4", context.cacheDir)
        val original = byteArrayOf(0, 1, 2, 3, 4, 5)
        source.writeBytes(original)
        val created = store.create(Uri.fromFile(source), "Video kiểm thử", VideoMetadata(4000, 1280, 720))

        jobs.save(created.copy(glossary = "名字=tên riêng"))
        assertArrayEquals(original, source.readBytes())
        assertEquals(Uri.fromFile(source).toString(), store.load(created.id).uri)
        assertNotEquals(source.absolutePath, store.exportFile(created.id, "mp4").absolutePath)
    }

    @Test fun fallbackCopyStillProtectsTheOriginalDocumentFromExportTruncation() = runBlocking {
        val original = "selected original video bytes".toByteArray(Charsets.UTF_8)
        val selected = File.createTempFile("selected-video-", ".mp4", context.cacheDir).apply { writeBytes(original) }
        val uri = registerDocument("video-source-test", selected)
        val created = store.create(uri, "Selected video", VideoMetadata(4000, 1280, 720))
        assertEquals("file", Uri.parse(created.uri).scheme) // This provider supplies no persistent grant.
        assertEquals(uri.toString(), store.load(created.id).originalSourceUri)
        store.save(created.copy(cues = project().cues))

        var rejected = false
        try { jobs.exportText(created.id, uri) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("The original document must be rejected before opening a truncating output stream", rejected)
        assertArrayEquals(original, selected.readBytes())
    }

    @Test fun reselectingEditedSubtitleAtTheSameUriCreatesANewRevisionAndClearsOldApproval() = runBlocking {
        val textA = "1\n00:00:00,100 --> 00:00:01,000\n你好\n"
        val textB = "1\n00:00:02,000 --> 00:00:03,000\n别走\n"
        val selected = File.createTempFile("selected-subtitle-", ".srt", context.cacheDir).apply { writeText(textA, Charsets.UTF_8) }
        val uri = registerDocument("video-subtitle-test", selected)
        val base = store.save(project())
        jobs.selectSubtitle(base.id, uri, "subtitles.srt")
        val first = store.load(base.id)
        store.save(first.copy(cues = base.cues, scanMs = 4000, stage = VideoStage.REVIEW))
        selected.writeText(textB, Charsets.UTF_8)

        jobs.selectSubtitle(base.id, uri, "subtitles.srt")
        val second = store.load(base.id)
        assertTrue(second.cues.isEmpty())
        assertEquals(0L, second.scanMs)
        assertEquals(VideoStage.EXTRACTING, second.resumeStage)
        assertEquals(uri.toString(), second.originalSubtitleUri)
        assertNotEquals(first.subtitleUri, second.subtitleUri)
        // Android's Uri.fromFile uses Unix paths; inspect the retained revisions directly on the
        // Windows JVM rather than treating a simulated Android file URI as a Windows pathname.
        val revisions=store.folder(base.id).listFiles().orEmpty().filter{it.name.startsWith("subtitle-") && it.extension=="input"}
        assertEquals(2,revisions.size)
        assertEquals(setOf(textA,textB),revisions.map{it.readText(Charsets.UTF_8)}.toSet())
    }

    private fun registerDocument(authority: String, file: File): Uri {
        val provider = LocalDocumentProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/document/selected")
    }

    private class LocalDocumentProvider(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private fun project() = VideoProject(UUID.randomUUID().toString(), "Video", "content://selected/source",
        4000, 1280, 720, stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
        cues = listOf(
            VideoCue("one", 100, 1000, "你好", "Chào bạn, anh Quân.", approved = true, model = "user-reviewed"),
            VideoCue("two", 1000, 2000, "再见", "Tạm biệt.", model = "draft-model", error = "previous diagnostic")),
        scanMs = 4000, carry = JSONObject().put("last", 3999), progress = 100,
        exportedMp4 = "/generated/translated.mp4")

    private fun hybridProject():VideoProject{
        val base=project()
        return base.copy(translationMode=VideoTranslationMode.HYBRID,cues=base.cues.mapIndexed{index,cue->
            if(index==0)cue.copy(localDraft="Nháp trước khi duyệt",refinementState="done")
            else cue.copy(localDraft="Tạm biệt.",refinementState="pending",model=VideoOfflineTranslator.MODEL,error="Gemini hết hạn, chỉ còn nháp local")
        })
    }

    private fun partialOcrProject():VideoProject=project().copy(sourceMode=VideoSourceMode.OCR,
        durationMs=240000,stage=VideoStage.PAUSED,resumeStage=VideoStage.EXTRACTING,
        scanMs=3001,progress=1,carry=JSONObject().put("source","继续说话").put("start",2500)
            .put("last",3000).put("scanLast",3000).put("stableBlanks",0))
}
