package com.gemini.ocr.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiNumericResponseTest {
    private fun response(text: String) = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()

    @Test fun structuredSlotsNeverStripLeadingDecimalsTimesOrNegativeNumbers() {
        val values = listOf("1.5 triệu đồng.", "2:30 chiều.", "-3 độ C.", "1. Đây là mục thứ nhất.")
        val text = JSONObject().put("translations", JSONArray(values)).toString()
        assertEquals(values, StandaloneTranslator.parseGeminiResponseJson(response(text), values.size))
    }
    @Test fun plainSingleCuePreservesNumbersAndAllLines() {
        for (text in listOf("1.5 triệu đồng.", "2:30 chiều.", "Không phải 300.\nLà 30.")) {
            assertEquals(listOf(text), StandaloneTranslator.parseGeminiResponseJson(response(text), 1))
        }
    }
    @Test fun singleCueUsesPlainWhileBatchesAndExplicitJsonKeepTheirSchema() {
        val plain = JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("2人各拿3张票"),
            requestModel = "gemini-3.5-flash-lite", structuredOutput = false)).getJSONObject("generationConfig")
        assertEquals("text/plain", plain.getString("responseMimeType"))
        assertFalse(plain.has("responseSchema"))
        assertTrue(JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("好", "再见")))
            .getJSONObject("generationConfig").has("responseSchema"))
        assertTrue(JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("好"), structuredOutput = true))
            .getJSONObject("generationConfig").has("responseSchema"))
    }
    @Test fun malformedStructuredResultsNeverBecomeVisibleJson() {
        for (text in listOf("{\"translations\":[]}", "{\"translations\":[\"\"]}", "{broken}")) {
            assertThrows(IOException::class.java) {
                StandaloneTranslator.parseGeminiResponseJson(response(text), 1)
            }
        }
    }
    @Test fun batchFallbackRequiresUnambiguousSequentialListMarkers() {
        assertNull(CostOptimizationEngine.parseNumberedLines("1.5 triệu đồng.\n2:30 chiều.", 2))
        assertNull(CostOptimizationEngine.parseNumberedLines("2. Một\n1. Hai", 2))
        assertEquals(listOf("1.5 triệu đồng.", "2:30 chiều."),
            StandaloneTranslator.parseGeminiResponseJson(response("1. 1.5 triệu đồng.\n2. 2:30 chiều."), 2))
    }
    @Test fun truncatedOrBlockedOutputsAreNeverAcceptedAsCompleteTranslations() {
        for (reason in listOf("MAX_TOKENS", "SAFETY", "RECITATION")) {
            val body = JSONObject(response("Hai người, mỗi người lấy"))
            body.getJSONArray("candidates").getJSONObject(0).put("finishReason", reason)
            assertThrows(IOException::class.java) {
                StandaloneTranslator.parseGeminiResponseJson(body.toString(), 1)
            }
        }
    }
    @Test fun boundedPilotDisablesTransportRetriesAndRedirects() {
        val client = StandaloneTranslator.singleAttemptHttpClient
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
    @Test fun legacyCandidateUsesBudgetControlInsteadOfGemini3Level() {
        val config = JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("好"),
            requestModel = "gemini-2.5-flash-lite", structuredOutput = false)).getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig")
        assertEquals(0, config.getInt("thinkingBudget"))
        assertFalse(config.has("thinkingLevel"))
    }
}
