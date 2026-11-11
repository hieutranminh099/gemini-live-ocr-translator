package com.gemini.ocr.video

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Throughput only: 18 repetitions of the downloaded public clip, not 54 different film lines. */
@RunWith(AndroidJUnit4::class)
class VideoSpeedNativeTest {
    @Test fun fourMinutePublicLoopCompletesOfflineWithMeasuredStages():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeVideoSpeed")=="true")
        val mode=if(InstrumentationRegistry.getArguments().getString("speedMode")=="hybrid")VideoTranslationMode.HYBRID else VideoTranslationMode.OFFLINE
        val interval=InstrumentationRegistry.getArguments().getString("speedSampleMs")?.toLong()?:250L
        require(interval in setOf(250L,500L))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.gemini.ocr.codexbench" && !SessionState.running.value)
        val base=File(context.getExternalFilesDir(null),"verification")
        val source=File(base,"cee-interview-public-loop-241s.mp4")
        require(source.isFile)
        fun hash():String {val digest=MessageDigest.getInstance("SHA-256");source.inputStream().use{stream->
            val bytes=ByteArray(128*1024);while(true){val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
            return digest.digest().joinToString(""){"%02x".format(it)}}
        val original=hash();require(original=="c5629ac5692be1f3a6bcb55cae41a292fdf1a86043e802bbe21eea2768dc8fb3")
        val jobs=VideoJobs.get(context);val store=VideoStore(context)
        val folder=File(base,"milestone-d-speed-241s-${mode.name.lowercase()}${if(interval==250L)"" else "-${interval}ms"}").apply{mkdirs()}
        val report=JSONObject().put("completed",false).put("source_sha256",original)
            .put("fixture","18 repetitions of public CC BY-SA 13.4s interview; throughput only")
        val prior=StandaloneTranslator.beforeNetworkCallForTest;var calls=0
        StandaloneTranslator.beforeNetworkCallForTest={calls++;check(mode==VideoTranslationMode.HYBRID && calls<=45){"Maximum45 image/text calls for this public-loop test; offline may never dispatch."}}
        val start=SystemClock.elapsedRealtime()
        try {
            check(jobs.busy.value==null);check(VideoOfflineTranslator.ready())
            val resumeId=InstrumentationRegistry.getArguments().getString("speedProjectId")
            val created=if(resumeId==null)jobs.create(Uri.fromFile(source),"Verification · public 241s throughput") else store.load(resumeId).also{
                require(it.title=="Verification · public 241s throughput" && Uri.parse(it.uri)==Uri.fromFile(source) &&
                    it.translationMode==mode && it.visualSampleMs==interval)}
            require(created.durationMs in 240000L..243000L)
            val selected=if(resumeId==null)jobs.save(created.copy(sourceMode=VideoSourceMode.OCR,translationMode=mode,
                roi=listOf(.25f,.80f,.75f,.95f),visualSampleMs=interval)) else created
            report.put("project_id",selected.id).put("duration_ms",selected.durationMs)
                .put("resumed",resumeId!=null).put("prior_elapsed_ms",selected.elapsedMs).put("prior_cloud_calls",selected.cloudCalls)
            jobs.start(selected.id)
            var lastReport=0L
            val complete=withTimeout(15*60*1000L){var p=store.load(selected.id)
                while(p.stage!=VideoStage.REVIEW || jobs.busy.value!=null){
                    // The foreground service starts asynchronously. A resumed FAILED checkpoint
                    // is still visible until its first new persistence; do not fail on that old row.
                    check(p.stage!=VideoStage.FAILED || p.updatedAt<=selected.updatedAt){p.message}
                    delay(500);p=store.load(selected.id)
                    if(SystemClock.elapsedRealtime()-lastReport>=10000){lastReport=SystemClock.elapsedRealtime()
                        File(folder,"progress.json").writeText(JSONObject().put("stage",p.stage.name).put("scan_ms",p.scanMs)
                            .put("elapsed_ms",lastReport-start).put("cloud_calls",calls).toString(),Charsets.UTF_8)}
                };p}
            assertTrue(complete.cues.isNotEmpty());assertTrue(complete.cues.all{it.translation.isNotBlank() && !it.approved})
            if(mode==VideoTranslationMode.OFFLINE)assertEquals(0,calls)
            else assertTrue(complete.cues.all{it.localDraft.isNotBlank() && it.refinementState=="done"})
            assertEquals(original,hash())
            File(folder,"translated.srt").writeText(VideoTimeline.srt(complete.cues),Charsets.UTF_8)
            report.put("completed",true).put("cues",JSONArray(complete.cues.map{it.json()}))
                .put("extraction_elapsed_ms",complete.extractionElapsedMs).put("translation_elapsed_ms",complete.translationElapsedMs)
                .put("ocr_metrics",complete.ocrMetrics).put("max_pss_kib",complete.maxPssKiB)
                .put("source_unchanged",true).put("translation_mode",mode.name).put("sample_interval_ms",interval)
        }catch(e:Throwable){report.put("error",e.message?.take(400));throw e}
        finally{jobs.pause();StandaloneTranslator.beforeNetworkCallForTest=prior
            File(folder,"report.json").writeText(report.put("elapsed_ms",SystemClock.elapsedRealtime()-start)
                .put("gemini_calls",calls).toString(2),Charsets.UTF_8)}
    }
}
