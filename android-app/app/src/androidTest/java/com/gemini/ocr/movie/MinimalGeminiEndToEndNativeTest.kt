package com.gemini.ocr.movie

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.VideoActivity
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Continues the one previously fresh Gemini fixture. Export/confirm/Drive only; no further AI calls. */
@RunWith(AndroidJUnit4::class)
class MinimalGeminiEndToEndNativeTest {
    @Test(timeout=180_000L)
    fun existingRealGeminiResultExportsConfirmsAndRestoresFromDrive():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMinimalEndToEnd")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr" && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value)
        val jobs=VideoJobs.get(context)
        require(jobs.busy.value==null && jobs.batchBusy.value==null && BatchJobs.get(context).busy.value==null)
        require(StandaloneTranslator.beforeNetworkCallForTest==null && StandaloneTranslator.overrideGeminiTranslator==null &&
            StandaloneTranslator.overrideOnDeviceTranslator==null)
        val projectId=args.getString("projectId").orEmpty()
        require(projectId=="e26bf024-9050-4799-a7f6-c41f46ee866d")
        val base=File(checkNotNull(context.getExternalFilesDir(null)),"stability-fixtures")
        val source=File(base,"chinese-4s.mp4").canonicalFile
        val sourceHash="85200f1c38d0fac2d600cf6253696babf2881574ce94f84a903bc687815165d4"
        require(source.isFile && source.length() in 1L..2_000_000L && sha256(source)==sourceHash)
        val store=VideoStore(context)
        val original=store.load(projectId)
        val marker=File(store.folder(projectId),"batch-owner")
        require(marker.isFile && marker.readText().trim().matches(Regex("[a-f0-9-]{36}")))
        require(original.title=="Synthetic four-second Gemini audit" && original.stage==VideoStage.REVIEW)
        require(original.sourceMode==VideoSourceMode.OCR && original.translationMode==VideoTranslationMode.GEMINI &&
            original.cloudCalls>0 && original.scanMs==original.durationMs && original.cues.none{it.approved})
        require(Uri.parse(original.uri).scheme=="file" && File(checkNotNull(Uri.parse(original.uri).path)).canonicalFile==source)
        val sources=listOf("你好,我是小明","我没有两本书")
        val translations=listOf("Xin chào, tôi là Tiểu Minh","Tôi không có hai cuốn sách")
        require(original.cues.map{it.source}==sources && original.cues.map{it.translation}==translations)
        require(original.cues.map{it.startMs}==listOf(750L,2250L) && original.cues.map{it.endMs}==listOf(2000L,3500L))
        val library=MovieLibrary.get(context)
        require(library.find(projectId)==null){"This known synthetic movie must not already be confirmed."}
        val identity=VideoCloudSync.get(context)
        val cloud=MovieCloudSync.get(context)
        val runId=UUID.randomUUID().toString()
        val reportFile=File(base,"end-to-end-$runId.json")
        val preview=File(base,"end-to-end-$runId.png")
        val restoreFolder=File(base,"end-to-end-restore-$runId").apply{check(mkdirs())}
        val started=SystemClock.elapsedRealtime()
        val forbiddenGemini=AtomicInteger()
        var phase="export"
        var maxPss=0
        val report=JSONObject().put("schema",1).put("result","RUNNING").put("projectId",projectId)
            .put("sourceSha256",sourceHash).put("freshGeminiInThisTest",false)
            .put("scope","Continues the previously verified two-cue real Gemini fixture through MP4 export, explicit movie confirmation and real Drive restore. Not a second-device test.")
        fun snapshot(){
            val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);maxPss=maxOf(maxPss,info.totalPss)
            report.put("phase",phase).put("elapsedMs",SystemClock.elapsedRealtime()-started).put("sampledMaxPssKiB",maxPss)
                .put("additionalGeminiDispatchesBlocked",forbiddenGemini.get())
            reportFile.writeText(report.toString(2),Charsets.UTF_8)
        }
        StandaloneTranslator.beforeNetworkCallForTest={
            forbiddenGemini.incrementAndGet();throw IllegalStateException("No additional Gemini request authorized in the export/Drive witness")
        }
        try {
            withTimeout(160_000L){
                snapshot()
                ActivityScenario.launch<VideoActivity>(Intent(context,VideoActivity::class.java).putExtra("project",projectId)).use{scenario->
                    scenario.onActivity{jobs.exportMp4(projectId)}
                    withTimeout(60_000L){while(jobs.busy.value==projectId){snapshot();delay(200)}}
                }
                val complete=store.load(projectId)
                assertEquals(VideoStage.COMPLETE,complete.stage)
                assertEquals(original.cues,complete.cues)
                assertEquals(original.cloudCalls,complete.cloudCalls)
                assertEquals(0,forbiddenGemini.get())
                val export=File(complete.exportedMp4)
                require(export.isFile && export.length()>0 && export.canonicalFile.toPath().startsWith(store.folder(projectId).canonicalFile.toPath()))
                val metadata=VideoDecoder.metadata(context,Uri.fromFile(export))
                assertTrue(abs(metadata.durationMs-original.durationMs)<=500)
                assertEquals(original.width,metadata.width);assertEquals(original.height,metadata.height)
                val frame=checkNotNull(VideoDecoder.preview(context,Uri.fromFile(export),2500))
                try{preview.outputStream().use{assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{frame.recycle()}
                val exportHash=sha256(export)
                report.put("exportSha256",exportHash).put("exportBytes",export.length()).put("exportDurationMs",metadata.durationMs)
                    .put("exportElapsedMs",complete.exportElapsedMs).put("exportPreview",preview.name)
                    .put("subtitleVisualReview","Inspect the synthetic exported PNG at 2500ms; this assertion only verifies decode and preserved cue text/timing.")
                phase="confirm";snapshot()
                if(identity.state.value.accountEmail.isBlank() || identity.state.value.authorizationRequired)
                    throw AssumptionViolatedException("BLOCKED_CLOUD_AUTH_REQUIRED")
                if(!identity.state.value.enabled)throw AssumptionViolatedException("BLOCKED_CLOUD_UPLOAD_DISABLED")
                val confirmed=library.confirm(projectId,"Kiểm thử tổng hợp OCR–dịch–Kho phim",
                    listOf(MovieEpisode("fixture","Phụ đề Gemini thực",export.absolutePath,metadata.durationMs)))
                assertEquals(exportHash,sha256(File(confirmed.episodes.single().path)))
                assertTrue(confirmed.episodes.single().path!=export.absolutePath)
                report.put("movieConfirmed",true).put("independentMovieCopy",true)
                phase="workmanager-upload";snapshot()
                cloud.enqueue(projectId)
                val synced=withTimeout(120_000L){
                    var current=checkNotNull(library.find(projectId))
                    while(current.syncState!="SYNCED"){
                        if(current.syncState=="FAILED"){
                            if(identity.state.value.authorizationRequired)throw AssumptionViolatedException("BLOCKED_CLOUD_AUTH_REQUIRED")
                            error("Synthetic movie WorkManager upload failed; no retry is authorized by this witness")
                        }
                        report.put("movieSyncState",current.syncState);snapshot();delay(250)
                        current=checkNotNull(library.find(projectId))
                    }
                    current
                }
                assertTrue(synced.cloudId.isNotBlank())
                report.put("workManagerSynced",true)
                phase="list-restore-own-movie";snapshot()
                val repository=identity.repositoryForMovies(identity.state.value.accountEmail)
                val listed=repository.listMovies().single{it.id==projectId}
                assertEquals(synced.cloudId,listed.cloudId)
                val downloaded=repository.downloadMovie(listed,restoreFolder)
                assertEquals(exportHash,sha256(File(downloaded.episodes.single().path)))
                assertEquals(sourceHash,sha256(source))
                assertEquals(0,forbiddenGemini.get())
                report.put("result","PASS").put("restoredSha256",exportHash).put("sourceUnchanged",true)
                    .put("existingDriveDataChanged",false).put("cloudSyntheticMovieRetained",true)
                phase="complete";snapshot()
            }
        }catch(e:AssumptionViolatedException){
            cloud.cancel(projectId)
            report.put("result","BLOCKED").put("reason",e.message).put("ownCloudJobCancelled",true)
            snapshot();throw e
        }catch(e:Throwable){
            cloud.cancel(projectId)
            report.put("result","FAIL").put("failureType",e.javaClass.simpleName).put("ownCloudJobCancelled",true)
                .put("reason","Stopped at the recorded phase; no additional Gemini call or cloud retry was started.")
            snapshot();throw AssertionError("Known synthetic end-to-end witness failed at $phase; see sanitized own-fixture report.")
        }finally{
            if(jobs.busy.value==projectId){jobs.pause();withTimeoutOrNull(10_000L){while(jobs.busy.value==projectId)delay(100)}}
            StandaloneTranslator.beforeNetworkCallForTest=null
            snapshot()
        }
    }
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(64*1024)
            while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
