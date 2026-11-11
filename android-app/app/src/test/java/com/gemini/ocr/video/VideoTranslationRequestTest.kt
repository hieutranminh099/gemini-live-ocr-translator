package com.gemini.ocr.video

import com.gemini.ocr.network.StandaloneTranslator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual request builder only; no API call. Live defaults retain their one-line context contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoTranslationRequestTest{
    @Test fun videoBatchTransportAllowsItsAdvertisedDeadlineWithoutChangingLiveOrReplayingPosts(){
        val live=StandaloneTranslator.httpClientForRequest(false,true)
        val video=StandaloneTranslator.httpClientForRequest(true,true)
        // The verified 1.7.9 baseline raised Live's cold-response deadline to 20s.
        // Batch changes must preserve that existing deadline, not restore the obsolete 12s value.
        assertEquals(20000,live.readTimeoutMillis)
        assertEquals(20000,live.callTimeoutMillis)
        assertEquals(45000,video.readTimeoutMillis)
        assertEquals(45000,video.callTimeoutMillis)
        assertFalse(video.retryOnConnectionFailure)
        assertFalse(video.followRedirects)
        assertFalse(video.followSslRedirects)
        assertSame(video,StandaloneTranslator.httpClientForRequest(true,false))
        assertSame(live,StandaloneTranslator.httpClientForRequest(false,true))
    }
    @Test fun videoRefinementKeepsOriginalChineseAndDraftAsSeparateDataAndUsesExplicitTokenBudget(){
        val cue=VideoCue("one",0,1000,"不卖250匹马","Bán 250 con ngựa",localDraft="Bán 250 con ngựa",refinementState="pending")
        val batch=VideoTranslationPlan.nextCloudBatch(listOf(cue),true)!!
        val request=JSONObject(StandaloneTranslator.buildGeminiRequestJson(texts=batch.texts,
            contextItems=listOf("THIS_IS_FUTURE" to "WRONG_AS_PREVIOUS"),videoTranslationContext=batch.videoContext,
            videoMaxOutputTokens=4096,structuredOutput=true))
        val system=request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val user=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("NGUYÊN VĂN Trung"))
        assertTrue(user.contains("\"source\":\"不卖250匹马\""))
        assertTrue(user.contains("\"localDraft\":\"Bán 250 con ngựa\""))
        assertTrue(user.endsWith("不卖250匹马"))
        assertFalse(user.contains("THIS_IS_FUTURE"))
        assertEquals(4096,request.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertEquals("application/json",request.getJSONObject("generationConfig").getString("responseMimeType"))
    }
    @Test fun liveDefaultDoesNotReceiveVideoInstructionOrLargeOutputBudget(){
        val request=JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("你好"),contextItems=listOf("前句" to "Câu trước")))
        val system=request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val user=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertFalse(system.contains("JSON video"));assertFalse(user.contains("localDraft"))
        assertTrue(user.contains("Ngữ cảnh câu trước: 前句 -> Câu trước"))
        assertTrue(request.getJSONObject("generationConfig").getInt("maxOutputTokens")<=250)
    }
    @Test fun largeOutputBudgetCannotBeEnabledWithoutExplicitVideoContext(){
        try{StandaloneTranslator.buildGeminiRequestJson(listOf("你好"),videoMaxOutputTokens=4096);fail("must require video optin")}
        catch(_:IllegalArgumentException){}
    }
}
