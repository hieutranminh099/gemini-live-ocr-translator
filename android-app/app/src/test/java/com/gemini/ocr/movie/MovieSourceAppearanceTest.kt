package com.gemini.ocr.movie

import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSubtitleStyle
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovieSourceAppearanceTest {
    private val cue = VideoCue("one", 100, 900, "你好", "Xin chào", approved = true, origin = "OCR")
    private fun project(style: VideoSubtitleStyle) = VideoProject("00000000-0000-0000-0000-000000000001",
        "Fixture", "content://fixtures/video", 1000, 1280, 720, style = style, cues = listOf(cue),
        scanMs = 1000, stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING)

    @Test fun oldLibraryRevisionStaysByteCompatibleWhilePlaybackDefaultImproves() {
        val style = VideoSubtitleStyle.from(JSONObject("""{"position":0.86,"sizeFraction":0.045,"background":true,"outline":true}"""))
        val snapshot = MovieSourceSnapshot.freeze(project(style), "a".repeat(64), 7, revisedAt = 100)
        assertEquals("39576e3aac574dab2f85d1420128a307d6e8b28afc627a3f20d713617989972e", snapshot.revision)
        assertEquals(1, snapshot.json(false).getInt("schema"))
        val legacyJson = snapshot.json(false).apply { remove("sourceRoi"); remove("sourceMode") }
        val restored = MovieSourceSnapshot.from(legacyJson)
        restored.validate(1000)
        assertEquals(snapshot, restored)
        assertFalse(restored.style.displayStyle().background)
        assertTrue(restored.style.background)
    }

    @Test fun sourceRegionAndSpeechModeSurviveTheLibraryAndAreCoveredByTheRevision() {
        val project = project(VideoSubtitleStyle()).copy(sourceMode = VideoSourceMode.ASR,
            roi = listOf(.1f, .65f, .9f, .73f))
        val snapshot = MovieSourceSnapshot.freeze(project, "a".repeat(64), 7, revisedAt = 100)
        val restored = MovieSourceSnapshot.from(snapshot.json(false))
        restored.validate(1000)
        assertEquals(project.roi, restored.sourceRoi)
        assertEquals(VideoSourceMode.ASR, restored.sourceMode)
        val episode = MovieEpisode("one", "Fixture", "", 1000)
        assertEquals(project.roi, restored.project(episode).roi)
        assertEquals(VideoSourceMode.ASR, restored.project(episode).sourceMode)
        val tampered = snapshot.json(false).put("sourceMode", "AUTO")
        assertThrows(IllegalArgumentException::class.java) { MovieSourceSnapshot.from(tampered).validate(1000) }
    }

    @Test fun newAppearanceIsCoveredByTheImmutableRevisionAndPortableRoundTrip() {
        val snapshot = MovieSourceSnapshot.freeze(project(VideoSubtitleStyle()), "a".repeat(64), 7, revisedAt = 100)
        val portable = snapshot.json(true)
        assertEquals(2, portable.getInt("schema"))
        MovieSourceSnapshot.from(portable).validate(1000)
        assertThrows(IllegalArgumentException::class.java) {
            MovieSourceSnapshot.from(snapshot.json(true).put("schema", 1)).validate(1000)
        }
        portable.getJSONObject("style").put("blurOriginal", false)
        assertThrows(IllegalArgumentException::class.java) { MovieSourceSnapshot.from(portable).validate(1000) }
        val changed = MovieSourceSnapshot.freeze(project(VideoSubtitleStyle(yellowText = false)), "a".repeat(64), 7, revisedAt = 100)
        assertNotEquals(snapshot.revision, changed.revision)
    }

    @Test fun strippingExplicitBlackAppearanceFieldsCannotTurnItIntoAnAuthenticatedLegacyPreset() {
        val explicitBlack = VideoSubtitleStyle(background = true, blurOriginal = false, yellowText = false)
        val snapshot = MovieSourceSnapshot.freeze(project(explicitBlack), "a".repeat(64), 7, revisedAt = 100)
        val restored = MovieSourceSnapshot.from(snapshot.json(false))
        restored.validate(1000)
        assertEquals(explicitBlack, restored.style.displayStyle())
        val tampered = snapshot.json(false)
        tampered.getJSONObject("style").apply { remove("blurOriginal"); remove("yellowText") }
        assertThrows(IllegalArgumentException::class.java) { MovieSourceSnapshot.from(tampered).validate(1000) }
        tampered.put("schema", 1)
        assertThrows(IllegalArgumentException::class.java) { MovieSourceSnapshot.from(tampered).validate(1000) }
    }

    @Test fun oldCustomTransparentStyleKeepsItsHistoricalRevisionAndAppearance() {
        val custom = VideoSubtitleStyle.from(JSONObject("""{"position":0.7,"sizeFraction":0.045,"background":false,"outline":true}"""))
        assertTrue(custom.legacyDefault)
        assertEquals(custom, custom.displayStyle())
        val snapshot = MovieSourceSnapshot.freeze(project(custom), "a".repeat(64), 7, revisedAt = 100)
        assertEquals("5b6b2bac6cd5e21ce7e4ec32e30bb42b34b349d2385bce4c6f8fbf428155cf9d", snapshot.revision)
        assertEquals(1, snapshot.json(false).getInt("schema"))
        val restored = MovieSourceSnapshot.from(snapshot.json(false))
        restored.validate(1000)
        assertEquals(custom, restored.style)
        assertFalse(restored.style.json().has("blurOriginal"))
    }
}
