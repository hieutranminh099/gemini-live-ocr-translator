package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.*
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchRescanPrivacyTest {
    @Test fun visualRescanCannotLoseBatchOwnershipAndEnterAutomaticProjectBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val source = File(context.cacheDir, "batch-rescan-${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(1)) }
        val old = store.save(VideoProject(UUID.randomUUID().toString(), "Private batch part", source.toURI().toString(),
            2000, 720, 1280, sourceMode = VideoSourceMode.OCR,
            cues = listOf(VideoCue("cue", 0, 1000, "你好", "Xin chào", approved = true))))
        val owner = UUID.randomUUID().toString()
        File(store.folder(old.id), "batch-owner").writeText(owner)
        val revised = VideoJobs(context).createVisualRevision(old.id)
        assertNotEquals(old.id, revised.id)
        assertEquals(owner, File(store.folder(revised.id), "batch-owner").readText())
        val translated = store.save(revised.copy(cues = old.cues))
        assertFalse(VideoCloudSync(context).ready(translated))
        assertEquals(old.cues, store.load(old.id).cues)
        assertTrue(source.exists())
    }
}
