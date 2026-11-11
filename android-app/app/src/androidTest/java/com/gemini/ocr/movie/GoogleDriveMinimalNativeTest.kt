package com.gemini.ocr.movie

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.sync.DriveAuthorizationRequired
import com.gemini.ocr.video.sync.VideoCloudSync
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit opt-in: one owned synthetic clip only, no existing Drive writes/deletes or account logs. */
@RunWith(AndroidJUnit4::class)
class GoogleDriveMinimalNativeTest {
    @Test(timeout=180_000L)
    fun publishListRestoreOneOwnedSyntheticMovie():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMinimalDrive")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr")
        require(!BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value)
        val jobs=VideoJobs.get(context)
        require(jobs.busy.value==null && jobs.batchBusy.value==null)
        val base=File(checkNotNull(context.getExternalFilesDir(null)),"stability-fixtures")
        val source=File(base,"chinese-4s.mp4")
        require(source.isFile && source.length() in 1L..2L*1024*1024)
        val expected=args.getString("expectedSha256").orEmpty()
        require(expected.matches(Regex("[a-f0-9]{64}")) && sha256(source)==expected){"Only the known synthetic fixture is authorized."}
        val duration=VideoDecoder.metadata(context,Uri.fromFile(source)).durationMs
        require(duration in 1L..10_000L)
        val id=UUID.randomUUID().toString()
        val reportFile=File(base,"drive-minimal-$id.json")
        val output=File(base,"drive-audit-$id").apply{check(mkdirs())}
        val started=SystemClock.elapsedRealtime()
        var phase="authorize"
        val report=JSONObject().put("schema",1).put("result","RUNNING").put("movieId",id)
            .put("sourceSha256",expected).put("sourceBytes",source.length())
            .put("scope","One synthetic movie in appDataFolder; no existing content is changed/deleted. Not a second-device test.")
        fun snapshot(){report.put("phase",phase).put("elapsedMs",SystemClock.elapsedRealtime()-started)
            reportFile.writeText(report.toString(2),Charsets.UTF_8)}
        snapshot()
        try {
            withTimeout(160_000L){
                val auth=VideoCloudSync.get(context)
                if(auth.state.value.accountEmail.isBlank())throw AssumptionViolatedException("BLOCKED: choose a Google account in the application first.")
                if(auth.authorize(selectAccount=false)!=null)throw AssumptionViolatedException("BLOCKED: Google requires foreground account consent.")
                val repository=auth.repositoryForMovies(auth.state.value.accountEmail)
                val movie=MovieRecord(id,"Codex synthetic Drive audit $id",listOf(MovieEpisode("fixture","Synthetic 4 seconds",
                    source.absolutePath,duration)))
                phase="publish";snapshot()
                val cloudId=repository.publishMovie(movie)
                phase="repeat-publish";snapshot()
                assertEquals("A retry must reuse the immutable manifest",cloudId,repository.publishMovie(movie))
                phase="list-own-record";snapshot()
                val listed=repository.listMovies().single{it.id==id}
                assertEquals(cloudId,listed.cloudId)
                assertEquals(movie.title,listed.title)
                assertEquals(1,listed.episodes.size)
                phase="restore-own-record";snapshot()
                val restored=repository.downloadMovie(listed,output)
                assertEquals(expected,sha256(File(restored.episodes.single().path)))
                assertEquals(expected,sha256(source))
                assertEquals(duration,restored.episodes.single().durationMs)
                report.put("result","PASS").put("publishIdempotent",true).put("listedOwnRecord",true)
                    .put("restoredSha256",expected).put("sourceUnchanged",true)
                    .put("existingCloudDataChanged",false).put("cloudFixtureRetained",true)
                phase="complete";snapshot()
            }
        }catch(e:AssumptionViolatedException){
            report.put("result","BLOCKED").put("reason","Foreground Google account authorization is required; no retry started.")
            snapshot();throw e
        }catch(e:Throwable){
            val api=generateSequence<Throwable>(e){it.cause}.filterIsInstance<ApiException>().firstOrNull()
            val authorization=e is DriveAuthorizationRequired || api!=null || phase=="authorize"
            report.put("result",if(authorization)"BLOCKED" else "FAIL").put("failureType",e.javaClass.simpleName)
                .put("googleStatusCode",api?.statusCode?:JSONObject.NULL)
                .put("reason","Cloud verification stopped at the recorded phase; no retry or alternative account was used.")
            snapshot()
            if(authorization)throw AssumptionViolatedException("BLOCKED: Google authorization failed; see sanitized fixture report.")
            throw AssertionError("Synthetic Drive verification failed at $phase; see sanitized fixture report.")
        }finally{snapshot()}
    }
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(64*1024)
            while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
