package com.gemini.ocr.movie

import android.content.Context
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gemini.ocr.MainActivity
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.sync.VideoCloudSync
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Navigation only: Robolectric records the playback intent without opening OAuth or media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovieCloudPlaybackUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var movie: MovieRecord
    private lateinit var manifestBefore: ByteArray
    private val account = "viewer@example.com"
    private val reset = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            VideoCloudSync.get(context).apply {
                disconnect()
                rememberVerifiedAccount(account)
            }
            SessionState.running.value = false
            val first = MovieEpisode("ep-1", "Synthetic episode one", "", 10_000)
            val second = MovieEpisode("ep-2", "Synthetic episode two", "", 10_000)
            movie = MovieRecord(UUID.randomUUID().toString(), "Synthetic cloud movie", listOf(first, second),
                accountEmail = account, syncState = "CLOUD", cloudId = "synthetic-manifest")
            val library = MovieLibrary.get(context)
            library.save(movie)
            manifestBefore = File(library.folder(movie.id), "movie.json").readBytes()
        }

        override fun after() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val library = MovieLibrary.get(context)
            val fixture = library.folder(movie.id)
            AtomicFile(File(fixture, "movie.json")).delete()
            fixture.delete()
            library.refresh()
            VideoCloudSync.get(context).disconnect()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @Test fun cloudOnlyMovieOffersWatchAndOfflineAsSeparateActions() {
        openLibrary()
        scrollToMovieCard()
        movieAction("Xem ngay").performScrollTo().assertIsEnabled()
        movieAction("Tải ngoại tuyến").assertIsEnabled()
        movieAction("Xem ngay").performClick()
        assertPlaybackIntent("ep-1")
    }

    @Test fun choosingCloudEpisodeOpensThatEpisodeWithoutDownloadingOrStartingTranslation() {
        openLibrary()
        scrollToMovieCard()
        movieAction("Danh sách tập").performScrollTo().performClick()
        movieAction("Synthetic episode two").performScrollTo().assertIsEnabled().performClick()
        assertPlaybackIntent("ep-2")
    }

    @Test fun anotherGoogleAccountsMovieIsHidden() {
        compose.runOnIdle {
            val library = MovieLibrary.get(compose.activity)
            library.save(movie.copy(accountEmail = "another@example.com"))
        }
        openLibrary()
        compose.onNodeWithTag(cardTag()).assertDoesNotExist()
        movieAction("Xem ngay").assertDoesNotExist()
    }

    private fun openLibrary() {
        compose.onNodeWithText("Kho phim", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("movie_library").assertExists()
    }

    private fun scrollToMovieCard() {
        compose.onNodeWithTag("movie_library").performScrollToNode(hasTestTag(cardTag()))
    }

    private fun cardTag() = "movie_card_${movie.id}"

    private fun movieAction(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(cardTag())))

    private fun assertPlaybackIntent(episode: String) = compose.runOnIdle {
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(MoviePlaybackActivity::class.java.name, intent.component?.className)
        assertEquals(movie.id, intent.getStringExtra("movie"))
        assertEquals(episode, intent.getStringExtra("episode"))
        val library = MovieLibrary.get(compose.activity)
        assertArrayEquals(manifestBefore, File(library.folder(movie.id), "movie.json").readBytes())
        assertTrue(library.folder(movie.id).listFiles().orEmpty().all { it.name == "movie.json" })
        assertTrue(library.find(movie.id)!!.episodes.all { it.path.isBlank() && it.mediaUri.isBlank() })
        assertNull(VideoJobs.get(compose.activity).busy.value)
        assertNull(VideoJobs.get(compose.activity).batchBusy.value)
    }
}
