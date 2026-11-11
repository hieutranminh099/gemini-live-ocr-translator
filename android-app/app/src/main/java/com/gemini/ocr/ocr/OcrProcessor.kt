package com.gemini.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.CostOptimizationEngine
import com.gemini.ocr.network.SelfLearningDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.roundToInt

data class OcrBlock(val id: String, val text: String, val rect: Rect) {
    fun json(width: Int, height: Int): JSONObject = JSONObject().put("id", id).put("text", text.take(2000))
        .put("box", JSONObject().put("x", rect.left.toDouble() / width).put("y", rect.top.toDouble() / height)
            .put("width", rect.width().toDouble() / width).put("height", rect.height().toDouble() / height))
}

/**
 * Dedicated Chinese (Simplified & Traditional) ML Kit OCR Processor.
 *
 * Large ROIs may be downscaled before ML Kit. Both axes use one scale so shallow tracked rows
 * retain their glyph proportions; native OCR latency and accuracy must be measured separately.
 */
class OcrProcessor private constructor(
    private val language: String,
    private val recognizeOverride: (suspend (Bitmap) -> List<OcrRecoveryPolicy.Line>)?,
    private val onClosed: () -> Unit
) : AutoCloseable {
    constructor(language: String = "zh") : this(language, null, {})
    internal constructor(recognize: suspend (Bitmap) -> List<OcrRecoveryPolicy.Line>) : this("zh", recognize, {})
    internal constructor(recognize: suspend (Bitmap) -> List<OcrRecoveryPolicy.Line>,
        onClosed: () -> Unit) : this("zh", recognize, onClosed)
    data class TargetSize(val width: Int, val height: Int)

    private var recognizerInstance: TextRecognizer? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun getRecognizer(): TextRecognizer {
        return recognizerInstance ?: createRecognizer().also { recognizerInstance = it }
    }

    suspend fun process(
        bitmap: Bitmap,
        retainSourceFragments: Boolean = false,
        fastDownscale: Boolean = true,
        ocrPreCorrection: Boolean = true,
        traceFrameId: Long? = null
    ): List<OcrBlock> = processObservation(bitmap, retainSourceFragments, fastDownscale,
        ocrPreCorrection, traceFrameId).blocks

    /**
     * Batch-only source evidence: retain actual low-confidence Han for explicit review.
     * Live continues to use processObservation and its unchanged confidence/recovery guards.
     * The caller owns the bitmap; cancellation cannot return while native OCR still uses it.
     */
    suspend fun processVideoDraft(bitmap:Bitmap):OcrVideoDraft {
        require(!bitmap.isRecycled && bitmap.width>0 && bitmap.height>0)
        require(language.substringBefore('-').substringBefore('_').equals("zh",true))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val lines=kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){recognize(bitmap)}
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return OcrVideoDraft.from(lines,bitmap.width,bitmap.height)
    }

    suspend fun processObservation(
        bitmap: Bitmap,
        retainSourceFragments: Boolean = false,
        fastDownscale: Boolean = true,
        ocrPreCorrection: Boolean = true,
        traceFrameId: Long? = null,
        enforceSubtitlePolicy: Boolean = false
    ): OcrObservation {
        val target = computeTargetSize(bitmap.width, bitmap.height, fastDownscale)
        val targetBitmap = if (target.width != bitmap.width || target.height != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
        } else {
            bitmap
        }
        val scaleX = bitmap.width.toDouble() / targetBitmap.width
        val scaleY = bitmap.height.toDouble() / targetBitmap.height

        val firstNativeAt = android.os.SystemClock.elapsedRealtime()
        val lines = try {
            recognize(targetBitmap)
        } finally {
            if (targetBitmap !== bitmap && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }
        val firstNativeMs = android.os.SystemClock.elapsedRealtime() - firstNativeAt
        val first = filterLines(lines, retainSourceFragments,
            ocrPreCorrection, false) { bounds -> OcrRecoveryPolicy.Bounds(
                (bounds.left * scaleX).toInt(), (bounds.top * scaleY).toInt(),
                (bounds.right * scaleX).toInt(), (bounds.bottom * scaleY).toInt())
                .clipped(bitmap.width, bitmap.height) }
        tracePass(traceFrameId, bitmap.width, bitmap.height, target.width, target.height, 1, firstNativeMs, first)
        val hasAcceptedSource = first.blocks.isNotEmpty() &&
            (!enforceSubtitlePolicy || !language.substringBefore('-').substringBefore('_').equals("zh", true) ||
                first.blocks.any { OcrRecoveryPolicy.hasHan(it.text) })
        if (hasAcceptedSource || first.uncertainLines.isEmpty()) {
            return OcrObservation(first.blocks)
        }
        val plan = OcrRecoveryPolicy.plan(bitmap.width, bitmap.height, first.uncertainLines)
        if (plan == null) {
            traceRecovery(traceFrameId, 1, "pixel_bound", 0)
            return OcrObservation(emptyList(), uncertain = true, recoveryReason = "pixel_bound")
        }
        // There is at most one recrop and one extra native pass. Never retry the retry result.
        val crop = Bitmap.createBitmap(bitmap, plan.crop.left, plan.crop.top, plan.crop.width, plan.crop.height)
        var enlarged: Bitmap? = null
        var recoveryNativeMs = 0L
        val recovered = try {
            val retryBitmap = Bitmap.createScaledBitmap(crop, plan.targetWidth, plan.targetHeight, true)
            enlarged = retryBitmap
            val retryNativeAt = android.os.SystemClock.elapsedRealtime()
            val retryLines = recognize(retryBitmap)
            recoveryNativeMs = android.os.SystemClock.elapsedRealtime() - retryNativeAt
            filterLines(retryLines, retainSourceFragments,
                ocrPreCorrection, true) { plan.mapToSource(it, bitmap.width, bitmap.height) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            traceRecovery(traceFrameId, 2, "native_failure", 0)
            return OcrObservation(emptyList(), uncertain = true, nativePasses = 2, recoveryReason = "native_failure")
        } finally {
            enlarged?.let { if (it !== bitmap && it !== crop && !it.isRecycled) it.recycle() }
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
        tracePass(traceFrameId, bitmap.width, bitmap.height, plan.targetWidth, plan.targetHeight, 2, recoveryNativeMs, recovered)
        val reason = if (recovered.blocks.isEmpty()) "low_confidence_han" else "recovered_han"
        traceRecovery(traceFrameId, 2, reason, recovered.blocks.size)
        return OcrObservation(recovered.blocks, uncertain = recovered.blocks.isEmpty(),
            nativePasses = 2, recoveryReason = reason)
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrRecoveryPolicy.Line> {
        check(!closed.get()) { "OCR processor is closed" }
        recognizeOverride?.let { return it(bitmap) }
        return getRecognizer().process(InputImage.fromBitmap(bitmap, 0)).await().textBlocks
            .flatMap { it.lines }.map { line -> OcrRecoveryPolicy.Line(line.text,
                line.confidence?.toDouble() ?: Double.NaN, line.boundingBox?.let {
                    OcrRecoveryPolicy.Bounds(it.left, it.top, it.right, it.bottom)
                }) }
    }

    private data class FilteredLines(val blocks: List<OcrBlock>,
        val uncertainLines: List<OcrRecoveryPolicy.Line>, val diagnostics: OcrLineDiagnostics)

    private fun filterLines(detectedLines: List<OcrRecoveryPolicy.Line>,
        retainSourceFragments: Boolean, ocrPreCorrection: Boolean, requireReliableHan: Boolean,
        mapToSource: (OcrRecoveryPolicy.Bounds) -> OcrRecoveryPolicy.Bounds?): FilteredLines {
        var total = 0
        var noiseRejected = 0; var confidenceRejected = 0; var policyRejected = 0
        var geometryRejected = 0; var budgetRejected = 0
        val uncertainLines = mutableListOf<OcrRecoveryPolicy.Line>()
        val eligible = detectedLines.mapNotNull { block ->
            val rawRect = block.bounds ?: run { geometryRejected++; return@mapNotNull null }
            val rawNormalized = Normalizer.normalize(block.text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
            val text = if (ocrPreCorrection) {
                SelfLearningDatabase.preprocessAndCorrectOcr(rawNormalized)
            } else {
                rawNormalized
            }
            val confidence = block.confidence
            val singleHan = text.length == 1 && Character.UnicodeScript.of(text[0].code) == Character.UnicodeScript.HAN
            val fragment = retainSourceFragments && text.any { it.isDigit() || it == '_' } &&
                text.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
            if (CostOptimizationEngine.isOcrNoise(text, "zh", retainSourceFragments) && !singleHan) {
                SessionState.noiseFiltered.value++
                noiseRejected++
                return@mapNotNull null
            }
            if ((text.length < 2 && !singleHan && !fragment) || (text.none { it.isLetter() } && !fragment) || text.length > 2000) {
                policyRejected++; return@mapNotNull null
            }
            if (!confidence.isNaN() && confidence < OcrRecoveryPolicy.MIN_CONFIDENCE) {
                confidenceRejected++
                if (OcrRecoveryPolicy.isUncertainHan(block)) {
                    mapToSource(rawRect)?.let { uncertainLines.add(block.copy(bounds = it)) }
                }
                return@mapNotNull null
            }
            if (requireReliableHan && !OcrRecoveryPolicy.isReliableHan(block)) {
                policyRejected++; return@mapNotNull null
            }
            val mapped = mapToSource(rawRect) ?: run { geometryRejected++; return@mapNotNull null }
            val clipped = Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
            total += text.length
            if (total > 12000) { budgetRejected++; return@mapNotNull null }
            OcrBlock("b${block.hashCode().toUInt().toString(16)}", text, clipped)
        }
        val returned = eligible.sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left })).take(40)
            .mapIndexed { i, block -> block.copy(id = "b$i") }
        return FilteredLines(returned, uncertainLines, OcrLineDiagnostics(detectedLines.size,
            noiseRejected, confidenceRejected, policyRejected, geometryRejected, budgetRejected,
            eligible.size, returned.size))
    }

    private fun tracePass(frameId: Long?, inputWidth: Int, inputHeight: Int,
        targetWidth: Int, targetHeight: Int, pass: Int, nativeMs: Long, filtered: FilteredLines) {
        com.gemini.ocr.AndroidPerformanceTelemetry.counters.recordFrame(com.gemini.ocr.PerformanceFrameStage.OCR_COMPLETED)
        com.gemini.ocr.capture.PipelineTrace.event("ocr_native_completed", "frame_id" to frameId,
            "input_width" to inputWidth, "input_height" to inputHeight,
            "target_width" to targetWidth, "target_height" to targetHeight, "native_pass" to pass, "native_ms" to nativeMs,
            *filtered.diagnostics.fields())
    }

    private fun traceRecovery(frameId: Long?, passes: Int, reason: String, returned: Int) {
        com.gemini.ocr.capture.PipelineTrace.event("ocr_recovery_completed", "frame_id" to frameId,
            "native_passes" to passes, "recovery_reason" to reason, "returned_lines" to returned)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            recognizerInstance?.close()
        } catch (_: Throwable) {
        } finally {
            onClosed()
        }
    }

    companion object {
        /** Native candidate injection is restricted to the disposable verification package. */
        @Volatile internal var factoryForVerification: ((String) -> OcrProcessor)? = null

        internal fun create(language: String): OcrProcessor {
            val factory = factoryForVerification ?: return OcrProcessor(language)
            check(com.gemini.ocr.BuildConfig.DEBUG &&
                com.gemini.ocr.BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench") {
                "OCR candidate factory is restricted to the verification APK"
            }
            return factory(language)
        }

        const val MIN_ROI_HEIGHT_PX = 160
        const val TARGET_ROI_HEIGHT_PX = 200
        const val MAX_ROI_HEIGHT_PX = 240
        private const val MAX_OCR_WIDTH_PX = 960

        fun computeDownscaleFactor(width: Int, height: Int, fastDownscale: Boolean = true): Float {
            if (!fastDownscale || width <= 0 || height <= 0) return 1f
            val heightScale = if (height > MAX_ROI_HEIGHT_PX) {
                TARGET_ROI_HEIGHT_PX.toFloat() / height.toFloat()
            } else {
                1f
            }
            val widthScale = MAX_OCR_WIDTH_PX.toFloat() / width.toFloat()
            val preferredScale = minOf(1f, heightScale, widthScale)
            // A lower input-height bound constrains the ONE scale; clamping only the resulting
            // height stretched shallow rows vertically. Preserve a short row at original size,
            // allowing width above the soft width target instead of reducing glyph resolution.
            val minimumHeightScale = minOf(1f, MIN_ROI_HEIGHT_PX.toFloat() / height.toFloat())
            return maxOf(preferredScale, minimumHeightScale)
        }

        fun computeTargetSize(width: Int, height: Int, fastDownscale: Boolean = true): TargetSize {
            require(width > 0 && height > 0) { "OCR bitmap dimensions must be positive" }
            val scale = computeDownscaleFactor(width, height, fastDownscale)
            if (scale >= .96f) return TargetSize(width, height)
            return TargetSize(
                (width * scale).roundToInt().coerceIn(1, width),
                (height * scale).roundToInt().coerceIn(1, height),
            )
        }

        private fun createRecognizer(): TextRecognizer =
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        suspend fun preWarm(@Suppress("UNUSED_PARAMETER") language: String = "zh") {
            try {
                val client = createRecognizer()
                val dummy = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    client.process(InputImage.fromBitmap(dummy, 0)).await()
                } catch (_: Throwable) {
                } finally {
                    dummy.recycle()
                    client.close()
                }
            } catch (_: Throwable) {
            }
        }
    }
}
