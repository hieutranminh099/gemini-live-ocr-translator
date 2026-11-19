package com.gemini.ocr.video

import android.view.Choreographer
import androidx.media3.common.Player
import com.gemini.ocr.BuildConfig
import kotlin.math.abs

/** One display-frame request per player owner. No elapsed wall clock estimates media time. */
internal interface VideoFrameScheduler {
    fun request(delayMs: Long, frame: () -> Unit)
    fun cancel()
}

internal class AndroidVideoFrameScheduler : VideoFrameScheduler {
    private val choreographer=Choreographer.getInstance()
    private var pending:Choreographer.FrameCallback?=null
    override fun request(delayMs: Long, frame: () -> Unit) {
        check(pending==null)
        lateinit var callback:Choreographer.FrameCallback
        callback=Choreographer.FrameCallback { if(pending===callback){pending=null;frame()} }
        pending=callback
        if (delayMs == 0L) choreographer.postFrameCallback(callback)
        else choreographer.postFrameCallbackDelayed(callback, delayMs)
    }
    override fun cancel(){pending?.let{choreographer.removeFrameCallback(it)};pending=null}
}

/** Reads the real player position on events and display frames; late cancelled frames are ignored. */
internal class VideoSubtitleClock(
    private val scheduler:VideoFrameScheduler,
    private val position:()->Long,
    private val playing:()->Boolean,
    private val ended:()->Boolean,
    private val delayAfterPosition: (Long) -> Long = { 0L },
    private val publish:(Long,Boolean)->Unit
) {
    private var enabled=false
    private var released=false
    private var scheduled=false
    private var delayedFrame = false
    private var generation=0L
    fun resume(){if(released)return;enabled=true;signal()}
    fun suspend(){enabled=false;cancelFrame()}
    fun release(){released=true;enabled=false;cancelFrame()}
    fun signal(){
        if(!enabled || released)return
        // A seek, speed change or timeline edit can move a subtitle boundary ahead of a delayed tick.
        if (delayedFrame) cancelFrame()
        tick()
    }
    private fun tick() {
        val current = position().coerceAtLeast(0)
        publish(current,ended())
        if(!enabled || released)return
        if(playing() && !ended())schedule(delayAfterPosition(current)) else cancelFrame()
    }
    private fun schedule(delayMs: Long){
        if(scheduled)return
        scheduled=true
        delayedFrame = delayMs > 0
        val owner=generation
        scheduler.request(delayMs) {
            if(released || !enabled || owner!=generation)return@request
            scheduled=false
            delayedFrame=false
            tick()
        }
    }
    private fun cancelFrame(){generation++;if(scheduled)scheduler.cancel();scheduled=false;delayedFrame=false}
}

/** Sleep between boundaries, then sample actual media time each display frame near a transition. */
internal fun movieSubtitlePollDelayMs(position: Long, boundary: Long?, speed: Float): Long {
    if (!speed.isFinite() || speed <= 0f) return 0L
    if (boundary == null) return 200L
    val untilBoundaryMs = ((boundary - position) / speed.toDouble()).toLong()
    return (untilBoundaryMs - 80L).coerceIn(0L, 200L)
}

/** Callback belongs to its player, even while Compose replaces the currently selected project. */
internal class VideoPositionReporter(initialPosition: Long = -1000, private val intervalMs: Long = 1000,
    var callback: (Long) -> Unit
) {
    var onFlush: () -> Unit = {}
    private var last = initialPosition
    private var changed = false
    fun report(position:Long) {
        if (abs(position - last) >= intervalMs) publish(position)
    }
    fun flush(position:Long) {
        if (position != last) publish(position)
        if (changed) onFlush()
        changed = false
    }
    private fun publish(position: Long) {
        last = position
        changed = true
        callback(position)
    }
}

internal data class VideoPreviewFrame(val projectId:String,val positionMs:Long,val cueId:String?,
    val text:String,val ended:Boolean,val invalidationCount:Long,val drawCount:Long)

/** Inert unless an opt-in native test in its own package installs a callback. Never persisted. */
internal object VideoPreviewDiagnostics {
    @Volatile internal var onPlayerForTest:((String,Player?)->Unit)?=null
    @Volatile internal var onFrameForTest:((VideoPreviewFrame)->Unit)?=null
    fun player(projectId:String,player:Player?){if(BuildConfig.DEBUG)onPlayerForTest?.invoke(projectId,player)}
    inline fun frame(observation: () -> VideoPreviewFrame) {
        if (BuildConfig.DEBUG) onFrameForTest?.invoke(observation())
    }
}
