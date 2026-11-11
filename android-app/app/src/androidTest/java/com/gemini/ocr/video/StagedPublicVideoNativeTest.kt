package com.gemini.ocr.video

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Opt-in real decoder/local OCR on a hash-pinned public interview, with networking forbidden.
 * Does not claim Gemini translation, Chinese speech ASR or rendered MP4 quality. */
@RunWith(AndroidJUnit4::class)
class StagedPublicVideoNativeTest {
    @Test fun publicVideoExtractCleanReopenAndFourScreenLayout():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeStagedPublic")=="true")
        val context=instrument.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.stagedtest")
        val folder=File(context.getExternalFilesDir(null),"staged-public").apply{mkdirs()}
        val source=File(folder,"cee-interview-chinese-cc-by-sa.mp4")
        val hash=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",hash)
        val calls=AtomicInteger();val prior=StandaloneTranslator.beforeNetworkCallForTest
        StandaloneTranslator.beforeNetworkCallForTest={calls.incrementAndGet();error("Native extraction must never call Gemini")}
        val jobs=VideoJobs.get(context);val store=VideoStore(context)
        val started=SystemClock.elapsedRealtime()
        val report=JSONObject().put("completed",false).put("source_sha256",hash)
            .put("source_url","https://assets.videocome.com/examples/subtitle-localization/cee-interview-v3-20261016/after.mp4")
            .put("license","CC BY-SA 4.0; VideoCome adaptation / Wikimedia Eesti / Amadvr / Misosoof")
            .put("scope","Real public media, native decoder/local Chinese OCR, cleanup, disk reopen and UI. Optional render uses actual Chinese OCR; no cloud Vietnamese translation or ASR.")
        var reservation=""
        val draftEvidence=JSONArray()
        val priorDraftObserver=VideoDecoder.draftObserverForTest
        VideoDecoder.draftObserverForTest={ms,draft->
            if(draftEvidence.length()<400)draftEvidence.put(JSONObject().put("ms",ms).put("text",draft.text)
                .put("confidence",draft.minConfidence?:JSONObject.NULL).put("flags",JSONArray(draft.flags)))
        }
        val device=UiDevice.getInstance(instrument)
        try {
            val batches=BatchJobs.get(context)
            var batch=batches.create("Kiểm thử video công khai",BatchKind.SERIES)
            batch=batches.add(batch.id,listOf(Uri.fromFile(source)))
            val id=batch.items.single().projectId
            val original=store.load(id)
            jobs.save(original.copy(sourceMode=VideoSourceMode.OCR,translationMode=VideoTranslationMode.GEMINI,
                roi=listOf(.25f,.80f,.75f,.95f)))
            reservation=batch.id;jobs.reserveBatch(reservation)
            val sampleText=JSONArray()
            for(ms in listOf(1_000L,5_000L,11_000L)){
                sampleText.put(JSONObject().put("ms",ms).put("text",VideoRoiPreview.recognize(context,
                    store.load(id),ms,VideoSubtitleFrame.fromRoi(store.load(id).roi))))
            }
            report.put("real_rgb_roi_samples",sampleText)
            val extracted=withTimeout(240_000){jobs.runStageForBatch(reservation,id,BatchPipelineStage.EXTRACT)}
            assertTrue(extracted.cues.isNotEmpty())
            assertTrue(extracted.cues.all{it.translation.isBlank() && it.rawZh.isNotBlank()})
            assertEquals(0,calls.get())
            report.put("extraction_metrics",extracted.ocrMetrics).put("extracted_cues",JSONArray(extracted.cues.map{it.json()}))
                .put("raw_draft_evidence",draftEvidence)
            File(folder,"report.json").writeText(report.toString(2))
            // Manually transcribed visible prefixes from this public clip at 1s / 5s / 11s.
            // This is a real-media coverage check, not a simulated translation or a quality percentage.
            val actualSource=extracted.cues.joinToString(" "){it.source}
            for(prefix in listOf("见到","不知不觉","就是它"))assertTrue("Missing visible public subtitle: $prefix",actualSource.contains(prefix))
            val reopened=VideoStore(context).load(id)
            assertEquals(extracted.cues,reopened.cues)
            val repeated=jobs.runStageForBatch(reservation,id,BatchPipelineStage.EXTRACT)
            assertEquals(extracted.cues,repeated.cues)
            assertEquals(extracted.ocrMetrics.toString(),repeated.ocrMetrics.toString())
            val cleaned=jobs.runStageForBatch(reservation,id,BatchPipelineStage.CLEAN)
            assertEquals(extracted.cues.map{it.id},cleaned.cues.map{it.id})
            assertEquals(extracted.cues.map{it.startMs to it.endMs},cleaned.cues.map{it.startMs to it.endMs})
            val srt=VideoStagedPipeline.sourceSrt(cleaned.cues)
            assertTrue(srt.contains(" --> "))
            assertTrue(cleaned.cues.all{it.rawZh.isNotBlank() && it.correctedZh.isNotBlank()})
            File(folder,"source-zh.srt").writeText(srt)
            val srtRoundTrip=VideoSubtitles.parse("source-zh.srt",srt.toByteArray(Charsets.UTF_8),cleaned.durationMs)
            assertEquals(cleaned.cues.map{it.startMs to it.endMs},srtRoundTrip.map{it.startMs to it.endMs})
            assertEquals(cleaned.cues.map{it.correctedZh.ifBlank{it.source}},srtRoundTrip.map{it.source})
            report.put("ocr_completed",true).put("cue_count",cleaned.cues.size).put("ocr_metrics",cleaned.ocrMetrics)
                .put("checkpoint_reopen_preserved",true).put("completed_extraction_not_repeated",true)
            jobs.releaseBatch(reservation);reservation=""
            instrument.runOnMainSync { context.startActivity(Intent(context,BatchActivity::class.java)
                .putExtra("batch",batch.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)),10000)
            for ((orientation, rotate) in listOf("natural" to { device.setOrientationNatural() }, "rotated" to { device.setOrientationLeft() })) {
                rotate();device.waitForIdle()
                assertTrue("Batch activity did not return after rotation",device.wait(
                    Until.hasObject(By.text(Pattern.compile("^[1-4]\\. .*"))),15_000))
                for ((index,label) in listOf("1. Chọn phim","2. Thiết lập phụ đề","3. Tiến độ","4. Kiểm tra và xuất").withIndex()) {
                    var selected=false
                    repeat(4) {
                        if(!selected) {
                            val tab=device.findObject(By.text(label))
                            if(tab!=null && tab.visibleBounds.width()>20 && tab.visibleBounds.height()>0) {
                                tab.click();device.waitForIdle();selected=true
                            } else {
                                device.wait(Until.hasObject(By.text(Pattern.compile("^[1-4]\\. .*"))),5_000)
                                val visible=device.findObjects(By.text(Pattern.compile("^[1-4]\\. .*")))
                                    .firstOrNull{it.visibleBounds.width()>40 && it.visibleBounds.height()>0}
                                val y=requireNotNull(visible).visibleBounds.centerY()
                                val width=device.displayWidth
                                if(index==0)device.swipe(width/5,y,width*4/5,y,20)
                                else device.swipe(width*4/5,y,width/5,y,20)
                                device.waitForIdle()
                            }
                        }
                    }
                    assertTrue("Tab not reachable: $label",selected)
                    val expectedContent=listOf("Danh sách video","Thiết lập phụ đề",
                        "Mỗi công đoạn đã xong được giữ lại khi tiếp tục.",
                        "Xem video, đối chiếu câu Trung và chỉnh câu Việt trước khi xuất.")[index]
                    assertTrue("Tab content did not render: $label",device.wait(Until.hasObject(By.text(expectedContent)),10_000))
                    device.waitForIdle()
                    if(index==3)assertTrue("OCR review warning was hidden",device.wait(Until.hasObject(
                        By.text(Pattern.compile("Còn [0-9]+ câu được đánh dấu chưa duyệt.*"))),10_000))
                    assertTrue(device.takeScreenshot(File(folder,"batch-$orientation-step-${index+1}.png")))
                    if(orientation=="natural" && index==1){
                        var roiButton=device.findObject(By.text("Chọn vùng chữ Trung"))
                        repeat(4){
                            if(roiButton==null || roiButton!!.visibleBounds.height()<10){
                                device.swipe(device.displayWidth*3/4,device.displayHeight*4/5,
                                    device.displayWidth*3/4,device.displayHeight/3,25)
                                device.waitForIdle();roiButton=device.findObject(By.text("Chọn vùng chữ Trung"))
                            }
                        }
                        requireNotNull(roiButton).click()
                        assertTrue("Shared ROI dialog did not open",device.wait(Until.hasObject(By.text("Xác nhận vùng")),10_000))
                        device.waitForIdle()
                        assertTrue(device.takeScreenshot(File(folder,"batch-natural-roi-dialog.png")))
                        requireNotNull(device.findObject(By.text("Hủy"))).click();device.waitForIdle()
                        assertEquals(cleaned.roi,store.load(id).roi)
                        assertEquals(cleaned.cues,store.load(id).cues)
                        report.put("shared_roi_dialog_open_cancel_preserved_work",true)
                    }
                }
            }
            if(InstrumentationRegistry.getArguments().getString("executeStagedRender")=="true") {
                // Render actual recognized Chinese, explicitly not a simulated Vietnamese translation.
                val render=File(folder,"source-zh-render.mp4")
                if(render.exists())check(render.delete())
                val preview=cleaned.copy(cues=cleaned.cues.map{it.copy(translation=it.correctedZh.ifBlank{it.source})})
                withTimeout(180_000){VideoExporter.export(context,preview,render)}
                val metadata=VideoDecoder.metadata(context,Uri.fromFile(render))
                assertEquals(cleaned.width,metadata.width);assertEquals(cleaned.height,metadata.height)
                assertTrue(kotlin.math.abs(cleaned.durationMs-metadata.durationMs)<1000)
                assertTrue(metadata.hasAudio)
                val frame=VideoDecoder.preview(context,Uri.fromFile(render),cleaned.cues.first().let{(it.startMs+it.endMs)/2})
                requireNotNull(frame)
                try { File(folder,"source-zh-render-frame.png").outputStream().use{frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
                finally {frame.recycle()}
                assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)})
                report.put("real_chinese_overlay_render",true).put("render_bytes",render.length())
                    .put("render_width",metadata.width).put("render_height",metadata.height)
                    .put("render_duration_ms",metadata.durationMs).put("render_has_audio",metadata.hasAudio)
                    .put("source_srt_roundtrip_preserved",true)
            }
            report.put("completed",true).put("cue_count",cleaned.cues.size).put("ocr_metrics",cleaned.ocrMetrics)
                .put("pipeline",cleaned.pipeline.json()).put("checkpoint_reopen_preserved",true)
                .put("completed_extraction_not_repeated",true).put("timestamp_mapping_preserved",true)
        } finally {
            VideoDecoder.draftObserverForTest=priorDraftObserver
            if(reservation.isNotEmpty())jobs.releaseBatch(reservation)
            StandaloneTranslator.beforeNetworkCallForTest=prior
            device.unfreezeRotation()
            File(folder,"report.json").writeText(report.put("cloud_attempts",calls.get())
                .put("elapsed_ms",SystemClock.elapsedRealtime()-started).toString(2))
        }
    }
}
