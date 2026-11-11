package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoVisualSamplingTest {
    @Test fun thirtyFpsRoundingDoesNotAccumulateIntoASlowerSamplingInterval(){
        var next=VideoVisualSampling.firstGridAtOrAfter(0)
        val selected=mutableListOf<Long>()
        for(frame in 0..60){val ms=frame*1000L/30
            if(ms>=next){selected+=ms;next=VideoVisualSampling.firstGridStrictlyAfter(ms)}}
        assertEquals(listOf(0L,266L,500L,766L,1000L,1266L,1500L,1766L,2000L),selected)
    }
    @Test fun resumeDoesNotRepeatTheLastCompletedSample(){
        assertEquals(4000L,VideoVisualSampling.firstGridAtOrAfter(3751))
        assertEquals(4000L,VideoVisualSampling.firstGridStrictlyAfter(3750))
        assertEquals(4000L,VideoVisualSampling.firstGridAtOrAfter(4000))
        assertEquals(0L,VideoVisualSampling.firstGridAtOrAfter(0))
    }
    @Test fun fastSamplingHalvesImageCountAndResumesOnItsOwnGrid(){
        val selected=mutableListOf<Long>();var next=VideoVisualSampling.firstGridAtOrAfter(0,500)
        for(frame in 0..60){val ms=frame*1000L/30
            if(ms>=next){selected+=ms;next=VideoVisualSampling.firstGridStrictlyAfter(ms,500)}}
        assertEquals(listOf(0L,500L,1000L,1500L,2000L),selected)
        assertEquals(4000L,VideoVisualSampling.firstGridAtOrAfter(3751,500))
        assertEquals(4500L,VideoVisualSampling.firstGridAtOrAfter(4001,500))
        // No frame in this 300ms display window is selected: fast mode has a real tradeoff.
        assertFalse(selected.any{it in 100L..399L})
        assertThrows(IllegalArgumentException::class.java){VideoVisualSampling.firstGridAtOrAfter(0,1000)}
    }
}
