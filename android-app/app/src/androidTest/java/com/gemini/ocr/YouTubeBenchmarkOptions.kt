package com.gemini.ocr

internal object YouTubeBenchmarkOptions {
    fun performanceRequested(collect: String?, legacyMeasure: String?): Boolean =
        collect == "true" || legacyMeasure == "true"
}
