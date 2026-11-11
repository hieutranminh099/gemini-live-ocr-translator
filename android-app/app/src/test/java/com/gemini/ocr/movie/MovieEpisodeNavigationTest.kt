package com.gemini.ocr.movie

import org.junit.Assert.*
import org.junit.Test

class MovieEpisodeNavigationTest {
    private val movie = MovieRecord("11111111-2222-3333-4444-555555555555", "Saved order",
        listOf(MovieEpisode("ep10", "10", "", 1000, positionMs = 700),
            MovieEpisode("ep2", "2", "", 1000, positionMs = 300),
            MovieEpisode("ep3", "3", "", 1000)))

    @Test fun swipeUsesSavedOrderAndKeepsTheNextEpisodesResumePosition() {
        val next = requireNotNull(movieAdjacentEpisode(movie, "ep10", 1))
        assertEquals("ep2", next.id)
        assertEquals(300L, next.positionMs)
        assertEquals("ep10", movieAdjacentEpisode(movie, "ep2", -1)?.id)
    }

    @Test fun listEndsUnknownEpisodesAndInvalidDirectionsDoNotWrapOrSkip() {
        assertNull(movieAdjacentEpisode(movie, "ep10", -1))
        assertNull(movieAdjacentEpisode(movie, "ep3", 1))
        assertNull(movieAdjacentEpisode(movie, "removed", 1))
        assertNull(movieAdjacentEpisode(movie, "ep2", 2))
    }
}
