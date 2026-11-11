package com.gemini.ocr.video

import androidx.test.core.app.ApplicationProvider
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
class VideoSmartBatchContractTest {
    private fun cues(count: Int = 4) = List(count) { VideoCue("cue-$it", it * 1000L, it * 1000L + 900, "第${it}句") }
    private fun batch() = VideoTranslationPlan.nextCloudBatch(cues(), false)!!
    private fun response(vararg rows: Pair<String, String>) = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
            JSONObject().put("translations", JSONArray(rows.map { JSONObject().put("id", it.first).put("translation", it.second) })).toString())))))).toString()
    @Test fun reorderedIdsMapToTheirOwnCueAndMissingIdsStayMissing() {
        val result = VideoTranslationClient.parseResponse(response("cue-2" to "Câu hai", "cue-0" to "Câu đầu"), batch(), "", "model")
        assertEquals(mapOf("cue-2" to "Câu hai", "cue-0" to "Câu đầu"), result.accepted)
        assertEquals(listOf("cue-1", "cue-3"), result.missingIds)
        val selected = VideoTranslationPlan.selectBatch(batch(), listOf(0, 2))
        val updated = VideoTranslationPlan.applyCloudResult(cues(), selected,
            selected.cueIds.map { result.accepted.getValue(it) }, result.model, false)
        assertEquals("Câu đầu", updated[0].translation); assertEquals("Câu hai", updated[2].translation)
        assertTrue(updated[1].translation.isEmpty()); assertEquals(cues()[1].source, updated[1].source)
    }
    @Test fun unknownOrDuplicatedIdsRejectTheWholeResponseEvenAfterValidRows() {
        for (raw in listOf(response("cue-0" to "Chào", "other" to "Sai"), response("cue-0" to "Chào", "cue-0" to "Trùng"))) {
            try { VideoTranslationClient.parseResponse(raw, batch(), "", "model"); fail("ambiguous ID must reject all") }
            catch (_: IOException) { }
        }
    }
    @Test fun blankAndUntranslatedRowsArePendingWithoutInventingTranslations() {
        val result = VideoTranslationClient.parseResponse(response("cue-0" to "", "cue-1" to "原文", "cue-2" to "Xin chào"), batch(), "", "model")
        assertEquals(mapOf("cue-2" to "Xin chào"), result.accepted)
        assertEquals(listOf("cue-0", "cue-1", "cue-3"), result.missingIds)
    }
    @Test fun requestUsesExistingModelConfigurationButStructuredIdsAndExactTimestamps() {
        val request = JSONObject(VideoTranslationClient.buildRequest(batch(), "manga", "张三=Trương Tam"))
        val schema = request.getJSONObject("generationConfig").getJSONObject("responseSchema")
            .getJSONObject("properties").getJSONObject("translations").getJSONObject("items")
        assertEquals("OBJECT", schema.getString("type"))
        assertTrue(schema.getJSONObject("properties").has("id"))
        val raw = request.toString()
        assertTrue(raw.contains("cue-0")); assertTrue(raw.contains("startMs")); assertTrue(raw.contains("endMs"))
    }
    @Test fun selectableSizesRespectSourceBudgetAndNeverSendApprovedRows() {
        val rows = cues(120).mapIndexed { i, cue -> if (i == 0) cue.copy(translation = "Đã duyệt", approved = true) else cue }
        for (size in VideoSmartBatchSettings.GROUP_SIZES) {
            val next = VideoTranslationPlan.nextCloudBatch(rows, false, maxCues = size)!!
            assertEquals(size, next.indices.size); assertFalse(next.cueIds.contains(rows.first().id))
            assertTrue(next.texts.sumOf { it.length } <= 1000)
        }
        assertEquals(4, VideoTranslationPlan.nextCloudBatch(cues(60).map { it.copy(source = "中".repeat(250)) }, false, 50)!!.indices.size)
    }
    @Test fun sizePreferenceDoesNotChangeConcurrencyOrAcceptUnsupportedSizes() {
        val settings = VideoSmartBatchSettings(ApplicationProvider.getApplicationContext())
        settings.groupSize = 30; assertEquals(30, settings.groupSize); assertEquals(1, settings.maxConcurrentRequests)
        try { settings.groupSize = 200; fail("unsupported size") } catch (_: IllegalArgumentException) { }
        assertEquals(30, settings.groupSize)
    }
}
