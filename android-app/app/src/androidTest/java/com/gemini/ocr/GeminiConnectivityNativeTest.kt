package com.gemini.ocr
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.HttpTransportTiming
import com.gemini.ocr.network.awaitBody
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Protocol
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.gemini.ocr.network.singleAttemptBody
import com.gemini.ocr.settings.AppSettings

/** Anonymous GET only; no API key, generation, account or model configuration is read. */
@RunWith(AndroidJUnit4::class)
class GeminiConnectivityNativeTest {
    @Test fun oneSyntheticPhraseWithAThirtyFiveSecondReadLimit():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeSingleGeneration")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr" && !SessionState.running.value)
        val source="想让兄弟们信服你";val settings=AppSettings(context)
        require(settings.effectiveStandaloneApiKey().isNotBlank())
        val timing=HttpTransportTiming();val report=JSONObject().put("source",source).put("maximumPosts",1).put("readLimitMs",35000)
        try {
            val payload=StandaloneTranslator.buildGeminiRequestJson(listOf(source),profile=settings.profile)
            val request=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${StandaloneTranslator.FAST_MODEL}:generateContent")
                .header("x-goog-api-key",settings.effectiveStandaloneApiKey()).tag(HttpTransportTiming::class.java,timing)
                .post(singleAttemptBody(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))).build()
            val client=StandaloneTranslator.httpClientForRequest(false,true).newBuilder().readTimeout(35,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build()
            val response=client.newCall(request).awaitBody()
            report.put("httpStatus",response.code)
            if(response.isSuccessful)report.put("translation",StandaloneTranslator.parseGeminiResponseJson(response.body,1).single())
        }catch(e:Exception){report.put("failureType",e.javaClass.simpleName)}
        timing.snapshot().traceFields().forEach{(key,value)->report.put(key,value?:JSONObject.NULL)}
        val folder=File(context.getExternalFilesDir(null),"live-roi-fixtures").apply{mkdirs()}
        File(folder,"single-generation-diagnostic.json").writeText(report.toString(2))
    }
    @Test fun anonymousOfficialEndpointTransportProbe():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeConnectivity")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.gemini.ocr" && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        val rows=JSONArray()
        for(h1 in listOf(false,true)){
            val timing=HttpTransportTiming();val row=JSONObject().put("requestedProtocol",if(h1)"HTTP1" else "default")
            try{
                val base=StandaloneTranslator.httpClientForRequest(false,true)
                val client=if(h1)base.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() else base
                val request=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models")
                    .tag(HttpTransportTiming::class.java,timing).get().build()
                val response=withTimeout(10000){client.newCall(request).awaitBody()}
                row.put("httpStatus",response.code)
            }catch(e:Exception){row.put("failureType",e.javaClass.simpleName)}
            timing.snapshot().traceFields().forEach{(key,value)->row.put(key,value?:JSONObject.NULL)};rows.put(row)
        }
        val folder=File(context.getExternalFilesDir(null),"live-roi-fixtures").apply{mkdirs()}
        File(folder,"anonymous-connectivity.json").writeText(JSONObject().put("authenticated",false).put("generationRequests",0).put("rows",rows).toString(2))
    }
}
