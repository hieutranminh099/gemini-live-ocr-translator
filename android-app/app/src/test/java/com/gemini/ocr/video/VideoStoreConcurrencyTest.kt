package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
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

    @Test fun sparseCheckpointMatchesSchemaOneDefaultsAndPreservesEveryNonDefaultField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val encoding = VideoCheckpointEncoding()
        val defaults = VideoCue("default", 0, 500, "你好")
        val nonDefaults = VideoCue("custom", 500, 1_500, "原文", translation = "Bản dịch", approved = true,
            origin = "", model = "user-edited", error = "Lỗi thử nghiệm", localDraft = "Bản dự thảo",
            refinementState = "confirmed", rawZh = "原字", correctedZh = "更正", draftVi = "Nháp",
            polishedVi = "Câu đã sửa", ocrConfidence = 0f, qualityFlags = listOf("OCR", "EDITED"),
            version = 3, normalizedOcrText = "标准", ocrAlternatives = listOf("替代"))
        val id = UUID.randomUUID().toString()
        val project = VideoProject(id, "Compatibility", "file:///fixture.mp4", 5_000, 640, 360,
            cues = listOf(defaults, nonDefaults))
        try {
            val saved = store.save(project, encoding)
            val checkpoint = JSONObject(File(store.folder(id), "project.json").readText())
            val rows = checkpoint.getJSONArray("cues")
            assertEquals(1, checkpoint.getInt("schema"))
            assertEquals(setOf("id", "startMs", "endMs", "source"), rows.getJSONObject(0).keys().asSequence().toSet())
            assertEquals(nonDefaults.json().toString(), rows.getJSONObject(1).toString())
            assertTrue(defaults.json().has("translation"))
            assertTrue(defaults.json().isNull("ocrConfidence"))
            assertEquals(saved.json().toString(), VideoStore(context).load(id).json().toString())
        } finally {
            store.folder(id).deleteRecursively()
        }
    }

    @Test fun dispatchMarkerRestoresDiagnosticsAndNeverOverlaysChangedCuesOrRetryState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val encoding = VideoCheckpointEncoding(dispatchOverlays = true)
        val flags = mutableListOf("ORIGINAL")
        val id = UUID.randomUUID().toString()
        val cues = List(520) { index ->
            VideoCue("cue-$index", index * 250L, index * 250L + 200L, "你好", "Xin chào",
                approved = index == 0, qualityFlags = if (index == 0) flags else emptyList())
        }
        val project = VideoProject(id, "Long file", "file:///fixture.mp4", 130_000L, 640, 360, cues = cues)
        val base = File(store.folder(id), "project.json")
        val marker = File(base.parentFile, "project.dispatch.json")
        try {
            val ordinary = VideoCheckpointEncoding()
            val plain = store.save(project, ordinary)
            store.save(plain.copy(cloudCalls = 1), ordinary, dispatchOnly = true)
            val plainJson = JSONObject(base.readText())
            assertFalse(plainJson.has("checkpointGeneration"))
            assertEquals(1, plainJson.getInt("cloudCalls"))
            assertFalse(marker.exists())

            val initial = store.save(project, encoding)
            val bytes = base.readBytes()
            var last = store.save(initial.copy(cloudCalls = 1, elapsedMs = 77L, message = "Dispatch"), encoding,
                dispatchOnly = true)
            assertArrayEquals(bytes, base.readBytes())
            val current = VideoStore(context).load(id)
            assertEquals(last.json().toString(), current.json().toString())
            val previousReader = VideoProject.from(JSONObject(base.readText()))
            assertEquals(0, previousReader.cloudCalls)
            assertEquals(current.cues, previousReader.cues)
            assertTrue(marker.renameTo(File(marker.path + ".bak")))
            assertEquals(1, VideoStore(context).load(id).cloudCalls)
            val diagnostic = marker.readText()

            flags += "UPDATED"
            last = store.save(last.copy(cloudCalls = 2), encoding, dispatchOnly = true)
            assertEquals(diagnostic, marker.readText())
            assertEquals(last.json().toString(), VideoStore(context).load(id).json().toString())
            assertEquals(listOf("ORIGINAL", "UPDATED"), VideoStore(context).load(id).cues.first().qualityFlags)
            val retryAt = 123_000L
            val retry = last.copy(cloudCalls = 3, pipeline = last.pipeline.record(
                BatchPipelineStage.TRANSLATE, "RETRY_WAIT", 520L, nextRetryAt = retryAt))
            val expected = store.save(retry, encoding, dispatchOnly = true)
            assertEquals(diagnostic, marker.readText())
            assertEquals(expected.json().toString(), VideoStore(context).load(id).json().toString())
            assertEquals(retryAt, VideoProject.from(JSONObject(base.readText())).pipeline.task(BatchPipelineStage.TRANSLATE).nextRetryAt)

            val edited = VideoStore(context).save(expected.copy(title = "Normal writer"))
            assertEquals(diagnostic, marker.readText())
            assertEquals(edited.json().toString(), VideoStore(context).load(id).json().toString())
            val latest = store.save(edited.copy(cloudCalls = 4), encoding, dispatchOnly = true)
            assertEquals(diagnostic, marker.readText())
            assertEquals(latest.json().toString(), VideoStore(context).load(id).json().toString())
        } finally {
            store.folder(id).deleteRecursively()
        }
    }
}
