package com.gemini.ocr

import org.junit.Assert.*
import org.junit.Test

class MeasuredTelemetryTest {
    @Test fun measuredFrameIntervalIsNotReplacedByOcrPlusTranslation() {
        assertEquals("OCR: 45ms | Dịch: 1037ms | Khung → vẽ: 1902ms",
            SessionState.formatMeasuredTelemetry(45, 1037, 1902))
    }
    @Test fun cachedAndZeroDurationValuesAreNotInflated() {
        assertEquals("OCR: 0ms | Dịch: 0ms | Khung → vẽ: 56ms",
            SessionState.formatMeasuredTelemetry(0, 0, 56))
    }
}
