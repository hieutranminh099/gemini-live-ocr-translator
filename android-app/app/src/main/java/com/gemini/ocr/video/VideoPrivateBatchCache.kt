package com.gemini.ocr.video

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.batch.BatchTranslationCache
import com.gemini.ocr.network.StandaloneTranslator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Two bounded cache levels inside the existing private batch boundary; never a shared bank. */
internal class VideoPrivateBatchCache(context: Context) {
    private val videos = VideoStore(context)
    private val batches = BatchStore(context)
    private val legacy = BatchTranslationCache(context)
    enum class Level { RAM, SQLITE, LEGACY_JSON }
    data class Hit(val translations: List<String>, val model: String, val level: Level)
    private data class Location(val database: File, val key: String, val ramKey: String)
    private fun location(project: VideoProject, request: VideoTranslationPlan.Batch): Location? {
        val ownerFile = File(videos.folder(project.id), "batch-owner")
        if (!ownerFile.isFile || ownerFile.length() != 36L) return null
        val owner = ownerFile.readText(Charsets.UTF_8)
        if (!owner.matches(Regex("[a-f0-9-]{36}"))) return null
        val scope = StandaloneTranslator.cacheNamespace(project.profile, project.glossary, emptyList(),
            filmId = "private-batch:$owner", domain = "official-gemini-video:${project.translationMode.name}")
        val key = BatchTranslationCache.key(scope, request.videoContext)
        return Location(File(batches.folder(owner), "translation-cache-v2.sqlite"), key, "$owner:$key")
    }
    fun lookup(project: VideoProject, request: VideoTranslationPlan.Batch): Hit? = synchronized(lock) {
        runCatching {
            val where = location(project, request) ?: return@synchronized null
            ram[where.ramKey]?.let { payload -> decode(payload, request, project)?.let { return@synchronized it.copy(level = Level.RAM) } }
            if (where.database.isFile) {
                open(where.database).use { db ->
                    db.rawQuery("SELECT payload FROM responses WHERE cache_key=?", arrayOf(where.key)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val payload = cursor.getString(0)
                            decode(payload, request, project)?.let { hit ->
                                remember(where.ramKey, payload)
                                return@synchronized hit
                            }
                        }
                    }
                }
            }
            // Previous releases' exact private cache remains readable and is never rewritten.
            legacy.lookup(project, request)?.let { hit ->
                runCatching { save(project, request, hit.translations, hit.model) }
                return@synchronized Hit(hit.translations, hit.model, Level.LEGACY_JSON)
            }
            null
        }.getOrNull()
    }
    fun save(project: VideoProject, request: VideoTranslationPlan.Batch, translations: List<String>, model: String) = synchronized(lock) {
        val where = location(project, request) ?: return@synchronized
        val payload = JSONObject().put("model", model).put("translations", JSONArray(translations)).toString()
        require(payload.length <= MAX_ENTRY_CHARS && decode(payload, request, project) != null)
        // A valid exact result can still avoid repeated requests when disk admission fails.
        // RAM remains bounded and owner-scoped; persistence failures do not corrupt prior entries.
        remember(where.ramKey, payload)
        require(where.database.parentFile!!.usableSpace > payload.length * 4L + 16L * 1024 * 1024)
        open(where.database).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("INSERT OR REPLACE INTO responses(cache_key,payload,updated_ms) VALUES(?,?,?)",
                    arrayOf<Any>(where.key, payload, System.currentTimeMillis()))
                db.execSQL("DELETE FROM responses WHERE cache_key IN (SELECT cache_key FROM responses ORDER BY updated_ms DESC LIMIT -1 OFFSET 5000)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    private fun open(path: File): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
        path.absolutePath.replace(File.separatorChar, '/'), null).also {
        it.execSQL("CREATE TABLE IF NOT EXISTS responses(cache_key TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,updated_ms INTEGER NOT NULL)")
    }
    private fun decode(payload: String, request: VideoTranslationPlan.Batch, project: VideoProject): Hit? = runCatching {
        require(payload.length <= MAX_ENTRY_CHARS)
        val json = JSONObject(payload); val rows = json.getJSONArray("translations")
        require(rows.length() == request.indices.size)
        val texts = List(rows.length()) { rows.getString(it) }
        require(texts.all { it.isNotBlank() && it.length <= 8000 && !VideoTranslationPlan.hasUnexpectedCyrillic(it, project.glossary) })
        Hit(texts, json.getString("model").also { require(it.isNotBlank()) }, Level.SQLITE)
    }.getOrNull()
    companion object {
        private const val MAX_ENTRY_CHARS = 256 * 1024
        private const val MAX_RAM_CHARS = 1024 * 1024
        private val lock = Any()
        private val ram = LinkedHashMap<String, String>(128, .75f, true)
        private fun remember(key: String, payload: String) {
            ram[key] = payload
            while (ram.size > 128 || ram.values.sumOf { it.length } > MAX_RAM_CHARS) ram.remove(ram.keys.first())
        }
        internal fun clearRamForTest() = synchronized(lock) { ram.clear() }
    }
}
