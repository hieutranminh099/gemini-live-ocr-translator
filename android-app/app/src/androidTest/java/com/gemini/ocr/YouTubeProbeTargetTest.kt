package com.gemini.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** URL validation only; no navigation, playback or API calls. */
@RunWith(AndroidJUnit4::class)
class YouTubeProbeTargetTest {
    @Test fun watchAndShortLinksBecomeOnePublicVideoCoordinate() {
        val target = YouTubeProbeTarget.parse("https://www.youtube.com/watch?v=AbcDEf_12-3&t=999s", 936)
        assertEquals("AbcDEf_12-3", target.videoId)
        assertEquals("https://www.youtube.com/watch?v=AbcDEf_12-3&t=936s", target.uri.toString())
        assertEquals(target, YouTubeProbeTarget.parse("https://youtu.be/AbcDEf_12-3", 936))
    }

    @Test fun otherHostsCredentialsLocalFilesAndExtraQueryDataAreRejected() {
        for (url in listOf("http://www.youtube.com/watch?v=AbcDEf_12-3",
            "file:///sdcard/movie.mp4", "https://example.invalid/watch?v=AbcDEf_12-3",
            "https://www.youtube.com.evil.invalid/watch?v=AbcDEf_12-3",
            "https://user:pass@www.youtube.com/watch?v=AbcDEf_12-3",
            "https://www.youtube.com/watch?v=AbcDEf_12-3&token=private",
            "https://www.youtube.com/watch?v=AbcDEf_12-3&list=private-list",
            "https://www.youtube.com/watch?v=AbcDEf_12-3&v=ZbcDEf_12-3",
            "https://www.youtube.com:8443/watch?v=AbcDEf_12-3",
            "https://www.youtube.com/watch?v=short", "https://youtu.be/AbcDEf_12-3/extra")) {
            try { YouTubeProbeTarget.parse(url, 0); fail("Unexpected navigation accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun startTimeCannotAddressUnboundedOrNegativePlayback() {
        for (time in listOf(-1, 21601)) {
            try { YouTubeProbeTarget.parse("https://youtu.be/AbcDEf_12-3", time); fail("Invalid time accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun copiedShortLinkStripsShareTrackingBeforeCanonicalValidation() {
        val target = YouTubeProbeTarget.fromCopiedLink("https://youtu.be/AbcDEf_12-3?si=share-marker&utm_source=share", 936)
        assertEquals("https://www.youtube.com/watch?v=AbcDEf_12-3&t=936s", target.uri.toString())
        assertFalse(target.uri.toString().contains("share-marker"))
    }

    @Test fun copiedWatchLinkStripsOnlyTrackingAndRejectsPrivateOrActionQueries() {
        val clean = YouTubeProbeTarget.fromCopiedLink("https://www.youtube.com/watch?v=AbcDEf_12-3&feature=share&utm_medium=mobile", 5)
        assertEquals("https://www.youtube.com/watch?v=AbcDEf_12-3&t=5s", clean.uri.toString())
        for (query in listOf("list=private-list", "action=delete", "token=private-value", "access_token=private-value")) {
            try {
                YouTubeProbeTarget.fromCopiedLink("https://www.youtube.com/watch?v=AbcDEf_12-3&$query", 0)
                fail("Unexpected clipboard navigation accepted")
            } catch (error: IllegalArgumentException) {
                assertFalse(error.message.orEmpty().contains("private-value"))
                assertFalse(error.message.orEmpty().contains("private-list"))
            }
        }
    }

    @Test fun malformedOrNonVideoClipboardItemsFailWithoutEchoingTheirContents() {
        for (raw in listOf("private-clipboard-value", "file:///sdcard/private-clipboard-value",
            "https://www.youtube.com/watch?v=AbcDEf_12-3&v=ZbcDEf_12-3", "x".repeat(2049))) {
            try { YouTubeProbeTarget.fromCopiedLink(raw, 0); fail("Invalid copied item accepted") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("private-clipboard-value")) }
        }
    }
}
