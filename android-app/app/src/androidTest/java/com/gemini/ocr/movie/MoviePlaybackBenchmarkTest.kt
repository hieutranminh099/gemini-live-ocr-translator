@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gemini.ocr.movie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.gemini.ocr.MainActivity
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.video.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MoviePlaybackBenchmarkTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=ins.runOnMainSync(block)
    private fun reports(context: Context): File {
        val internal = InstrumentationRegistry.getArguments().getString("benchmarkInternalFiles") == "true"
        val root = if (internal) context.filesDir else requireNotNull(context.getExternalFilesDir(null))
        return File(root, "playback-bench").apply { mkdirs() }
    }
    private fun waitFor(label:String, timeout:Long=15_000, predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { check(SystemClock.elapsedRealtime()<until){"Timeout: $label"}; Thread.sleep(10) }
    }
    private fun views(view:View):List<View> = listOf(view)+ if(view is ViewGroup)
        (0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()

    @Test fun renameAndLatestEpisode():Unit=runBlocking {
        val context=ins.targetContext
        require(context.packageName=="com.gemini.ocr.playbacktest" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        val folder=reports(context)
        val report=JSONObject().put("completed",false)
        val library=MovieLibrary.get(context)
        waitFor("movie catalog"){library.movies.value.any { it.episodes.size == 3 }}
        val original=library.movies.value.first{it.episodes.size==3}
        library.save(original.copy(title="Rename Fixture"))
        library.progress(original.id,original.episodes[0].id,9000)
        val before=library.find(original.id)!!
        val device=UiDevice.getInstance(ins)
        var screen:ActivityScenario<MainActivity>?=null
        var playerScreen:ActivityScenario<MoviePlaybackActivity>?=null
        try {
            screen=ActivityScenario.launch(Intent(context,MainActivity::class.java).putExtra("ux_tab",2))
            val search=checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),8000))
            search.text="Rename Fixture"
            fun visibleOptions(): UiObject2 {
                val height = device.displayHeight
                val x = device.displayWidth / 2
                repeat(12) {
                    device.waitForIdle(1000)
                    val icon = device.findObject(By.desc("Tùy chọn phim"))
                    val button = icon?.takeIf { it.isClickable } ?: icon?.parent?.takeIf { it.isClickable }
                    val bounds = button?.visibleBounds
                    if (button != null && bounds != null && bounds.height() >= bounds.width() / 2 &&
                        bounds.centerY() in height / 5..height * 3 / 5) return button
                    // Center the button with a slow drag so the tap cannot be consumed by a fling.
                    val start = height / 2
                    val end = if (bounds != null && bounds.centerY() < height / 5) height * 2 / 3 else height / 3
                    device.swipe(x, start, x, end, 80)
                }
                error("Movie option button did not scroll into view")
            }
            fun openRename() {
                repeat(2) {
                    visibleOptions().click()
                    val rename = device.wait(Until.findObject(By.text("Đổi tên phim")), 2000)
                    if (rename != null) {
                        rename.click()
                        return
                    }
                }
                error("Movie menu did not expose the rename action")
            }
            openRename()
            val field=device.findObjects(By.clazz("android.widget.EditText")).last()
            assertEquals("Rename Fixture",field.text)
            field.text="   "
            device.findObject(By.text("Lưu")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Tên phim không được để trống.")),3000))
            device.findObject(By.text("Hủy")).click()
            assertEquals("Rename Fixture",library.find(before.id)!!.title)
            openRename()
            device.findObjects(By.clazz("android.widget.EditText")).last().text="  Phim đổi tên thử  "
            device.findObject(By.text("Lưu")).click()
            waitFor("rename persisted"){library.find(before.id)?.title=="Phim đổi tên thử"}
            device.findObject(By.clazz("android.widget.EditText")).text="Phim đổi tên thử"
            assertTrue(device.wait(Until.hasObject(By.text("Phim đổi tên thử")),5000))
            assertTrue(device.takeScreenshot(File(folder,"library.png")))
            val renamed=library.find(before.id)!!
            assertEquals(before.episodes,renamed.episodes)
            assertEquals(before.cloudId,renamed.cloudId)
            assertEquals("Phim đổi tên thử",MovieLibrary(context).find(before.id)!!.title)
            report.put("rename_prefill_cancel_blank_trim_search_persistence",true)
            screen.close();screen=null

            val active=AtomicReference<Pair<String,ExoPlayer>?>(null)
            val ready=ConcurrentHashMap.newKeySet<Player>()
            VideoPreviewDiagnostics.onPlayerForTest={id,p->if(p is ExoPlayer){active.set(id to p);p.addListener(object:Player.Listener{
                override fun onRenderedFirstFrame(){ready.add(p)}
            })}}
            playerScreen=ActivityScenario.launch(Intent(context,MoviePlaybackActivity::class.java)
                .putExtra("movie",before.id).putExtra("episode",before.episodes[0].id))
            waitFor("resume"){active.get()?.let{it.second in ready}==true}
            main{active.get()!!.second.pause();assertTrue(kotlin.math.abs(active.get()!!.second.currentPosition-9000)<600)}
            assertTrue(device.hasObject(By.text("Phim đổi tên thử")))
            assertTrue(device.hasObject(By.desc("Về Kho phim")) || device.hasObject(By.text("Về Kho phim")))
            assertTrue(device.takeScreenshot(File(folder,"player.png")))
            report.put("resume_and_player_title",true)
            fun pressEpisode(label: String) {
                waitFor("visible episode control") {
                    device.hasObject(By.desc(label)) || device.hasObject(By.text(label))
                }
                checkNotNull(device.findObject(By.desc(label)) ?: device.findObject(By.text(label))).click()
            }
            pressEpisode("Tập tiếp")
            waitFor("visible next control advanced the episode") {
                active.get()?.let { it.first == before.episodes[1].id && it.second in ready } == true
            }
            pressEpisode("Tập trước")
            waitFor("visible previous control restored the episode") {
                active.get()?.let { it.first == before.episodes[0].id && it.second in ready } == true
            }
            main { active.get()!!.second.pause() }
            report.put("visible_episode_controls", true)
            // Three navigation choices before source I/O has time to finish: final choice must win.
            playerScreen.onActivity { a ->
                val view=views(a.window.decorView).filterIsInstance<FullscreenPlayerView>().single()
                val navigate=checkNotNull(view.onEpisodeSwipe)
                assertTrue(navigate(1));assertTrue(navigate(1));assertTrue(navigate(-1))
            }
            waitFor("latest episode wins"){active.get()?.let{it.first==before.episodes[1].id && it.second in ready}==true}
            main{active.get()!!.second.pause()}
            report.put("rapid_next_next_previous",true)
            playerScreen.onActivity { a->views(a.window.decorView).filterIsInstance<FullscreenPlayerView>().single().onDoubleTap() }
            assertTrue(device.wait(Until.hasObject(By.desc("Thoát toàn màn hình")),3000) ||
                (!device.hasObject(By.text("Về Kho phim")) && !device.hasObject(By.desc("Về Kho phim"))))
            device.pressBack()
            report.put("fullscreen_back",true)
            device.takeScreenshot(File(folder,"renamed-player.png"))
            File(folder,"persisted-fixture.json").writeText(JSONObject().put("movie",before.id).put("episode",before.episodes[0].id).toString())
            report.put("completed",true)
        }catch(t:Throwable){
            report.put("error",t.stackTraceToString())
            runCatching { device.takeScreenshot(File(folder,"navigation-failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(folder,"navigation-failure.xml")) }
            throw t
        }
        finally{
            playerScreen?.close();screen?.close();VideoPreviewDiagnostics.onPlayerForTest=null
            File(folder,"rename.json").writeText(report.toString(2))
        }
    }

    @Test fun coldLibraryCatalog(): Unit = runBlocking {
        val context = ins.targetContext
        require(context.packageName == "com.gemini.ocr.playbacktest" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        val folder = reports(context)
        val fixture = File(context.filesDir, "playback-fixture.mp4")
        require(fixture.isFile)
        val uri = Uri.fromFile(fixture)
        val metadata = VideoDecoder.metadata(context, uri)
        val (sha, bytes) = MovieSourceAccess.inspect(context, uri)
        val project = VideoProject(UUID.randomUUID().toString(), "Catalog fixture", uri.toString(), 2_160_000L,
            metadata.width, metadata.height, scanMs = 2_160_000L, stage = VideoStage.REVIEW,
            cues = List(5065) { i -> VideoCue("catalog-$i", i * 420L, i * 420L + 400L, "字幕 $i", "Câu $i", approved = true) })
        val snapshot = MovieSourceSnapshot.freeze(project, sha, bytes)
        val seeded = MovieLibrary(context)
        val movieIds = List(8) { index ->
            val id = UUID.randomUUID().toString()
            seeded.save(MovieRecord(id, "Phim thử ${index + 1}", listOf(
                MovieEpisode("catalog-episode", "Tập 1", fixture.path, project.durationMs, source = snapshot))))
            id
        }
        var onMainNs = 0L
        var started = 0L
        lateinit var library: MovieLibrary
        main {
            started = SystemClock.elapsedRealtimeNanos()
            library = MovieLibrary(context)
            onMainNs = SystemClock.elapsedRealtimeNanos() - started
        }
        waitFor("catalog contains all fixtures") { library.movies.value.count { it.id in movieIds } == movieIds.size }
        assertTrue(library.movies.value.filter { it.id in movieIds }.all { it.episodes.single().source?.cues?.size == 5065 })
        File(folder, "cold-library.json").writeText(JSONObject().put("catalog_movies", movieIds.size)
            .put("cues_per_movie", 5065).put("main_thread_ns", onMainNs)
            .put("loaded_ns", SystemClock.elapsedRealtimeNanos() - started).toString())
    }

    @Test fun measuredPlayback():Unit=runBlocking {
        val context=ins.targetContext
        require(context.packageName=="com.gemini.ocr.playbacktest" && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val folder=reports(context)
        val variant=InstrumentationRegistry.getArguments().getString("variant") ?: "baseline"
        val output=File(folder,"$variant.json")
        val report=JSONObject().put("completed",false).put("variant",variant)
        val runs=JSONArray();report.put("runs",runs)
        val trace=CopyOnWriteArrayList<JSONObject>()
        MoviePlaybackTrace.observer={stage,ns->trace.add(JSONObject().put("stage",stage).put("ns",ns))}
        val library=MovieLibrary.get(context)
        val fixture = File(context.filesDir, "playback-input.mp4").takeIf { it.isFile }
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "playback-bench/playback.mp4")
        require(fixture.isFile)
        val owned=File(context.filesDir,"playback-fixture.mp4")
        fixture.copyTo(owned,true)
        val metadata=VideoDecoder.metadata(context,Uri.fromFile(owned))
        val fingerprint=MovieSourceAccess.inspect(context,Uri.fromFile(owned))
        report.put("fixture_sha256",fingerprint.first).put("fixture_bytes",fingerprint.second)
        val movieId=UUID.randomUUID().toString()
        val episodes=(1..3).map { n ->
            val id=UUID.randomUUID().toString()
            val p=VideoProject(id,"Tập $n",Uri.fromFile(owned).toString(),metadata.durationMs,metadata.width,metadata.height,
                cues=(0..11).map{VideoCue("cue-$it",it*2000L,it*2000L+1500,"字幕$it","Câu $it",approved=true)},
                style=VideoSubtitleStyle(blurOriginal=false), stage=VideoStage.REVIEW,
                scanMs=metadata.durationMs,resumeStage=VideoStage.TRANSLATING)
            MovieEpisode(id,p.title,owned.path,p.durationMs,source=MovieSourceSnapshot.freeze(p,fingerprint.first,fingerprint.second,p.uri))
        }
        library.save(MovieRecord(movieId,"Phim kiểm thử",episodes))
        val active=AtomicReference<Pair<String,ExoPlayer>?>(null)
        val rendered=ConcurrentHashMap<Player,Long>()
        val frames=ConcurrentHashMap<Player,Pair<Long,Long>>()
        val drops=AtomicLong()
        val observed=AtomicReference<VideoPreviewFrame?>()
        val subtitleUpdates=AtomicLong()
        val collector = HandlerThread("movie-frame-metrics").apply { start() }
        val collectFrameMetrics = AtomicBoolean(false)
        val frameDurations = CopyOnWriteArrayList<Long>()
        val frameListener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            if (collectFrameMetrics.get()) {
                frame.getMetric(FrameMetrics.TOTAL_DURATION).takeIf { it > 0 }?.let(frameDurations::add)
            }
        }
        VideoPreviewDiagnostics.onFrameForTest={observed.set(it);subtitleUpdates.incrementAndGet()}
        VideoPreviewDiagnostics.onPlayerForTest={id,player->
            if(player is ExoPlayer) {
                active.set(id to player)
                player.addListener(object:Player.Listener {
                    override fun onRenderedFirstFrame(){rendered[player]=SystemClock.elapsedRealtimeNanos()}
                })
                player.addAnalyticsListener(object:AnalyticsListener {
                    override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long){drops.addAndGet(droppedFrames.toLong())}
                })
                player.setVideoFrameMetadataListener { pts,_,_,_-> frames[player]=pts/1000 to SystemClock.elapsedRealtimeNanos() }
            }
        }
        var scenario:ActivityScenario<MoviePlaybackActivity>?=null
        try {
            repeat(5) { round ->
                library.progress(movieId,episodes[0].id,0)
                active.set(null)
                val start=SystemClock.elapsedRealtimeNanos()
                scenario=ActivityScenario.launch(Intent(context,MoviePlaybackActivity::class.java)
                    .putExtra("movie",movieId).putExtra("episode",episodes[0].id))
                waitFor("opening first frame"){active.get()?.second?.let{rendered.containsKey(it)}==true}
                val row=JSONObject().put("round",round+1).put("open_ns",rendered[active.get()!!.second]!!-start)
                runs.put(row)
                scenario!!.onActivity { activity ->
                    activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(collector.looper))
                }
                frameDurations.clear()
                val cpuStart=android.os.Process.getElapsedCpuTime()
                val updateStart=subtitleUpdates.get()
                val dwellStart=SystemClock.elapsedRealtime()
                collectFrameMetrics.set(true)
                Thread.sleep(2000)
                collectFrameMetrics.set(false)
                scenario!!.onActivity { activity -> activity.window.removeOnFrameMetricsAvailableListener(frameListener) }
                row.put("playing_wall_ms",SystemClock.elapsedRealtime()-dwellStart)
                    .put("playing_cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStart)
                    .put("subtitle_update_callbacks",subtitleUpdates.get()-updateStart)
                    .put("window_frames", frameDurations.size)
                    .put("window_frames_over_16ms", frameDurations.count { it > 16_666_667L })
                val seeks=JSONArray();row.put("seeks",seeks)
                for(paused in listOf(false,true)) {
                    val player=active.get()!!.second
                    main{if(paused)player.pause() else player.play()}
                    Thread.sleep(180)
                    var bar:DefaultTimeBar?=null
                    scenario!!.onActivity { a ->
                        val view=views(a.window.decorView).filterIsInstance<FullscreenPlayerView>().single()
                        view.showController();bar=views(view).filterIsInstance<DefaultTimeBar>().first()
                    }
                    var target=0L;var commands=0
                    val listener=object:TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar:TimeBar,position:Long){}
                        override fun onScrubMove(timeBar:TimeBar,position:Long){}
                        override fun onScrubStop(timeBar:TimeBar,position:Long,canceled:Boolean){target=position}
                    }
                    val playerListener=object:Player.Listener {
                        override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)commands++}
                    }
                    var released=0L
                    main {
                        player.addListener(playerListener);bar!!.addListener(listener)
                        val b=bar!!;val down=SystemClock.uptimeMillis()
                        fun touch(action:Int,f:Float) { val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,b.width*f,b.height/2f,0);b.dispatchTouchEvent(e);e.recycle() }
                        touch(MotionEvent.ACTION_DOWN,.2f)
                        listOf(.8f,.3f,.65f,.4f,.75f).forEach{touch(MotionEvent.ACTION_MOVE,it)}
                        released=SystemClock.elapsedRealtimeNanos();touch(MotionEvent.ACTION_UP,.75f)
                    }
                    waitFor("seek frame") { frames[player]?.let{it.second>=released && kotlin.math.abs(it.first-target)<150}==true }
                    val renderedAt=frames[player]!!.second
                    waitFor("subtitle follows seek") { observed.get()?.let{it.projectId==active.get()!!.first && kotlin.math.abs(it.positionMs-target)<400}==true }
                    main {
                        assertEquals("one seek per drag",1,commands)
                        if(paused)assertFalse(player.playWhenReady)
                        bar!!.removeListener(listener);player.removeListener(playerListener)
                    }
                    seeks.put(JSONObject().put("paused",paused).put("target_ms",target).put("release_to_frame_ns",renderedAt-released).put("commands",commands))
                }
                val switches=JSONArray();row.put("switches",switches)
                for(direction in listOf(1,-1)) {
                    val old=active.get()!!.second
                    val startSwitch=SystemClock.elapsedRealtimeNanos()
                    scenario!!.onActivity { a ->
                        val view=views(a.window.decorView).filterIsInstance<FullscreenPlayerView>().single()
                        assertTrue(view.onEpisodeSwipe!!.invoke(direction))
                    }
                    waitFor("episode first frame"){active.get()?.second?.let{it!==old && rendered.containsKey(it)}==true}
                    switches.put(JSONObject().put("direction",direction).put("ns",rendered[active.get()!!.second]!!-startSwitch))
                }
                scenario!!.close();scenario=null
                Thread.sleep(200)
                output.writeText(report.toString(2))
            }
            report.put("dropped_video_frames",drops.get()).put("trace",JSONArray(trace))
            report.put("completed",true)
        } catch(t:Throwable) {report.put("error",t.stackTraceToString());throw t}
        finally {
            scenario?.close();VideoPreviewDiagnostics.onPlayerForTest=null;VideoPreviewDiagnostics.onFrameForTest=null;MoviePlaybackTrace.observer=null
            collector.quitSafely()
            report.put("trace",JSONArray(trace))
            output.writeText(report.toString(2))
        }
    }
}
