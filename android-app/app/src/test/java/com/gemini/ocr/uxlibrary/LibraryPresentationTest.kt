package com.gemini.ocr.uxlibrary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.filteredReviewPage
import com.gemini.ocr.movie.MovieEpisode
import com.gemini.ocr.movie.MovieRecord
import com.gemini.ocr.movie.MovieSort
import com.gemini.ocr.movie.continueEpisode
import com.gemini.ocr.movie.sortedMovies
import com.gemini.ocr.network.ContextualTranslationMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryPresentationTest {
    private fun episode(id: String, position: Long = 0, stamp: Long = 0) = MovieEpisode(id, id, "", 10_000, position, stamp)
    private fun movie(id: String, name: String, time: Long, vararg episodes: MovieEpisode) =
        MovieRecord(id, name, episodes.toList(), confirmedAt = time)

    @Test fun sortingAndSearchDoNotChangeStoredOrderOrEpisodeAssignments() {
        val older = movie("a", "Zebra", 10, episode("ep1"))
        val newer = movie("b", "alpha", 20, episode("ep1"), episode("ep2"))
        val original = listOf(older, newer)
        assertEquals(listOf(newer, older), sortedMovies(original, "", MovieSort.RECENT))
        assertEquals(listOf(newer, older), sortedMovies(original, "", MovieSort.NAME))
        assertEquals(listOf(newer, older), sortedMovies(original, "", MovieSort.EPISODES))
        assertEquals(listOf(newer), sortedMovies(original, " ALP ", MovieSort.RECENT))
        assertEquals(listOf(older, newer), original)
        assertEquals(listOf("ep1", "ep2"), newer.episodes.map { it.id })
    }

    @Test fun continueUsesMostRecentlyWatchedEpisodeAndMovesPastCompletedEpisode() {
        val first = episode("ep1", 5000, 40)
        val second = episode("ep2", 2000, 30)
        assertEquals(first, continueEpisode(movie("m", "Movie", 1, first, second)))
        val finished = second.copy(positionMs = second.durationMs, positionUpdatedAt = 50)
        val next = episode("ep3")
        assertEquals(next, continueEpisode(movie("m", "Movie", 1, first, finished, next)))
        assertEquals(first.copy(positionMs = 0), continueEpisode(movie("m", "Movie", 1, first.copy(positionMs = 0), next)))
    }

    @Test fun reviewedSearchFiltersQualityAndNeverReadsAnotherFilmScope() = runBlocking {
        val memory = ContextualTranslationMemory(ApplicationProvider.getApplicationContext<Context>(), databaseName = null)
        val film = ContextualTranslationMemory.Scope("film-a", "modern", "test", "none", ContextualTranslationMemory.Scope.emptyContextFingerprint())
        val provenance = ContextualTranslationMemory.Provenance("user://synthetic-library-test", "owned-fixture")
        try {
            memory.recordDraft(ContextualTranslationMemory.Entry(film, "第一句", "Xin chào bản nháp", "dialogue", provenance, 1.0))
            memory.saveReviewed(ContextualTranslationMemory.Entry(film, "第二句", "Xin chào đã duyệt", "dialogue", provenance, 1.0))
            memory.saveReviewed(ContextualTranslationMemory.Entry(film.copy(filmId = "film-b"), "第三句", "Xin chào phim khác", "dialogue", provenance, 1.0))
            val page = filteredReviewPage(memory, film, 0, "xin CHÀO", "USER_APPROVED")
            assertEquals(listOf("第二句"), page.entries.map { it.source })
            assertNull(page.nextId)
            assertEquals(ContextualTranslationMemory.Quality.USER_APPROVED, page.entries.single().quality)
        } finally { memory.close() }
    }

    @Test fun reviewedSearchContinuationDoesNotSkipUnreturnedMatchesOrPretendCapIsTheEnd() = runBlocking {
        val memory = ContextualTranslationMemory(ApplicationProvider.getApplicationContext<Context>(), databaseName = null)
        val film = ContextualTranslationMemory.Scope("film-a", "modern", "test", "none", ContextualTranslationMemory.Scope.emptyContextFingerprint())
        val provenance = ContextualTranslationMemory.Provenance("user://synthetic-library-test", "owned-fixture")
        try {
            repeat(4) { index -> memory.recordDraft(ContextualTranslationMemory.Entry(film, "第${index}句", "Câu $index", "dialogue", provenance, 1.0)) }
            val first = filteredReviewPage(memory, film, 0, "Câu", "ALL", resultLimit = 1)
            val second = filteredReviewPage(memory, film, checkNotNull(first.nextId), "Câu", "ALL", resultLimit = 1)
            assertNotEquals(first.entries.single().id, second.entries.single().id)
            val partial = filteredReviewPage(memory, film, 0, "Câu 3", "ALL", resultLimit = 1, scanLimit = 2)
            assertTrue(partial.entries.isEmpty())
            assertNotNull(partial.nextId)
            val found = filteredReviewPage(memory, film, checkNotNull(partial.nextId), "Câu 3", "ALL", resultLimit = 1, scanLimit = 2)
            assertEquals("Câu 3", found.entries.single().translation)
            assertNull(found.nextId)
        } finally { memory.close() }
    }
}
