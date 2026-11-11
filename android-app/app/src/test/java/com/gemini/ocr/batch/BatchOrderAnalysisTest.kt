package com.gemini.ocr.batch

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
class BatchOrderAnalysisTest {
    private val items = listOf(BatchItem("clip-a", "random-a.mp4", episode = "separate-a"),
        BatchItem("clip-b", "random-b.mp4", episode = "separate-b"), BatchItem("clip-c", "random-c.mp4", episode = "separate-c"))

    @Test fun explicitEpisodeMarkersResolveLocallyWithoutChangingMergeGroups() {
        val named = listOf(items[0].copy(name = "EP10.mp4"), items[1].copy(name = "EP02.mp4"), items[2].copy(name = "EP01.mp4"))
        val suggestion = requireNotNull(BatchOrderAnalysis.localSuggestion(named))
        assertEquals(listOf("clip-c", "clip-b", "clip-a"), suggestion.projectIds)
        assertEquals("FILENAME", suggestion.method)
        assertEquals("EXPLICIT", suggestion.confidence)
        assertEquals(listOf("separate-a", "separate-b", "separate-c"), named.map { it.episode })
    }

    @Test fun ambiguousOrDuplicatedMarkersDoNotClaimAConfidentLocalOrder() {
        assertNull(BatchOrderAnalysis.localSuggestion(items))
        assertNull(BatchOrderAnalysis.localSuggestion(listOf(items[0].copy(name = "EP01-a.mp4"), items[1].copy(name = "EP01-b.mp4"))))
        assertNull(BatchOrderAnalysis.localSuggestion(listOf(items[0].copy(name = "EP01.mp4"), items[1].copy(name = "Part02.mp4"))))
    }

    @Test fun leadingNumbersAreAlwaysUnconfirmedAndNeverChangeEpisodeIdentity() {
        val numbered = listOf(items[0].copy(name = "10.mp4"), items[1].copy(name = "2.mp4"), items[2].copy(name = "1.mp4"))
        val suggestion = requireNotNull(BatchOrderAnalysis.numericSuggestion(numbered))
        assertEquals(listOf("clip-c", "clip-b", "clip-a"), suggestion.projectIds)
        assertEquals("LOW", suggestion.confidence)
        assertEquals(listOf("separate-a", "separate-b", "separate-c"), numbered.map { it.episode })
    }

    @Test fun onlyConsistentIsolatedOcrHeadingsCanSuggestAnOrder() {
        val snippets = mapOf("clip-a" to listOf("1000ms: 第10集"), "clip-b" to listOf("2000ms: 第2集"), "clip-c" to listOf("3000ms: 第1集"))
        val suggestion = requireNotNull(BatchOrderAnalysis.ocrTitleSuggestion(items, snippets))
        assertEquals(listOf("clip-c", "clip-b", "clip-a"), suggestion.projectIds)
        assertEquals("LOCAL_OCR", suggestion.method)
        assertNull(BatchOrderAnalysis.ocrTitleSuggestion(items, snippets + ("clip-b" to listOf("2000ms: 他在第2集出现"))))
        assertNull(BatchOrderAnalysis.ocrTitleSuggestion(items, snippets + ("clip-b" to listOf("2000ms: 第2集", "4000ms: 第3集"))))
        assertNull(BatchOrderAnalysis.ocrTitleSuggestion(items, snippets + ("clip-b" to listOf("2000ms: 第1集"))))
    }

    @Test fun aValidSuggestionContainsEveryInputExactlyOnceWithAReason() {
        val suggestion = BatchOrderAnalysis.parseResponse(envelope(payload(listOf("clip-c", "clip-a", "clip-b"))), items)
        assertEquals(listOf("clip-c", "clip-a", "clip-b"), suggestion.projectIds)
        assertEquals(items.map { it.projectId }.toSet(), suggestion.reasons.keys)
        assertEquals("TENTATIVE", suggestion.confidence)
    }

    @Test fun unknownEvidenceKeepsInputOrderEvenIfProviderInventsAPermutation() {
        val suggestion = BatchOrderAnalysis.parseResponse(envelope(payload(listOf("clip-c", "clip-b", "clip-a"), "UNKNOWN")), items)
        assertEquals(items.map { it.projectId }, suggestion.projectIds)
        assertEquals("UNKNOWN", suggestion.confidence)
    }

    @Test fun duplicateMissingAndForeignIdsCannotChangeTheQueue() {
        for (ids in listOf(listOf("clip-a", "clip-a", "clip-c"), listOf("clip-a", "clip-b"), listOf("clip-a", "clip-b", "foreign"))) {
            assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(envelope(payload(ids)), items) }
        }
    }

    @Test fun sparseEvidenceNeverAcceptsHighConfidenceOrMissingReasonsOrNonStringIds() {
        val missingReason = payload(items.map { it.projectId }).put("reasons", JSONArray())
        assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(envelope(missingReason), items) }
        assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(envelope(payload(items.map { it.projectId }, "HIGH")), items) }
        val numericId = payload(items.map { it.projectId }).put("orderedIds", JSONArray().put(1).put("clip-b").put("clip-c"))
        assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(envelope(numericId), items) }
    }

    @Test fun truncatedAndBlockedProviderResponsesAreNotSavedAsAResult() {
        assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(envelope(payload(items.map { it.projectId }), "MAX_TOKENS"), items) }
        assertThrows(IOException::class.java) { BatchOrderAnalysis.parseResponse(JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")).toString(), items) }
    }

    @Test fun exactEvidenceFingerprintDistinguishesNegationAndModelAndDoesNotNormalizeText() {
        val input = "batch=x|model=current|id=a|source=我爱你"
        assertEquals(BatchOrderAnalysis.fingerprint(input), BatchOrderAnalysis.fingerprint(input))
        assertNotEquals(BatchOrderAnalysis.fingerprint(input), BatchOrderAnalysis.fingerprint(input.replace("我爱你", "我不爱你")))
        assertNotEquals(BatchOrderAnalysis.fingerprint(input), BatchOrderAnalysis.fingerprint(input.replace("current", "other")))
    }

    @Test fun requestSendsOnlySuppliedTextAndUsesABoundedStructuredResponse() {
        val request = JSONObject(BatchOrderAnalysis.buildRequest(JSONObject().put("items", JSONArray().put(JSONObject()
            .put("id", "clip-a").put("filename", "random-a.mp4").put("snippets", JSONArray().put("1000ms: 你好"))))))
        val parts = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertFalse(parts.getJSONObject(0).has("inlineData"))
        assertEquals(2048, request.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertEquals("application/json", request.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    private fun payload(ids: List<String>, confidence: String = "TENTATIVE") = JSONObject()
        .put("orderedIds", JSONArray(ids)).put("confidence", confidence)
        .put("reasons", JSONArray(items.map { JSONObject().put("id", it.projectId).put("reason", "Đoạn chữ chỉ gợi ý, cần kiểm tra.") }))
    private fun envelope(result: JSONObject, finish: String = "STOP") = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("finishReason", finish).put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", result.toString())))))).toString()
}
