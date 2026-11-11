@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gemini.ocr.video

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoiConfirmationNativeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun fixture():VideoProject {
        require(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK && context.packageName=="com.gemini.ocr.codexbench")
        require(!SessionState.running.value && VideoJobs.get(context).busy.value==null)
        val folder=File(context.filesDir,"roi-native-fixtures").apply{mkdirs()}
        val source=File(folder,"source-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("stability-source.mp4").use{input->source.outputStream().use{input.copyTo(it)}}
        val m=VideoDecoder.metadata(context,Uri.fromFile(source))
        return VideoStore(context).save(VideoProject(UUID.randomUUID().toString(),"ROI synthetic fixture",Uri.fromFile(source).toString(),m.durationMs,m.width,m.height,
            sourceMode=VideoSourceMode.OCR,style=VideoSubtitleStyle(frame=VideoSubtitleFrame(.8f,.8f,.9f,.98f).move(-9f,0f))))
    }
    @Test(timeout=90000) fun baselineMinimumFrameHandleDragProbe():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeRoiBaseline")=="true")
        val p=fixture()
        val folder=File(context.getExternalFilesDir(null),"roi-confirm-fixtures").apply{mkdirs()}
        val file=File(folder,"baseline-probe.json")
        val report=JSONObject().put("pid",Process.myPid()).put("project",p.id).put("phase","launch").put("finished",false)
        file.writeText(report.toString(2))
        ActivityScenario.launch<VideoActivity>(Intent(context,VideoActivity::class.java).putExtra("project",p.id).putExtra("ux_step","setup")).use{scenario->
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)),10000))
            fun click(text:String){
                repeat(8){
                    val found=device.findObject(By.pkg(context.packageName).text(text))
                    if(found!=null){
                        if(text=="Chỉnh khung phụ đề sau dịch") {
                            val y=found.visibleBounds.centerY()
                            val checkbox=device.findObjects(By.pkg(context.packageName).checkable(true)).minByOrNull{kotlin.math.abs(it.visibleBounds.centerY()-y)}
                            assertNotNull(checkbox);checkbox!!.click()
                        }else found.click()
                        device.waitForIdle();return
                    }
                    device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,20)
                };fail("Missing own UI: $text")
            }
            click("Chỉnh vùng chữ")
            var full=device.findObject(By.pkg(context.packageName).desc("Toàn màn hình"))
            repeat(8){if(full==null){device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,18)
                device.waitForIdle();full=device.findObject(By.pkg(context.packageName).desc("Toàn màn hình"))}}
            assertNotNull("Fullscreen entry must be scrolled into view",full);full!!.click();device.waitForIdle()
            val edit=device.wait(Until.findObject(By.pkg(context.packageName).desc("Chỉnh phụ đề")),5000)
            assertNotNull(edit);edit!!.click();device.waitForIdle()
            val canvas=device.wait(Until.findObject(By.pkg(context.packageName).descStartsWith("Khung phụ đề Việt")),5000)
            assertNotNull(canvas)
            val b=canvas!!.visibleBounds
            val v=VideoViewport.calculate(p.width,p.height,b.width(),b.height(),false)
            val x=(b.left+v.screenX(.002f)).toInt();val y=(b.top+v.screenY(.89f)).toInt()
            report.put("phase","drag-left-handle").put("frame",p.style.frame!!.json()).put("canvasWidth",b.width()).put("canvasHeight",b.height())
            file.writeText(report.toString(2))
            device.swipe(x,y,x+maxOf(20,(v.width*.05f).toInt()),y,25)
            SystemClock.sleep(700)
            scenario.onActivity{assertFalse(it.isFinishing)}
            report.put("finished",true).put("phase","drag-complete");file.writeText(report.toString(2))
        }
    }
    @Test(timeout=300000) fun confirmedDraftSurvivesTwentyInteractionsAndLocalOcr():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeRoiConfirm")=="true")
        val p=fixture();val store=VideoStore(context);val jobs=VideoJobs.get(context)
        File(store.folder(p.id),"batch-owner").writeText(UUID.randomUUID().toString())
        val folder=File(context.getExternalFilesDir(null),"roi-confirm-fixtures").apply{mkdirs()}
        val report=JSONObject().put("pid",Process.myPid()).put("project",p.id).put("completed",false).put("cloudCalls",0)
        val file=File(folder,"confirmation.json");file.writeText(report.toString(2))
        fun click(text:String){
            repeat(12){
                val found=device.wait(Until.findObject(By.pkg(context.packageName).text(text)),700)
                if(found!=null){found.click();device.waitForIdle();return}
                if(it<5)device.swipe(device.displayWidth*3/4,device.displayHeight*3/4,device.displayWidth*3/4,device.displayHeight/3,18)
                else device.swipe(device.displayWidth*3/4,device.displayHeight/3,device.displayWidth*3/4,device.displayHeight*3/4,18)
            }
            if(device.hasObject(By.pkg(context.packageName))){device.takeScreenshot(File(folder,"missing-own-button.png"));device.dumpWindowHierarchy(File(folder,"missing-own-button.xml"))}
            fail("Own button missing: $text")
        }
        fun canvas()=requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).descStartsWith("Khung phụ đề Việt")),8000))
        fun drag(frame:List<Float>,resize:Boolean){
            val b=canvas().visibleBounds;val v=VideoViewport.calculate(p.width,p.height,b.width(),b.height(),false)
            val x=if(resize)frame[2] else (frame[0]+frame[2])/2
            val y=if(resize)frame[3] else (frame[1]+frame[3])/2
            val sx=(b.left+v.screenX(x)).toInt().coerceIn(b.left+3,b.right-3)
            val sy=(b.top+v.screenY(y)).toInt().coerceIn(b.top+3,b.bottom-3)
            device.swipe(sx,sy,(sx-v.width*.025f).toInt(),(sy-v.height*.025f).toInt(),18)
            device.waitForIdle()
        }
        com.gemini.ocr.network.StandaloneTranslator.beforeNetworkCallForTest={error("Cloud forbidden in local ROI witness")}
        try {
            ActivityScenario.launch<VideoActivity>(Intent(context,VideoActivity::class.java).putExtra("project",p.id).putExtra("ux_step","setup")).use{scenario->
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)),10000))
                click("Chỉnh vùng chữ")
                withTimeout(10000){while(true){
                    var ready=false
                    scenario.onActivity{ready=findPlayer(it.window.decorView)?.player?.playbackState==androidx.media3.common.Player.STATE_READY}
                    if(ready)break
                    delay(100)
                }}
                scenario.onActivity{requireNotNull(findPlayer(it.window.decorView)?.player).apply{pause();seekTo(1500)}}
                delay(250)
                repeat(20){index->
                    val old=store.load(p.id)
                    click("Khoanh vùng chữ Trung");canvas()
                    drag(old.roi,false);drag(old.roi,true)
                    assertEquals("Gestures must remain private drafts",old.roi,store.load(p.id).roi)
                    assertEquals(old.style,store.load(p.id).style)
                    when(index%4){
                        0->{click("Hủy");assertEquals(old.roi,store.load(p.id).roi)}
                        1->{click("Đặt lại");click("Xác nhận vùng")}
                        2->{device.pressBack();click("Tiếp tục chỉnh");click("Xác nhận vùng")}
                        else->{device.pressBack();click("Bỏ thay đổi");assertEquals(old.roi,store.load(p.id).roi)}
                    }
                    SystemClock.sleep(120)
                    scenario.onActivity{assertFalse(it.isFinishing)}
                    report.put("completedCycles",index+1);file.writeText(report.toString(2))
                }
                click("Khoanh vùng chữ Trung");drag(store.load(p.id).roi,false)
                device.setOrientationLeft();device.waitForIdle();canvas()
                device.pressBack();click("Bỏ thay đổi")
                device.setOrientationNatural();device.unfreezeRotation();device.waitForIdle()
                click("Khoanh vùng chữ Trung");click("Đặt lại")
                click("Xem thử OCR")
                withTimeout(30000){while(true){
                    val text=device.findObject(By.res("video_region_ocr_result"))?.text.orEmpty()
                    if(text.any{Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN}){report.put("ocrPreview",text);break}
                    delay(100)
                }}
                assertNull(jobs.busy.value);assertTrue(store.load(p.id).cues.isEmpty())
                assertTrue(device.takeScreenshot(File(folder,"roi-confirmed-preview.png")))
                click("Xác nhận vùng");SystemClock.sleep(250)
                val roi=store.load(p.id).roi
                click("Chỉnh khung phụ đề sau dịch");canvas();click("Đặt lại")
                drag(listOf(.03f,.75f,.97f,.93f),true);click("Xác nhận vùng");SystemClock.sleep(250)
                assertEquals("Output frame must not alter OCR ROI",roi,store.load(p.id).roi)
                assertNotEquals(p.style,store.load(p.id).style)
                scenario.onActivity{jobs.start(p.id)}
                withTimeout(150000){while(jobs.busy.value!=null)delay(150)}
                val translated=store.load(p.id)
                assertEquals(translated.message,VideoStage.REVIEW,translated.stage)
                assertTrue(translated.cues.isNotEmpty() && translated.cues.all{it.translation.isNotBlank()})
                assertEquals(roi,translated.roi);assertEquals(0,translated.cloudCalls)
                report.put("translatedCues",translated.cues.size).put("localSource",translated.cues.first().source)
                    .put("localTranslation",translated.cues.first().translation).put("roi",org.json.JSONArray(roi))
                val output=File(folder,"confirmed-region-export.mp4")
                VideoExporter.export(context,translated,output,{},{})
                assertTrue(output.length()>0)
                val decoded=VideoDecoder.metadata(context,Uri.fromFile(output))
                assertEquals(p.width,decoded.width);assertEquals(p.height,decoded.height)
                assertNotNull(VideoDecoder.preview(context,Uri.fromFile(output),1500)?.also{it.recycle()})
                report.put("exportBytes",output.length()).put("exportGeometryPreserved",true).put("completed",true)
            }
        }catch(e:Throwable){report.put("failure",e.javaClass.simpleName+": "+e.message.orEmpty().take(250));throw e}
        finally{com.gemini.ocr.network.StandaloneTranslator.beforeNetworkCallForTest=null
            device.setOrientationNatural();device.unfreezeRotation();file.writeText(report.toString(2))}
    }
    private fun findPlayer(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findPlayer(view.getChildAt(i))?.let{return it}
        return null
    }
}
