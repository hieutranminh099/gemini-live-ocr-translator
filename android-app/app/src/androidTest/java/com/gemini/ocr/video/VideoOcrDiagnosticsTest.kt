package com.gemini.ocr.video

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Bounded raw native OCR of owned public pixels, bypassing production line filters. No API calls. */
@RunWith(AndroidJUnit4::class)
class VideoOcrDiagnosticsTest {
    @Test fun inspectRawChineseLinesAcrossFivePixelVariants():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeVideoOcrDiagnostics")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr.codexbench")
        require(!SessionState.running.value && VideoJobs.get(context).busy.value==null)
        val base=File(context.getExternalFilesDir(null),"verification")
        val source=File(base,"cee-interview-chinese-cc-by-sa.mp4")
        require(source.isFile)
        val digest=MessageDigest.getInstance("SHA-256")
        source.inputStream().use { stream->val bytes=ByteArray(128*1024)
            while(true){val count=stream.read(bytes);if(count<0)break;digest.update(bytes,0,count)} }
        val sourceHash=digest.digest().joinToString(""){"%02x".format(it)}
        assertEquals("bb1bff9fd2b3810a30361d04efcbc8eb0265fe64fda16565d031de462d0caa33",sourceHash)
        val folder=File(base,"milestone-d-ocr-diagnostics").apply{mkdirs()}
        val rows=JSONArray()
        val report=JSONObject().put("source_sha256",sourceHash).put("completed",false)
            .put("scope","Raw Chinese ML Kit, three public frames, five variants; no production filters or translation")
            .put("roi",JSONArray(listOf(.25,.80,.75,.95))).put("results",rows)
        fun save(){File(folder,"report.json").writeText(report.toString(2),Charsets.UTF_8)}
        val retriever=MediaMetadataRetriever()
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val started=SystemClock.elapsedRealtime()
        var calls=0
        try {
            retriever.setDataSource(source.absolutePath)
            for(timeMs in listOf(800L,6500L,10600L)) {
                val full=requireNotNull(retriever.getFrameAtTime(timeMs*1000,MediaMetadataRetriever.OPTION_CLOSEST))
                val roi=try {
                    require(full.width==1280 && full.height==720)
                    Bitmap.createBitmap(full,320,576,640,108)
                } finally {full.recycle()}
                try {
                    for(variant in listOf("rgb_1x","rgb_2x","rgb_3x","gray_2x","white_threshold_2x")) {
                        val factor=if(variant=="rgb_1x")1 else if(variant=="rgb_3x")3 else 2
                        val transformed=if(variant in listOf("gray_2x","white_threshold_2x")) {
                            val pixels=IntArray(roi.width*roi.height)
                            roi.getPixels(pixels,0,roi.width,0,0,roi.width,roi.height)
                            for(i in pixels.indices){val pixel=pixels[i]
                                pixels[i]=if(variant=="gray_2x") {
                                    val gray=(Color.red(pixel)*299+Color.green(pixel)*587+Color.blue(pixel)*114)/1000
                                    Color.rgb(gray,gray,gray)
                                } else if(Color.red(pixel)>=160 && Color.green(pixel)>=160 && Color.blue(pixel)>=160)Color.BLACK else Color.WHITE
                            }
                            Bitmap.createBitmap(pixels,roi.width,roi.height,Bitmap.Config.ARGB_8888)
                        } else roi
                        val input=if(factor==1)transformed else Bitmap.createScaledBitmap(transformed,roi.width*factor,roi.height*factor,true)
                        try {
                            val imageName="${timeMs}-${variant}.png"
                            File(folder,imageName).outputStream().use{input.compress(Bitmap.CompressFormat.PNG,100,it)}
                            val before=SystemClock.elapsedRealtime();calls++
                            val text=recognizer.process(InputImage.fromBitmap(input,0)).await()
                            val lines=JSONArray()
                            text.textBlocks.flatMap{it.lines}.forEach { line->
                                val bounds=line.boundingBox
                                lines.put(JSONObject().put("text",line.text)
                                    .put("confidence",line.confidence?.toDouble()?.takeIf{it.isFinite()}?:JSONObject.NULL)
                                    .put("bounds",if(bounds==null)JSONObject.NULL else JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))))
                            }
                            rows.put(JSONObject().put("time_ms",timeMs).put("variant",variant)
                                .put("width",input.width).put("height",input.height)
                                .put("native_ms",SystemClock.elapsedRealtime()-before)
                                .put("text",text.text).put("lines",lines).put("input_png",imageName))
                            save()
                        } finally {
                            if(input!==transformed)input.recycle()
                            if(transformed!==roi)transformed.recycle()
                        }
                    }
                } finally {roi.recycle()}
            }
            assertEquals(15,calls)
            report.put("completed",true)
        } catch(error:Throwable){report.put("error",error.message?.take(400));throw error}
        finally {recognizer.close();retriever.release()
            report.put("native_calls",calls).put("elapsed_ms",SystemClock.elapsedRealtime()-started);save()}
    }
}
