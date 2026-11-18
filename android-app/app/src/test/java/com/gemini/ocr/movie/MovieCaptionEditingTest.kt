package com.gemini.ocr.movie

import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovieCaptionEditingTest {
    private val first = VideoCue("one", 50, 370, "你好", "Chào")
    private val second = VideoCue("two", 370, 800, "再见", "Hẹn gặp lại", approved = true)
    private val project = VideoProject(UUID.randomUUID().toString(), "Fixture", "file:///fixture.mp4",
        1000, 640, 360, stage = VideoStage.REVIEW, scanMs = 1000, cues = listOf(first, second))

    @Test fun versionedLocalEditAndUndoKeepMediaTimingAndOtherCues() {
        val source = MovieSourceSnapshot.freeze(project, "a".repeat(64), 1024, revisedAt = 100)
        val episode = MovieEpisode("episode", "Tập 1", "/fixture", 1000, 230, source = source)
        val edited = reviseMovieCaption(episode, source.revision, first.id, "Xin chào", 200)
        val revised = requireNotNull(edited.source)
        assertEquals(source.sha256, revised.sha256)
        assertEquals(source.uri, revised.uri)
        assertEquals(episode.positionMs, edited.positionMs)
        assertNotEquals(source.revision, revised.revision)
        assertEquals(50L to 370L, revised.cues.first().let { it.startMs to it.endMs })
        assertTrue(revised.cues.first().protectedText)
        assertEquals(second, revised.cues[1])
        assertFalse(edited.json(portable = true).has("captionUndo"))
        val saved = MovieEpisode.from(edited.json())
        assertEquals(first, saved.captionUndo?.previous)
        assertTrue(runCatching { reviseMovieCaption(saved, source.revision, first.id, "Phản hồi cũ", 300) }.isFailure)
        val restored = reviseMovieCaption(saved, revised.revision, first.id, null, 300)
        assertEquals(first.translation, restored.source!!.cues.first().translation)
        assertEquals(first.approved, restored.source!!.cues.first().approved)
        assertNull(restored.captionUndo)
    }

    @Test fun geminiSuggestionTargetsOnlyChosenCueAndLegacyWithoutTimelineCannotBeEdited() {
        val batch = movieCaptionRequest(project, second.id)
        assertEquals(listOf(second.id), batch.cueIds)
        assertEquals(listOf(second.source), batch.texts)
        assertTrue(batch.videoContext.contains(first.source))
        assertEquals("Hẹn gặp lại", project.cues.last().translation)
        val legacy = MovieEpisode("legacy", "Tập cũ", "/burned.mp4", 1000)
        assertTrue(runCatching { reviseMovieCaption(legacy, "", first.id, "Sửa", 200) }.isFailure)
    }
}
