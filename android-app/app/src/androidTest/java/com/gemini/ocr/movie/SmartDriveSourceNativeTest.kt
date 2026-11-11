package com.gemini.ocr.movie

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.*
import com.gemini.ocr.video.sync.DriveAuthorizationRequired
import com.gemini.ocr.video.sync.VideoCloudSync
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.TimeoutCancellationException
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

/** Opt-in same-account Drive readback of one known synthetic source + two manual cues only. */
@RunWith(AndroidJUnit4::class)
class SmartDriveSourceNativeTest {
    @Test(timeout=180_000L)
    fun sourceAndSeparateTimelineRoundTripUsingExistingGoogleGrant():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeSmartDrive")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && !BuildConfig.VERIFICATION_OFFLINE_NETWORK && context.packageName=="com.gemini.ocr")
        val jobs=VideoJobs.get(context)
        require(!SessionState.running.value && jobs.busy.value==null && jobs.batchBusy.value==null)
        val base=File(checkNotNull(context.getExternalFilesDir(null)),"stability-fixtures")
        val source=File(base,"chinese-4s.mp4")
        val expected=(args.getString("expectedSha256")?:args.getString("expectedSha")).orEmpty()
        require(source.isFile && source.length() in 1L..2L*1024*1024)
        require(expected.matches(Regex("[a-f0-9]{64}")) && sha256(source)==expected){"Only the caller-verified synthetic fixture is authorized."}
        val metadata=VideoDecoder.metadata(context,Uri.fromFile(source))
        require(metadata.durationMs in 3500L..6000L)
        val id=UUID.randomUUID().toString()
        val folder=File(base,"smart-source-drive-$id").apply{check(mkdirs())}
        val reportFile=File(base,"smart-source-drive-$id.json")
        val report=JSONObject().put("schema",1).put("result","RUNNING").put("movieId",id)
            .put("sourceSha256",expected).put("sourceBytes",source.length())
            .put("scope","One owned 4-second source + two manual cues in Drive appDataFolder; same-account publish/list/download, not a physical second device. No existing catalog record is changed/deleted.")
        val began=SystemClock.elapsedRealtime();var phase="existing-authorization"
        fun snapshot(){report.put("phase",phase).put("elapsedMs",SystemClock.elapsedRealtime()-began)
            reportFile.writeText(report.toString(2),Charsets.UTF_8)}
        fun blocked(code:String):Nothing {
            report.put("result","BLOCKED").put("blockerCode",code)
                .put("reason","Existing Google authorization or external service is unavailable. No account was selected/changed and no foreground consent was launched.")
            snapshot();throw AssumptionViolatedException("BLOCKED: $code; see sanitized synthetic fixture report.")
        }
        snapshot()
        try {
            withTimeout(160_000L){
                val auth=VideoCloudSync.get(context)
                val account=auth.state.value.accountEmail
                if(account.isBlank())blocked("GOOGLE_ACCOUNT_NOT_CONNECTED")
                if(auth.state.value.authorizationRequired)blocked("GOOGLE_AUTHORIZATION_REQUIRED")
                // repositoryForMovies pins the existing account; accessToken fails if consent or a switch is required.
                val repository=auth.repositoryForMovies(account)
                val project=VideoProject(UUID.randomUUID().toString(),"Synthetic smart Drive source",Uri.fromFile(source).toString(),
                    metadata.durationMs,metadata.width,metadata.height,
                    cues=listOf(VideoCue("drive-approved",250,1500,"你好","Xin chào Việt Nam",approved=true,origin="MANUAL_TEST_FIXTURE"),
                        VideoCue("drive-draft",2000,3400,"再见","Hẹn gặp lại",approved=false,origin="MANUAL_TEST_FIXTURE")),
                    style=VideoSubtitleStyle(position=.78f,sizeFraction=.055f,background=true,outline=false),
                    scanMs=metadata.durationMs,stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING)
                val sourceSnapshot=MovieSourceSnapshot.freeze(project,expected,source.length())
                val movie=MovieRecord(id,"Codex synthetic smart source $id",
                    listOf(MovieEpisode("synthetic-source","Synthetic source 4 seconds",source.absolutePath,metadata.durationMs,
                        source=sourceSnapshot)))
                phase="publish-source-and-timeline";snapshot()
                val cloudId=repository.publishMovie(movie)
                phase="repeat-publish";snapshot()
                assertEquals("Retry must reuse source and immutable manifest",cloudId,repository.publishMovie(movie))
                phase="list-own-record";snapshot()
                val listed=repository.listMovies().single{it.id==id}
                assertEquals(cloudId,listed.cloudId)
                assertEquals(movie.title,listed.title)
                val portable=listed.episodes.single().source!!
                assertTrue(portable.uri.isBlank() && portable.projectId.isBlank())
                assertEquals(project.cues,portable.cues)
                assertEquals(project.style,portable.style)
                assertEquals(sourceSnapshot.revision,portable.revision)
                phase="download-own-source-and-timeline";snapshot()
                val restored=repository.downloadMovie(listed,folder)
                val episode=restored.episodes.single();val timeline=episode.source!!
                assertEquals(expected,sha256(File(episode.path)))
                assertEquals(expected,sha256(source))
                assertEquals(sourceSnapshot.sha256,timeline.sha256)
                assertEquals(sourceSnapshot.bytes,timeline.bytes)
                assertEquals(project.cues,timeline.cues)
                assertEquals(project.style,timeline.style)
                assertEquals(listOf(true,false),timeline.cues.map{it.approved})
                assertEquals(metadata.width,timeline.width);assertEquals(metadata.height,timeline.height)
                assertEquals(sourceSnapshot.revision,timeline.revision)
                assertEquals(account,auth.state.value.accountEmail)
                val decoded=VideoDecoder.metadata(context,Uri.fromFile(File(episode.path)))
                assertEquals(metadata.width,decoded.width);assertEquals(metadata.height,decoded.height)
                assertEquals(metadata.durationMs,decoded.durationMs)
                report.put("result","PASS").put("publishIdempotent",true).put("listedOwnRecord",true)
                    .put("restoredSha256",expected).put("timelineAndStyleRestored",true).put("approvalMarksRestored",true)
                    .put("originalGeometryPreserved",true).put("sourceUnchanged",true).put("accountUnchanged",true)
                    .put("physicalSecondDeviceTested",false).put("existingCloudDataChanged",false)
                    .put("cloudFixtureRetained",true).put("renderedFiles",0)
                phase="complete";snapshot()
            }
        } catch(e:AssumptionViolatedException){throw e}
        catch(e:Throwable){
            val causes=generateSequence<Throwable>(e){it.cause}.take(12).toList()
            val api=causes.filterIsInstance<ApiException>().firstOrNull()
            if(api!=null){report.put("googleStatusCode",api.statusCode);blocked("GOOGLE_AUTH_STATUS_${api.statusCode}")}
            if(causes.any{it is DriveAuthorizationRequired})blocked("GOOGLE_FOREGROUND_CONSENT_OR_GRANT_REQUIRED")
            if(causes.any{it.javaClass.simpleName=="AuthorizationFailure"})blocked("GOOGLE_AUTHORIZATION_UNAVAILABLE")
            if(e is TimeoutCancellationException)blocked("EXTERNAL_SERVICE_TIMEOUT")
            if(phase=="existing-authorization")blocked("GOOGLE_AUTHORIZATION_UNAVAILABLE")
            report.put("result","FAIL").put("failureType",e.javaClass.simpleName)
                .put("reason","Synthetic source/timeline roundtrip stopped at the recorded phase; no alternate account or retry was used.")
            snapshot();throw AssertionError("Smart source Drive roundtrip failed at $phase; see sanitized report.")
        } finally {snapshot()}
    }
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val bytes=ByteArray(64*1024)
            while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
