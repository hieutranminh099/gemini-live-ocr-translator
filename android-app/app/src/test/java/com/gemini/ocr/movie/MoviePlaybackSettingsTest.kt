package com.gemini.ocr.movie

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoSubtitleFrame
import com.gemini.ocr.video.VideoSubtitleStyle
import com.gemini.ocr.video.VideoTimeline
import com.gemini.ocr.video.movieDoubleTapTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoviePlaybackSettingsTest {
    private val cue = VideoCue("cue", 87, 407, "好的", "Được", approved = true)
    private val project = VideoProject("fixture", "Fixture", "file:///fixture.mp4", 850, 640, 360, cues = listOf(cue))

    @Test fun onlySavedOverlayCanChangeLanguageAndLegacyVideoHasNoOverlaySwitch() {
        assertEquals(MovieSubtitleMode.entries.toList(), movieSubtitleModes(project))
        assertEquals(listOf(MovieSubtitleMode.TRANSLATED, MovieSubtitleMode.OFF),
            movieSubtitleModes(project.copy(sourceMode = VideoSourceMode.OCR)))
        assertTrue(movieSubtitleModes(project.copy(cues = emptyList())).isEmpty())
        val projected = movieDisplayCues(project.cues, MovieSubtitleMode.BOTH)
        assertNull(VideoTimeline.active(projected, 86))
        assertEquals("好的\nĐược", VideoTimeline.active(projected, 87)?.translation)
        assertEquals(cue.id, VideoTimeline.active(projected, 406)?.id)
        assertNull(VideoTimeline.active(projected, 407))
        assertEquals("Được", project.cues.single().translation)
    }

    @Test fun stylePreferencesPersistOnlyForPlaybackAndKeepCustomFrameInsideTheImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val style = VideoSubtitleStyle(frame = VideoSubtitleFrame(.09f, .56f, .91f, .86f))
        val settings = MoviePlaybackSettings(MovieSubtitleMode.BOTH, true, 6000, .055f, .95f, MovieTextColor.CYAN,
            MovieTextColor.WHITE, .12f, true, false, 1.3f)
        settings.save(context)
        val saved = MoviePlaybackSettings.load(context)
        assertEquals(settings, saved)
        assertSame(style, MoviePlaybackSettings().style(style))
        val viewerStyle = saved.style(style)
        assertEquals(.95f, viewerStyle.frame!!.bottom, .0001f)
        assertEquals(.86f, style.frame!!.bottom, .0001f)
        assertFalse(viewerStyle.background)
        assertEquals(MovieTextColor.CYAN.argb, saved.appearance().textColor)
    }

    @Test fun seekClampsToDurationAndFinishedEpisodeRestartsFromBeginning() {
        assertEquals(0L, movieDoubleTapTarget(.19f, 7900, 60_000))
        assertEquals(60_000L, movieDoubleTapTarget(.84f, 58_000, 60_000))
        assertEquals(15_000L, movieDoubleTapTarget(.1f, 25_000, 60_000))
        assertNull(movieDoubleTapTarget(.5f, 25_000, 60_000))
        assertEquals(0L, movieResumePosition(59_000, 60_000))
        assertEquals(57_000L, movieResumePosition(57_000, 60_000))
    }
}
