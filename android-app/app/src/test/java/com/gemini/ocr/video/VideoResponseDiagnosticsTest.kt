package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Entirely synthetic response failures. No provider requests or private source media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoResponseDiagnosticsTest {
    private fun batch(count: Int = 3): VideoTranslationPlan.Batch = VideoFullDialoguePlan.next(
        List(count) { VideoCue("fixture-$it", it * 1000L, it * 1000L + 800, "你好") }, count * 1000L)!!

    private fun rows(vararg ids: String) = JSONObject().put("translations", JSONArray(ids.map {
        JSONObject().put("id", it).put("translation", "Xin chào")
    })).toString()

    private fun envelope(text: String, finish: String = "STOP") = JSONObject().put("candidates", JSONArray()
        .put(JSONObject().put("finishReason", finish).put("content", JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", text)))))).toString()

    private fun failure(body: String, reason: VideoResponseFailureReason, plan: VideoTranslationPlan.Batch = batch()): VideoApiException {
        val error = assertThrows(VideoApiException::class.java) {
            VideoTranslationClient.parseResponse(body, plan, "", "fixture-model")
        }
        assertEquals("TEXT_INVALID_RESPONSE", error.code)
        assertEquals(reason, error.responseReason)
        assertEquals(!plan.fullDialogue, error.splitAllowed)
        assertNull(error.cause)
        return error
    }

    @Test fun failureReasonsSeparateEnvelopeFinishEmptyJsonSchemaAndIdErrorsWithoutLeakingPayloads() {
        val marker = "private-key-source-response-marker"
        val cases = listOf(
            "{\"private\":\"$marker\"}" to VideoResponseFailureReason.ENVELOPE,
            envelope(rows("fixture-0"), marker) to VideoResponseFailureReason.FINISH_REASON,
            envelope("") to VideoResponseFailureReason.EMPTY_CONTENT,
            envelope("$marker is not JSON") to VideoResponseFailureReason.JSON_SYNTAX,
            envelope("{\"translations\":\"$marker\"}") to VideoResponseFailureReason.SCHEMA,
            envelope(rows("fixture-0", marker)) to VideoResponseFailureReason.UNKNOWN_ID,
            envelope(rows("fixture-0", "fixture-0")) to VideoResponseFailureReason.DUPLICATE_ID,
            "x".repeat(2 * 1024 * 1024 + 1) to VideoResponseFailureReason.SIZE_LIMIT
        )
        for ((body, reason) in cases) {
            val error = failure(body, reason)
            assertFalse(error.toString().contains(marker))
            assertTrue(error.message.orEmpty().contains(reason.label))
            assertEquals(reason, VideoResponseFailureReason.fromId(reason.id))
        }
        assertEquals(VideoResponseFailureReason.UNKNOWN, VideoResponseFailureReason.fromId(-1))
    }

    @Test fun mappingFailuresRejectTheWholeAmbiguousResponseEvenAfterAnApparentlyValidRow() {
        val plan = batch()
        failure(envelope(rows("fixture-0", "not-requested")), VideoResponseFailureReason.UNKNOWN_ID, plan)
        failure(envelope(rows("fixture-0", "fixture-0")), VideoResponseFailureReason.DUPLICATE_ID, plan)
        assertEquals(listOf("fixture-0", "fixture-1", "fixture-2"), plan.cueIds)
        val good = VideoTranslationClient.parseResponse(envelope(rows("fixture-2", "fixture-0")), plan, "", "fixture-model")
        assertEquals(setOf("fixture-2", "fixture-0"), good.accepted.keys)
        assertEquals(listOf("fixture-1"), good.missingIds)
    }

    @Test fun smallFullDialogueRequestsConstrainOnlyTargetIdsAndLargeLegacyPlansKeepCompactSchema() {
        for (count in listOf(1, 16, 64, 65, 1000)) {
            val plan = batch(count)
            val request = JSONObject(VideoTranslationClient.buildRequest(plan, "manga", ""))
            val id = request.getJSONObject("generationConfig").getJSONObject("responseSchema")
                .getJSONObject("properties").getJSONObject("translations").getJSONObject("items")
                .getJSONObject("properties").getJSONObject("id")
            if (count <= 64) {
                val ids = id.getJSONArray("enum")
                assertEquals(plan.cueIds, (0 until ids.length()).map(ids::getString))
            } else assertFalse(id.has("enum"))
            assertEquals(count, JSONObject(plan.videoContext).getJSONArray("dialogue").length())
        }
    }

    @Test fun declaredTokenCutoffStillKeepsCompletePrefixRowsButNeverGuessesOrAcceptsBadIds() {
        val plan = batch()
        val cut = """{"translations":[{"id":"fixture-0","translation":"Xin chào"},{"id":"fixture-1","translation":"dở"""
        val accepted = VideoTranslationClient.parseResponse(envelope(cut, "MAX_TOKENS"), plan, "", "fixture-model")
        assertTrue(accepted.truncated)
        assertEquals(setOf("fixture-0"), accepted.accepted.keys)
        assertEquals(listOf("fixture-1", "fixture-2"), accepted.missingIds)
        failure(envelope(cut), VideoResponseFailureReason.JSON_SYNTAX, plan)
        failure(envelope(rows("fixture-0", "unknown"), "MAX_TOKENS"), VideoResponseFailureReason.UNKNOWN_ID, plan)
        failure(envelope(rows("fixture-0", "fixture-0"), "MAX_TOKENS"), VideoResponseFailureReason.DUPLICATE_ID, plan)
    }

    @Test fun missingAndThoughtOnlyContentAreIdentifiedWithoutPretendingTheRequestCompleted() {
        for (reason in listOf("STOP", "MAX_TOKENS")) {
            val noContent = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", reason))).toString()
            failure(noContent, VideoResponseFailureReason.EMPTY_CONTENT)
            val thoughtOnly = JSONObject(envelope("private-thought", reason))
            thoughtOnly.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).put("thought", true)
            val error = failure(thoughtOnly.toString(), VideoResponseFailureReason.EMPTY_CONTENT)
            assertFalse(error.message.orEmpty().contains("private-thought"))
        }
    }

    @Test fun providerContentBlockRetainsItsSeparateManualFailureContract() {
        val error = assertThrows(VideoApiException::class.java) {
            VideoTranslationClient.parseResponse(envelope("", "SAFETY"), batch(), "", "fixture-model")
        }
        assertEquals("TEXT_BLOCKED", error.code)
        assertNull(error.responseReason)
        assertNull(error.cause)
    }
}
