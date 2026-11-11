package com.gemini.ocr

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.ocr.OcrRecoveryPolicy
import com.paddle.ocr.OcrPreprocessProfile
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.SerializedPaddleOCR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** Test APK only: real native OCR, through the existing single capture consumer and filters. */
internal class PaddleOcrProbe private constructor(
    private val native: SerializedPaddleOCR,
    val loadMs: Long,
    val warmupMs: Long,
) {
    private val attached = AtomicBoolean(false)
    private val processorClosed = CompletableDeferred<Unit>()

    fun attach() {
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench")
        check(OcrProcessor.factoryForVerification == null)
        OcrProcessor.factoryForVerification = {
            check(attached.compareAndSet(false, true)) { "One native OCR consumer per probe" }
            OcrProcessor(recognize = { bitmap ->
                native.recognize(bitmap).results.mapNotNull { result ->
                    val bounds = mappedBounds(result.box.points, bitmap.width, bitmap.height)
                        ?: return@mapNotNull null
                    // Invalid confidence cannot bypass the common low-confidence recovery policy.
                    OcrRecoveryPolicy.Line(result.text,
                        if (result.confidence.isFinite()) result.confidence.toDouble() else 0.0, bounds)
                }
            }, onClosed = { processorClosed.complete(Unit); Unit })
        }
    }

    /** The caller first stops capture. running=false alone does not prove native OCR is finished. */
    suspend fun releaseAfterCaptureStopped() = withContext(NonCancellable) {
        try {
            if (attached.get()) withTimeout(10000) { processorClosed.await() }
        } finally {
            OcrProcessor.factoryForVerification = null
            native.release() // The native owner's gate also waits for active inference.
        }
    }

    companion object {
        internal fun mappedBounds(points: List<PointF>, width: Int, height: Int): OcrRecoveryPolicy.Bounds? {
            if (points.size != 4 || width <= 0 || height <= 0 ||
                points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val left = floor(points.minOf { it.x }.toDouble()).coerceIn(0.0, width.toDouble()).toInt()
            val top = floor(points.minOf { it.y }.toDouble()).coerceIn(0.0, height.toDouble()).toInt()
            val right = ceil(points.maxOf { it.x }.toDouble()).coerceIn(0.0, width.toDouble()).toInt()
            val bottom = ceil(points.maxOf { it.y }.toDouble()).coerceIn(0.0, height.toDouble()).toInt()
            return if (left < right && top < bottom) OcrRecoveryPolicy.Bounds(left, top, right, bottom) else null
        }

        suspend fun prepare(context: Context, v4: Boolean = false, threads: Int = 1,
                            allowSpinning: Boolean = true): PaddleOcrProbe {
            require(threads in 1..4)
            val started = SystemClock.elapsedRealtime()
            val config = if (v4) PaddleOCRConfig(modelProfile = OcrPreprocessProfile.RAPIDOCR_V4,
                detImgMode = "BGR", detLimitSideLen = 64, detLimitType = "min", detMaxSideLimit = 1600,
                detThresh = .3f, detBoxThresh = .5f, detUnclipRatio = 1.6f,
                detMaxCandidates = 1000, detUseDilation = true, recScoreThresh = 0f, recBatchSize = 1)
            else PaddleOCRConfig(detImgMode = "BGR", detLimitSideLen = 64, detLimitType = "min",
                detMaxSideLimit = 1600, recScoreThresh = 0f, recBatchSize = 1)
            val native = SerializedPaddleOCR.create(context, config, EngineConfig(numThreads = threads, allowSpinning = allowSpinning),
                if (v4) "models/v4-det/inference.onnx" else "models/det/inference.onnx",
                if (v4) "models/v4-rec/inference.onnx" else "models/rec/inference.onnx",
                if (v4) "models/v4-rec/inference.yml" else "models/rec/inference.yml")
            val loadMs = SystemClock.elapsedRealtime() - started
            try {
                val blank = android.graphics.Bitmap.createBitmap(128, 64, android.graphics.Bitmap.Config.ARGB_8888)
                val warm = SystemClock.elapsedRealtime()
                try { native.recognize(blank) } finally { blank.recycle() }
                return PaddleOcrProbe(native, loadMs, SystemClock.elapsedRealtime() - warm)
            } catch (error: Throwable) {
                native.release()
                throw error
            }
        }
    }
}
