package com.gemini.ocr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.network.SecureConnection
import com.gemini.ocr.network.TranslationClient
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeConnectionTest {
    @Test fun realAndroidKeystorePairingAndEncryptedWebSocket() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("serverHost"))
        val pin = requireNotNull(args.getString("serverPin"))
        val code = requireNotNull(args.getString("pairCode"))
        val context = instrumentation.targetContext
        val settings = AppSettings(context)
        settings.unpair()
        SecureConnection.pair(settings,host,pin,code,"Android 15 native test")
        assertTrue(settings.token.length >= 32)
        val rawStored = context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("token","")!!
        assertNotEquals(settings.token,rawStored)
        assertEquals(host,settings.host)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val hello = CompletableDeferred<Unit>()
        val result = CompletableDeferred<JSONObject>()
        val client = TranslationClient(settings,scope,{ message ->
            if(message.optString("type")=="hello") hello.complete(Unit)
            if(message.optString("type")=="result") result.complete(message)
            if(message.optString("type")=="error") result.completeExceptionally(AssertionError(message.toString()))
        },{})
        try {
            withContext(Dispatchers.Main) { client.connect() }
            withTimeout(15000) { hello.await() }
            val request=JSONObject().put("type","translate").put("request_id","native-android")
                .put("source","en").put("profile","game").put("mode","smart")
                .put("blocks",JSONArray().put(JSONObject().put("id","greeting").put("text","Hello traveler")
                    .put("box",JSONObject().put("x",.1).put("y",.5).put("width",.8).put("height",.1))))
            withContext(Dispatchers.Main) { assertTrue(client.send(request)) }
            val response=withTimeout(15000) { result.await() }
            assertEquals("native-android",response.getString("request_id"))
            assertEquals("Xin chào, lữ khách!",response.getJSONArray("blocks").getJSONObject(0).getString("text"))
            assertTrue(response.getJSONArray("blocks").getJSONObject(0).getBoolean("cached"))
        } finally {
            withContext(Dispatchers.Main) { client.close() }
            scope.cancel(); settings.unpair()
        }
    }
}
