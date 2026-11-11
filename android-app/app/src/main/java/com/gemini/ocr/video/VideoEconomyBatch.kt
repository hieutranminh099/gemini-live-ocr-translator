package com.gemini.ocr.video

import android.content.Context
import android.util.AtomicFile
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.awaitBody
import com.gemini.ocr.network.singleAttemptBody
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.settings.GeminiKeyMode
import com.gemini.ocr.settings.GeminiKeySlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class VideoEconomyPendingException : IOException("Gemini đang xử lý tác vụ nền tiết kiệm. Tiến độ và mã tác vụ đã lưu. Bấm Tiếp tục để kiểm tra; không gửi lại nhóm câu đã nộp.")

/** Explicit opt-in official Batch API. A pause never submits or cancels another paid job. */
internal class VideoEconomyBatch(private val context: Context, private val id: String,
    private val transportForTest: (suspend (String,JSONObject?)->JSONObject)? = null) {
    private val checkpoint = AtomicFile(File(VideoStore(context).folder(id), "gemini-economy-job.json"))
    suspend fun translate(key: String, batch: VideoTranslationPlan.Batch, profile: String, glossary: String,
                          beforeDispatch: suspend () -> Unit): VideoTranslationClient.Result = withContext(Dispatchers.IO) {
        val payload=VideoTranslationClient.buildRequest(batch,profile,glossary)
        val fingerprint=MessageDigest.getInstance("SHA-256").digest((StandaloneTranslator.FAST_MODEL+payload).toByteArray()).joinToString(""){"%02x".format(it)}
        var saved=if(checkpoint.baseFile.exists()) JSONObject(checkpoint.openRead().bufferedReader().use{it.readText()}) else null
        if(saved!=null) require(saved.getString("fingerprint")==fingerprint) {
            "Có tác vụ Gemini nền cho cấu hình khác. Giữ dự án cũ hoặc hủy tác vụ nền trước khi đổi thiết lập."
        }
        val selected = boundKey(saved, key)
        if(saved==null) {
            // Persist the submission intent first. A lost POST response must not spend quota again.
            saved=JSONObject().put("fingerprint",fingerprint).put("state","SUBMITTING")
                .put("keyFingerprint", keyFingerprint(selected.key)).put("keySlot", selected.slot)
            val result=exchange(selected.key,"models/${StandaloneTranslator.FAST_MODEL}:batchGenerateContent",createRequest(payload,fingerprint),beforeDispatch,saved)
            val name=result.getString("name").also{require(validName(it))}
            saved.put("name",name).put("state","PENDING");write(saved)
        }
        require(saved.has("name")) { "Chưa xác định được mã tác vụ sau lần gửi trước. Không tự gửi lại để tránh tính phí trùng; kiểm tra tác vụ trên Google AI Studio." }
        val name=saved.getString("name").also{require(validName(it))}
        val result=exchange(selected.key,name,null,beforeDispatch)
        val response=completedResponse(result,fingerprint) ?: throw VideoEconomyPendingException()
        val parsed=VideoTranslationClient.parseResponse(response.toString(),batch,glossary,StandaloneTranslator.FAST_MODEL)
        saved.put("state","COMPLETE").put("acceptedIds",JSONArray(parsed.accepted.keys.toList()));write(saved)
        // Keep the completed response until the timeline checkpoint is durable; a resumed caller
        // can GET the same immutable job safely rather than resubmitting the POST.
        parsed
    }
    fun acknowledge() { checkpoint.delete() }
    fun reconcile(project:VideoProject) {
        if(!checkpoint.baseFile.exists())return
        val saved=JSONObject(checkpoint.openRead().bufferedReader().use{it.readText()})
        if(saved.optString("state")!="COMPLETE")return
        val ids=saved.optJSONArray("acceptedIds")?:return
        if(ids.length()>0 && (0 until ids.length()).all { index -> project.cues.firstOrNull{it.id==ids.getString(index)}?.let{
                VideoTranslationPlan.isComplete(it,project.translationMode==VideoTranslationMode.HYBRID)
            }==true })checkpoint.delete()
    }
    suspend fun cancel(key:String,gate:suspend()->Unit) = withContext(Dispatchers.IO) {
        if(!checkpoint.baseFile.exists())return@withContext
        val saved=JSONObject(checkpoint.openRead().bufferedReader().use{it.readText()})
        require(saved.has("name")){"Không có mã tác vụ để hủy tự động. Kiểm tra tác vụ trên Google AI Studio trước khi gửi lại."}
        val name=saved.getString("name").also{require(validName(it))}
        val selected = boundKey(saved, key)
        exchange(selected.key,"$name:cancel",JSONObject(),gate)
        checkpoint.delete()
    }
    private fun boundKey(saved: JSONObject?, primary: String): GeminiKeySlot {
        val settings = AppSettings(context)
        val legacy = primary.trim()
        if (saved != null && !saved.has("keyFingerprint")) {
            require(legacy.isNotBlank()) { "Tác vụ nền cũ cần khóa chính đã dùng khi gửi. Nhập lại khóa trước khi tiếp tục." }
            return GeminiKeySlot(1, legacy)
        }
        val configured = settings.geminiVideoKeys()
        if (saved != null) {
            val available = (configured + if (legacy.isBlank()) emptyList() else listOf(GeminiKeySlot(1, legacy)))
                .distinctBy { it.key }
            return requireNotNull(available.firstOrNull { keyFingerprint(it.key) == saved.optString("keyFingerprint") }) {
                "Khóa đã gửi tác vụ nền không còn được cấu hình. Nhập lại đúng khóa để kiểm tra hoặc hủy tác vụ này."
            }
        }
        if (settings.geminiKeyMode == GeminiKeyMode.PRIMARY_ONLY) {
            require(legacy.isNotBlank()) { "Chưa có khóa Gemini API." }
            return GeminiKeySlot(1, legacy)
        }
        val available = configured.ifEmpty { if (legacy.isBlank()) emptyList() else listOf(GeminiKeySlot(1, legacy)) }
        require(available.isNotEmpty()) { "Chưa có khóa Gemini API cho video." }
        return if (settings.geminiKeyMode == GeminiKeyMode.FAILOVER) available.first()
            else available[Math.floorMod(id.hashCode(), available.size)]
    }
    private suspend fun exchange(key:String,path:String,body:JSONObject?,gate:suspend()->Unit,submission:JSONObject?=null):JSONObject {
        gate();StandaloneTranslator.beforeNetworkCallForTest?.invoke()
        transportForTest?.let { transport ->
            if(submission!=null)write(submission)
            return transport(path,body)
        }
        val builder=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/$path").header("x-goog-api-key",key)
        if(body!=null)builder.post(singleAttemptBody(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))
        if(submission!=null)write(submission)
        val result=StandaloneTranslator.httpClientForRequest(true,true).newCall(builder.build()).awaitBody()
        if(!result.isSuccessful) {
            if(submission!=null && result.code in 400..499)checkpoint.delete()
            if(result.code==429)throw IOException("Đạt giới hạn lượt gọi Gemini API (429).")
            throw IOException("Gemini Batch API chưa chấp nhận tài khoản/model hoặc kết nối (HTTP ${result.code}). Không tự đổi sang dịch nhanh.")
        }
        return JSONObject(result.body)
    }
    private fun write(value:JSONObject) {
        val out=checkpoint.startWrite()
        try {out.write(value.toString().toByteArray());checkpoint.finishWrite(out)}catch(e:Throwable){checkpoint.failWrite(out);throw e}
    }
    companion object {
        private fun keyFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun validName(name:String)=name.matches(Regex("batches/[a-zA-Z0-9_-]{1,200}"))
        internal fun createRequest(payload:String,key:String):JSONObject {
            val row=JSONObject().put("request",JSONObject(payload)).put("metadata",JSONObject().put("key",key))
            val input=JSONObject().put("requests",JSONObject().put("requests",JSONArray().put(row)))
            return JSONObject().put("batch",JSONObject().put("display_name","Gemini OCR subtitle group").put("input_config",input))
        }
        internal fun completedResponse(result:JSONObject,key:String):JSONObject? {
            if(result.has("error"))throw IOException("Gemini Batch API báo tác vụ lỗi; checkpoint được giữ, không tự gửi lại.")
            val state=result.optJSONObject("metadata")?.optString("state").orEmpty()
            if(state in setOf("JOB_STATE_FAILED","JOB_STATE_CANCELLED","JOB_STATE_EXPIRED","BATCH_STATE_FAILED","BATCH_STATE_CANCELLED","BATCH_STATE_EXPIRED"))throw IOException("Gemini Batch API: $state. Không tự nộp lại tác vụ.")
            if(!result.optBoolean("done") && state!="JOB_STATE_SUCCEEDED")return null
            val rows=result.getJSONObject("response").getJSONObject("inlinedResponses").getJSONArray("inlinedResponses")
            require(rows.length()==1){"Phản hồi tác vụ nền không đúng số nhóm."}
            val row=rows.getJSONObject(0)
            require(row.getJSONObject("metadata").getString("key")==key){"Phản hồi tác vụ nền không khớp ID nhóm."}
            if(row.has("error"))throw IOException("Gemini Batch API chưa trả bản dịch hợp lệ cho nhóm này.")
            return row.getJSONObject("response")
        }
    }
}
