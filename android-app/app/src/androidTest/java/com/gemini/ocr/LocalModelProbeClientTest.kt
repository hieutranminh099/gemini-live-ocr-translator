package com.gemini.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException

/** Parser/protocol tests only: never starts a model or calls a network service. */
@RunWith(AndroidJUnit4::class)
class LocalModelProbeClientTest {
    private fun response(content: String = "Sáng mai đừng đến muộn."): JSONObject = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", content))))
        .put("timings", JSONObject().put("cache_n", 0))

    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid local research response must be rejected") }
        catch (_: IOException) { }
    }

    private fun wire(status: Int, body: String, extra: String = "", declaredSize: Int? = null): ByteArrayInputStream {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${declaredSize ?: bytes.size}\r\n$extra\r\n"
        return ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + bytes)
    }

    @Test fun payloadSeparatesContextFromSourceAndDisablesCacheThinkingAndStreaming() {
        val payload = LocalModelProbeClient.payload("明天早上不要迟到", listOf("今晚不出门。" to "Tối nay không ra ngoài."))
        assertFalse(payload.getBoolean("cache_prompt"))
        assertFalse(payload.getBoolean("stream"))
        assertEquals("none", payload.getString("reasoning_effort"))
        val messages = payload.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(LocalModelProbeClient.INSTRUCTION, messages.getJSONObject(0).getString("content"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("Trung: 今晚不出门。"))
        assertTrue(user.endsWith("Câu nguồn hiện tại cần dịch:\n明天早上不要迟到"))
        assertFalse(payload.toString().contains("api_key"))
    }

    @Test fun completedFreshTranslationPreservesNumbersAndTime() {
        assertEquals("Sáng mai lúc 2:30, hai người lấy ba vé.",
            LocalModelProbeClient.translation(response("Sáng mai lúc 2:30, hai người lấy ba vé.")))
    }

    @Test fun incompleteOrMultipleChoicesCannotBeShownAsTranslations() {
        reject { LocalModelProbeClient.translation(response().apply { getJSONArray("choices").getJSONObject(0).put("finish_reason", "length") }) }
        reject { LocalModelProbeClient.translation(response().apply { getJSONArray("choices").put(getJSONArray("choices").getJSONObject(0)) }) }
        reject { LocalModelProbeClient.translation(response(" ")) }
    }

    @Test fun missingOrNonzeroPromptCacheIsRejected() {
        reject { LocalModelProbeClient.translation(response().apply { remove("timings") }) }
        reject { LocalModelProbeClient.translation(response().apply { getJSONObject("timings").put("cache_n", 3) }) }
        reject { LocalModelProbeClient.translation(response().apply { getJSONObject("timings").put("cache_n", "0") }) }
    }

    @Test fun reasoningCannotBeStrippedToMakeGenerationAppearFaster() {
        reject { LocalModelProbeClient.translation(response("</THINK>Bản dịch")) }
        reject { LocalModelProbeClient.translation(response().apply {
            getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("reasoning_content", "hidden work")
        }) }
    }

    @Test fun utf8LengthDelimitedJsonIsReadWithoutAlteration() {
        val original = response("Sáng mai đừng đến muộn.")
        val decoded = LocalModelProbeClient.readResponse(wire(200, original.toString()))
        assertEquals("Sáng mai đừng đến muộn.", LocalModelProbeClient.translation(decoded))
    }

    @Test fun redirectsAndHttpErrorsAreNotFollowed() {
        reject { LocalModelProbeClient.readResponse(wire(302, "{}", "Location: https://example.invalid\r\n")) }
        reject { LocalModelProbeClient.readResponse(wire(500, "{}")) }
    }

    @Test fun streamingOversizedAndTruncatedBodiesAreRejected() {
        reject { LocalModelProbeClient.readResponse(wire(200, "{}", "Transfer-Encoding: chunked\r\n")) }
        reject { LocalModelProbeClient.readResponse(wire(200, "{}", declaredSize = 262145)) }
        reject { LocalModelProbeClient.readResponse(wire(200, "{}", declaredSize = 5)) }
    }
}
