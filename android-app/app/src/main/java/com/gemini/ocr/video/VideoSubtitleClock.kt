package com.gemini.ocr.video

import android.view.Choreographer
import androidx.media3.common.Player
import com.gemini.ocr.BuildConfig
import kotlin.math.abs

/** One display-frame request per player owner. No elapsed wall clock estimates media time. */
internal interface VideoFrameScheduler {
    fun request(frame:()->Unit)
    fun cancel()
}

internal class AndroidVideoFrameScheduler : VideoFrameScheduler {
    private val choreographer=Choreographer.getInstance()
    private var pending:Choreographer.FrameCallback?=null
    override fun request(frame:()->Unit) {
        check(pending==null)
        lateinit var callback:Choreographer.FrameCallback
        callback=Choreographer.FrameCallback { if(pending===callback){pending=null;frame()} }
        pending=callback;choreographer.postFrameCallback(callback)
    }
    override fun cancel(){pending?.let{choreographer.removeFrameCallback(it)};pending=null}
}

/** Reads the real player position on events and display frames; late cancelled frames are ignored. */
internal class VideoSubtitleClock(
    private val scheduler:VideoFrameScheduler,
    private val position:()->Long,
    private val playing:()->Boolean,
    private val ended:()->Boolean,
    private val publish:(Long,Boolean)->Unit
) {
    private var enabled=false
    private var released=false
    private var scheduled=false
    private var generation=0L
    fun resume(){if(released)return;enabled=true;signal()}
    fun suspend(){enabled=false;cancelFrame()}
    fun release(){released=true;enabled=false;cancelFrame()}
    fun signal(){
        if(!enabled || released)return
        publish(position().coerceAtLeast(0),ended())
        if(!enabled || released)return
        if(playing() && !ended())schedule() else cancelFrame()
    }
    private fun schedule(){
        if(scheduled)return
        scheduled=true
        val owner=generation
        scheduler.request {
            if(released || !enabled || owner!=generation)return@request
            scheduled=false
            signal()
        }
    }
    private fun cancelFrame(){generation++;if(scheduled)scheduler.cancel();scheduled=false}
}

/** Callback belongs to its player, even while Compose replaces the currently selected project. */
internal class VideoPositionReporter(initialPosition:Long = -1000, var callback:(Long)->Unit) {
    var onFlush: () -> Unit = {}
    private var last = initialPosition
    private var changed = false
    fun report(position:Long) {
        if (abs(position - last) >= 1000) publish(position)
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
    fun frame(frame:VideoPreviewFrame){if(BuildConfig.DEBUG)onFrameForTest?.invoke(frame)}
}
