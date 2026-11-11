package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPipelineCheckpoint
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchAutoMergeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun item(name: String, episode: String) = BatchItem(UUID.randomUUID().toString(), name, episode = episode)

    @Test fun legacyProjectsRetainSeparateGroupsAndExplicitModePreservesReviewedOrder() {
        val inputs = listOf(item("EP10.mp4", "10"), item("EP2-Part2.mp4", "2"), item("EP2-Part1.mp4", "2"))
        val old = BatchProject(UUID.randomUUID().toString(), "Series", BatchKind.SERIES, inputs)
        val legacy = old.json().apply { remove("mergeEpisodes") }
        assertFalse(BatchProject.from(legacy).mergeEpisodes)
        assertEquals(listOf("10", "2"), BatchPlan.groups(BatchProject.from(legacy)).keys.toList())
        val enabled = BatchProject.from(old.copy(mergeEpisodes = true).json())
        assertTrue(enabled.mergeEpisodes)
        assertEquals(inputs, BatchPlan.groups(enabled).getValue(BatchPlan.ALL_EPISODES))
        assertEquals(inputs, enabled.items) // Opt-in does not rename or numerically reorder any episode.
        assertTrue(BatchPlan.groups(enabled.copy(items = emptyList())).isEmpty())
    }

    @Test fun togglingFromSourceLibraryChoosesMp4AndPreservesIndividualFilesAndLabels() = runBlocking {
        val store = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val old = jobs.create("Series", BatchKind.SERIES)
        val retained = File(store.folder(old.id), "retained-episode.mp4").also { it.writeBytes(byteArrayOf(7, 8, 9)) }
        val inputs = listOf(item("EP2.mp4", "2"), item("EP1.mp4", "1"))
        store.save(old.copy(items = inputs, state = "REVIEW", outputs = listOf(BatchOutput("2", "Episode", retained.path, 1_000))))
        val enabled = jobs.setMergeEpisodes(old.id, true)
        assertEquals(BatchDelivery.LIBRARY_AND_MP4, enabled.delivery)
        assertTrue(store.load(old.id).mergeEpisodes)
        assertEquals(inputs, enabled.items)
        assertTrue(enabled.outputs.isEmpty())
        assertEquals("READY", enabled.state)
        assertArrayEquals(byteArrayOf(7, 8, 9), retained.readBytes())
        val disabled = jobs.setMergeEpisodes(old.id, false)
        assertEquals(listOf("2", "1"), BatchPlan.groups(disabled).keys.toList())
        assertEquals(inputs, disabled.items)
        assertArrayEquals(byteArrayOf(7, 8, 9), retained.readBytes())
    }

    @Test fun switchingBackToSourceQualityExplicitlyTurnsOffSingleMp4Mode() = runBlocking {
        val store = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val b = jobs.create("Series", BatchKind.SERIES)
        val inputs = listOf(item("EP1.mp4", "1"), item("EP2.mp4", "2"))
        store.save(b.copy(items = inputs))
        jobs.setDelivery(b.id, BatchDelivery.MP4_ONLY)
        assertEquals(BatchDelivery.MP4_ONLY, jobs.setMergeEpisodes(b.id, true).delivery)
        val source = jobs.setDelivery(b.id, BatchDelivery.SOURCE_LIBRARY)
        assertFalse(source.mergeEpisodes)
        assertEquals(BatchDelivery.SOURCE_LIBRARY, source.delivery)
        assertEquals(inputs, source.items)
        assertEquals(2, BatchPlan.groups(source).size)
        val stale = BatchProject.from(source.json().put("mergeEpisodes", true))
        assertFalse(stale.mergeEpisodes) // Cannot load an ineffective source-only/physical-merge combination.
    }

    @Test fun reorderingJoinedSeriesInvalidatesOnlyOutputMetadataAndKeepsOptIn() = runBlocking {
        val store = BatchStore(context)
        val jobs = BatchJobs(context, VideoJobs(context))
        val b = jobs.create("Series", BatchKind.SERIES)
        val inputs = listOf(item("EP1.mp4", "1"), item("EP2.mp4", "2"))
        val retained = File(store.folder(b.id), "previous-joined.mp4").also { it.writeBytes(byteArrayOf(1, 2)) }
        val output = BatchOutput(BatchPlan.ALL_EPISODES, b.title, retained.path, 2_000)
        store.save(b.copy(items = inputs, mergeEpisodes = true, delivery = BatchDelivery.LIBRARY_AND_MP4,
            outputs = listOf(output), state = "REVIEW"))
        assertEquals(listOf(output), jobs.setMergeEpisodes(b.id, true).outputs) // An unchanged toggle is idempotent.
        val moved = jobs.reorder(b.id, 0, 1)
        assertTrue(moved.mergeEpisodes)
        assertEquals(inputs.reversed(), BatchPlan.groups(moved).getValue(BatchPlan.ALL_EPISODES))
        assertTrue(moved.outputs.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2), retained.readBytes())
    }

    @Test fun completedButUnapprovedEpisodesWaitForReviewWithoutRenderingOrJoining() = runBlocking {
        SessionState.running.value = false
        val store = VideoStore(context)
        val checkpoint = BatchPipelineStage.entries.fold(VideoPipelineCheckpoint()) { state, stage ->
            state.record(stage, "COMPLETE", 2_000)
        }
        val projects = (1..2).map { index ->
            store.save(VideoProject(UUID.randomUUID().toString(), "Episode $index", "file:///not-dispatched.mp4", 2_000, 640, 360,
                sourceMode = VideoSourceMode.SIDECAR, cues = listOf(VideoCue("cue", 0, 1_000, "你好", "Xin chào")),
                scanMs = 2_000, stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, pipeline = checkpoint))
        }
        val jobs = BatchJobs(context, VideoJobs(context))
        val batchStore = BatchStore(context)
        val b = jobs.create("Series", BatchKind.SERIES)
        projects.forEach { File(store.folder(it.id), "batch-owner").writeText(b.id) }
        batchStore.save(b.copy(items = projects.mapIndexed { i, p -> BatchItem(p.id, p.title, episode = "${i + 1}") }))
        jobs.setMergeEpisodes(b.id, true)
        val done = CompletableDeferred<Unit>()
        jobs.start(b.id)
        jobs.execute(b.id) { done.complete(Unit) }
        withTimeout(15_000) { done.await() }
        val result = batchStore.load(b.id)
        assertEquals(result.message, "REVIEW", result.state)
        assertTrue(result.message, result.message.contains("duyệt"))
        assertTrue(result.outputs.isEmpty())
        projects.forEach { before ->
            val after = store.load(before.id)
            assertEquals(before.cues, after.cues)
            assertTrue(after.exportedMp4.isEmpty())
            assertEquals(0, after.cloudCalls)
        }
        assertFalse(batchStore.folder(b.id).listFiles().orEmpty().any { it.name.startsWith("merged-") })
        assertNull(jobs.busy.value)
    }
}
