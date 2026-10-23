package com.gemini.ocr

import kotlinx.coroutines.flow.MutableStateFlow

/** Server-advertised mode; null availability keeps older companions explicitly unknown. */
data class TranslationBackendState(
    val provider: String = "",
    val mode: String = "",
    val translationAvailable: Boolean? = null,
    val experimentalCliEnabled: Boolean = false,
) {
    val badge: String get() = when {
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
    val status = MutableStateFlow("Sẵn sàng kết nối")
    val connected = MutableStateFlow(false)
    val provider = MutableStateFlow("")
    val backend = MutableStateFlow(TranslationBackendState())
    val latency = MutableStateFlow(0L)
    val blocks = MutableStateFlow(0)
    val cacheHits = MutableStateFlow(0)
    val ocrMs = MutableStateFlow(0L)
    val ocrFrames = MutableStateFlow(0)
    val ocrArea = MutableStateFlow(100)
    val activityVisible = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)
    val lastOcr = MutableStateFlow("")
    val lastTranslation = MutableStateFlow("")
    val timings = MutableStateFlow("")
    val networkRtt = MutableStateFlow(0L)
    val frameToDraw = MutableStateFlow(0L)
}

