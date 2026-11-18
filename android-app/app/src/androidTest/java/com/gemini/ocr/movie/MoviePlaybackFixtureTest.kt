package com.gemini.ocr.movie

import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoPreviewDiagnostics
import com.gemini.ocr.video.VideoPreviewFrame
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MoviePlaybackFixtureTest {
    @Test fun seekPauseEditAndAdvanceWithoutShowingThePreviousEpisode(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeTemporalFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK &&
            context.packageName == "com.gemini.ocr.subtitles1816")
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "temporal-fixture")
        suspend fun episode(index: Int, name: String): MovieEpisode {
            val file = File(folder, name)
            val uri = Uri.fromFile(file)
            val metadata = VideoDecoder.metadata(context, uri)
            val id = UUID.randomUUID().toString()
            val rows = listOf(VideoCue("a", 500, 820, "你好", "Tập $index câu A"),
                VideoCue("b", 820, 1600, "再见", "Tập $index câu B"))
            val project = VideoProject(id, "Tập $index", uri.toString(), metadata.durationMs, metadata.width,
                metadata.height, stage = VideoStage.REVIEW, scanMs = metadata.durationMs,
                sourceMode = VideoSourceMode.SIDECAR, cues = rows)
            val (sha, bytes) = MovieSourceAccess.inspect(context, uri)
            return MovieEpisode(id, "Tập $index", file.absolutePath, metadata.durationMs,
                source = MovieSourceSnapshot.freeze(project, sha, bytes))
        }
        val first = episode(1, "cfr.mp4")
        val second = episode(2, "vfr.mp4")
        val library = MovieLibrary.get(context)
        val movie = library.save(MovieRecord(UUID.randomUUID().toString(), "Fixture tổng hợp", listOf(first, second)))
        MoviePlaybackSettings(autoNext = true).save(context)

        val active = AtomicReference<Pair<String, Player>?>(null)
        val frame = AtomicReference<VideoPreviewFrame?>(null)
        val originalPlayerHook = VideoPreviewDiagnostics.onPlayerForTest
        val originalFrameHook = VideoPreviewDiagnostics.onFrameForTest
        val projects = setOf(first.id, second.id)
        VideoPreviewDiagnostics.onPlayerForTest = { id, player ->
            if (id in projects && player != null) active.set(id to player)
        }
        VideoPreviewDiagnostics.onFrameForTest = { if (it.projectId in projects) frame.set(it) }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun await(expected: String, condition: () -> Boolean) {
            try { withTimeout(12_000) { while (!condition()) delay(20) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(expected, error) }
        }
        suspend fun seek(time: Long, text: String, projectId: String = first.id) {
            frame.set(null)
            main { requireNotNull(active.get()).second.apply { pause(); seekTo(time) } }
            await("seek $time to $text") { frame.get()?.let {
                it.projectId == projectId && abs(it.positionMs - time) <= 4 && it.text == text
            } == true }
        }
        try {
            val intent = Intent(context, MoviePlaybackActivity::class.java)
                .putExtra("movie", movie.id).putExtra("episode", first.id)
            ActivityScenario.launch<MoviePlaybackActivity>(intent).use {
                await("first player") { active.get()?.first == first.id }
                val originalPlayer = requireNotNull(active.get()).second
                seek(900, "Tập 1 câu B")
                seek(600, "Tập 1 câu A")
                val paused = requireNotNull(frame.get())
                delay(150)
                assertEquals(paused.invalidationCount, frame.get()?.invalidationCount)
                val saved = library.savePlaybackCaption(movie.id, first.id, first.source!!.revision, "a", "Đã sửa", 600)
                await("visible edited line") { frame.get()?.text == "Đã sửa" }
                assertSame(originalPlayer, active.get()?.second)
                val editedEpisode = saved.episodes.first()
                library.savePlaybackCaption(movie.id, first.id, editedEpisode.source!!.revision, "a", null, 600)
                await("visible restored line") { frame.get()?.text == "Tập 1 câu A" }
                main { originalPlayer.seekTo(first.durationMs - 450); originalPlayer.play() }
                await("automatic next episode") { active.get()?.first == second.id }
                seek(600, "Tập 2 câu A", second.id)
                assertTrue(requireNotNull(library.find(movie.id)).episodes.first().watchState() == MovieWatchState.FINISHED)
            }
        } finally {
            VideoPreviewDiagnostics.onPlayerForTest = originalPlayerHook
            VideoPreviewDiagnostics.onFrameForTest = originalFrameHook
        }
    }
}
