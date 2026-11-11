package com.gemini.ocr.batch

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun orderPreviewAppliesOnlyToUnchangedCompleteInputAndPreservesEpisodeGroups() = kotlinx.coroutines.runBlocking {
        val store = BatchStore(context)
        val initial = store.create("Order confirmation", BatchKind.SERIES)
        val rows = (1..3).map { BatchItem(UUID.randomUUID().toString(), "clip$it.mp4", episode = "manual-group-$it") }
        val snapshot = store.save(initial.copy(items = rows))
        val jobs = BatchJobs(context)
        try { jobs.applyOrder(snapshot, listOf(rows[0].projectId, rows[0].projectId, rows[2].projectId)); fail("duplicate") }
        catch (_: IllegalArgumentException) { }
        assertEquals(snapshot, store.load(snapshot.id))
        val applied = jobs.applyOrder(snapshot, rows.reversed().map { it.projectId })
        assertEquals(rows.reversed(), applied.items)
        try { jobs.applyOrder(snapshot, rows.map { it.projectId }); fail("stale preview") }
        catch (_: IllegalArgumentException) { }
        assertEquals(rows.reversed(), store.load(snapshot.id).items)
    }
    @Test fun reopenedStoreKeepsFiveVideosManualOrderFailureAndCompletedResults() {
        val store = BatchStore(context)
        val initial = store.create("Series", BatchKind.SERIES)
        val items = (1..5).map { BatchItem(UUID.randomUUID().toString(), "EP$it.mp4", 100L * it, it.toString(),
            state = if (it == 1) "DONE" else if (it == 2) "FAILED" else "PENDING", error = if (it == 2) "Không có quyền đọc" else "", durationMs = 2000) }
        val output = BatchOutput("1", "Series · Tập 1", "/temporary/ep1.mp4", 2000, "fingerprint")
        val saved = store.save(initial.copy(items = BatchPlan.move(items, 4, 0), outputs = listOf(output), state = "FAILED"))
        val reopened = BatchStore(context).load(saved.id)
        assertEquals(saved, reopened)
        assertEquals("EP5.mp4", reopened.items.first().name)
        assertEquals(output, reopened.outputs.single())
        assertEquals("Không có quyền đọc", reopened.items.first { it.episode == "2" }.error)
    }
    @Test fun failedWriteDoesNotDestroyPreviousCheckpoint() {
        val store = BatchStore(context); val batch = store.create("Keep", BatchKind.MOVIE_PARTS)
        val bad = batch.copy(message = "x".repeat(33 * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { store.save(bad) }
        assertEquals("Keep", BatchStore(context).load(batch.id).title)
    }
    @Test fun exactContextCacheReusesAcrossBatchVideosButRejectsNewTextContextGlossaryAndOwner() {
        val store = VideoStore(context); val batch = BatchStore(context).create("Cache", BatchKind.SERIES)
        fun project(owner: String): VideoProject {
            val id = UUID.randomUUID().toString()
            File(store.folder(id), "batch-owner").writeText(owner)
            return store.save(VideoProject(id, "Video", Uri.fromFile(File(store.folder(id), "source.mp4")).toString(), 4000, 720, 1280,
                translationMode = VideoTranslationMode.GEMINI, cues = listOf(VideoCue("1", 0, 1000, "我有两本书")), scanMs = 4000))
        }
        val first = project(batch.id); val second = project(batch.id)
        val request = checkNotNull(VideoTranslationPlan.nextCloudBatch(first.cues, false))
        val cache = BatchTranslationCache(context)
        cache.save(first, request, listOf("Tôi có hai quyển sách"), "test-model")
        assertEquals("Tôi có hai quyển sách", BatchTranslationCache(context).lookup(second, request)?.translations?.single())
        val changed = second.copy(cues = listOf(VideoCue("1", 0, 1000, "我没有两本书")))
        assertNull(cache.lookup(changed, checkNotNull(VideoTranslationPlan.nextCloudBatch(changed.cues, false))))
        assertNull(cache.lookup(second.copy(glossary = "书=sổ"), request))
        assertNull(cache.lookup(project(UUID.randomUUID().toString()), request))
        val shifted = request.copy(videoContext = request.videoContext.replace("\"startMs\":0", "\"startMs\":2500"))
        assertNotEquals(request.videoContext, shifted.videoContext)
        assertNotNull(cache.lookup(second, shifted))
        val contextChanged = request.copy(videoContext = request.videoContext.replace("\"previous\":[]", "\"previous\":[{\"source\":\"你看过吗\",\"vietnamese\":\"Bạn xem chưa\"}]"))
        assertNull(cache.lookup(second, contextChanged))
    }
    @Test fun sameTextInAnotherModeOrAnUnownedSingleVideoCannotReadPrivateBatchCache() {
        val store = VideoStore(context); val id = UUID.randomUUID().toString(); val owner = UUID.randomUUID().toString()
        val p = VideoProject(id, "Video", "file:///unused.mp4", 1000, 720, 1280,
            translationMode = VideoTranslationMode.GEMINI, cues = listOf(VideoCue("1", 0, 1000, "你好")))
        val request = checkNotNull(VideoTranslationPlan.nextCloudBatch(p.cues, false))
        File(store.folder(id), "batch-owner").writeText(owner)
        val cache = BatchTranslationCache(context); cache.save(p, request, listOf("Xin chào"), "test-model")
        assertNull(cache.lookup(p.copy(translationMode = VideoTranslationMode.HYBRID), request))
        File(store.folder(id), "batch-owner").delete()
        assertNull(cache.lookup(p, request))
    }
}
