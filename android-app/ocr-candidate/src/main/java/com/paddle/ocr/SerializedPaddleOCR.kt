package com.paddle.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One owner for one Paddle session pair. Call release in finally before recycling owned frames. */
class SerializedPaddleOCR private constructor(native: PaddleOCR) {
    val coldLoadTimeMs: Long = native.coldLoadTimeMs
    private val owner = SerializedOcrOwner<Bitmap, OCRRunResult>(native::recognize, native::release)

    suspend fun recognize(bitmap: Bitmap): OCRRunResult = owner.recognize(bitmap)
    suspend fun release() = owner.release()

    companion object {
        suspend fun create(
            context: Context,
            config: PaddleOCRConfig = PaddleOCRConfig(),
            engineConfig: EngineConfig = EngineConfig(),
            detModelAssetPath: String = "models/det/inference.onnx",
            recModelAssetPath: String = "models/rec/inference.onnx",
            recConfigAssetPath: String = "models/rec/inference.yml",
        ): SerializedPaddleOCR {
            require(engineConfig.numThreads > 0)
            val caller = currentCoroutineContext()
            caller.ensureActive()
            var native: PaddleOCR? = null
            try {
                withContext(NonCancellable) {
                    check(OpenCVUtils.init(context.applicationContext)) { "OpenCV native library unavailable" }
                    native = PaddleOCR.create(context, config, engineConfig,
                        detModelAssetPath, recModelAssetPath, recConfigAssetPath)
                }
                caller.ensureActive()
                return SerializedPaddleOCR(checkNotNull(native))
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { native?.release() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
                }
                throw failure
            }
        }
    }
}
