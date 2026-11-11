package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test

class VideoSubtitleClockTest {
    private class Frames:VideoFrameScheduler {
        var pending:(()->Unit)?=null
        var requests=0
        override fun request(frame:()->Unit){check(pending==null);pending=frame;requests++}
        override fun cancel(){pending=null}
        fun fire(){val callback=requireNotNull(pending);pending=null;callback()}
    }
    private data class Tick(val position:Long,val ended:Boolean)

    @Test fun displayFramesUseActualMediaPositionAndRetainAShortCue(){
        val frames=Frames();var position=0L
        val samples=mutableListOf<String?>()
        val cues=listOf(VideoCue("first",0,65,"甲","Một"),VideoCue("short",65,130,"乙","Hai"),
            VideoCue("last",130,250,"丙","Ba"))
        val clock=VideoSubtitleClock(frames,{position},{true},{false}){p,_->samples+=VideoTimeline.active(cues,p)?.id}
        clock.resume()
        for(ms in listOf(33L,65L,98L,130L)){position=ms;frames.fire()}
        assertEquals(listOf("first","first","short","short","last"),samples)
        clock.release()
    }
    @Test fun seekWhilePausedPublishesImmediatelyWithoutWaitingForADisplayFrame(){
        val frames=Frames();var position=59_000L
        val ticks=mutableListOf<Tick>()
        val clock=VideoSubtitleClock(frames,{position},{false},{false}){p,e->ticks+=Tick(p,e)}
        clock.resume()
        for(target in listOf(30_000L,59_950L,31_000L,59_999L,0L)){position=target;clock.signal()}
        assertEquals(listOf(59_000L,30_000L,59_950L,31_000L,59_999L,0L),ticks.map{it.position})
        assertNull(frames.pending);assertEquals(0,frames.requests)
        clock.release()
    }
    @Test fun pauseResumeAndChangedPlaybackSpeedNeverEstimateFromCallbackCount(){
        val frames=Frames();var position=1_000L;var playing=true
        val ticks=mutableListOf<Long>()
        val clock=VideoSubtitleClock(frames,{position},{playing},{false}){p,_->ticks+=p}
        clock.resume();val late=requireNotNull(frames.pending)
        playing=false;position=1_017;clock.signal()
        assertNull(frames.pending)
        late();assertEquals(listOf(1_000L,1_017L),ticks)
        playing=true;clock.signal()
        for(actual in listOf(1_058L,1_125L,1_146L)){position=actual;frames.fire()}
        assertEquals(listOf(1_000L,1_017L,1_017L,1_058L,1_125L,1_146L),ticks)
        clock.release()
    }
    @Test fun endedCancelsFramesAndReplayStartsAgainFromTheCurrentPlayerPosition(){
        val frames=Frames();var position=59_999L;var playing=true;var ended=false
        val ticks=mutableListOf<Tick>()
        val clock=VideoSubtitleClock(frames,{position},{playing},{ended}){p,e->ticks+=Tick(p,e)}
        clock.resume();val late=requireNotNull(frames.pending)
        position=60_000;ended=true;playing=false;clock.signal()
        assertEquals(Tick(60_000,true),ticks.last());assertNull(frames.pending)
        val count=ticks.size;late();assertEquals(count,ticks.size)
        position=0;ended=false;playing=true;clock.signal()
        assertEquals(Tick(0,false),ticks.last());assertNotNull(frames.pending)
        clock.release()
    }
    @Test fun stoppedScreenAndItsLateFrameCannotOverwriteAResumedScreen(){
        val frames=Frames();var position=500L;val ticks=mutableListOf<Long>()
        val clock=VideoSubtitleClock(frames,{position},{true},{false}){p,_->ticks+=p}
        clock.resume();val stale=requireNotNull(frames.pending)
        clock.suspend();position=8_000;clock.signal();assertEquals(listOf(500L),ticks)
        clock.resume();val current=requireNotNull(frames.pending)
        stale();assertSame(current,frames.pending);assertEquals(listOf(500L,8_000L),ticks)
        position=8_033;frames.fire();assertEquals(8_033L,ticks.last())
        clock.release()
    }
    @Test fun releasedOwnerIgnoresPendingFramesAndPlayerEvents(){
        val frames=Frames();var released=false;var reads=0;val ticks=mutableListOf<Long>()
        val clock=VideoSubtitleClock(frames,{check(!released);reads++;400L},{true},{false}){p,_->ticks+=p}
        clock.resume();val late=requireNotNull(frames.pending)
        clock.release();released=true
        late();clock.signal();clock.resume()
        assertNull(frames.pending);assertEquals(1,reads);assertEquals(listOf(400L),ticks)
    }
    @Test fun repeatedPlayerEventsKeepOnlyOnePendingDisplayFrame(){
        val frames=Frames()
        val clock=VideoSubtitleClock(frames,{500L},{true},{false}){_,_->}
        clock.resume();repeat(30){clock.signal()}
        assertEquals(1,frames.requests)
        frames.fire();assertEquals(2,frames.requests)
        clock.release()
    }
    @Test fun positionCallbackCanCloseTheScreenWithoutReadingAReleasedPlayer(){
        val frames=Frames();var released=false;var updates=0
        lateinit var clock:VideoSubtitleClock
        clock=VideoSubtitleClock(frames,{100L},{check(!released);true},{false}){_,_->
            updates++;clock.release();released=true
        }
        clock.resume()
        assertEquals(1,updates);assertNull(frames.pending);assertEquals(0,frames.requests)
    }
    @Test fun positionCallbackBelongsToItsPlayerEvenWhenAnotherOwnerBecomesCurrent(){
        val old=mutableListOf<Long>();val newer=mutableListOf<Long>()
        val oldReporter=VideoPositionReporter{old+=it}
        oldReporter.report(0);oldReporter.report(500);oldReporter.report(1_000)
        oldReporter.callback={old+=it} // Same owner can receive a refreshed callback.
        val newReporter=VideoPositionReporter{newer+=it}
        newReporter.report(20_000)
        oldReporter.flush(1_100)
        assertEquals(listOf(0L,1_000L,1_100L),old)
        assertEquals(listOf(20_000L),newer)
    }

    @Test fun openingAtSavedPositionDoesNotPublishAndPlaybackFlushesOnlyOnce() {
        val samples = mutableListOf<Long>()
        var finalSyncs = 0
        val reporter = VideoPositionReporter(5_000) { samples += it }
        reporter.onFlush = { finalSyncs++ }
        reporter.report(5_000)
        reporter.flush(5_000)
        assertTrue(samples.isEmpty())
        assertEquals(0, finalSyncs)
        reporter.report(6_300)
        reporter.flush(6_400)
        reporter.flush(6_400)
        assertEquals(listOf(6_300L, 6_400L), samples)
        assertEquals(1, finalSyncs)
    }
}
