package com.gemini.ocr

import kotlinx.coroutines.flow.MutableStateFlow

/** Server-advertised or standalone on-device mode; null availability keeps older companions explicitly unknown. */
data class TranslationBackendState(
    val provider: String = "",
    val mode: String = "",
    val translationAvailable: Boolean? = null,
    val experimentalCliEnabled: Boolean = false,
) {
    val badge: String get() = when {
        mode == "hybrid_gemini" -> "Chế độ 1: Kho Phụ Đề + Gemini (Kho cục bộ + Tự học)"
        mode == "gemini_direct" -> "Chế độ 2: Chỉ dùng Gemini (Gemini Direct · LruCache 100)"
        mode == "bank_only" -> "Chế độ 1: Kho phụ đề + Gemini"
        mode == "standalone_hybrid" -> "Độc lập trên điện thoại • ML Kit + Gemini Flash-Lite"
        mode == "standalone_mlkit" -> "Độc lập ngoại tuyến • ML Kit trên thiết bị"
        mode == "standalone_gemini" -> "Độc lập trên điện thoại • Gemini Flash-Lite trực tiếp"
        mode == "cache_only" -> "Chỉ dùng cache / từ điển • Chưa bật dịch câu mới"
        mode == "cli_experimental" && experimentalCliEnabled -> when (translationAvailable) {
            true -> "CLI cá nhân thử nghiệm • Đã bật dịch câu mới"
            false -> "CLI cá nhân thử nghiệm • Bộ dịch chưa sẵn sàng"
            null -> "CLI cá nhân thử nghiệm • Chưa rõ trạng thái bộ dịch"
        }
        mode == "api" -> when (translationAvailable) {
            true -> "Gemini API • Khóa trên Windows"
            false -> "Gemini API • Bộ dịch chưa sẵn sàng"
            null -> "Gemini API • Chưa rõ trạng thái bộ dịch"
        }
        provider == "cli" -> "CLI trên Windows • Chưa rõ chế độ và khả năng dịch"
        provider == "api" -> "Gemini API • Chưa rõ chế độ và khả năng dịch"
        else -> "Windows đã kết nối • Chưa rõ bộ máy dịch"
    }
}

object SessionState {
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("Sẵn sàng dịch độc lập Tiếng Trung → Tiếng Việt")
    val connected = MutableStateFlow(false)
    val standaloneActive = MutableStateFlow(true)
    val offlineModelStatus = MutableStateFlow("Kho phụ đề Tiếng Trung SQLite (idx_zh_hash) + LruCache(300) sẵn sàng")
    val provider = MutableStateFlow("standalone")
    val backend = MutableStateFlow(TranslationBackendState())
    val latency = MutableStateFlow(0L)
    val instantMs = MutableStateFlow(0L)
    val blocks = MutableStateFlow(0)
    val cacheHits = MutableStateFlow(0)
    val preloadedHits = MutableStateFlow(0)
    val fuzzyHits = MutableStateFlow(0)
    val savedApiCalls = MutableStateFlow(0)
    val noiseFiltered = MutableStateFlow(0)
    val ocrCorrectedCount = MutableStateFlow(0)
    val bankSize = MutableStateFlow(0)
    val activeModelName = MutableStateFlow("gemini-3.1-flash-lite")
    val ocrMs = MutableStateFlow(0L)
    val ocrFrames = MutableStateFlow(0)
    val ocrArea = MutableStateFlow(30)
    val activityVisible = MutableStateFlow(false)
    val videoActivityVisible = MutableStateFlow(false)
    val batchActivityVisible = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)
    val lastOcr = MutableStateFlow("")
    val lastTranslation = MutableStateFlow("")
    val timings = MutableStateFlow("")
    val networkRtt = MutableStateFlow(0L)
    val frameToDraw = MutableStateFlow(0L)

    fun formatMeasuredTelemetry(ocrMs: Long, translateMs: Long, frameToDrawMs: Long): String =
        "OCR: ${ocrMs.coerceAtLeast(0)}ms | Dịch: ${translateMs.coerceAtLeast(0)}ms | Khung → vẽ: ${frameToDrawMs.coerceAtLeast(0)}ms"

    /**
     * Requirement 6: Formats real-time status bar latency telemetry (`OCR: 55ms | Dịch: 320ms | Tổng: 375ms`).
     */
    fun formatCompactTelemetry(ocrMs: Long, translateMs: Long, totalMs: Long = ocrMs + translateMs): String {
        val safeOcr = ocrMs.coerceAtLeast(1L)
        val safeTranslate = translateMs.coerceAtLeast(0L)
        val safeTotal = totalMs.coerceAtLeast(safeOcr + safeTranslate)
        return "OCR: ${safeOcr}ms | Dịch: ${safeTranslate}ms | Tổng: ${safeTotal}ms"
    }
}
