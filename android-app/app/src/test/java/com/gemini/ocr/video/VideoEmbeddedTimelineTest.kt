package com.gemini.ocr.video

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class VideoEmbeddedTimelineTest {
    @Test fun emptySamplesCloseTx3gCuesWithoutFillingGapsOrRemovingRepeatedDialogue() {
        val cues=mutableListOf<VideoCue>()
        val timeline=VideoEmbeddedTimeline(3,4000,cues)
        timeline.sample(0,C.TIME_UNSET,"",true)
        timeline.sample(500_000,C.TIME_UNSET,"你好",true)
        timeline.sample(1_750_000,C.TIME_UNSET,"",true)
        timeline.sample(2_000_000,C.TIME_UNSET,"你好",true)
        timeline.sample(3_250_000,C.TIME_UNSET,"",true)
        timeline.finish()
        assertEquals(listOf(500L to 1750L,2000L to 3250L),cues.map{it.startMs to it.endMs})
        assertEquals(listOf("track-3-1","track-3-2"),cues.map{it.id})
        assertTrue(cues.all{it.source=="你好" && it.origin=="EMBEDDED"})
    }
    @Test fun replaceHonorsExplicitEarlierEndAndFinishesAnOpenLastCueAtVideoEnd() {
        val cues=mutableListOf<VideoCue>();val timeline=VideoEmbeddedTimeline(1,4000,cues)
        timeline.sample(500_000,500_000,"前",true)
        timeline.sample(2_000_000,C.TIME_UNSET,"后\n两行",true)
        timeline.finish()
        assertEquals(listOf(500L to 1000L,2000L to 4000L),cues.map{it.startMs to it.endMs})
        assertEquals("后\n两行",cues.last().source)
    }
    @Test fun explicitMergeDurationsRetainTheOriginalBounds() {
        val cues=mutableListOf<VideoCue>();val timeline=VideoEmbeddedTimeline(1,4000,cues)
        timeline.sample(200_000,700_000,"甲",false)
        timeline.sample(1_000_000,600_000,"乙",false)
        timeline.finish()
        assertEquals(listOf(200L to 900L,1000L to 1600L),cues.map{it.startMs to it.endMs})
    }
    @Test(expected=IllegalArgumentException::class) fun unknownDurationInMergeTrackRemainsInvalid() {
        VideoEmbeddedTimeline(1,4000,mutableListOf()).sample(0,C.TIME_UNSET,"甲",false)
    }
    @Test(expected=IllegalArgumentException::class) fun outOfOrderSamplesCannotSilentlyChangeTiming() {
        val timeline=VideoEmbeddedTimeline(1,4000,mutableListOf())
        timeline.sample(1000,C.TIME_UNSET,"甲",true)
        timeline.sample(999,C.TIME_UNSET,"乙",true)
    }
}
