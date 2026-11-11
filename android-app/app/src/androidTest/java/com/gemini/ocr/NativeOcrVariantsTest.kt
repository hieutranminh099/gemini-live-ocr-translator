package com.gemini.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.capture.SourceRowTracker
import com.gemini.ocr.capture.SubtitleRegion
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Offline native ML Kit experiment on in-memory synthetic pixels. No screen capture, Activity,
 * settings, credentials, translation, learned correction or user media is read or changed.
 * Accuracy is reported without replacing OCR characters with the reference answer.
 */
@RunWith(AndroidJUnit4::class)
class NativeOcrVariantsTest {
    private val sources = listOf(
        "关键时候掉链子", "还得是我兄弟", "今天我们先去车站", "明天早上不要迟到",
        "这件事情我还没答应", "请把窗户打开一点", "他已经等了很长时间", "你先回去我随后就到",
        "这个办法恐怕不行", "别把钥匙忘在屋里", "他们刚才往东边走了", "我以为你知道这件事",
        "等雨停了我们再出发", "请问附近有没有药店", "有些话现在还不能说", "这回总算赶上末班车",
    )
    private val variants = listOf("native_rgb", "rgb_2x_bilinear", "grayscale_contrast_1_5")

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\p{Z}]+"), "")

    private fun errors(reference: String, recognized: String): Int {
        val expected = reference.codePoints().toArray()
        val actual = recognized.codePoints().toArray()
        var previous = IntArray(actual.size + 1) { it }
        for (left in expected.indices) {
            val current = IntArray(actual.size + 1)
            current[0] = left + 1
            for (right in actual.indices) current[right + 1] = minOf(
                current[right] + 1, previous[right + 1] + 1,
                previous[right] + if (expected[left] == actual[right]) 0 else 1,
            )
            previous = current
        }
        return previous[actual.size]
    }

    /** Same font, colors and positions as the synthetic TestSceneActivity subtitle scene. */
    private fun renderScene(width: Int, height: Int, density: Float, source: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(17, 33, 42))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(34, 65, 82)
        canvas.drawRect(0f, 0f, width.toFloat(), height * .75f, paint)
        paint.color = Color.YELLOW
        paint.textSize = 30f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(source, (width - paint.measureText(source)) / 2f, height * .80f, paint)
        val vietnamese = "Dòng tiếng Việt có sẵn bên dưới"
        paint.typeface = Typeface.DEFAULT
        paint.color = Color.WHITE
        paint.textSize = 30f * .7f * density
        canvas.drawText(vietnamese, (width - paint.measureText(vietnamese)) / 2f, height * .88f, paint)
        return bitmap
    }

    private fun transform(source: Bitmap, variant: String): Bitmap = when (variant) {
        "native_rgb" -> source
        "rgb_2x_bilinear" -> Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, true)
        "grayscale_contrast_1_5" -> Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            // Luma plus contrast around 128. This changes only pixel intensity, never OCR text.
            val contrast = 1.5f
            val red = .299f * contrast
            val green = .587f * contrast
            val blue = .114f * contrast
            val offset = 128f * (1f - contrast)
            val matrix = ColorMatrix(floatArrayOf(
                red, green, blue, 0f, offset,
                red, green, blue, 0f, offset,
                red, green, blue, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ))
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            Canvas(it).drawBitmap(source, 0f, 0f, paint)
        }
        else -> error("Unknown OCR raster variant")
    }

    @Test fun syntheticChineseRasterVariantsReportRawAccuracyAndLatency() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench") {
            "Run the offline raster experiment only in the disposable verification package"
        }
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val captureScale = minOf(1.0, 1920.0 / maxOf(width, height))
        val captureWidth = (width * captureScale).roundToInt().coerceAtLeast(1)
        val captureHeight = (height * captureScale).roundToInt().coerceAtLeast(1)
        val crop = SubtitleRegion.pixels(RectF(.06f, .72f, .94f, .86f), captureWidth, captureHeight)
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val report = File(directory, "ocr-raster-variants.json")
        val samples = JSONArray()
        val startedAt = SystemClock.elapsedRealtime()
        var warmupMs: Double? = null
        var warmupCalls = 0
        var nativeCalls = 0
        var completed = false
        var failure: String? = null

        fun persist() {
            val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
            val summary = JSONArray()
            for (variant in variants) {
                val own = rows.filter { it.getString("variant") == variant }
                val characters = own.sumOf { it.getInt("reference_characters") }
                val characterErrors = own.sumOf { it.getInt("raw_character_errors") }
                val durations = own.map { it.getDouble("process_ms") }.sorted()
                fun percentile(p: Int): Any = if (durations.isEmpty()) JSONObject.NULL else
                    durations[(ceil(durations.size * p / 100.0).toInt() - 1).coerceIn(0, durations.lastIndex)]
                summary.put(JSONObject().put("variant", variant).put("cues", own.size)
                    .put("exact_cues", own.count { it.getBoolean("raw_exact") })
                    .put("empty_cues", own.count { it.getString("source_only_ocr").isEmpty() })
                    .put("reference_characters", characters).put("raw_character_errors", characterErrors)
                    .put("raw_cer", if (characters == 0) JSONObject.NULL else characterErrors.toDouble() / characters)
                    .put("process_p50_ms", percentile(50)).put("process_p95_ms", percentile(95)))
            }
            report.writeText(JSONObject()
                .put("kind", "IN_MEMORY_NATIVE_OCR_VARIANTS_NO_TRANSLATION_NO_API")
                .put("package", context.packageName).put("started_at_ms", startedAt)
                .put("completed", completed).put("failure", failure ?: JSONObject.NULL)
                .put("real_google_requests", 0).put("translation_calls", 0)
                .put("settings_or_credentials_read", false)
                .put("display_width", width).put("display_height", height).put("density", metrics.density.toDouble())
                .put("capture_width", captureWidth).put("capture_height", captureHeight)
                .put("roi", JSONObject().put("left", crop.left).put("top", crop.top)
                    .put("right", crop.right).put("bottom", crop.bottom))
                .put("source_font_sp", 30).put("source_baseline_fraction", .80)
                .put("vietnamese_baseline_fraction", .88).put("warmup_ms", warmupMs ?: JSONObject.NULL)
                .put("warmup_calls", warmupCalls).put("native_ocr_calls", nativeCalls)
                .put("normalization", "NFKC and whitespace removal only; no typo, script or learned replacement")
                .put("limitations", JSONArray(listOf(
                    "Software Canvas synthetic raster, not MediaProjection, actual movie frames or display scanout.",
                    "Uses current display metrics and the production 1920px capture cap; Activity insets and GPU rasterization can differ.",
                    "One warmup and one native observation per cue/variant; no result reuse and no reference-driven text correction.",
                    "Existing OcrProcessor noise/confidence filters and source-row selection remain; learned OCR correction and fast downscale are disabled.",
                    "Process duration measures native OCR plus existing filtering; pixel transformation time is recorded separately.",
                    "No translation quality, Gemini latency, network latency or end-to-end subtitle latency is measured.",
                )))
                .put("summary", summary).put("samples", samples).toString(2))
        }

        val processor = OcrProcessor("zh")
        try {
            // Warm the actual instance once with separate synthetic content; preserve that cost.
            val warmup = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
            try {
                Canvas(warmup).apply {
                    drawColor(Color.BLACK)
                    drawText("测试文字", 10f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE; textSize = 40f
                    })
                }
                val warmupStarted = SystemClock.elapsedRealtimeNanos()
                warmupCalls++; nativeCalls++
                processor.process(warmup, retainSourceFragments = true, fastDownscale = false, ocrPreCorrection = false)
                warmupMs = (SystemClock.elapsedRealtimeNanos() - warmupStarted) / 1_000_000.0
            } finally { warmup.recycle() }

            for ((cue, text) in sources.withIndex()) {
                val scene = renderScene(width, height, metrics.density, text)
                var captured: Bitmap? = null
                var roi: Bitmap? = null
                try {
                    val currentCapture = if (captureWidth == width && captureHeight == height) scene else
                        Bitmap.createScaledBitmap(scene, captureWidth, captureHeight, true)
                    captured = currentCapture
                    val sourceRoi = Bitmap.createBitmap(currentCapture, crop.left, crop.top, crop.width(), crop.height())
                    roi = sourceRoi
                    // Rotate order across cues rather than always giving the final variant warmer state.
                    for (offset in variants.indices) {
                        val variant = variants[(cue + offset) % variants.size]
                        val transformStarted = SystemClock.elapsedRealtimeNanos()
                        val input = transform(sourceRoi, variant)
                        val transformMs = (SystemClock.elapsedRealtimeNanos() - transformStarted) / 1_000_000.0
                        try {
                            val callStarted = SystemClock.elapsedRealtimeNanos()
                            nativeCalls++
                            val blocks = processor.process(input, retainSourceFragments = true,
                                fastDownscale = false, ocrPreCorrection = false)
                            val processMs = (SystemClock.elapsedRealtimeNanos() - callStarted) / 1_000_000.0
                            val sourceRows = SourceRowTracker.selectSourceRows(blocks, "zh", true)
                                .sortedWith(compareBy({ it.rect.top / 20 }, { it.rect.left }))
                            val raw = sourceRows.joinToString(" ") { it.text }
                            val reference = normalize(text)
                            val recognized = normalize(raw)
                            val referenceCount = reference.codePointCount(0, reference.length)
                            val errorCount = errors(reference, recognized)
                            samples.put(JSONObject().put("cue", cue).put("variant", variant)
                                .put("source", text).put("source_only_ocr", raw)
                                .put("processor_output_blocks", JSONArray(blocks.map { block ->
                                    JSONObject().put("text", block.text).put("left", block.rect.left)
                                        .put("top", block.rect.top).put("right", block.rect.right).put("bottom", block.rect.bottom)
                                }))
                                .put("input_width", input.width).put("input_height", input.height)
                                .put("transform_ms", transformMs).put("process_ms", processMs)
                                .put("transform_and_process_ms", transformMs + processMs)
                                .put("reference_characters", referenceCount).put("raw_character_errors", errorCount)
                                .put("raw_cer", errorCount.toDouble() / referenceCount)
                                .put("raw_exact", reference == recognized))
                        } finally { if (input !== sourceRoi) input.recycle() }
                    }
                } finally {
                    roi?.takeIf { it !== captured && it !== scene }?.recycle()
                    captured?.takeIf { it !== scene }?.recycle()
                    scene.recycle()
                }
                persist()
            }
            completed = true
            persist()
            assertEquals("Every cue/variant must have an observation, including errors", sources.size * variants.size, samples.length())
        } catch (error: Throwable) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
            persist()
            throw error
        } finally { processor.close() }
    }
}
