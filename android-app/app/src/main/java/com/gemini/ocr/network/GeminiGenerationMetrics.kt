package com.gemini.ocr.network

import org.json.JSONObject

/** Numeric generation accounting only; never contains the prompt, response text or credentials. */
data class GeminiGenerationMetrics(
    val inputTokens: Int?, val outputTokens: Int?, val thinkingTokens: Int?, val cachedInputTokens: Int?,
) {
    companion object {
        fun fromResponse(body: String): GeminiGenerationMetrics? {
            val usage = runCatching { JSONObject(body).optJSONObject("usageMetadata") }.getOrNull() ?: return null
            fun count(name: String): Int? = usage.optInt(name, -1).takeIf { it >= 0 }
            return GeminiGenerationMetrics(count("promptTokenCount"), count("candidatesTokenCount"),
                count("thoughtsTokenCount"), count("cachedContentTokenCount"))
        }
    }
}
