package com.gemini.ocr

import android.content.ComponentName
import android.content.Intent
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.gemini.ocr.capture.PipelineTrace
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.batch.BatchJobs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Three bounded actual requests on synthetic scenes. No fake response or account/key edits. */
@RunWith(AndroidJUnit4::class)
class LiveRecoveryNativeTest {
    @Test(timeout=180000) fun actualRequestedPhraseAndFollowingCuesReachTheLiveOverlay():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeLiveReal")=="true")
        val context=instrumentation.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr" && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value && VideoJobs.get(context).busy.value==null && BatchJobs.get(context).busy.value==null)
        require(StandaloneTranslator.overrideGeminiTranslator==null && StandaloneTranslator.beforeNetworkCallForTest==null)
        val settings=AppSettings(context)
        val report=JSONObject().put("pid",Process.myPid()).put("completed",false).put("result","RUNNING")
            .put("scope","Real MediaProjection, native Chinese OCR, existing official Gemini configuration and actual Canvas overlay on synthetic scenes. No iQIYI/private media. Three maximum dispatches.")
        val folder=File(context.getExternalFilesDir(null),"live-roi-fixtures").apply{mkdirs()}
        val file=File(folder,"live-real-${UUID.randomUUID()}.json")
        fun persist(){file.writeText(report.toString(2))}
        if(settings.effectiveStandaloneApiKey().isBlank()){
            report.put("result","BLOCKED_NO_KEY").put("dispatches",0);persist();assumeTrue(false)
        }
        val original=listOf(settings.region,settings.subtitleCapture,settings.translationMode,settings.connectionMode,settings.overlay,
            settings.excludedApps,settings.localGlossary,settings.memoryFilmId,settings.memoryDomain)
        val full=settings.fullScreen;val interval=settings.interval;val roi=settings.subtitleRegion
        val turbo=settings.turboLatency;val down=settings.fastOcrDownscale;val correction=settings.ocrPreCorrection
        val stabilize=settings.stabilizeBeforeCloud;val saving=settings.costSavingMode;val track=settings.trackSourceRow
        val device=UiDevice.getInstance(instrumentation)
        val events=ConcurrentLinkedQueue<String>();val calls=AtomicInteger();val armed=AtomicInteger(-1);val denied=AtomicInteger()
        val rows=JSONArray();report.put("rows",rows)
        var phase="setup";val began=SystemClock.elapsedRealtime()
        fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().use{it.readText()}
        fun scene(text:String,id:Int){context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                if(id==0)"com.gemini.ocr.LiveVideoSceneActivity" else "com.gemini.ocr.TestSceneActivity"))
            .putExtra("subtitle",true).putExtra("animate",true).putExtra("movingBackground",false).putExtra("landscape",true)
            .putExtra("sourceText",text).putExtra("hardcodedVietnamese","").putExtra("subtitleSp",40f).putExtra("subtitleBaseline",.80f)
            .putExtra("eventId","roi-live-$id").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val marker=if(id==0)"gemini-live-video-scene" else "gemini-live-scene:roi-live-$id"
            assertTrue("Requested synthetic activity must actually be visible",device.wait(Until.hasObject(By.pkg(instrumentation.context.packageName).desc(marker)),5000))}
        try {
            settings.apply{region="subtitle";subtitleCapture="auto";translationMode="gemini_direct";connectionMode="standalone"
                overlay="subtitle";excludedApps="";localGlossary="验收标记=Kiểm tra ${UUID.randomUUID()}";memoryFilmId="";memoryDomain="general"
                fullScreen=true;this.interval=150;subtitleRegion=RectF(.06f,.67f,.94f,.94f);turboLatency=true;fastOcrDownscale=false
                ocrPreCorrection=false;stabilizeBeforeCloud=false;costSavingMode=false;trackSourceRow=false}
            PipelineTrace.observerForTest={events.add(it);Unit}
            StandaloneTranslator.beforeNetworkCallForTest={
                if(armed.get()<0 || calls.get()>=3){denied.incrementAndGet();error("Bounded live verification dispatch denied")}
                calls.incrementAndGet();Unit
            }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            context.startActivity(Intent(context,MainActivity::class.java).putExtra("ux_tab",0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Bắt đầu dịch")),10000))
            device.findObject(UiSelector().packageName(context.packageName).text("Bắt đầu dịch")).click()
            val consent=device.wait(Until.findObject(By.pkg("com.android.systemui").res("android:id/button1")
                .text(Pattern.compile("Start now|Start recording|Start sharing|Share screen|Start|Bắt đầu ngay|Bắt đầu",Pattern.CASE_INSENSITIVE))),10000)
            assertNotNull(consent);consent!!.click()
            withTimeout(20000){while(!SessionState.running.value)delay(50)}
            scene("",-1);delay(800)
            val sources=listOf("想让兄弟们信服你","我们明天再见","请不要关门")
            for((index,source) in sources.withIndex()) {
                phase="cue-$index";report.put("phase",phase);persist();armed.set(index)
                val at=SystemClock.elapsedRealtime();scene(source,index)
                val row=JSONObject().put("source",source).put("sourceAt",at);rows.put(row)
                try {withTimeout(30000){while(true){
                    val own=events.map{JSONObject(it)}.filter{it.optLong("at_ms")>=at}
                    val sent=own.lastOrNull{it.optString("event")=="request_sent" && it.optString("transport")=="standalone"}
                    val draw=sent?.let{s->own.firstOrNull{it.optString("event")=="overlay_drawn" && it.optString("request_id")==s.optString("request_id")}}
                    if(draw!=null && SessionState.lastTranslation.value.isNotBlank()){
                        row.put("ocr",SessionState.lastOcr.value).put("translation",SessionState.lastTranslation.value)
                            .put("sourceToCanvasMs",draw.getLong("at_ms")-at).put("draw",draw)
                        break
                    };delay(80)
                }}}
                catch(e:TimeoutCancellationException){row.put("ocr",SessionState.lastOcr.value).put("translation",SessionState.lastTranslation.value)
                    .put("status",SessionState.status.value).put("timeout",true);throw e}
                val recognized=SessionState.lastOcr.value.replace(Regex("[\\s\\p{P}]+"),"")
                assertEquals(source,com.gemini.ocr.network.ChineseDialogueBank.normalizeTraditional(recognized))
                row.put("scriptEquivalent",recognized!=source)
                assertFalse("A drawn result must leave the source-confirmation waiting status",
                    SessionState.status.value.contains("Đang xác nhận chữ"))
                assertFalse(SessionState.lastTranslation.value.any{Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN})
                if(index==0){assertTrue(device.takeScreenshot(File(folder,"live-requested-phrase.png")))}
                val beforeHold=calls.get();delay(1000)
                assertEquals("The same accepted source must reuse its translation",beforeHold,calls.get())
                armed.set(-1);scene("",10+index);delay(600);persist()
            }
            assertEquals(3,calls.get())
            assertEquals("No extra network attempt may be hidden by the test budget",0,denied.get())
            report.put("stableSourceNoRedispatch",true).put("firstSourceMedia","Bundled 24-second real H264 video with generated Chinese subtitles")
            report.put("completed",true).put("result","PASS_REQUIRES_SEMANTIC_REVIEW")
        }catch(e:Throwable){report.put("result","FAIL").put("failureType",e.javaClass.simpleName).put("phase",phase);throw e}
        finally {
            context.stopService(Intent(context,ScreenCaptureService::class.java))
            withContext(NonCancellable){withTimeoutOrNull(10000){while(SessionState.running.value)delay(50)}}
            StandaloneTranslator.beforeNetworkCallForTest=null;PipelineTrace.observerForTest=null
            settings.apply{region=original[0];subtitleCapture=original[1];translationMode=original[2];connectionMode=original[3];overlay=original[4]
                excludedApps=original[5];localGlossary=original[6];memoryFilmId=original[7];memoryDomain=original[8]
                fullScreen=full;this.interval=interval;subtitleRegion=roi;turboLatency=turbo;fastOcrDownscale=down;ocrPreCorrection=correction
                stabilizeBeforeCloud=stabilize;costSavingMode=saving;trackSourceRow=track}
            report.put("dispatches",calls.get()).put("deniedExtraDispatches",denied.get()).put("elapsedMs",SystemClock.elapsedRealtime()-began)
                .put("events",JSONArray(events.map{JSONObject(it)}));persist()
        }
    }
}
