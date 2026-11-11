package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoVisualRecoveryTest {
    @Test fun validFencedDictionaryKeepsAllFramePositionsInsteadOfDiscardingRecognizedText() {
        val payload = "{\"sources\":[\"\",\"别走\\n明天见\"],\"frameSourceIndices\":[1,0,1]}"
        assertEquals(listOf("别走\n明天见", "", "别走\n明天见"),
            VideoVisualOcrClient.parseResponse(envelope("```json\n$payload\n```"), 3))
    }

    @Test fun explicitFrameIdsCanBeOutOfOrderButMustBeCompleteUniqueAndInRange() {
        fun row(index: Int, source: String) = JSONObject().put("frameIndex", index).put("source", source)
        fun body(rows: JSONArray) = envelope(JSONObject().put("frames", rows).toString())
        assertEquals(listOf("你好", "再见"), VideoVisualOcrClient.parseResponse(body(JSONArray()
            .put(row(1, "再见")).put(row(0, "你好"))), 2))
        for (rows in listOf(JSONArray().put(row(1, "再见")), JSONArray().put(row(0, "你好")).put(row(0, "再见")),
            JSONArray().put(row(0, "你好")).put(row(2, "再见")))) {
            assertThrows(VideoApiException::class.java) { VideoVisualOcrClient.parseResponse(body(rows), 2) }
        }
    }

    @Test fun invalidLargeGroupSplitsOnceAndMapsResultsInOriginalTimestampOrder() = runBlocking {
        val frames = (0..3).map { VisualOcrFrame(it * 250L, byteArrayOf(1)) }
        val dispatched = mutableListOf<List<Long>>()
        val result = VideoVisualOcrClient.recognizeRecovering(frames) { group ->
            dispatched += group.map { it.ms }
            if (group.size == 4) throw VideoApiException("VISION_INVALID_RESPONSE", splitAllowed = true)
            group.map { "第${it.ms}句" }
        }
        assertEquals(listOf("第0句", "第250句", "第500句", "第750句"), result)
        assertEquals(listOf(listOf(0L,250L,500L,750L),listOf(0L,250L),listOf(500L,750L)), dispatched)
    }

    @Test fun exhaustedQuotaIsNeverSplitAndInvalidChildrenAreNotRetriedForever() = runBlocking {
        val frames = (0..3).map { VisualOcrFrame(it * 250L, byteArrayOf(1)) }
        var requests = 0
        try { VideoVisualOcrClient.recognizeRecovering(frames) { requests++; throw VideoApiRecovery.httpFailure(429,
            "{\"error\":{\"message\":\"daily quota exceeded\"}}") }; fail("must stop") }
        catch (error: VideoApiException) { assertEquals("API_QUOTA_EXHAUSTED", error.code) }
        assertEquals(1, requests)
        requests = 0
        try { VideoVisualOcrClient.recognizeRecovering(frames) { requests++; throw VideoApiException("VISION_INVALID_RESPONSE", splitAllowed = true) }; fail("must stop") }
        catch (_: VideoApiException) { }
        assertEquals(2, requests) // Parent plus failed left half; never recurse indefinitely.
    }

    @Test fun successfulLeftSplitSurvivesRightAuthenticationFailureAndReopenOnlyDispatchesRemainingFrames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        var project = store.save(VideoProject(UUID.randomUUID().toString(), "Synthetic split fixture",
            "file:///unused.mp4", 1000, 640, 360, sourceMode = VideoSourceMode.OCR))
        var accumulator = VideoCueAccumulator()
        val frames = (0..3).map { VisualOcrFrame(it * 250L, byteArrayOf(1)) }
        val acknowledgements = mutableListOf<List<Long>>()
        val requests = mutableListOf<List<Long>>()
        val terminal = VideoApiRecovery.httpFailure(401)
        val checkpoint: suspend (List<VisualOcrFrame>, List<String>) -> Unit = { group, texts ->
            group.forEachIndexed { index, frame -> accumulator.add(frame.ms, texts[index]) }
            project = store.save(project.copy(scanMs = group.last().ms + 1, cues = accumulator.cues.toList(),
                carry = accumulator.snapshot(), resumeStage = VideoStage.EXTRACTING))
            acknowledgements += group.map { it.ms }
        }
        try {
            VideoVisualOcrClient.recognizeRecovering(frames, onValidated = checkpoint) { group ->
                requests += group.map { it.ms }
                when {
                    group.size == 4 -> throw VideoApiException("VISION_INVALID_RESPONSE", splitAllowed = true)
                    group.first().ms == 0L -> listOf("你好", "你好")
                    else -> {
                        assertEquals(251L, VideoStore(context).load(project.id).scanMs)
                        throw terminal
                    }
                }
            }
            fail("authentication must stop the remainder")
        } catch (actual: VideoApiException) { assertSame(terminal, actual) }
        val reopened = VideoStore(context).load(project.id)
        assertEquals(251L, reopened.scanMs); assertEquals("你好", reopened.carry.getString("source"))
        assertEquals(listOf(listOf(0L, 250L)), acknowledgements)
        accumulator = VideoCueAccumulator(reopened.cues, reopened.carry)
        project = reopened
        val remaining = frames.filter { it.ms >= reopened.scanMs }
        VideoVisualOcrClient.recognizeRecovering(remaining, onValidated = checkpoint) { group ->
            requests += group.map { it.ms }; group.map { "再见" }
        }
        val complete = accumulator.finish(1000)
        assertEquals(listOf(listOf(0L, 250L), listOf(500L, 750L)), acknowledgements)
        assertEquals(listOf(listOf(0L,250L,500L,750L), listOf(0L,250L), listOf(500L,750L), listOf(500L,750L)), requests)
        assertEquals(listOf("你好", "再见"), complete.map { it.source })
        assertEquals(listOf(0L to 500L, 500L to 1000L), complete.map { it.startMs to it.endMs })
        assertEquals(2, complete.map { it.id }.distinct().size)
    }

    @Test fun checkpointFailureOrCancellationCannotBeMistakenForProviderErrorAndSplitAgain() = runBlocking {
        val frames = (0..3).map { VisualOcrFrame(it * 250L, byteArrayOf(1)) }
        for (terminal in listOf(IOException("[VISION_INVALID_RESPONSE] synthetic disk checkpoint failure"),
            CancellationException("pause during checkpoint"))) {
            var requests = 0; var checkpoints = 0
            try {
                VideoVisualOcrClient.recognizeRecovering(frames, onValidated = { _, _ ->
                    checkpoints++; throw terminal
                }) { group -> requests++; group.map { "你好" } }
                fail("checkpoint failure must propagate without reupload")
            } catch (actual: Exception) { assertSame(terminal, actual) }
            assertEquals(1, requests); assertEquals(1, checkpoints)
        }
    }

    private fun envelope(text: String) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()
}
