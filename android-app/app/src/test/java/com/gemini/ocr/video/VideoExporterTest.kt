@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import androidx.media3.common.util.Size
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoExporterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun cue(id: String, start: Long, end: Long) =
        VideoCue(id, start, end, "你好", "Xin chào, tôi không đồng ý: 250 người.")

    private fun project(cues: List<VideoCue> = listOf(cue("one", 1000, 2000))) = VideoProject(
        "export-test", "video", "file:///source.mp4", 10_000, 1920, 1080, cues = cues
    )

    @Test fun adjacentIdsWithSameVisibleTextReuseBitmapButRealGapClearsIt() {
        val overlay=TimelineBitmapOverlay(listOf(cue("a",0,1000),cue("b",1000,2000),cue("later",2500,3000)),VideoSubtitleStyle())
        overlay.configure(Size(1280,720))
        try {
            val bitmap=overlay.getBitmap(500_000)
            assertSame(bitmap,overlay.getBitmap(1500_000));assertFalse(bitmap.isRecycled)
            assertEquals(1,overlay.getBitmap(2200_000).width);assertTrue(bitmap.isRecycled)
            assertNotSame(bitmap,overlay.getBitmap(2700_000))
        }finally{overlay.release()}
    }
    @Test fun futureEditKeepsCurrentBitmapAndSameIdVisibleTextEditReplacesIt() {
        val first=cue("a",0,1000);val second=cue("b",1000,2000)
        val overlay=TimelineBitmapOverlay(listOf(second,first),VideoSubtitleStyle())
        overlay.configure(Size(1280,720))
        try {
            val bitmap=overlay.getBitmap(500_000)
            overlay.updateTimeline(listOf(first,second.copy(translation="Tạm biệt")))
            assertSame(bitmap,overlay.getBitmap(600_000))
            overlay.updateTimeline(listOf(first.copy(translation="Xin đừng đi"),second))
            assertNotSame(bitmap,overlay.getBitmap(700_000));assertTrue(bitmap.isRecycled)
        }finally{overlay.release()}
    }

    @Test fun framePtsUsesHalfOpenCueBoundsAndReleasesCompletedCueBitmaps() {
        val rows = listOf(cue("one", 1000, 2000), cue("repeat", 7_200_000, 7_201_000))
        val overlay = TimelineBitmapOverlay(rows, VideoSubtitleStyle())
        overlay.configure(Size(1280, 720))
        try {
            assertEquals(1, overlay.getBitmap(999_999).width)
            assertEquals(0f, overlay.getOverlaySettings(999_999).alphaScale, 0f)
            val first = overlay.getBitmap(1_000_000)
            assertTrue(first.width > 100)
            assertSame(first, overlay.getBitmap(1_999_999))
            assertEquals(1f, overlay.getOverlaySettings(1_999_999).alphaScale, 0f)
            assertEquals(1, overlay.getBitmap(2_000_000).width)
            assertTrue(first.isRecycled)
            // Two hours later, use media PTS directly rather than an accumulated frame counter.
            val repeated = overlay.getBitmap(7_200_000_000)
            assertNotSame(first, repeated)
            assertSame(repeated, overlay.getBitmap(7_200_999_999))
            assertEquals(1, overlay.getBitmap(7_201_000_000).width)
            assertTrue(repeated.isRecycled)
        } finally { overlay.release() }
    }

    @Test fun stripRendersVietnameseWithOpaqueMaskAndAnchorsAtSelectedPosition() {
        val style = VideoSubtitleStyle(position = .8f, sizeFraction = .05f, background = true, outline = true)
        val overlay = TimelineBitmapOverlay(listOf(cue("one", 1000, 2000)), style)
        overlay.configure(Size(1920, 1080))
        try {
            val image = overlay.getBitmap(1_500_000)
            assertEquals(android.graphics.Color.BLACK, image.getPixel(0, 0))
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            assertTrue("Vietnamese text must draw visible foreground pixels", pixels.any {
                android.graphics.Color.red(it) > 150 && android.graphics.Color.alpha(it) > 0
            })
            val center = .8f * 1080 - image.height / 2f
            assertEquals(1f - 2f * center / 1080, overlay.getOverlaySettings(1_500_000).backgroundFrameAnchor.second, .0001f)
            overlay.configure(Size(1080, 1920))
            assertTrue(image.isRecycled)
            assertTrue(overlay.getBitmap(1_500_000).width < 1080)
        } finally { overlay.release() }
    }

    @Test fun invalidOrUntranslatedTimelineFailsBeforeAnyEncoding() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoExporter.validate(project(listOf(cue("one", 1000, 2000).copy(translation = ""))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExporter.validate(project(listOf(cue("one", 1000, 2000), cue("two", 1900, 3000))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExporter.validate(project().copy(style = VideoSubtitleStyle(position = Float.NaN)))
        }
    }

    @Test fun livePreflightDoesNotCreateAnOutputOrConsumeGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(temp.root, "export.mp4")
        val previous = SessionState.running.value
        SessionState.running.value = true
        var gates = 0
        try {
            assertThrows(VideoExporter.LivePreemptedException::class.java) {
                runBlocking { VideoExporter.export(context, project(), output, gate = { gates++ }) }
            }
            assertEquals(0, gates)
            assertFalse(output.exists())
            assertTrue(temp.root.listFiles()!!.isEmpty())
        } finally { SessionState.running.value = previous }
    }

    @Test fun cancellationAtGateLeavesNoPartialAndExistingOutputIsNeverReplaced() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(temp.root, "export.mp4")
        val previous = SessionState.running.value
        SessionState.running.value = false
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    VideoExporter.export(context, project(), output, gate = { throw CancellationException("pause") })
                }
            }
            assertTrue(temp.root.listFiles()!!.isEmpty())
            output.writeText("keep-existing-export")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { VideoExporter.export(context, project(), output) }
            }
            assertEquals("keep-existing-export", output.readText())
            assertEquals(1, temp.root.listFiles()!!.size)
        } finally { SessionState.running.value = previous }
    }
}
