package com.gemini.ocr.video

import android.content.Context
import com.gemini.ocr.settings.AppSettings

import com.gemini.ocr.network.GeminiGenerationMetrics
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.TranslationOutputGuard
import com.gemini.ocr.network.singleAttemptBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official video text requests use IDs, while Live keeps its existing response contract. */
internal class VideoTranslationClient(private val context: Context? = null) {
    data class Result(val accepted: Map<String, String>, val missingIds: List<String>, val model: String,
        val elapsedMs: Long, val quotaWaitMs: Long = 0, val tokens: GeminiGenerationMetrics? = null,
        val needsReviewIds: Set<String> = emptySet(), val truncated: Boolean = false)

    suspend fun translate(apiKey: String, batch: VideoTranslationPlan.Batch, profile: String, glossary: String,
                          onRetry: suspend (Int, Long, String) -> Unit = { _, _, _ -> },
                          beforeDispatch: suspend () -> Unit): Result = withContext(Dispatchers.IO) {
        require(batch.cueIds.distinct().size == batch.cueIds.size && batch.cueIds.size == batch.texts.size)
        val fullPayload=if(batch.fullDialogue)buildRequest(batch,profile,glossary) else null
        val started = System.nanoTime()
        overrideResponseForTest?.let { override ->
            check(com.gemini.ocr.BuildConfig.DEBUG)
            beforeDispatch()
            return@withContext parseResponse(override(batch), batch, glossary, StandaloneTranslator.FAST_MODEL,
                (System.nanoTime() - started) / 1_000_000L)
        }
        // Keep the established offline/mock verification seam; it does not spend API quota.
        if (StandaloneTranslator.overrideGeminiTranslator != null || StandaloneTranslator.overrideMt60TranslatorForVerification != null) {
            beforeDispatch()
            val outcome = StandaloneTranslator.translateWithGemini(apiKey = apiKey, texts = batch.texts,
                profile = profile, glossaryRaw = glossary, saveToSelfLearningBank = false, deferSideEffects = true,
                cacheNamespace = "video-private", contextItems = emptyList(), applyLegacyOcrCorrection = false,
                videoTranslationContext = batch.videoContext, videoMaxOutputTokens = batch.maxOutputTokens)
            return@withContext Result(batch.cueIds.zip(outcome.translations).toMap(), emptyList(), outcome.modelUsed,
                outcome.elapsedMs, outcome.quotaWaitMs, outcome.generationMetrics)
        }
        val keyReady = context?.let { AppSettings(it).hasGeminiVideoKey() } ?: apiKey.isNotBlank()
        require(keyReady) { "Chưa có khóa Gemini API. Mở Tùy chỉnh để cấu hình." }
        val payload = fullPayload ?: buildRequest(batch, profile, glossary)
        val operation: suspend (String) -> String = { selectedKey ->
            StandaloneTranslator.beforeNetworkCallForTest?.invoke()
            val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${StandaloneTranslator.FAST_MODEL}:generateContent")
                .header("x-goog-api-key", selectedKey)
                .post(singleAttemptBody(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))).build()
            VideoApiRecovery.exchange(requestClient(batch.fullDialogue), request)
        }
        val body = if (context == null) VideoApiRecovery.run(beforeDispatch, onRetry = onRetry) { operation(apiKey) }
            else VideoGeminiAccounts.request(context, apiKey, beforeDispatch, onRetry, operation)
        parseResponse(body, batch, glossary, StandaloneTranslator.FAST_MODEL, (System.nanoTime() - started) / 1_000_000L)
    }

    companion object {
        @Volatile internal var overrideResponseForTest: (suspend (VideoTranslationPlan.Batch) -> String)? = null
        internal fun requestClient(fullDialogue:Boolean)=StandaloneTranslator.httpClientForRequest(true,true).let{client->
            if(!fullDialogue)client else client.newBuilder().readTimeout(240,TimeUnit.SECONDS)
                .callTimeout(240,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()
        }
        internal fun buildRequest(batch: VideoTranslationPlan.Batch, profile: String, glossary: String): String {
            require(batch.cueIds.size == batch.texts.size && batch.cueIds.distinct().size == batch.cueIds.size)
            if(batch.fullDialogue)return fullDialogueRequest(batch,profile,glossary)
            val context = JSONObject(batch.videoContext)
            val targets = context.getJSONArray("targets")
            require(targets.length() == batch.cueIds.size)
            for (index in batch.cueIds.indices) targets.getJSONObject(index).put("id", batch.cueIds[index])
            val request = JSONObject(StandaloneTranslator.buildGeminiRequestJson(batch.texts, profile = profile,
                glossary = PhoneTranslationCache.parseGlossary(glossary), contextItems = emptyList(),
                structuredOutput = true, videoTranslationContext = context.toString(), videoMaxOutputTokens = batch.maxOutputTokens))
            val parts = request.getJSONObject("systemInstruction").getJSONArray("parts")
            parts.put(JSONObject().put("text", "Hợp đồng trả lời video: trả JSON {\"translations\":[{\"id\":\"ID câu nguồn\",\"translation\":\"bản Việt\"}]}. Sao chép đúng ID từ targets. Mỗi ID đúng một lần. Chỉ dịch targets; không đổi ID, timestamp hoặc nguồn. Không suy ID từ thứ tự và không trả mảng chuỗi. Nếu không dịch được một câu, bỏ ID đó; không tự tạo bản dịch để đủ số lượng."))
            val item = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "STRING").put("enum", JSONArray(batch.cueIds)))
                .put("translation", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("id").put("translation"))
            request.getJSONObject("generationConfig").put("responseSchema", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("translations", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray().put("translations")))
            return request.toString()
        }

        private fun fullDialogueRequest(batch:VideoTranslationPlan.Batch,profile:String,glossary:String):String {
            require(batch.indices.isNotEmpty() && batch.indices.size==batch.cueIds.size &&
                batch.indices.distinct().size==batch.indices.size && batch.indices.sorted()==batch.indices)
            require(batch.maxOutputTokens in 8192..VideoFullDialoguePlan.MAX_OUTPUT_TOKENS)
            require(batch.videoContext.toByteArray(Charsets.UTF_8).size<=VideoFullDialoguePlan.MAX_CONTEXT_BYTES)
            val context=JSONObject(batch.videoContext)
            val targets=context.getJSONArray("targetIds")
            require((0 until targets.length()).map{targets.getString(it)}==batch.cueIds)
            val dialogue=context.getJSONArray("dialogue")
            val byId=HashMap<String,JSONObject>()
            for(index in 0 until dialogue.length()){
                val row=dialogue.getJSONObject(index)
                require(byId.put(row.getString("id"),row)==null)
            }
            batch.cueIds.forEachIndexed{index,id->
                val row=requireNotNull(byId[id])
                require(row.getInt("index")==batch.indices[index] && row.getString("source")==batch.texts[index])
            }
            // Reuse model settings, with one video-only instruction contract. Live and legacy
            // batches retain their own prompts; source occurs only in dialogue.
            val request=JSONObject(StandaloneTranslator.buildGeminiRequestJson(listOf("video"),profile=profile,
                structuredOutput=true,videoTranslationContext="{}",videoMaxOutputTokens=8192))
            request.put("systemInstruction",JSONObject().put("parts",JSONArray().put(
                JSONObject().put("text",VideoDialoguePrompt.system(profile)))))
            val terms=PhoneTranslationCache.parseGlossary(glossary)
            context.put("promptRevision",VideoDialoguePrompt.REVISION).put("profile",profile).put("glossary",JSONObject(terms))
            val userPart=JSONObject().put("text","Phần hội thoại, ngữ cảnh và các ID cần dịch (JSON dữ liệu):\n"+context.toString())
            val user=JSONObject().put("role","user").put("parts",JSONArray().put(userPart))
            request.put("contents",JSONArray().put(user))
            val item=JSONObject().put("type","OBJECT").put("properties",JSONObject()
                .put("id",JSONObject().put("type","STRING").apply {
                    // Small staged groups can constrain IDs without a very large enum schema.
                    if (batch.cueIds.size <= 64) put("enum", JSONArray(batch.cueIds))
                })
                .put("translation",JSONObject().put("type","STRING")))
                .put("required",JSONArray().put("id").put("translation"))
            request.getJSONObject("generationConfig").put("maxOutputTokens",batch.maxOutputTokens)
                .put("responseSchema",JSONObject().put("type","OBJECT").put("properties",JSONObject()
                    .put("translations",JSONObject().put("type","ARRAY").put("items",item)))
                    .put("required",JSONArray().put("translations")))
            return request.toString().also{require(it.toByteArray(Charsets.UTF_8).size<=2*1024*1024){
                "Hội thoại và từ điển quá lớn cho một yêu cầu; nguồn đã lưu, chưa gửi dữ liệu."
            }}
        }

        internal fun parseResponse(body: String, batch: VideoTranslationPlan.Batch, glossary: String,
                                   model: String, elapsedMs: Long = 0, quotaWaitMs: Long = 0): Result {
            var reason = VideoResponseFailureReason.ENVELOPE
            try {
                reason = VideoResponseFailureReason.SIZE_LIMIT
                require(if(batch.fullDialogue)body.toByteArray(Charsets.UTF_8).size<=2*1024*1024 else body.length<=512*1024)
                reason = VideoResponseFailureReason.ENVELOPE
                val root = JSONObject(body)
                if (root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                        .let { it.isNotBlank() && it != "BLOCK_REASON_UNSPECIFIED" })
                    throw VideoApiException("TEXT_BLOCKED", detail = "Dịch vụ từ chối nhóm nội dung; bản dịch trước được giữ.")
                val candidates = root.getJSONArray("candidates")
                require(candidates.length() == 1)
                val candidate = candidates.getJSONObject(0)
                if (candidate.optString("finishReason") in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII"))
                    throw VideoApiException("TEXT_BLOCKED", detail = "Dịch vụ từ chối nhóm nội dung; bản dịch trước được giữ.")
                reason = VideoResponseFailureReason.FINISH_REASON
                require(candidate.optString("finishReason") in setOf("", "STOP", "MAX_TOKENS"))
                val truncated=batch.fullDialogue && candidate.optString("finishReason")=="MAX_TOKENS"
                reason = VideoResponseFailureReason.EMPTY_CONTENT
                val parts = candidate.getJSONObject("content").getJSONArray("parts")
                val raw = buildString { for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (!part.optBoolean("thought", false)) append(part.optString("text"))
                } }
                require(raw.isNotBlank())
                reason = VideoResponseFailureReason.JSON_SYNTAX
                val rows = responseRows(raw,truncated)
                reason = VideoResponseFailureReason.SCHEMA
                require(rows.length() <= batch.cueIds.size)
                val seen = HashSet<String>()
                val accepted = linkedMapOf<String, String>()
                val terms = PhoneTranslationCache.parseGlossary(glossary)
                val sources=batch.cueIds.zip(batch.texts).toMap()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val id = row.get("id") as? String ?: error("Invalid ID type")
                    if (!sources.containsKey(id)) throw InvalidResponsePart(VideoResponseFailureReason.UNKNOWN_ID)
                    if (!seen.add(id)) throw InvalidResponsePart(VideoResponseFailureReason.DUPLICATE_ID)
                    val rawTranslation = row.opt("translation") as? String ?: continue
                    val text = rawTranslation.trim()
                    if (text.isBlank() || text.length > 8000) continue
                    val source = sources.getValue(id)
                    val processed = runCatching {
                        TranslationOutputGuard.validateModelOutput("zh", listOf(source), listOf(text), terms)
                        StandaloneTranslator.applyGlossaryPostProcess(listOf(source), listOf(text), glossary, enrichWithBank = false).single()
                    }.getOrNull() ?: continue
                    accepted[id] = processed
                }
                // Even valid closing JSON can contain a sentence cut by the token budget.
                // Return order is independent of target order: keep the final RETURNED row
                // pending when every requested ID appeared, rather than guess the target ID.
                if(truncated && accepted.size==batch.cueIds.size && rows.length()>0)
                    accepted.remove(rows.getJSONObject(rows.length()-1).getString("id"))
                return Result(accepted, batch.cueIds.filterNot(accepted::containsKey), model, elapsedMs,
                    quotaWaitMs, GeminiGenerationMetrics.fromResponse(body),truncated=truncated)
            } catch (error: VideoApiException) { throw error }
            catch (error: Exception) {
                val safeReason = (error as? InvalidResponsePart)?.reason ?: reason
                throw VideoApiException("TEXT_INVALID_RESPONSE", splitAllowed = !batch.fullDialogue,
                    detail = "${safeReason.label}. Bản dịch trước và nguồn được giữ.", responseReason = safeReason)
            }
        }

        private class InvalidResponsePart(val reason: VideoResponseFailureReason) : Exception()

        /** Only a provider-declared token cutoff permits accepting complete prefix objects.
         * No guessed IDs, string repair, prose extraction or salvage of malformed complete rows. */
        private fun responseRows(raw:String,truncated:Boolean):JSONArray {
            val json=VideoApiRecovery.jsonText(raw)
            val complete = runCatching { JSONObject(json) }.getOrNull()
            if (complete != null) return complete.optJSONArray("translations")
                ?: throw InvalidResponsePart(VideoResponseFailureReason.SCHEMA)
            if (!truncated) throw InvalidResponsePart(VideoResponseFailureReason.JSON_SYNTAX)
            val prefix=Regex("^\\s*\\{\\s*\"translations\"\\s*:\\s*\\[").find(json)
            require(prefix!=null){"Unexpected truncated response shape"}
            val rows=JSONArray();var position=prefix.range.last+1;var afterComma=false
            while(true){
                while(position<json.length && json[position].isWhitespace())position++
                if(position==json.length)return rows
                if(json[position]==']'){
                    require(!afterComma){"Trailing comma is not a truncated row"}
                    require(json.substring(position+1).trim().let{it.isEmpty() || it=="}"})
                    return rows
                }
                require(json[position]=='{'){"Invalid truncated row"}
                afterComma=false
                val start=position;var depth=0;var string=false;var escaped=false
                while(position<json.length){
                    val c=json[position++]
                    if(string){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='\"')string=false}
                    else when(c){'\"'->string=true;'{'->depth++;'}'->{depth--;if(depth==0)break}}
                }
                if(depth!=0 || string)return rows // the final incomplete object is not evidence
                rows.put(JSONObject(json.substring(start,position)))
                while(position<json.length && json[position].isWhitespace())position++
                if(position==json.length)return rows
                if(json[position]==']')continue
                require(json[position++]==','){"Invalid row separator"}
                afterComma=true
            }
        }
    }
}
