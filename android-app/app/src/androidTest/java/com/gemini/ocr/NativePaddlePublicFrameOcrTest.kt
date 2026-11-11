package com.gemini.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.capture.SubtitleText
import com.paddle.ocr.OcrPreprocessProfile
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.SerializedPaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/** Public crop OCR screening only. This does not measure live capture or translation latency. */
class NativePaddlePublicFrameOcrTest {
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun comparable(text: String): String = SubtitleText.sourceIdentity(text, "zh")
        .replace(Regex("[\\s\\p{Z}]+"), "")

    private fun errors(a: String, b: String): Int {
        val left = a.codePoints().toArray(); val right = b.codePoints().toArray()
        var previous = IntArray(right.size + 1) { it }
        for (i in left.indices) {
            val next = IntArray(right.size + 1); next[0] = i + 1
            for (j in right.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1)
            previous = next
        }
        return previous.last()
    }

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject().put("pss_kib", info.totalPss)
            .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun stages(result: OCRRunResult): JSONObject = JSONObject()
        .put("det_preprocess_ms", result.detPreprocessMs).put("det_inference_ms", result.detInferenceMs)
        .put("det_postprocess_ms", result.detPostprocessMs).put("rec_preprocess_ms", result.recPreprocessMs)
        .put("rec_inference_ms", result.recInferenceMs).put("rec_postprocess_ms", result.recPostprocessMs)
        .put("pipeline_overhead_ms", result.pipelineOverheadMs)
        .put("upstream_total_ms", result.totalTimeMs)

