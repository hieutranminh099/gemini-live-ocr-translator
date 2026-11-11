package com.gemini.ocr.batch

import android.content.Context
import android.util.AtomicFile
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private exact/contextual reuse within one batch only. No row enters Live's bank or shared cache. */
class BatchTranslationCache(context: Context) {
    private val videos = VideoStore(context)
    private val batches = BatchStore(context)
    data class Hit(val translations: List<String>, val model: String)
    private fun file(project: VideoProject, request: VideoTranslationPlan.Batch): File? {
        val ownerFile = File(videos.folder(project.id), "batch-owner")
        if (!ownerFile.isFile || ownerFile.length() != 36L) return null
        val owner = ownerFile.readText(Charsets.UTF_8)
        if (!owner.matches(Regex("[a-f0-9-]{36}"))) return null
        val scope = StandaloneTranslator.cacheNamespace(project.profile, project.glossary, emptyList(),
            filmId = "private-batch:$owner", domain = "official-gemini-video:${project.translationMode.name}")
        val key = key(scope, request.videoContext)
        return File(File(batches.folder(owner), "translation-cache").apply { mkdirs() }, "$key.json")
    }
    fun lookup(project: VideoProject, request: VideoTranslationPlan.Batch): Hit? = synchronized(lock) {
        runCatching {
            val path = file(project, request) ?: return@synchronized null
            if (!path.isFile || path.length() > MAX_ENTRY) return@synchronized null
            val json = JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val rows = json.getJSONArray("translations")
            require(rows.length() == request.indices.size)
            val translations = (0 until rows.length()).map { rows.getString(it) }
            require(translations.all { it.isNotBlank() && it.length <= 8000 && !VideoTranslationPlan.hasUnexpectedCyrillic(it, project.glossary) })
            Hit(translations, json.getString("model").also { require(it.isNotBlank()) })
        }.getOrNull()
    }
    fun save(project: VideoProject, request: VideoTranslationPlan.Batch, translations: List<String>, model: String) = synchronized(lock) {
        val path = file(project, request) ?: return@synchronized
        require(model.isNotBlank() && translations.size == request.indices.size)
        require(translations.all { it.isNotBlank() && it.length <= 8000 && !VideoTranslationPlan.hasUnexpectedCyrillic(it, project.glossary) })
        val bytes = JSONObject().put("model", model).put("translations", JSONArray(translations)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ENTRY && path.parentFile!!.usableSpace > bytes.size + 16L * 1024 * 1024)
        val atomic = AtomicFile(path); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Throwable) { atomic.failWrite(output); throw e }
    }
    companion object {
        private val lock = Any()
        private const val MAX_ENTRY = 512 * 1024
        /** Keep source, translations, draft and repair context; only positional labels are ignored. */
        internal fun key(scope: String, rawContext: String): String {
            fun canonical(value: Any?): String = when (value) {
                is JSONObject -> value.keys().asSequence().toList().filterNot { it in setOf("index", "startMs", "endMs") }.sorted()
                    .joinToString("", prefix = "{", postfix = "}") { name -> "${name.length}:$name${canonical(value.get(name))}" }
                is JSONArray -> (0 until value.length()).joinToString("", prefix = "[", postfix = "]") { canonical(value.get(it)) }
                null, JSONObject.NULL -> "null"
                else -> value.toString().let { "${it.length}:$it" }
            }
            val payload = "${scope.length}:$scope${canonical(JSONObject(rawContext))}"
            return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
