package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoHybridWorkflowTest {
    private val approved = VideoCue("approved", 0, 900, "已经确认", "Đã duyệt", approved = true,
        model = "user-edited", rawZh = "已经确认", version = 3)
    private fun project(carry: JSONObject = JSONObject()) = VideoProject(
        "11111111-2222-3333-4444-555555555555", "Synthetic checkpoint", "file:///unused.mp4", 8000, 1280, 720,
        sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID,
        stage = VideoStage.FAILED, resumeStage = VideoStage.EXTRACTING, scanMs = 5250,
        cues = listOf(approved), carry = carry)
    private fun carry() = JSONObject().put("visualScan", true).put("source", "正在说话")
        .put("start", 1000).put("last", 5000).put("blank", -1).put("blanks", 0)
        .put("metrics", JSONObject().put("engine", "gemini-vision").put("decoded_frames", 180))

    @Test fun hybridRequiresTheWholeSourceEvenIfAnOldCheckpointSaysTranslating() {
        assertTrue(VideoHybridWorkflow.requiresExtraction(project().copy(resumeStage = VideoStage.TRANSLATING)))
        assertFalse(VideoHybridWorkflow.requiresExtraction(project().copy(scanMs = 8000)))
        assertFalse(VideoHybridWorkflow.requiresExtraction(project().copy(translationMode = VideoTranslationMode.GEMINI)))
        assertFalse(VideoHybridWorkflow.requiresExtraction(project().copy(translationMode = VideoTranslationMode.OFFLINE)))
    }

    @Test fun migrationPreservesKnownOpenSpanAndApprovedPrefixWithoutMergingItIntoNewLocalOcr() {
        val old = project(carry())
        val before = old.json().toString()
        val updated = VideoHybridWorkflow.prepareLocalExtraction(old)
        assertEquals(before, old.json().toString())
        assertEquals(old.cues, updated.cues)
        assertEquals(old.scanMs, updated.scanMs)
        assertEquals(old.carry.getJSONObject("metrics").toString(), updated.carry.getJSONObject("metrics").toString())
        val run = updated.carry.getJSONObject("draftRun")
        assertEquals(1000L, run.getLong("start")); assertEquals(5000L, run.getLong("last"))
        assertTrue(run.getBoolean("uncertain")); assertEquals(1, run.getInt("count"))
        val timeline = VideoDraftTimeline(updated.cues, updated.carry)
        timeline.add(5250, "正在说话")
        val transferred = timeline.cues.last()
        assertEquals("正在说话", transferred.rawZh)
        assertEquals(1000L, transferred.startMs)
        assertEquals(5200L, transferred.endMs)
        assertTrue("OCR_UNCERTAIN_INTERVAL" in transferred.qualityFlags)
        assertEquals(approved, timeline.cues.first())
        assertEquals(5250L, timeline.snapshot().getJSONObject("draftRun").getLong("start"))
        assertSame(updated, VideoHybridWorkflow.prepareLocalExtraction(updated))
    }

    @Test fun aPendingBlankClosesAtItsOriginalBoundaryAndDoesNotInventATail() {
        val old = project(carry().put("blank", 4800).put("blanks", 1))
        val before = old.json().toString()
        val updated = VideoHybridWorkflow.prepareLocalExtraction(old)
        assertEquals(before, old.json().toString())
        assertEquals(approved, updated.cues.first())
        val pending = updated.cues.last()
        assertEquals(1000L, pending.startMs); assertEquals(4800L, pending.endMs)
        assertEquals("正在说话", pending.source)
        assertEquals("", updated.carry.getString("source"))
        val timeline = VideoDraftTimeline(updated.cues, updated.carry)
        timeline.add(5250, "后来的话")
        assertEquals(updated.cues, timeline.cues)
        VideoTimeline.validate(timeline.cues, old.durationMs)
    }

    @Test fun noOpenSourceRetainsMetricsWithoutProducingAnInventedCue() {
        val old = project(carry().put("source", "").put("blank", -1))
        val updated = VideoHybridWorkflow.prepareLocalExtraction(old)
        assertEquals(old.cues, updated.cues)
        assertTrue(updated.carry.isNull("draftRun"))
        assertEquals(5000L, updated.carry.getLong("scanLast"))
        assertEquals(180, updated.carry.getJSONObject("metrics").getInt("decoded_frames"))
    }

    @Test fun invalidPendingIntervalFailsWithoutChangingTheOriginalCheckpoint() {
        for (invalid in listOf(carry().put("start", 800), carry().put("last", 6000),
            carry().put("blank", 6000), carry().put("source", "字".repeat(2001)))) {
            val old = project(invalid); val before = old.json().toString()
            assertThrows(IllegalArgumentException::class.java) { VideoHybridWorkflow.prepareLocalExtraction(old) }
            assertEquals(before, old.json().toString())
        }
    }

    @Test fun unrelatedAndAlreadyLocalCheckpointsAreReturnedUnchanged() {
        val local = project(carry().put("visualScan", false))
        assertSame(local, VideoHybridWorkflow.prepareLocalExtraction(local))
        val gemini = project(carry()).copy(translationMode = VideoTranslationMode.GEMINI)
        assertSame(gemini, VideoHybridWorkflow.prepareLocalExtraction(gemini))
        val complete = project(carry()).copy(scanMs = 8000)
        assertSame(complete, VideoHybridWorkflow.prepareLocalExtraction(complete))
    }
}
