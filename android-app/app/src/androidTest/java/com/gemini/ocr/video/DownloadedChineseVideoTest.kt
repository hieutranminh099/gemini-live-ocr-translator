package com.gemini.ocr.video

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
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
import java.util.concurrent.atomic.AtomicInteger

/** A newly downloaded CC BY-SA clip with real Chinese hardsubs and original interview audio. */
@RunWith(AndroidJUnit4::class)
class DownloadedChineseVideoTest {
    @Test fun reviewedTimelineCanBeCorrectedAndReexportedWithoutCallingGemini():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reviseDownloadedVideo")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.gemini.ocr.codexbench")
        val store=VideoStore(context);val jobs=VideoJobs.get(context)
        val old=store.load(requireNotNull(args.getString("videoProjectId")))
        require(old.title=="Verification · downloaded Chinese" && old.translationMode in setOf(VideoTranslationMode.GEMINI,VideoTranslationMode.HYBRID) && old.cues.size==3)
        require(Uri.parse(old.uri).lastPathSegment=="cee-interview-chinese-cc-by-sa.mp4")
        val sourceFile=File(requireNotNull(Uri.parse(old.uri).path))
        assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",sha(sourceFile))
        val calls=AtomicInteger();val prior=StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest={calls.incrementAndGet();error("Editing timestamps/frame must not invoke Gemini.")}
        val start=SystemClock.elapsedRealtime()
        try {
            val reexportOnly=args.getString("reexportOnly")=="true"
            if(!reexportOnly)jobs.updateCue(old.id,old.cues.first().copy(startMs=167))
            val styled=jobs.save(store.load(old.id).copy(style=old.style.copy(frame=VideoSubtitleFrame(.15f,.79f,.85f,.96f))))
            assertEquals(old.cues.map{it.translation},styled.cues.map{it.translation})
            assertEquals(old.roi,styled.roi)
            jobs.exportMp4(old.id)
            val result=withTimeout(120000){var p=store.load(old.id)
                while(p.stage!=VideoStage.COMPLETE || jobs.busy.value!=null){check(p.stage!=VideoStage.FAILED){p.message};delay(200);p=store.load(old.id)};p}
            assertEquals(old.cloudCalls,result.cloudCalls);assertEquals(0,calls.get())
            val tag=args.getString("reportTag")?.also{require(it.matches(Regex("[a-z0-9-]{1,64}")))}?:"downloaded-chinese-gemini-final-review"
            val folder=File(context.getExternalFilesDir(null),"verification/$tag").apply{mkdirs()}
            File(folder,"translated.srt").writeText(VideoTimeline.srt(result.cues),Charsets.UTF_8)
            File(folder,"translated.ass").writeText(VideoTimeline.ass(result),Charsets.UTF_8)
            File(result.exportedMp4).copyTo(File(folder,"translated-custom-frame.mp4"),overwrite=true)
            val source=File(requireNotNull(Uri.parse(result.uri).path))
            assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",sha(source))
            File(folder,"report.json").writeText(JSONObject().put("completed",true)
                .put("project_id",result.id).put("phase",if(reexportOnly)"cached_timeline_reexport_no_translation" else "post_translation_timestamp_and_frame_edit")
                .put("source_sha256",sha(sourceFile)).put("duration_ms",result.durationMs)
                .put("first_cue_start_ms",result.cues.first().startMs).put("new_gemini_dispatch_attempts",calls.get())
                .put("existing_cloud_calls",result.cloudCalls).put("source_unchanged",true)
                .put("edit_reexport_elapsed_ms",SystemClock.elapsedRealtime()-start)
                .put("cues",JSONArray(result.cues.map{it.json()})).put("style",result.style.json()).toString(2),Charsets.UTF_8)
        }finally{StandaloneTranslator.beforeNetworkCallForTest=prior}
    }
    @Test fun downloadedChineseVideoRunsProductionPipelineAndCustomSubtitleFrame():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeDownloadedVideo")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        require(!SessionState.running.value)
        val mode=when(args.getString("downloadedMode")){"gemini"->VideoTranslationMode.GEMINI;"hybrid"->VideoTranslationMode.HYBRID;else->VideoTranslationMode.OFFLINE}
        val sourceMode=if(args.getString("downloadedSource")=="sidecar")VideoSourceMode.SIDECAR else VideoSourceMode.OCR
        val base=File(context.getExternalFilesDir(null),"verification")
        val source=File(base,"cee-interview-chinese-cc-by-sa.mp4")
        require(source.isFile)
        val expectedHash="bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33"
        assertEquals(expectedHash,sha(source))
        val tag=args.getString("reportTag")?.also{require(it.matches(Regex("[a-z0-9-]{1,64}")))}
        val folder=File(base,tag?:"downloaded-chinese-${mode.name.lowercase()}-${sourceMode.name.lowercase()}").apply{mkdirs()}
        val report=JSONObject().put("completed",false).put("source_url","https://assets.videocome.com/examples/subtitle-localization/cee-interview-v3-20261016/after.mp4")
            .put("license","CC BY-SA 4.0; VideoCome adaptation, Wikimedia Eesti / Amadvr / Misosoof original")
            .put("source_sha256",expectedHash).put("mode",mode.name)
            .put("source_mode",sourceMode.name)
            .put("audio","Original interview audio; English speech, Chinese burned subtitles")
        val start=SystemClock.elapsedRealtime()
        val attempts=AtomicInteger()
        val prior=StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest={
            val count=attempts.incrementAndGet()
            check(mode!=VideoTranslationMode.OFFLINE && count<=6){"Only explicit Gemini/hybrid mode may dispatch; maximum six image/text calls for this short public clip."}
        }
        val store=VideoStore(context);val jobs=VideoJobs.get(context)
        fun persist(){File(folder,"report.json").writeText(report.put("elapsed_ms",SystemClock.elapsedRealtime()-start)
            .put("dispatch_attempts",attempts.get()).toString(2),Charsets.UTF_8)}
        try {
            require(jobs.busy.value==null)
            val created=jobs.create(Uri.fromFile(source),"Verification · downloaded Chinese")
            val sidecar=File(base,"cee-interview-zh-reviewed.srt")
            if(sourceMode==VideoSourceMode.SIDECAR)require(sidecar.isFile){"Push the manually reviewed public Chinese sidecar first."}
            val p=jobs.save(created.copy(sourceMode=sourceMode,translationMode=mode,roi=listOf(.25f,.80f,.75f,.95f),
                subtitleUri=if(sourceMode==VideoSourceMode.SIDECAR)Uri.fromFile(sidecar).toString() else "",
                subtitleName=if(sourceMode==VideoSourceMode.SIDECAR)sidecar.name else ""))
            report.put("project_id",p.id).put("duration_ms",p.durationMs);persist()
            jobs.start(p.id)
            suspend fun awaitStage(stage:VideoStage):VideoProject=withTimeout(240000){
                var current=store.load(p.id)
                while(current.stage!=stage || jobs.busy.value!=null){
                    check(current.stage!=VideoStage.FAILED){current.message}
                    delay(200);current=store.load(p.id)
                };current
            }
            val translated=awaitStage(VideoStage.REVIEW)
            assertTrue(translated.cues.isNotEmpty())
            assertTrue(translated.cues.all{it.translation.isNotBlank() && !it.approved})
            if(mode==VideoTranslationMode.HYBRID)assertTrue(translated.cues.all{it.localDraft.isNotBlank() && it.refinementState=="done"})
            val frame=VideoSubtitleFrame(.15f,.79f,.85f,.96f)
            val styled=jobs.save(translated.copy(style=translated.style.copy(frame=frame)))
            assertEquals("Changing output frame must preserve OCR and translations",translated.cues,styled.cues)
            assertEquals(translated.roi,styled.roi)
            assertEquals(frame,store.load(p.id).style.frame)
            File(folder,"translated.srt").writeText(VideoTimeline.srt(styled.cues),Charsets.UTF_8)
            File(folder,"translated.ass").writeText(VideoTimeline.ass(styled),Charsets.UTF_8)
            report.put("cues",JSONArray(styled.cues.map{it.json()})).put("style",styled.json().getJSONObject("style"))
                .put("processing_elapsed_ms",styled.elapsedMs).put("job_max_pss_kib",styled.maxPssKiB)
                .put("extraction_elapsed_ms",styled.extractionElapsedMs).put("translation_elapsed_ms",styled.translationElapsedMs)
                .put("ocr_metrics",styled.ocrMetrics)
                .put("frame_edit_preserved_timeline",true);persist()
            jobs.exportMp4(styled.id)
            val complete=awaitStage(VideoStage.COMPLETE)
            val mp4=File(complete.exportedMp4)
            require(mp4.isFile && mp4.length()>0)
            mp4.copyTo(File(folder,"translated-custom-frame.mp4"),overwrite=true)
            assertEquals(expectedHash,sha(source))
            if(mode==VideoTranslationMode.OFFLINE)assertEquals(0,attempts.get())
            report.put("completed",true).put("source_unchanged",true).put("export_bytes",mp4.length())
                .put("total_job_elapsed_ms",complete.elapsedMs).put("job_max_pss_kib",complete.maxPssKiB)
        }catch(e:Throwable){report.put("error_type",e.javaClass.name).put("error",e.message?.take(400));throw e}
        finally{jobs.pause();StandaloneTranslator.beforeNetworkCallForTest=prior;persist()}
    }
    private fun sha(file:File):String {
        val h=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{stream->val buffer=ByteArray(128*1024);while(true){val n=stream.read(buffer);if(n<0)break;h.update(buffer,0,n)}}
        return h.digest().joinToString(""){"%02x".format(it)}
    }
}
