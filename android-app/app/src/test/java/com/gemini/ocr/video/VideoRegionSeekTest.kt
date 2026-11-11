package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoRegionSeekTest {
    @Test fun seekNeverRequestsPastTheLastFrameOrBeforeTheStart() {
        assertEquals(0L,VideoRegionSeek.clamp(-5000,8000))
        assertEquals(7999L,VideoRegionSeek.clamp(15000,8000))
        assertEquals(5432L,VideoRegionSeek.clamp(5432,8000))
        assertEquals(0L,VideoRegionSeek.clamp(5000,0))
        assertEquals(0L,VideoRegionSeek.end(Long.MIN_VALUE))
    }
    @Test fun timestampsCoverShortVideosAndLongEpisodes() {
        assertEquals("00:00",VideoRegionSeek.time(-1))
        assertEquals("07:44",VideoRegionSeek.time(464000))
        assertEquals("1:17:22",VideoRegionSeek.time(4642000))
    }
}
