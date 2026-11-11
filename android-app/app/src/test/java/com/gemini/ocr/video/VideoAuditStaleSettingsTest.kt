package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Audit regression: a settings operation captured before an edit must preserve the saved edit.
 * Uses ordinary public persistence/edit APIs, no decoder, model download, service or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoAuditStaleSettingsTest {
    @Test fun staleExtractionSettingsCannotResetANewerSourceTimeline()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val store=VideoStore(context);val jobs=VideoJobs(context)
        val old=store.save(VideoProject(UUID.randomUUID().toString(),"Audit source revision","content://audit/source",3000,640,360,
            sourceMode=VideoSourceMode.OCR,scanMs=3000,resumeStage=VideoStage.TRANSLATING,
            cues=listOf(VideoCue("one",100,2000,"别走","Đừng đi.",approved=true))))
        val newer=store.save(old.copy(roi=listOf(.1f,.7f,.9f,.9f),cues=listOf(old.cues.single().copy(source="军哥别走",translation="Anh Quân, đừng đi."))))
        assertThrows(IllegalArgumentException::class.java){runBlocking{jobs.save(old.copy(style=old.style.copy(position=.7f)))}}
        assertEquals(newer.cues,store.load(old.id).cues)
        assertEquals(newer.roi,store.load(old.id).roi)
    }
    @Test fun styleSnapshotCapturedBeforeApprovalCannotReplaceTheLatestReviewedCue()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=VideoStore(context)
        val jobs=VideoJobs(context)
        val beforeEdit=store.save(VideoProject(UUID.randomUUID().toString(),"Audit synthetic stale settings",
            "content://audit/source",3000,640,360,scanMs=3000,stage=VideoStage.REVIEW,
            resumeStage=VideoStage.TRANSLATING,
            cues=listOf(VideoCue("cue-1",100,2000,"别走","Bản nháp cũ"))))
        jobs.updateCue(beforeEdit.id,beforeEdit.cues.single().copy(translation="Đừng đi, anh Quân.",approved=true))
        val reviewed=store.load(beforeEdit.id).cues.single()
        // A previously captured settings snapshot arrives after the independent cue editor.
        jobs.save(beforeEdit.copy(style=beforeEdit.style.copy(position=.7f)))
        val afterSettings=store.load(beforeEdit.id)
        assertEquals("A style-only save must use current persisted cue content",reviewed,afterSettings.cues.single())
        assertEquals(.7f,afterSettings.style.position)
    }

    @Test fun glossarySnapshotCapturedBeforeManualEditCannotDropTheLatestHandWrittenTranslation()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=VideoStore(context)
        val jobs=VideoJobs(context)
        val beforeEdit=store.save(VideoProject(UUID.randomUUID().toString(),"Audit synthetic stale glossary",
            "content://audit/source",3000,640,360,scanMs=3000,stage=VideoStage.REVIEW,
            resumeStage=VideoStage.TRANSLATING,
            cues=listOf(VideoCue("cue-1",100,2000,"军哥别走","Bản nháp cũ"))))
        jobs.updateCue(beforeEdit.id,beforeEdit.cues.single().copy(translation="Anh Quân, đừng đi."))
        val manual=store.load(beforeEdit.id).cues.single()
        jobs.save(beforeEdit.copy(glossary="军哥=anh Quân"))
        assertEquals("Invalidation must protect the current manual edit, not the stale caller's flags",
            manual,store.load(beforeEdit.id).cues.single())
    }
}
