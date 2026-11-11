package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.settings.GeminiKeyMode
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoEconomyBatchTest {
    private fun seedSecondary(context: Context, slot: Int, value: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("gemini_video_api_key_$slot", "synthetic-invalid-ciphertext")
            .putString("gemini_video_api_key_${slot}_plain_fallback", value).commit()
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun response(key:String)=JSONObject().put("done",true).put("response",JSONObject().put("inlinedResponses",JSONObject()
        .put("inlinedResponses",JSONArray().put(JSONObject().put("metadata",JSONObject().put("key",key))
            .put("response",JSONObject().put("candidates",JSONArray()))))))
    @Test fun officialInlineRequestKeepsMetadataAndCompletePayload() {
        val payload=JSONObject().put("contents",JSONArray().put(JSONObject().put("text","fixture"))).toString()
        val rows=VideoEconomyBatch.createRequest(payload,"group-id").getJSONObject("batch").getJSONObject("input_config")
            .getJSONObject("requests").getJSONArray("requests")
        assertEquals(1,rows.length());assertEquals("group-id",rows.getJSONObject(0).getJSONObject("metadata").getString("key"))
        assertEquals(JSONObject(payload).toString(),rows.getJSONObject(0).getJSONObject("request").toString())
    }
    @Test fun pendingCannotBeMistakenForTranslationAndUnknownGroupIsRejected() {
        assertNull(VideoEconomyBatch.completedResponse(JSONObject().put("done",false),"key"))
        assertNotNull(VideoEconomyBatch.completedResponse(response("key"),"key"))
        assertThrows(IllegalArgumentException::class.java){VideoEconomyBatch.completedResponse(response("foreign"),"key")}
        assertThrows(java.io.IOException::class.java){VideoEconomyBatch.completedResponse(JSONObject().put("error",JSONObject()),"key")}
        assertFalse(VideoEconomyBatch.validName("batches/../../other"));assertTrue(VideoEconomyBatch.validName("batches/abc-12_3"))
    }
    @Test fun processRestartFetchesOneSavedJobAndNeverSubmitsTheGroupTwice():Unit=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val id=UUID.randomUUID().toString()
        val batch=VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("one",0,500,"你好")),false)!!
        var posts=0;var gets=0;var key=""
        val transport:suspend(String,JSONObject?)->JSONObject={_,body->
            if(body!=null){posts++;key=body.getJSONObject("batch").getJSONObject("input_config").getJSONObject("requests")
                .getJSONArray("requests").getJSONObject(0).getJSONObject("metadata").getString("key");JSONObject().put("name","batches/generated")}
            else { gets++;if(gets==1)JSONObject().put("done",false) else {
                val result=response(key)
                val records=JSONArray().put(JSONObject().put("id","one").put("translation","Xin chào"))
                val content=JSONObject().put("parts",JSONArray().put(JSONObject().put("text",JSONObject().put("translations",records).toString())))
                val generated=JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason","STOP").put("content",content)))
                result.getJSONObject("response").getJSONObject("inlinedResponses").getJSONArray("inlinedResponses").getJSONObject(0).put("response",generated)
                result
            }}
        }
        try{VideoEconomyBatch(context,id,transport).translate("test-only",batch,"manga",""){};fail("Pending result must pause")}
        catch(_:VideoEconomyPendingException){}
        val resumed=VideoEconomyBatch(context,id,transport)
        assertEquals("Xin chào",resumed.translate("test-only",batch,"manga",""){}.accepted["one"])
        assertEquals(1,posts);assertEquals(2,gets)
        val checkpoint=File(VideoStore(context).folder(id),"gemini-economy-job.json")
        assertTrue(checkpoint.isFile)
        resumed.reconcile(VideoProject(id,"fixture","file:///not-read",1000,640,360,cues=listOf(VideoCue("one",0,500,"你好","Xin chào"))))
        assertFalse(checkpoint.exists())
    }
    @Test fun lostSubmissionResponseCannotAutomaticallySpendQuotaAgain():Unit=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val id=UUID.randomUUID().toString()
        val batch=VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("one",0,500,"你好")),false)!!
        var posts=0
        val transport:suspend(String,JSONObject?)->JSONObject={_,_->posts++;throw java.io.IOException("Lost test response")}
        try{VideoEconomyBatch(context,id,transport).translate("test-only",batch,"manga",""){};fail()}catch(_:java.io.IOException){}
        try{VideoEconomyBatch(context,id,transport).translate("test-only",batch,"manga",""){};fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,posts)
    }

    @Test fun configuredEconomyJobKeepsItsChosenKeyAcrossModeChangesWithoutPersistingSecrets(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val settings = AppSettings(context)
        seedSecondary(context, 2, "fixture-second")
        seedSecondary(context, 3, "fixture-third")
        settings.geminiKeyMode = GeminiKeyMode.BALANCED
        val batch = VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("one", 0, 500, "你好")), false)!!
        var posts = 0
        var gets = 0
        val transport: suspend (String, JSONObject?) -> JSONObject = { _, body ->
            if (body != null) { posts++; JSONObject().put("name", "batches/pinned") }
            else { gets++; JSONObject().put("done", false) }
        }
        try { VideoEconomyBatch(context, id, transport).translate("fixture-primary", batch, "manga", "") {}; fail() }
        catch (_: VideoEconomyPendingException) {}
        val path = File(VideoStore(context).folder(id), "gemini-economy-job.json")
        val saved = JSONObject(path.readText())
        val slot = saved.getInt("keySlot")
        assertTrue(slot in 2..3)
        assertEquals(digest(settings.secondaryGeminiApiKey(slot)), saved.getString("keyFingerprint"))
        assertFalse(path.readText().contains("fixture-primary"))
        assertFalse(path.readText().contains("fixture-second"))
        assertFalse(path.readText().contains("fixture-third"))

        settings.geminiKeyMode = GeminiKeyMode.PRIMARY_ONLY
        try { VideoEconomyBatch(context, id, transport).translate("replacement-primary", batch, "manga", "") {}; fail() }
        catch (_: VideoEconomyPendingException) {}
        assertEquals(1, posts)
        assertEquals(2, gets)
        assertEquals(saved.getString("keyFingerprint"), JSONObject(path.readText()).getString("keyFingerprint"))
    }

    @Test fun removingTheBoundKeyPreventsPollingOrCancellingThroughAnotherAccount(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val settings = AppSettings(context)
        seedSecondary(context, 2, "fixture-second")
        seedSecondary(context, 3, "fixture-third")
        settings.geminiKeyMode = GeminiKeyMode.FAILOVER
        val batch = VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("one", 0, 500, "你好")), false)!!
        var requests = 0
        val transport: suspend (String, JSONObject?) -> JSONObject = { _, body ->
            requests++
            if (body != null) JSONObject().put("name", "batches/pinned") else JSONObject().put("done", false)
        }
        try { VideoEconomyBatch(context, id, transport).translate("fixture-primary", batch, "manga", "") {}; fail() }
        catch (_: VideoEconomyPendingException) {}
        val path = File(VideoStore(context).folder(id), "gemini-economy-job.json")
        val saved = JSONObject(path.readText())
        settings.setSecondaryGeminiApiKey(saved.getInt("keySlot"), "")
        val before = requests
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { VideoEconomyBatch(context, id, transport).translate("fixture-primary", batch, "manga", "") {} }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { VideoEconomyBatch(context, id, transport).cancel("fixture-primary") {} }
        }
        assertEquals(before, requests)
        assertEquals(saved.toString(), JSONObject(path.readText()).toString())
    }

    @Test fun legacyEconomyCheckpointStillRequiresThePassedPrimaryKey(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val batch = VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("one", 0, 500, "你好")), false)!!
        var requests = 0
        val transport: suspend (String, JSONObject?) -> JSONObject = { _, body ->
            requests++
            if (body != null) JSONObject().put("name", "batches/legacy") else JSONObject().put("done", false)
        }
        try { VideoEconomyBatch(context, id, transport).translate("fixture-legacy", batch, "manga", "") {}; fail() }
        catch (_: VideoEconomyPendingException) {}
        val path = File(VideoStore(context).folder(id), "gemini-economy-job.json")
        path.writeText(JSONObject(path.readText()).apply { remove("keyFingerprint"); remove("keySlot") }.toString())
        seedSecondary(context, 2, "fixture-second")
        AppSettings(context).geminiKeyMode = GeminiKeyMode.BALANCED
        val before = requests
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { VideoEconomyBatch(context, id, transport).translate("", batch, "manga", "") {} }
        }
        assertEquals(before, requests)
        try { VideoEconomyBatch(context, id, transport).translate("fixture-legacy", batch, "manga", "") {}; fail() }
        catch (_: VideoEconomyPendingException) {}
        assertEquals(before + 1, requests)
    }
}
