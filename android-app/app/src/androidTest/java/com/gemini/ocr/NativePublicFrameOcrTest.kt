package com.gemini.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.capture.SubtitleText
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/** Real public-recording crops: OCR-only screening, never a live translation latency claim. */
class NativePublicFrameOcrTest {
    private val variants = listOf("native_rgb", "rgb_2x", "grayscale_contrast_1_5", "bright_text_mask")
    private fun comparable(text: String) = SubtitleText.sourceIdentity(text, "zh")
        .replace(Regex("[\\s\\p{Z}]+"), "")
    private fun errors(expected: String, observed: String): Int {
        val a = expected.codePoints().toArray(); val b = observed.codePoints().toArray()
        var previous = IntArray(b.size + 1) { it }
        for (left in a.indices) {
            val next = IntArray(b.size + 1); next[0] = left + 1
            for (right in b.indices) next[right + 1] = minOf(next[right] + 1, previous[right + 1] + 1,
                previous[right] + if (a[left] == b[right]) 0 else 1)
            previous = next
        }
        return previous.last()
    }
    private fun transform(input: Bitmap, variant: String): Bitmap = when (variant) {
        "native_rgb" -> input
        "rgb_2x" -> Bitmap.createScaledBitmap(input, input.width * 2, input.height * 2, true)
        "grayscale_contrast_1_5" -> Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888).also {
            val r = .299f * 1.5f; val g = .587f * 1.5f; val b = .114f * 1.5f
            val matrix = ColorMatrix(floatArrayOf(r,g,b,0f,-64f,r,g,b,0f,-64f,r,g,b,0f,-64f,0f,0f,0f,1f,0f))
            Canvas(it).drawBitmap(input, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        }
        "bright_text_mask" -> Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888).also {
            val pixels = IntArray(input.width * input.height)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            pixels.indices.forEach { index ->
                val color = pixels[index]
                val minimum = minOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
                pixels[index] = if (minimum >= 180) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            it.setPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        }
        else -> error("Unsupported pixel transform")
    }

    @Test fun authorizedPublicRecordingCropsComparePixelVariantsWithoutInventingSource() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executePublicFrames") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        val directory = File(context.getExternalFilesDir(null), "verification/public-ocr-frames-v1")
        val manifest = File(directory, "manifest.json").also { require(it.length() in 1..32768) }
        val data = JSONObject(manifest.readText(Charsets.UTF_8))
        require(data.getString("kind") == "REAL_PUBLIC_VIDEO_FRAME_OCR_REGRESSION_NOT_E2E")
        require(data.getString("public_video_id") == "CJBA1VyDSPc")
        val cases = data.getJSONArray("cases"); require(cases.length() in 1..24)
        val output = File(context.getExternalFilesDir(null), "verification/public-frame-ocr-v1.json")
        check(!output.exists()) { "Preserve previous result; choose a new report before rerunning" }
        val processor = OcrProcessor("zh")
        val samples = JSONArray()
        val report = JSONObject().put("kind", "REAL_PUBLIC_RECORDING_CROP_OCR_ONLY_NOT_VIDEO_E2E")
            .put("input_limit", "1280x720 compressed recording; these are not original 1600x900 capture pixels")
            .put("api_requests", 0).put("translation_model_calls", 0).put("samples", samples).put("completed", false)
        fun persist() {
            val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
            report.put("summary", JSONArray(variants.map { name ->
                val own = rows.filter { it.getString("variant") == name }
                val durations = own.map { it.getDouble("process_ms") }.sorted()
                val characters = own.sumOf { it.getInt("reference_characters") }
                JSONObject().put("variant", name).put("cases", own.size)
                    .put("exact", own.count { it.getBoolean("exact") })
                    .put("character_errors", own.sumOf { it.getInt("character_errors") })
                    .put("reference_characters", characters)
                    .put("misses", own.count { !it.getBoolean("expected_blank") && it.getString("source_zh").isBlank() })
                    .put("false_positive_blanks", own.count { it.getBoolean("expected_blank") && it.getString("source_zh").isNotBlank() })
                    .put("process_p95_ms", if (durations.isEmpty()) JSONObject.NULL else durations[ceil(durations.size * .95).toInt() - 1])
            }))
            output.writeText(report.toString(2), Charsets.UTF_8)
        }
        try {
            val warm = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            val began = SystemClock.elapsedRealtimeNanos()
            try { processor.process(warm, ocrPreCorrection = false) } finally { warm.recycle() }
            report.put("separate_warmup_ms", (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0)
            // Rotate order per image to avoid always charging one variant the first real-image call.
            for (index in 0 until cases.length()) {
                val row = cases.getJSONObject(index)
                val name = row.getString("file"); require(name.matches(Regex("public-film-a-[0-9]{2}\\.png")))
                val file = File(directory,name); require(file.length() in 1..3_000_000)
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                require(hash == row.getString("sha256"))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 3..1600 && bounds.outHeight in 3..300)
                val image = checkNotNull(BitmapFactory.decodeFile(file.path))
                try {
                    for (offset in variants.indices) {
                        val variant = variants[(index + offset) % variants.size]
                        val started = SystemClock.elapsedRealtimeNanos()
                        val input = transform(image,variant)
                        try {
                            val observation = processor.processObservation(input, retainSourceFragments = true,
                                fastDownscale = false, ocrPreCorrection = false, enforceSubtitlePolicy = true)
                            val actual = observation.blocks.filter { block -> block.text.codePoints().anyMatch {
                                Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
                            } }.joinToString(" ") { it.text }
                            val reference = comparable(row.getString("source_zh")); val observed = comparable(actual)
                            samples.put(JSONObject().put("case", index).put("variant", variant).put("source_zh", actual)
                                .put("reference_zh", row.getString("source_zh")).put("expected_blank", reference.isBlank())
                                .put("exact", reference == observed).put("character_errors", errors(reference,observed))
                                .put("reference_characters", reference.codePointCount(0,reference.length))
                                .put("native_passes", observation.nativePasses).put("uncertain", observation.uncertain)
                                .put("process_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0))
                        } finally { if (input !== image) input.recycle() }
                    }
                } finally { image.recycle() }
                persist()
            }
            report.put("completed", true)
        } finally { processor.close(); persist() }
        Unit
    }
}
