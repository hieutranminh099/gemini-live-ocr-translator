package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises Media3's real parsers, never a fake parser or a timestamp-only regex. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoSubtitlesTest {
    @Test fun srtUtf8BomAndMultilineTextKeepTheirOriginalTimestamps() {
        val bytes = ("\uFEFF" + """
            1
            00:00:01,250 --> 00:00:03,750
            <i>你好，朋友</i>
            第二行

            2
            00:00:05,125 --> 00:00:06,500
            别走。
        """.trimIndent()).toByteArray(Charsets.UTF_8)

        val cues = VideoSubtitles.parse("中文.SRT", bytes, 7000)
        assertEquals(listOf(1250L to 3750L, 5125L to 6500L), cues.map { it.startMs to it.endMs })
        assertEquals(listOf("你好，朋友\n第二行", "别走。"), cues.map { it.source })
        assertTrue(cues.all { it.translation.isEmpty() && !it.approved })
    }

    @Test fun webVttCueIdentifiersAndSettingsDoNotShiftSubtitleTimes() {
        val text = """
            WEBVTT

            first-cue
            00:00:00.500 --> 00:00:02.250 align:start position:20%
            现在回家

            second-cue
            00:00:04.125 --> 00:00:05.875
            你好，世界。
        """.trimIndent()
        val cues = VideoSubtitles.parse("video.vtt", text.toByteArray(Charsets.UTF_8), 6000)

        assertEquals(listOf(500L to 2250L, 4125L to 5875L), cues.map { it.startMs to it.endMs })
        assertEquals(listOf("现在回家", "你好，世界。"), cues.map { it.source })
    }

    @Test fun assDialogueFormattingCommasAndLineBreaksKeepCentisecondTimestamps() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1280
            PlayResY: 720
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.23,0:00:03.45,Default,,0,0,0,,{\i1}你好，朋友{\i0}\N别走,请等我
            Dialogue: 0,0:00:04.56,0:00:06.78,Default,,0,0,0,,再见。
        """.trimIndent()
        val cues = VideoSubtitles.parse("video.ass", ass.toByteArray(Charsets.UTF_8), 7000)

        assertEquals(listOf(1230L to 3450L, 4560L to 6780L), cues.map { it.startMs to it.endMs })
        assertEquals(listOf("你好，朋友\n别走,请等我", "再见。"), cues.map { it.source })
    }

    @Test fun srtExportRoundTripPreservesLiteralMarkupVietnameseAndMilliseconds() {
        val cue = VideoCue("edited", 1251, 3759, "你好", "<b>Đừng đi</b> & chờ tôi > đó\nDòng thứ hai: 你好")
        val encoded = VideoTimeline.srt(listOf(cue)).toByteArray(Charsets.UTF_8)
        val output = encoded.toString(Charsets.UTF_8)
        assertTrue(output.contains("00:00:01,251 --> 00:00:03,759"))
        assertTrue(output.contains("&lt;b&gt;Đừng đi&lt;/b&gt; &amp; chờ tôi &gt; đó"))

        val readBack = VideoSubtitles.parse("translated.srt", encoded, 4000).single()
        assertEquals(cue.translation, readBack.source)
        assertEquals(cue.startMs, readBack.startMs)
        assertEquals(cue.endMs, readBack.endMs)
    }

    @Test fun repeatedSidecarTextRetainsBothOccurrencesAndTheGap() {
        val srt = "1\n00:00:00,100 --> 00:00:01,000\n别走\n\n2\n00:00:02,000 --> 00:00:03,000\n别走\n"
        val cues = VideoSubtitles.parse("repeat.srt", srt.toByteArray(Charsets.UTF_8), 4000)
        assertEquals(2, cues.size)
        assertEquals(listOf(100L to 1000L, 2000L to 3000L), cues.map { it.startMs to it.endMs })
        assertEquals(cues[0].source, cues[1].source)
        assertNotEquals(cues[0].id, cues[1].id)
        assertNull(VideoTimeline.active(cues, 1500))
    }

    @Test fun emptyAndOverlappingSidecarsCannotBecomeAnApparentlyValidProject() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoSubtitles.parse("empty.srt", ByteArray(0), 5000)
        }
        val overlap = "1\n00:00:00,100 --> 00:00:02,000\n你好\n\n2\n00:00:01,000 --> 00:00:03,000\n再见\n"
        assertThrows(IllegalArgumentException::class.java) {
            VideoSubtitles.parse("overlap.srt", overlap.toByteArray(Charsets.UTF_8), 5000)
        }
        val outside = "1\n00:00:01,000 --> 00:00:10,000\n你好\n"
        assertThrows(IllegalArgumentException::class.java) {
            VideoSubtitles.parse("outside.srt", outside.toByteArray(Charsets.UTF_8), 5000)
        }
    }
}
