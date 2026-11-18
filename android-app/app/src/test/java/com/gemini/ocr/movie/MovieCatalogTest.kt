package com.gemini.ocr.movie

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovieCatalogTest {
    @Test fun matchesEpisodeTitleAndActualStatusInLargeCatalogs() {
        for (size in listOf(10, 50, 100, 500)) {
            val movies = List(size) { index ->
                val id = "movie-$index"
                MovieRecord(id, "Bộ phim $index", listOf(MovieEpisode(id, "Tập số $index", "/movie/video.mp4",
                    10_000, positionMs = if (index == size - 1) 10_000 else 0, imported = true)),
                    confirmedAt = 20L, updatedAt = 100L + index)
            }
            val target = "Tập số ${size - 1}"
            assertEquals(listOf("movie-${size - 1}"), sortedMovies(movies, target, MovieSort.RECENT).map { it.id })
            assertEquals(1, movies.sumOf { matchingMovieEpisodes(it, "", MovieFilter.WATCHED).size })
            assertEquals(size, movies.sumOf { matchingMovieEpisodes(it, "", MovieFilter.IMPORTED).size })
            assertEquals(0, movies.sumOf { matchingMovieEpisodes(it, "", MovieFilter.TRANSLATED).size })
            assertEquals(movies.last().id, sortedMovies(movies, "", MovieSort.RECENT).first().id)
        }
    }

    @Test fun naturalEpisodeOrderRequiresUniqueExplicitEpisodeNumbers() {
        fun episode(id: String, title: String) = MovieEpisode(id, title, "", 1000)
        val episodes = listOf(episode("ten", "Episode 10"), episode("two", "第2集"), episode("one", "Tập 1"))
        assertEquals(listOf("one", "two", "ten"), suggestedMovieOrder(episodes))
        assertNull(suggestedMovieOrder(episodes + episode("duplicate", "EP02")))
        assertNull(suggestedMovieOrder(episodes + episode("no-number", "Mở đầu")))
        assertNull(suggestedMovieOrder(episodes + episode("season", "S02E02")))
        assertEquals(listOf("ten", "two", "one"), episodes.map { it.id })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun coverDecodeIsBoundedAndReusesTheSameRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cover = File(context.cacheDir, "${UUID.randomUUID()}.png")
        try {
            val original = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            cover.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            original.recycle()
            val cached = requireNotNull(MovieCoverCache.read("test", cover.absolutePath))
            assertTrue(maxOf(cached.width, cached.height) <= 640)
            assertSame(cached, MovieCoverCache.read("test", cover.absolutePath))
        } finally {
            cover.delete()
        }
    }
}