    @Test fun authorizedPublicRecordingCropsMeasurePaddleTinyWithoutInventingSource() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executePaddlePublicFrames") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        val threadCount = args.getString("paddleThreads")?.toIntOrNull() ?: 4
        require(threadCount in 1..4)
        val profile = args.getString("paddleProfile") ?: "tiny"
        require(profile in setOf("tiny", "v4"))
        val v4 = profile == "v4"
        val directory = File(context.getExternalFilesDir(null), "verification/public-ocr-frames-v1")
        val manifest = File(directory, "manifest.json").also { require(it.length() in 1..32768) }
        val manifestBytes = manifest.readBytes()
        require(digest(manifestBytes) == "dc64f714595afe41256214fa01f0bea43de6b6eecbdd53714ddc2821af0c2ad0")
        val data = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(data.getString("kind") == "REAL_PUBLIC_VIDEO_FRAME_OCR_REGRESSION_NOT_E2E")
        require(data.getString("public_video_id") == "CJBA1VyDSPc")
        val cases = data.getJSONArray("cases"); require(cases.length() == 12)
        val outputName = args.getString("paddleReport") ?: "paddle-public-frame-ocr-v1.json"
        require(outputName.matches(Regex("paddle-public-frame-ocr-[a-zA-Z0-9_-]{1,40}\\.json")))
        val output = File(context.getExternalFilesDir(null), "verification/$outputName")
        check(!output.exists()) { "Preserve previous report; pass a new paddleReport name" }
        val samples = JSONArray()
        val report = JSONObject().put("kind", "PADDLE_REAL_PUBLIC_CROPS_OCR_ONLY_NOT_VIDEO_E2E").put("model_profile", profile)
            .put("local_hardening", "CTC shape validation + serialized native lifetime")
            .put("sdk_revision", "dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf")
            .put("det_model_revision", if (v4) JSONObject.NULL else "2ba1506c0380b8f0b03dd142459aac66d4421f6c")
            .put("rec_model_revision", if (v4) JSONObject.NULL else "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30")
            .put("v4_source_package", if (v4) "rapidocr-onnxruntime==1.4.4" else JSONObject.NULL)
            .put("weight_redistribution_license_verified", !v4)
            .put("api_requests", 0).put("translation_model_calls", 0)
            .put("input_limit", "1280x720 compressed recording crops, not original capture pixels")
            .put("confidence_threshold", .5).put("confidence_calibrated", false)
            .put("threads", threadCount).put("completed", false).put("samples", samples)
            .put("memory_measurement", "snapshots, not peak RAM; native allocator may retain pages after release")
            .put("internal_stage_clock", "upstream System.currentTimeMillis; diagnostic only")
            .put("process_clock", "SystemClock.elapsedRealtimeNanos")
            .put("memory_before_load", memory())
        fun persist() {
            val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
            fun p95(field: String, nested: Boolean = false): Any {
                val values = rows.map { if (nested) it.getJSONObject("stages").getDouble(field) else it.getDouble(field) }.sorted()
                return if (values.isEmpty()) JSONObject.NULL else values[ceil(values.size * .95).toInt() - 1]
            }
            val summary = JSONObject().put("cases", rows.size)
                .put("exact", rows.count { it.getBoolean("exact") })
                .put("raw_exact", rows.count { it.getBoolean("raw_exact") })
                .put("character_errors", rows.sumOf { it.getInt("character_errors") })
                .put("raw_character_errors", rows.sumOf { it.getInt("raw_character_errors") })
                .put("reference_characters", rows.sumOf { it.getInt("reference_characters") })
                .put("misses", rows.count { !it.getBoolean("expected_blank") && it.getString("source_zh").isBlank() })
                .put("false_positive_blanks", rows.count { it.getBoolean("expected_blank") && it.getString("source_zh").isNotBlank() })
                .put("process_p95_ms", p95("process_ms")).put("input_decode_p95_ms", p95("input_decode_ms"))
            val referenceCharacters = rows.sumOf { it.getInt("reference_characters") }
            summary.put("cer", if (referenceCharacters == 0) JSONObject.NULL else
                rows.sumOf { it.getInt("character_errors") }.toDouble() / referenceCharacters)
            summary.put("raw_cer", if (referenceCharacters == 0) JSONObject.NULL else
                rows.sumOf { it.getInt("raw_character_errors") }.toDouble() / referenceCharacters)
            for (stage in listOf("det_preprocess_ms", "det_inference_ms", "det_postprocess_ms", "rec_preprocess_ms",
                "rec_inference_ms", "rec_postprocess_ms", "pipeline_overhead_ms")) summary.put("${stage}_p95", p95(stage, true))
            report.put("summary", summary)
            output.parentFile?.mkdirs(); output.writeText(report.toString(2), Charsets.UTF_8)
        }
        // Hash/config validation is deliberately outside model load and process timing.
        val models = if (v4) listOf(
            Triple("models/v4-det/inference.onnx", 4745517L, "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"),
            Triple("models/v4-rec/inference.onnx", 10857958L, "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"),
            Triple("models/v4-rec/inference.yml", 66093L, "23521ee42483e688514fc4fb6b5c47f254af3e56fb2208d4d2445beb10065569"),
        ) else listOf(
            Triple("models/det/inference.onnx", 1780590L, "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8"),
            Triple("models/rec/inference.onnx", 4462639L, "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6"),
            Triple("models/rec/inference.yml", 55571L, "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1"),
        )
        for ((asset, bytes, expectedHash) in models) {
            val md = MessageDigest.getInstance("SHA-256"); var total = 0L
            context.assets.open(asset).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= bytes); md.update(buffer, 0, count) }
            }
            require(total == bytes && md.digest().joinToString("") { "%02x".format(it) } == expectedHash)
        }
        require(OpenCVUtils.init(context)) { "OpenCV native library unavailable for this ABI" }
        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val config = if (v4) PaddleOCRConfig(modelProfile = OcrPreprocessProfile.RAPIDOCR_V4,
            detImgMode = "BGR", detLimitSideLen = 64, detLimitType = "min", detMaxSideLimit = 1600,
            detThresh = .3f, detBoxThresh = .5f, detUnclipRatio = 1.6f,
            detMaxCandidates = 1000, detUseDilation = true, recScoreThresh = 0f, recBatchSize = 1)
        else PaddleOCRConfig(detImgMode = "BGR", detLimitSideLen = 64, detLimitType = "min",
            detMaxSideLimit = 1600, recScoreThresh = 0f, recBatchSize = 1)
        val processor = SerializedPaddleOCR.create(context, config, EngineConfig(numThreads = threadCount),
            models[0].first, models[1].first, models[2].first)
        report.put("model_load_ms", (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000.0)
            .put("memory_after_load", memory())
        try {
            val warm = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            val warmStarted = SystemClock.elapsedRealtimeNanos()
            try { withContext(NonCancellable) { processor.recognize(warm) } } finally { warm.recycle() }
            report.put("separate_blank_warmup_ms", (SystemClock.elapsedRealtimeNanos() - warmStarted) / 1_000_000.0)
            // Exactly one reused native session pair; all calls and final release are sequential.
            for (index in 0 until cases.length()) {
                val row = cases.getJSONObject(index)
                val name = row.getString("file"); require(name.matches(Regex("public-film-a-[0-9]{2}\\.png")))
                val file = File(directory, name); require(file.length() in 1..3_000_000)
                require(digest(file.readBytes()) == row.getString("sha256"))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 3..1600 && bounds.outHeight in 3..300)
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
                val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1_000_000.0
                try {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result = withContext(NonCancellable) { processor.recognize(bitmap) }
                    val processMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    val hanRows = result.results.filter { it.text.codePoints().anyMatch { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN } }
                    val raw = hanRows.joinToString(" ") { it.text }
                    val selected = hanRows.filter { it.confidence.isFinite() && it.confidence >= .5f }.joinToString(" ") { it.text }
                    val reference = comparable(row.getString("source_zh")); val actual = comparable(selected); val rawActual = comparable(raw)
                    samples.put(JSONObject().put("case", index).put("source_zh", selected).put("raw_source_zh", raw)
                        .put("reference_zh", row.getString("source_zh")).put("expected_blank", reference.isBlank())
                        .put("exact", reference == actual).put("raw_exact", reference == rawActual)
                        .put("character_errors", errors(reference, actual)).put("raw_character_errors", errors(reference, rawActual))
                        .put("reference_characters", reference.codePointCount(0, reference.length))
                        .put("input_decode_ms", decodeMs).put("process_ms", processMs).put("stages", stages(result))
                        .put("det_input_shape", JSONArray(result.detInputShape)).put("rec_input_shapes", JSONArray(result.recInputShapes))
                        .put("all_rows", JSONArray(result.results.map { line -> JSONObject().put("text", line.text)
                            .put("confidence", if (line.confidence.isFinite()) line.confidence.toDouble() else JSONObject.NULL)
                            .put("quad", JSONArray(line.box.points.map { point -> JSONArray(listOf(point.x, point.y)) })) })))
                } finally { bitmap.recycle() }
                persist()
            }
            report.put("completed", true).put("memory_after_cases", memory())
        } finally {
            withContext(NonCancellable) { processor.release() }
            report.put("memory_after_release", memory()); persist()
        }
        Unit
    }
}
