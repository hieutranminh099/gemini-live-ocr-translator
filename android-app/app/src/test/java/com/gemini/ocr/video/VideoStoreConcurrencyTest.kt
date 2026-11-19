package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoStoreConcurrencyTest {
    @Test fun separateUiAndWorkerStoresNeverRecoverAnAtomicBackupDuringAnotherWrite() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val writer=VideoStore(context);val reader=VideoStore(context)
        val id=UUID.randomUUID().toString()
        val project=VideoProject(id,"Concurrent saved project","file:///video.mp4",5000,640,360,
            cues=listOf(VideoCue("one",500,2000,"不要走","Đừng đi",approved=true)))
        writer.save(project)
        val encoding = VideoCheckpointEncoding()
        val start=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        try {
            val writes=executor.submit{start.await();repeat(40){writer.save(project.copy(title="Revision $it"), encoding)}}
            val reads=executor.submit{start.await();repeat(120){
                val loaded=reader.load(id)
                assertEquals(id,loaded.id);assertEquals(project.cues,loaded.cues)
            }}
            start.countDown();writes.get(30,TimeUnit.SECONDS);reads.get(30,TimeUnit.SECONDS)
            assertEquals("Revision 39",reader.load(id).title)
        }finally{executor.shutdownNow();writer.folder(id).deleteRecursively()}
    }

    @Test fun checkpointReencodesChangedRowsAndKeepsCompleteCompatibleJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val encoding = VideoCheckpointEncoding()
        val flags = mutableListOf("A")
        val cue = VideoCue("one", 200, 1000, "你好\n\"中\"", "Chào \\ bạn", qualityFlags = flags)
        val project = VideoProject(UUID.randomUUID().toString(), "First", "file:///fixture.mp4", 5000, 640, 360,
            cues = listOf(cue))

        store.save(project, encoding)
        assertEquals(project.cues, VideoStore(context).load(project.id).cues)
        flags.add("B")
        val next = store.save(project.copy(title = "Second", cues = listOf(cue, cue.copy(id = "two",
            startMs = 1100, endMs = 2100, translation = "Xin chào"))), encoding)
        val loaded = VideoStore(context).load(project.id)

        assertEquals(next.json().toString(), loaded.json().toString())
        assertEquals(listOf("A", "B"), loaded.cues.first().qualityFlags)
    }

    @Test fun checkpointPreservesAllRowsBeyondCacheBudgetAndAfterAChangeAcrossBlocks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val encoding = VideoCheckpointEncoding()
        val flags = mutableListOf("FIRST")
        val source = "中文字幕。".repeat(29)
        val translation = "Câu phụ đề thử nghiệm. ".repeat(7)
        val cues = List(5_000) { index ->
            VideoCue("cue-$index", index * 400L, index * 400L + 300, source, translation,
                draftVi = translation, qualityFlags = if (index == 255) flags else emptyList())
        }
        val id = UUID.randomUUID().toString()
        val project = VideoProject(id, "Long file", "file:///fixture.mp4", 2_000_000, 640, 360, cues = cues)
        try {
            store.save(project, encoding)
            assertTrue(File(store.folder(id), "project.json").length() > 4L * 1024 * 1024)

            flags += "UPDATED"
            val changed = cues.toMutableList()
            changed[256] = changed[256].copy(translation = "Đổi câu đầu khối", ocrAlternatives = listOf("字幕"))
            changed[4_900] = changed[4_900].copy(translation = "Đổi câu cuối video")
            val updated = store.save(project.copy(title = "Changed", cues = changed), encoding)
            val loaded = VideoStore(context).load(id)
            assertEquals(updated.cues, loaded.cues)
            assertEquals(listOf("FIRST", "UPDATED"), loaded.cues[255].qualityFlags)

            val shortened = changed.take(514).toMutableList().apply { removeAt(255) }
            val compacted = store.save(updated.copy(cues = shortened), encoding)
            assertEquals(compacted.json().toString(), VideoStore(context).load(id).json().toString())
        } finally {
            store.folder(id).deleteRecursively()
        }
    }
}
