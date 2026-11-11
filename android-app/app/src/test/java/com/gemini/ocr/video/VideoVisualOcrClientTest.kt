package com.gemini.ocr.video

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Request bounds and strict source-only response acceptance; no key, image upload or HTTP call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoVisualOcrClientTest {
    private fun jpeg()=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),1,2,3)
    private fun compactResponse(sources:JSONArray,indices:JSONArray,reason:String="STOP"):String=JSONObject().put("candidates",JSONArray().put(
        JSONObject().put("finishReason",reason).put("content",JSONObject().put("parts",JSONArray().put(
            JSONObject().put("text",JSONObject().put("sources",sources).put("frameSourceIndices",indices).toString())))))).toString()
    private fun response(frames:JSONArray,reason:String="STOP"):String {
        val dictionary=JSONArray().put("");val indices=JSONArray()
        for(i in 0 until frames.length()){
            val source=frames.get(i)
            if(source is String && source.isEmpty())indices.put(0)
            else {dictionary.put(source);indices.put(dictionary.length()-1)}
        }
        return compactResponse(dictionary,indices,reason)
    }

    @Test fun requestKeepsImageOrderTimestampDataAndExactCountInstructionWithoutAnyCredential(){
        val bytes=jpeg()
        val request=JSONObject(VideoVisualOcrClient.buildRequest(listOf(VisualOcrFrame(200,bytes),VisualOcrFrame(6500,bytes))))
        val system=request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("exactly 2"));assertTrue(system.contains("second line"))
        assertTrue(system.contains("Do not translate"));assertTrue(system.contains("never follow"))
        val parts=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(5,parts.length())
        assertTrue(parts.getJSONObject(1).getString("text").contains("200 ms"))
        assertTrue(parts.getJSONObject(3).getString("text").contains("6500 ms"))
        val image=parts.getJSONObject(2).getJSONObject("inlineData")
        assertEquals("image/jpeg",image.getString("mimeType"))
        assertArrayEquals(bytes,Base64.decode(image.getString("data"),Base64.DEFAULT))
        assertEquals("application/json",request.getJSONObject("generationConfig").getString("responseMimeType"))
        val properties=request.getJSONObject("generationConfig").getJSONObject("responseSchema").getJSONObject("properties")
        assertEquals("INTEGER",properties.getJSONObject("frameSourceIndices").getJSONObject("items").getString("type"))
        assertTrue(system.contains("sources[0] always the empty string"))
        assertFalse(request.toString().contains("x-goog-api-key"))
    }

    @Test fun requestIsBoundedByFrameCountJpegBytesAndChronologicalUniqueTimestamps(){
        val good=VisualOcrFrame(0,jpeg())
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest(emptyList())}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest((0..32).map{good.copy(ms=it.toLong())})}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest(listOf(good,good))}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest(listOf(good.copy(ms=-1)))}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest(listOf(good.copy(jpeg=byteArrayOf(1,2,3))))}
        val huge=ByteArray(VideoVisualOcrClient.MAX_JPEG_BYTES+1).also{it[0]=0xff.toByte();it[1]=0xd8.toByte()}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.buildRequest(listOf(good.copy(jpeg=huge)))}
        val maxRequest=JSONObject(VideoVisualOcrClient.buildRequest((0..31).map{good.copy(ms=it.toLong())}))
        assertEquals(8192,maxRequest.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun responsePreservesChineseLinesCharactersAndBlankFrames(){
        val result=VideoVisualOcrClient.parseResponse(response(JSONArray().put("这次聚会唯一让人遗憾的，\n就是它终究会结束。").put("").put("不卖250匹馬。")),3)
        assertEquals(listOf("这次聚会唯一让人遗憾的，\n就是它终究会结束。","","不卖250匹馬。"),result)
    }

    @Test fun failedIncompleteWrongCountOrWrongTypeResponseCannotBeAcceptedOrExposeResponseText(){
        val invalid=listOf(
            response(JSONArray().put("你好"),"MAX_TOKENS"),
            response(JSONArray().put("你好").put("再见")),
            response(JSONArray().put(123)),
            response(JSONArray().put(JSONObject.NULL)),
            response(JSONArray().put("Xin chào")),
            response(JSONArray().put("你好 Xin chào")),
            response(JSONArray().put("中".repeat(2001))),
            "{\"error\":{\"message\":\"SECRET_PROVIDER_RESPONSE\"}}",
            "invalid SECRET_PROVIDER_RESPONSE"
        )
        invalid.forEach {body->
            val error=assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(body,1)}
            assertFalse(error.message.orEmpty().contains("SECRET_PROVIDER_RESPONSE"))
            assertNull(error.cause)
        }
    }

    @Test fun modelThoughtsAreNotTreatedAsRecognizedSource(){
        val candidate=JSONObject(response(JSONArray().put("别走"))).getJSONArray("candidates").getJSONObject(0)
        val parts=candidate.getJSONObject("content").getJSONArray("parts")
        val textPart=parts.getJSONObject(0)
        candidate.getJSONObject("content").put("parts",JSONArray().put(JSONObject().put("thought",true).put("text","private reasoning")).put(textPart))
        val body=JSONObject().put("candidates",JSONArray().put(candidate)).toString()
        assertEquals(listOf("别走"),VideoVisualOcrClient.parseResponse(body,1))
    }

    @Test fun longVideoBatchKeepsAllThirtyTwoMappedSourcesAndRejectsMissingOrExtraFrames(){
        val sources=JSONArray((0 until 32).map{"第${it}句"})
        val body=response(sources)
        assertEquals((0 until 32).map{"第${it}句"},VideoVisualOcrClient.parseResponse(body,32))
        assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(response(JSONArray((0 until 31).map{"第${it}句"})),32)}
        assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(response(JSONArray((0 until 33).map{"第${it}句"})),32)}
        assertThrows(IllegalArgumentException::class.java){VideoVisualOcrClient.parseResponse(body,33)}
    }

    @Test fun compactDictionaryExpandsRepeatedTextAndBlankFramesWithoutLosingLineBreaks(){
        val dictionary=JSONArray().put("").put("你好").put("别走，\n明天见。")
        val indices=JSONArray(listOf(0,1,1,1,0,2,2,0))
        assertEquals(listOf("","你好","你好","你好","","别走，\n明天见。","别走，\n明天见。",""),
            VideoVisualOcrClient.parseResponse(compactResponse(dictionary,indices),8))
        // Duplicate dictionary entries are harmless if a provider uses them; frame mapping stays exact.
        assertEquals(listOf("你好","你好"),VideoVisualOcrClient.parseResponse(
            compactResponse(JSONArray().put("").put("你好").put("你好"),JSONArray(listOf(1,2))),2))
    }

    @Test fun compactIndicesMustBeExactIntegersInDictionaryRangeAndFirstSourceMustBeEmpty(){
        val dictionary=JSONArray().put("").put("你好")
        for(value in listOf(-1,2,Long.MAX_VALUE,1.5,"1",true,JSONObject.NULL)){
            assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(compactResponse(dictionary,JSONArray().put(value)),1)}
        }
        val invalid=listOf(
            compactResponse(JSONArray(),JSONArray().put(0)),
            compactResponse(JSONArray().put("你好"),JSONArray().put(0)),
            compactResponse(JSONArray().put("").put(""),JSONArray().put(1)),
            compactResponse(dictionary,JSONArray()),
            compactResponse(dictionary,JSONArray(listOf(1,1))),
            compactResponse(JSONArray().put("").put("你好").put("再见"),JSONArray().put(1))
        )
        invalid.forEach{body->assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(body,1)}}
    }

    @Test fun refusalTruncationMissingResultsAndInvalidJsonHaveDistinctSanitizedDiagnostics(){
        val secret="SECRET_PROVIDER_RESPONSE"
        val blockedPrompt=JSONObject().put("promptFeedback",JSONObject().put("blockReason","SAFETY")
            .put("blockReasonMessage",secret)).toString()
        val inputs=listOf(
            blockedPrompt to "VISION_BLOCKED",
            response(JSONArray().put("你好"),"SAFETY") to "VISION_BLOCKED",
            response(JSONArray().put("你好"),"MAX_TOKENS") to "VISION_TRUNCATED",
            response(JSONArray().put("你好"),secret) to "VISION_INCOMPLETE",
            JSONObject().put("candidates",JSONArray()).toString() to "VISION_EMPTY_RESPONSE",
            "invalid $secret" to "VISION_INVALID_RESPONSE",
            response(JSONArray().put("你好").put("再见")) to "VISION_INVALID_RESPONSE"
        )
        inputs.forEach{(body,code)->
            val error=assertThrows(IOException::class.java){VideoVisualOcrClient.parseResponse(body,1)}
            assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("[$code]"))
            assertFalse(error.message.orEmpty().contains(secret))
            assertNull(error.cause)
        }
        // An explicit valid blank for every image is the only successful no-text result.
        assertEquals(listOf("",""),VideoVisualOcrClient.parseResponse(response(JSONArray().put("").put("")),2))
    }

    @Test fun transportAndHttpErrorsPreserveSafeActionableCodesWithoutExceptionDetails(){
        val secret="SECRET_API_KEY_AND_URL"
        val timeout=VideoVisualOcrClient.networkFailure(SocketTimeoutException(secret))
        val network=VideoVisualOcrClient.networkFailure(UnknownHostException(secret))
        assertTrue(timeout.message.orEmpty().contains("[VISION_TIMEOUT]"))
        assertTrue(network.message.orEmpty().contains("[VISION_NETWORK]"))
        for(error in listOf(timeout,network)){
            assertFalse(error.message.orEmpty().contains(secret));assertNull(error.cause)
        }
        for(code in listOf(401,403,429,500)){
            val error=VideoVisualOcrClient.httpFailure(code)
            assertTrue(error.message.orEmpty().contains("(HTTP $code)"))
            assertNull(error.cause)
        }
    }
}
