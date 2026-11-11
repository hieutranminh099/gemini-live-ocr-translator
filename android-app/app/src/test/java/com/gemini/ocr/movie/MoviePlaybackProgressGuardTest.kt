package com.gemini.ocr.movie

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.sync.VideoCloudSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoviePlaybackProgressGuardTest {
    private lateinit var library: MovieLibrary
    private lateinit var movie: MovieRecord
    private lateinit var identity: VideoCloudSync
    private val account = "viewer@example.com"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        identity = VideoCloudSync.get(context)
        identity.disconnect()
        identity.rememberVerifiedAccount(account)
        library = MovieLibrary(context, validateVideo = { 10_000 }, makeCover = { _, _ -> })
        val project = VideoProject(UUID.randomUUID().toString(), "Synthetic", "", 10_000, 320, 180,
            cues = listOf(VideoCue("cue-1", 100, 500, "你好", "Xin chào", approved = true)),
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, scanMs = 10_000)
        val source = MovieSourceSnapshot.freeze(project, "a".repeat(64), 1024, revisedAt = 100)
        movie = MovieRecord(UUID.randomUUID().toString(), "Synthetic", listOf(
            MovieEpisode("ep-1", "Synthetic episode", "", 10_000, 6000, 200, source = source)),
            accountEmail = account, confirmedAt = 100, updatedAt = 200)
        library.save(movie)
        assertNotNull("The signed-in account must see its synthetic movie fixture", library.find(movie.id))
    }

    @After fun teardown() {
        if (::movie.isInitialized) {
            val fixture = library.folder(movie.id)
            AtomicFile(File(fixture, "movie.json")).delete()
            fixture.delete()
            library.refresh()
        }
        if (::identity.isInitialized) identity.disconnect()
    }

    @Test fun queuedObservationCannotReplaceNewerRemoteProgress() {
        val remote = movie.copy(episodes = movie.episodes.map { it.copy(positionMs = 8000, positionUpdatedAt = 400) },
            updatedAt = 400)
        // Persist the result of a concurrent cloud refresh after the player observed position 6500.
        library.save(remote)
        library.progress(movie.id, "ep-1", 6500, observedAt = 300,
            expectedAccount = account, expectedSourceRevision = sourceRevision())
        val saved = library.find(movie.id)!!
        assertEquals(8000L, saved.episodes.single().positionMs)
        assertEquals(400L, saved.episodes.single().positionUpdatedAt)
    }

    @Test fun newerSeekBackwardsIsAcceptedAndDoesNotUseMaximumPosition() {
        library.progress(movie.id, "ep-1", 1000, observedAt = 300,
            expectedAccount = account, expectedSourceRevision = sourceRevision())
        val saved = library.find(movie.id)!!
        assertEquals(1000L, saved.episodes.single().positionMs)
        assertEquals(300L, saved.episodes.single().positionUpdatedAt)
        assertEquals(movie.episodes.single().source, saved.episodes.single().source)
    }

    @Test fun newerProgressOnAnotherEpisodeDoesNotInflateAnObservationTimestamp() {
        val first = movie.episodes.single()
        val second = MovieEpisode("ep-2", "Synthetic episode two", "", 10_000, 2000, 10_000)
        val local = movie.copy(episodes = listOf(first, second), syncState = "CLOUD", updatedAt = 10_000)
        library.save(local)

        library.progress(movie.id, first.id, 6500, observedAt = 300,
            expectedAccount = account, expectedSourceRevision = sourceRevision())
        val observed = library.find(movie.id)!!
        assertEquals(6500L, observed.episodes.first().positionMs)
        assertEquals(300L, observed.episodes.first().positionUpdatedAt)
        assertEquals(10_001L, observed.updatedAt)

        val remoteFirst = first.copy(positionMs = 8000, positionUpdatedAt = 400)
        val remoteSecond = second.copy(positionMs = 0, positionUpdatedAt = 0)
        library.mergeCloud(account, listOf(local.copy(episodes = listOf(remoteFirst, remoteSecond), updatedAt = 400)))
        assertEquals(listOf(remoteFirst, second), library.find(movie.id)!!.episodes)
    }

    @Test fun previousAccountCannotWriteAfterOwnershipChanged() {
        library.progress(movie.id, "ep-1", 1000, observedAt = 300,
            expectedAccount = "previous@example.com", expectedSourceRevision = sourceRevision())
        assertEquals(movie, library.find(movie.id))
    }

    @Test fun previousSubtitleSnapshotCannotUpdateCurrentEpisodesProgress() {
        library.progress(movie.id, "ep-1", 1000, observedAt = 300,
            expectedAccount = account, expectedSourceRevision = "b".repeat(64))
        assertEquals(movie, library.find(movie.id))
    }

    private fun sourceRevision() = movie.episodes.single().source!!.revision
}
