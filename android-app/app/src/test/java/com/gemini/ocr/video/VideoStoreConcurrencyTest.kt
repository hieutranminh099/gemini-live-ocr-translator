package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
