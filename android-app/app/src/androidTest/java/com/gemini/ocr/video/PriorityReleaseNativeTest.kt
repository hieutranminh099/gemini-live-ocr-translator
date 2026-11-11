@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.MainActivity
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PriorityReleaseNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val base get()=File(context.filesDir,"priority-release-175").apply{mkdirs()}
    private fun guard(){
        require(context.packageName=="com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        require(BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value)
    }
    @Test fun mainAndVideoScreensOpenWithNoActiveWorker(){
        guard()
        ActivityScenario.launch(MainActivity::class.java).use{assertEquals(Lifecycle.State.RESUMED,it.state)}
        ActivityScenario.launch(VideoActivity::class.java).use{assertEquals(Lifecycle.State.RESUMED,it.state)}
        assertNull(VideoJobs.get(context).busy.value)
    }
    @Test fun chineseOcrAndEncryptedSettingsWorkOnAndroid()=runBlocking {
        guard()
        val bitmap=Bitmap.createBitmap(1400,320,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        canvas.drawText("欢迎来到世界",50f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=88f})
        val ocr=OcrProcessor("zh")
        var recognized=""
        try{
            recognized=ocr.process(bitmap).joinToString(""){it.text}.replace(Regex("\\s+"),"")
            assertTrue("Expected the same Chinese phrase in Simplified or Traditional script",recognized in setOf("欢迎来到世界","欢迎來到世界"))
        }
        finally{ocr.close();bitmap.recycle()}
        val settings=AppSettings(context)
        val old=settings.standaloneApiKey
        try{settings.standaloneApiKey="priority-release-synthetic-value";assertEquals("priority-release-synthetic-value",AppSettings(context).standaloneApiKey)}
        finally{settings.standaloneApiKey=old}
        File(base,"ocr-settings.json").writeText(JSONObject().put("completed",true).put("chinese_ocr",true).put("recognized",recognized).put("variant_note","ML Kit may return the Traditional 來 for Simplified 来; exact glyph fidelity is not certified").put("encrypted_settings",true).toString(2))
    }
    @Test(timeout=240000) fun restoredReviewedTimelineExportsSrtAssMp4AndDownloadsWithoutNetwork()=runBlocking {
        guard()
        val input=File(base,"public-source.mp4");require(input.isFile)
        val before=sha(input)
        val jobs=VideoJobs.get(context);val store=VideoStore(context)
        val created=jobs.create(Uri.fromFile(input),"Verification · Priority 1.7.5")
        val row=VideoCue("release-one",1000,3000,"别走","Bản nháp")
        val saved=store.save(created.copy(cues=listOf(row),scanMs=created.durationMs,stage=VideoStage.PAUSED,resumeStage=VideoStage.TRANSLATING))
        jobs.updateCue(saved.id,row.copy(translation="Anh Quân, đừng đi.",approved=true))
        val reviewed=store.load(saved.id).cues.single()
        jobs.save(saved.copy(style=saved.style.copy(frame=VideoSubtitleFrame.BOTTOM)))
        var restored=VideoStore(context).load(saved.id)
        assertEquals(reviewed,restored.cues.single())
        assertEquals(saved.durationMs,restored.scanMs)
        val previous=StandaloneTranslator.beforeNetworkCallForTest
        var attempts=0
        StandaloneTranslator.beforeNetworkCallForTest={attempts++;error("Native acceptance must not call Gemini")}
        try {
            val srt=VideoDownloads.saveSubtitle(context,restored,false)
            val ass=VideoDownloads.saveSubtitle(context,restored,true)
            val srtText=context.contentResolver.openInputStream(srt.uri)!!.bufferedReader().use{it.readText()}
            val assText=context.contentResolver.openInputStream(ass.uri)!!.bufferedReader().use{it.readText()}
            assertEquals(VideoTimeline.srt(restored.cues),srtText)
            assertEquals(VideoTimeline.ass(restored),assText)
            val output=File(store.folder(restored.id),"priority-release.mp4")
            withTimeout(180000){VideoExporter.export(context,restored,output)}
            assertTrue(output.isFile && output.length()>10000)
            restored=store.save(restored.copy(exportedMp4=output.absolutePath,stage=VideoStage.COMPLETE))
            val downloaded=VideoDownloads.saveRenderedMp4(context,restored)
            val downloadedHash=context.contentResolver.openInputStream(downloaded.uri)!!.use{stream->
                val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
                digest.digest().joinToString(""){"%02x".format(it)}
            }
            assertEquals(sha(output),downloadedHash)
            assertEquals(before,sha(input));assertEquals(0,attempts)
            output.copyTo(File(base,"accepted-output.mp4"))
            File(base,"accepted.srt").writeText(srtText);File(base,"accepted.ass").writeText(assText)
            File(base,"native-export.json").writeText(JSONObject().put("completed",true).put("version",BuildConfig.VERSION_NAME)
                .put("project_id",restored.id).put("source_sha256",before).put("output_sha256",sha(output))
                .put("source_unchanged",true).put("srt_download",true).put("ass_download",true).put("mp4_download",true)
                .put("persisted_review_preserved",true).put("gemini_calls",attempts)
                .put("scope","Manually supplied timeline; does not certify translation quality or worker resume")
                .toString(2))
        }finally{StandaloneTranslator.beforeNetworkCallForTest=previous}
    }
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
}
