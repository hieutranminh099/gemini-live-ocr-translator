package com.gemini.ocr.network

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Writer
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Reviewed, contextual Android memory. This additive store never opens or migrates the
 * legacy subtitle bank. Integration must supply the SAME scope used for translation.
 * All database work runs on IO; imports release the store mutex every 64 records.
 */
class ContextualTranslationMemory(
    context: Context,
    databaseName: String? = "memory_reviewed_v1.db",
    private val hotCapacity: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Quality(val rank: Int) { DRAFT(0), VERIFIED(1), USER_APPROVED(2) }

    data class Scope(
        val filmId: String,
        val domain: String,
        val modelVersion: String,
        val glossaryVersion: String,
        val contextFingerprint: String,
        val sourceLanguage: String = "zh",
        val targetLanguage: String = "vi",
    ) {
        init {
            require(sourceLanguage == "zh" && targetLanguage == "vi") { "Only Chinese to Vietnamese is supported" }
            listOf(filmId, domain, modelVersion, glossaryVersion).forEach { requireLabel(it, 160) }
            require(contextFingerprint.matches(Regex("[a-f0-9]{64}"))) { "A complete dialogue fingerprint is required" }
        }
        private val canonicalJson by lazy { JSONObject().put("film_id", filmId).put("domain", domain)
            .put("model_version", modelVersion).put("glossary_version", glossaryVersion)
            .put("context_fingerprint", contextFingerprint).put("source_language", sourceLanguage)
            .put("target_language", targetLanguage).toString() }
        fun serialized(): String = canonicalJson
        internal val key by lazy { sha256(canonicalJson) }

        companion object {
            fun emptyContextFingerprint(): String = sha256("[]")
            internal fun fromJson(value: JSONObject) = Scope(value.getString("film_id"), value.getString("domain"),
                value.getString("model_version"), value.getString("glossary_version"),
                value.getString("context_fingerprint"), value.getString("source_language"), value.getString("target_language"))
        }
    }

    data class Provenance(val sourceUrl: String, val licenseId: String) {
        init {
            requireLabel(licenseId, 160)
            require(sourceUrl.length in 1..2048 && sourceUrl.none { it.isISOControl() }) { "Invalid provenance" }
            val uri = runCatching { URI(sourceUrl) }.getOrNull()
            require(uri != null && uri.scheme in setOf("https", "user", "model") && uri.userInfo == null) { "Invalid provenance" }
            require(uri.scheme != "https" || !uri.host.isNullOrBlank()) { "Invalid provenance host" }
            val queryNames = runCatching { uri.rawQuery.orEmpty().split('&').map {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()).lowercase()
            } }.getOrElse { throw IllegalArgumentException("Invalid provenance query") }
            require(queryNames.none { it in setOf("key", "api_key", "token", "access_token", "auth", "password") }) {
                "Provenance must not contain authentication parameters"
            }
        }
    }

    data class Entry(
        val scope: Scope,
        val source: String,
        val translation: String,
        val topic: String,
        val provenance: Provenance,
        val confidence: Double,
        val quality: Quality = Quality.DRAFT,
        val importVersion: String = MANUAL,
        val id: Long = 0,
        val usageCount: Long = 0,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
    ) {
        init {
            requireText(source, 2000); requireText(translation, 4000); requireLabel(topic, 160)
            require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid confidence" }
            requireVersion(importVersion)
        }
    }

    data class ImportManifest(
        val version: String,
        val scope: Scope,
        val topic: String,
        val provenance: Provenance,
        // Supplied by the app's explicit import UI, NEVER by a field in the input file.
        val rightsConfirmedByUser: Boolean,
    ) {
        init { requireVersion(version); require(version != MANUAL); requireLabel(topic, 160) }
    }

    data class ImportPreview(val version: String, val inputLines: Int, val uniqueRows: Int, val duplicateRows: Int)
    data class ImportInfo(val version: String, val state: String, val rowCount: Int, val rightsConfirmed: Boolean)
    data class Page(val entries: List<Entry>, val nextId: Long?)
    data class Correction(
        val raw: String, val corrected: String, val confidence: Double, val confirmations: Int,
        val quality: Quality, val provenance: Provenance,
    )

    private data class HotKey(val scopeKey: String, val scopeJson: String, val source: String)
    private val gate = Mutex()
    private var closed = false
    private val hot = object : LinkedHashMap<HotKey, Entry>(hotCapacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HotKey, Entry>?) = size > hotCapacity
    }
    private val helper: Helper

    init {
        require(hotCapacity in 1..128) { "Hot memory is limited to 128 entries" }
        require(databaseName == null || databaseName.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Use an app-owned database name" }
        helper = Helper(context.applicationContext, databaseName)
        if (databaseName != null) helper.setWriteAheadLoggingEnabled(true)
    }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE memory_imports (
                version TEXT PRIMARY KEY, scope_key TEXT NOT NULL, scope_json TEXT NOT NULL,
                state TEXT NOT NULL, rights_confirmed INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE reviewed_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT, scope_key TEXT NOT NULL, scope_json TEXT NOT NULL,
                source_hash TEXT NOT NULL, normalized_source TEXT NOT NULL, source TEXT NOT NULL,
                translation TEXT NOT NULL, topic TEXT NOT NULL, source_url TEXT NOT NULL, license_id TEXT NOT NULL,
                confidence REAL NOT NULL, quality TEXT NOT NULL, import_version TEXT NOT NULL,
                usage_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                UNIQUE(scope_key,scope_json,source_hash,normalized_source,import_version))""")
            db.execSQL("CREATE INDEX reviewed_memory_exact ON reviewed_memory(scope_key,source_hash)")
            db.execSQL("CREATE INDEX reviewed_memory_import ON reviewed_memory(import_version)")
            db.execSQL("""CREATE TABLE reviewed_corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT, scope_key TEXT NOT NULL, scope_json TEXT NOT NULL,
                raw_hash TEXT NOT NULL, normalized_raw TEXT NOT NULL, raw TEXT NOT NULL, corrected TEXT NOT NULL,
                confidence REAL NOT NULL, confirmations INTEGER NOT NULL, quality TEXT NOT NULL,
                source_url TEXT NOT NULL, license_id TEXT NOT NULL, updated_at INTEGER NOT NULL,
                UNIQUE(scope_key,scope_json,raw_hash,normalized_raw))""")
            db.execSQL("CREATE INDEX reviewed_corrections_exact ON reviewed_corrections(scope_key,raw_hash)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("An explicit non-destructive migration is required")
    }

    private suspend fun <T> io(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock { check(!closed) { "Memory is closed" }; block(helper.writableDatabase) }
    }

    /** AI/background output always remains Draft, even if its caller supplied a higher label. */
    suspend fun recordDraft(entry: Entry): Boolean = saveManual(entry.copy(quality = Quality.DRAFT, importVersion = MANUAL))

    /** Explicit human/validation action; never call this automatically after AI inference. */
    suspend fun saveReviewed(entry: Entry, quality: Quality = Quality.USER_APPROVED): Boolean {
        require(quality != Quality.DRAFT) { "Reviewed quality is required" }
        return saveManual(entry.copy(quality = quality, importVersion = MANUAL))
    }

    private suspend fun saveManual(entry: Entry): Boolean = io { db ->
        val existing = manualEntry(db, entry.scope, normalizeSource(entry.source))
        if (existing != null && existing.quality.rank > entry.quality.rank) return@io false
        val now = clock()
        val values = values(entry.copy(createdAt = existing?.createdAt ?: now, updatedAt = now,
            usageCount = existing?.usageCount ?: 0))
        // UPDATE preserves the record id used by paginated exports and usage counters.
        if (existing == null) db.insertOrThrow("reviewed_memory", null, values)
        else db.update("reviewed_memory", values, "id=?", arrayOf(existing.id.toString()))
        invalidate(entry.scope, normalizeSource(entry.source))
        true
    }

    /** Exact hash AND complete normalized source/scope are checked, including on the RAM path. */
    suspend fun lookup(scope: Scope, source: String): Entry? = io { db ->
        requireText(source, 2000)
        val normalized = normalizeSource(source)
        val key = HotKey(scope.key, scope.serialized(), normalized)
        val hit = hot[key] ?: db.rawQuery("""SELECT e.* FROM reviewed_memory e
            LEFT JOIN memory_imports i ON e.import_version=i.version
            WHERE e.scope_key=? AND e.scope_json=? AND e.source_hash=? AND e.normalized_source=?
            AND (e.import_version='$MANUAL' OR i.state='published')
            AND e.quality IN ('VERIFIED','USER_APPROVED') AND e.confidence>=?
            ORDER BY CASE e.quality WHEN 'USER_APPROVED' THEN 2 ELSE 1 END DESC,
                     e.updated_at DESC,e.id DESC LIMIT 1""", arrayOf(scope.key, scope.serialized(), sha256(normalized),
                normalized, MIN_TRANSLATION_CONFIDENCE.toString())).use { cursor ->
            if (cursor.moveToFirst()) readEntry(cursor) else null
        } ?: return@io null
        // Usage changes are also IO work; content timestamps do not change on a hit.
        db.execSQL("UPDATE reviewed_memory SET usage_count=usage_count+1 WHERE id=?", arrayOf(hit.id))
        hit.copy(usageCount = hit.usageCount + 1).also { hot[key] = it }
    }

    suspend fun hotSize(): Int = io { hot.size }

    /**
     * Strict JSONL staging, never publication. Consumes/closes the supplied stream.
     * Scope and rights come from the explicit import manifest; per-record provenance
     * and topic are validated and retained when supplied. File quality labels cannot
     * approve themselves. At most 64 rows are buffered.
     */
    suspend fun stageJsonl(manifest: ImportManifest, input: InputStream, maxRows: Int = 1_000_000,
                          maxBytes: Long = DEFAULT_MAX_IMPORT_BYTES): ImportPreview {
        val boundedInput = ImportByteBudget(input, maxBytes)
        try {
            return withContext(Dispatchers.IO) {
                require(maxRows in 1..1_000_000)
                require(maxBytes in 1..DEFAULT_MAX_IMPORT_BYTES) { "Invalid import byte limit" }
                var createdVersion = false
                var lines = 0; var inserted = 0; var duplicates = 0
                val batch = ArrayList<Entry>(IMPORT_BATCH_SIZE)
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    currentCoroutineContext().ensureActive()
                    io { db ->
                        db.beginTransaction()
                        try {
                            for (entry in batch) {
                                if (db.insertWithOnConflict("reviewed_memory", null, values(entry), SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                                    val normalized = normalizeSource(entry.source)
                                    db.rawQuery("""SELECT translation,confidence,topic,source_url,license_id FROM reviewed_memory
                                        WHERE scope_key=? AND scope_json=? AND source_hash=?
                                        AND normalized_source=? AND import_version=?""",
                                        arrayOf(entry.scope.key, entry.scope.serialized(), sha256(normalized), normalized,
                                            entry.importVersion)).use { existing ->
                                        require(existing.moveToFirst() && existing.getString(0) == entry.translation.trim() &&
                                            existing.getDouble(1) == entry.confidence && existing.getString(2) == entry.topic &&
                                            existing.getString(3) == entry.provenance.sourceUrl &&
                                            existing.getString(4) == entry.provenance.licenseId) { "Conflicting duplicate in import" }
                                    }
                                    duplicates++
                                } else inserted++
                            }
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    }
                    batch.clear()
                }
                try {
                    io { db ->
                        val fields = ContentValues().apply {
                            put("version", manifest.version); put("scope_key", manifest.scope.key)
                            put("scope_json", manifest.scope.serialized()); put("state", "staging")
                            put("rights_confirmed", if (manifest.rightsConfirmedByUser) 1 else 0); put("created_at", clock())
                        }
                        db.insertOrThrow("memory_imports", null, fields) // Never replaces an existing version.
                        // Cancellation at dispatcher return still owns the row created inside this block.
                        createdVersion = true
                    }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    BufferedReader(InputStreamReader(boundedInput, decoder), 8192).use { reader ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = readBoundedLine(reader) ?: break
                            if (line.isBlank()) continue
                            lines++
                            require(lines <= maxRows) { "Import exceeds its record limit" }
                            val json = try { JSONObject(if (lines == 1) line.removePrefix("\uFEFF") else line) } catch (_: Exception) {
                                throw IllegalArgumentException("Invalid JSONL record at line $lines")
                            }
                            val entry = try {
                                require(json.keys().asSequence().all { it in IMPORT_FIELDS })
                                if (json.has("scope")) require(Scope.fromJson(json.getJSONObject("scope")) == manifest.scope)
                                require(json.opt("source") is String && json.opt("translation") is String)
                                if (json.has("model_version")) require(json.opt("model_version") == manifest.scope.modelVersion)
                                val provenance = if (json.has("source_url") || json.has("license_id")) {
                                    require(json.opt("source_url") is String && json.opt("license_id") is String)
                                    Provenance(json.getString("source_url"), json.getString("license_id"))
                                } else manifest.provenance
                                val topic = if (json.has("topic")) {
                                    require(json.opt("topic") is String)
                                    json.getString("topic")
                                } else manifest.topic
                                val confidence = if (json.has("confidence")) {
                                    require(json.opt("confidence") is Number)
                                    json.getDouble("confidence")
                                } else 0.0 // An unscored draft is never eligible for automatic reuse.
                                Entry(manifest.scope, json.getString("source"), json.getString("translation"),
                                    topic, provenance, confidence,
                                    Quality.DRAFT, manifest.version, createdAt = clock(), updatedAt = clock())
                            } catch (_: Exception) { throw IllegalArgumentException("Invalid JSONL fields at line $lines") }
                            batch.add(entry)
                            if (batch.size == IMPORT_BATCH_SIZE) flush()
                        }
                    }
                    flush()
                    require(inserted > 0) { "Import contains no valid records" }
                    io { db ->
                        val changed = db.update("memory_imports", ContentValues().apply { put("state", "staged") },
                            "version=? AND state='staging'", arrayOf(manifest.version))
                        check(changed == 1) { "Import is no longer staging" }
                    }
                    ImportPreview(manifest.version, lines, inserted, duplicates)
                } catch (error: Throwable) {
                    // Failed/cancelled imports remain invisible; no old pack or manual row is deleted.
                    if (createdVersion) withContext(kotlinx.coroutines.NonCancellable) {
                        io { db -> db.execSQL("UPDATE memory_imports SET state='failed' WHERE version=? AND state='staging'",
                            arrayOf(manifest.version)) }
                    }
                    throw error
                }
            }
        } finally {
            // Also close the stream when cancellation prevents entry into Dispatchers.IO.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { boundedInput.close() }
        }
    }

    /** Preview source/translation pages before choosing whether to publish this pack. */
    suspend fun previewImport(version: String, afterId: Long = 0, limit: Int = 64): Page = io { db ->
        requireVersion(version); requirePage(afterId, limit)
        queryPage(db, "e.import_version=? AND e.id>?", arrayOf(version, afterId.toString()), limit)
    }

    /** Atomic visibility flip. A file cannot supply either of these user review decisions. */
    suspend fun publishImport(version: String, reviewedByUser: Boolean = false,
                              rightsConfirmedByUser: Boolean = false): Int = io { db ->
        requireVersion(version); require(version != MANUAL)
        val scopeKey = db.rawQuery("SELECT scope_key,state,rights_confirmed FROM memory_imports WHERE version=?",
            arrayOf(version)).use { cursor ->
            require(cursor.moveToFirst()) { "Unknown import version" }
            require(cursor.getString(1) == "staged") { "Import must be staged before publication" }
            require(cursor.getInt(2) == 1 || rightsConfirmedByUser) { "Usage rights must be explicitly confirmed" }
            cursor.getString(0)
        }
        db.beginTransaction()
        val changed: Int
        try {
            changed = if (reviewedByUser) db.update("reviewed_memory", ContentValues().apply {
                put("quality", Quality.VERIFIED.name)
            }, "import_version=? AND quality=?", arrayOf(version, Quality.DRAFT.name)) else 0
            db.execSQL("UPDATE memory_imports SET state='published',rights_confirmed=1 WHERE version=?", arrayOf(version))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        hot.keys.removeAll { it.scopeKey == scopeKey }
        changed
    }

    /** Rollback changes visibility, not data. A manual approval is independent of its source pack. */
    suspend fun rollbackImport(version: String): Boolean = io { db ->
        requireVersion(version); require(version != MANUAL)
        val scopeKey = db.rawQuery("SELECT scope_key,state FROM memory_imports WHERE version=?", arrayOf(version)).use {
            if (!it.moveToFirst()) return@io false
            require(it.getString(1) in setOf("staged", "published", "rolled_back")) {
                "Only a completed import can be rolled back; cancel staging first"
            }
            it.getString(0)
        }
        db.execSQL("UPDATE memory_imports SET state='rolled_back' WHERE version=?", arrayOf(version))
        hot.keys.removeAll { it.scopeKey == scopeKey }
        true
    }

    /** Explicit cleanup of one failed pack only; reviewed/manual/published data cannot enter this path. */
    suspend fun discardFailedImport(version: String): Int = io { db ->
        requireVersion(version); require(version != MANUAL) { "Manual entries are not an import pack" }
        val state = db.rawQuery("SELECT state FROM memory_imports WHERE version=?", arrayOf(version)).use {
            if (!it.moveToFirst()) return@io 0
            it.getString(0)
        }
        require(state == "failed") { "Only a failed import can be discarded" }
        db.beginTransaction()
        try {
            val removed = db.delete("reviewed_memory", "import_version=?", arrayOf(version))
            check(db.delete("memory_imports", "version=? AND state='failed'", arrayOf(version)) == 1)
            db.setTransactionSuccessful()
            removed
        } finally { db.endTransaction() }
    }

    /** Count raw UTF-8 bytes, including whitespace/BOM, before the decoder can buffer them. */
    private class ImportByteBudget(private val input: InputStream, private val maximum: Long) : InputStream() {
        private var consumed = 0L
        private var closed = false
        override fun read(): Int {
            check(!closed)
            val value = input.read()
            if (value >= 0) {
                consumed++
                require(consumed <= maximum) { "Import exceeds its byte limit" }
            }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            val remaining = maximum - consumed
            // One EOF probe distinguishes an exact-budget stream from an oversized one.
            if (remaining <= 0) return read()
            val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) consumed += count
            return count
        }
        override fun close() { if (!closed) { closed = true; input.close() } }
    }

    /** Read-only UI preview, including Draft; never increments automatic-reuse usage counters. */
    suspend fun previewExact(scope: Scope, source: String): Entry? = io { db ->
        requireText(source, 2000)
        val normalized = normalizeSource(source)
        db.rawQuery("""SELECT e.* FROM reviewed_memory e LEFT JOIN memory_imports i ON e.import_version=i.version
            WHERE e.scope_key=? AND e.scope_json=? AND e.source_hash=? AND e.normalized_source=?
            AND (e.import_version='$MANUAL' OR i.state='published')
            ORDER BY CASE e.quality WHEN 'USER_APPROVED' THEN 2 WHEN 'VERIFIED' THEN 1 ELSE 0 END DESC,
                     e.updated_at DESC,e.id DESC LIMIT 1""",
            arrayOf(scope.key, scope.serialized(), sha256(normalized), normalized)).use {
            if (it.moveToFirst()) readEntry(it) else null
        }
    }

    /** A bounded import history for this exact context; no records or entire corpora are loaded. */
    suspend fun recentImports(scope: Scope, limit: Int = 20): List<ImportInfo> = io { db ->
        require(limit in 1..128)
        db.rawQuery("""SELECT i.version,i.state,i.rights_confirmed,
            (SELECT COUNT(*) FROM reviewed_memory e WHERE e.import_version=i.version) AS row_count
            FROM memory_imports i WHERE i.scope_key=? AND i.scope_json=? ORDER BY i.rowid DESC LIMIT $limit""",
            arrayOf(scope.key, scope.serialized())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ImportInfo(cursor.getString(0), cursor.getString(1),
                cursor.getInt(3), cursor.getInt(2) == 1)) }
        }
    }

    /** A bounded page; callers never have to load a corpus into memory. */
    suspend fun exportPage(scope: Scope, afterId: Long = 0, limit: Int = 64): Page = io { db ->
        requirePage(afterId, limit)
        // Export the effective version of each source, not old pack alternatives which
        // could become the first duplicate when importing the exported stream elsewhere.
        val effective = """NOT EXISTS (SELECT 1 FROM reviewed_memory n
            LEFT JOIN memory_imports ni ON n.import_version=ni.version
            WHERE n.scope_key=e.scope_key AND n.scope_json=e.scope_json AND n.source_hash=e.source_hash
            AND n.normalized_source=e.normalized_source AND (n.import_version='$MANUAL' OR ni.state='published')
            AND ((CASE n.quality WHEN 'USER_APPROVED' THEN 2 WHEN 'VERIFIED' THEN 1 ELSE 0 END) >
                 (CASE e.quality WHEN 'USER_APPROVED' THEN 2 WHEN 'VERIFIED' THEN 1 ELSE 0 END)
              OR (n.quality=e.quality AND (n.updated_at>e.updated_at OR (n.updated_at=e.updated_at AND n.id>e.id)))))"""
        queryPage(db, "e.scope_key=? AND e.scope_json=? AND e.id>? AND " +
            "(e.import_version='$MANUAL' OR i.state='published') AND $effective",
            arrayOf(scope.key, scope.serialized(), afterId.toString()), limit)
    }

    /** Streaming export, finite at the maximum id present when it begins; does not close the writer. */
    suspend fun exportJsonl(scope: Scope, output: Writer, pageSize: Int = 64): Int = withContext(Dispatchers.IO) {
        requirePage(0, pageSize)
        val lastId = io { db -> db.rawQuery("SELECT COALESCE(MAX(id),0) FROM reviewed_memory WHERE scope_key=? AND scope_json=?",
            arrayOf(scope.key, scope.serialized())).use { it.moveToFirst(); it.getLong(0) } }
        var cursor = 0L; var count = 0
        while (cursor < lastId) {
            currentCoroutineContext().ensureActive()
            val page = exportPage(scope, cursor, pageSize)
            if (page.entries.isEmpty()) break
            for (entry in page.entries) {
                if (entry.id > lastId) break
                output.write(toJson(entry).toString()); output.write("\n")
                cursor = entry.id; count++
            }
            if (page.entries.last().id > lastId || page.nextId == null) break
        }
        output.flush()
        count
    }

    /** Low-confidence/model proposals stay Draft and can never overwrite a reviewed correction. */
    suspend fun proposeCorrection(scope: Scope, raw: String, corrected: String, confidence: Double,
                                  provenance: Provenance): Boolean = io { db ->
        validateCorrection(raw, corrected, confidence)
        val prior = correction(db, scope, raw)
        if (prior != null && prior.quality != Quality.DRAFT) return@io false
        saveCorrection(db, scope, raw, corrected, confidence, 0, Quality.DRAFT, provenance)
        true
    }

    /** Each confirmation is an explicit review action; changing the proposed target resets the count. */
    suspend fun confirmCorrection(scope: Scope, raw: String, corrected: String, confidence: Double,
                                  provenance: Provenance): Correction = io { db ->
        validateCorrection(raw, corrected, confidence)
        val prior = correction(db, scope, raw)
        val confirmations = if (prior != null && normalizeSource(prior.corrected) == normalizeSource(corrected))
            prior.confirmations + 1 else 1
        saveCorrection(db, scope, raw, corrected, confidence, confirmations, Quality.USER_APPROVED, provenance)
        checkNotNull(correction(db, scope, raw))
    }

    /** Exact raw/source scope only. No fuzzy or substring replacement is performed. */
    suspend fun lookupCorrection(scope: Scope, raw: String): Correction? = io { db ->
        requireText(raw, 2000)
        correction(db, scope, raw)?.takeIf {
            it.quality != Quality.DRAFT && it.confidence >= MIN_CORRECTION_CONFIDENCE && it.confirmations >= 2
        }
    }

    /** UI-only view of pending confirmations. It must never be used as an automatic correction. */
    suspend fun previewCorrection(scope: Scope, raw: String): Correction? = io { db ->
        requireText(raw, 2000)
        correction(db, scope, raw)
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        gate.withLock { if (!closed) { closed = true; hot.clear(); helper.close() } }
    }

    private fun invalidate(scope: Scope, normalizedSource: String) {
        hot.keys.removeAll { it.scopeKey == scope.key && it.scopeJson == scope.serialized() && it.source == normalizedSource }
    }

    private fun manualEntry(db: SQLiteDatabase, scope: Scope, normalized: String): Entry? =
        db.rawQuery("SELECT * FROM reviewed_memory WHERE scope_key=? AND scope_json=? AND source_hash=? " +
            "AND normalized_source=? AND import_version=?", arrayOf(scope.key, scope.serialized(), sha256(normalized), normalized, MANUAL)).use {
            if (it.moveToFirst()) readEntry(it) else null
        }

    private fun values(entry: Entry): ContentValues = ContentValues().apply {
        put("scope_key", entry.scope.key); put("scope_json", entry.scope.serialized())
        val normalized = normalizeSource(entry.source)
        put("source_hash", sha256(normalized)); put("normalized_source", normalized)
        put("source", entry.source.trim()); put("translation", entry.translation.trim()); put("topic", entry.topic)
        put("source_url", entry.provenance.sourceUrl); put("license_id", entry.provenance.licenseId)
        put("confidence", entry.confidence); put("quality", entry.quality.name); put("import_version", entry.importVersion)
        put("usage_count", entry.usageCount); put("created_at", entry.createdAt); put("updated_at", entry.updatedAt)
    }

    private fun readEntry(cursor: Cursor): Entry = with(cursor) {
        Entry(Scope.fromJson(JSONObject(string("scope_json"))), string("source"), string("translation"), string("topic"),
            Provenance(string("source_url"), string("license_id")), getDouble(getColumnIndexOrThrow("confidence")),
            Quality.valueOf(string("quality")), string("import_version"), getLong(getColumnIndexOrThrow("id")),
            getLong(getColumnIndexOrThrow("usage_count")), getLong(getColumnIndexOrThrow("created_at")), getLong(getColumnIndexOrThrow("updated_at")))
    }

    private fun queryPage(db: SQLiteDatabase, where: String, args: Array<String>, limit: Int): Page {
        val entries = ArrayList<Entry>(limit + 1)
        db.rawQuery("SELECT e.* FROM reviewed_memory e LEFT JOIN memory_imports i ON e.import_version=i.version " +
            "WHERE $where ORDER BY e.id LIMIT ${limit + 1}", args).use { cursor ->
            while (cursor.moveToNext()) entries.add(readEntry(cursor))
        }
        val more = entries.size > limit
        if (more) entries.removeAt(entries.lastIndex)
        return Page(entries, if (more) entries.last().id else null)
    }

    private fun correction(db: SQLiteDatabase, scope: Scope, raw: String): Correction? {
        val normalized = normalizeSource(raw)
        return db.rawQuery("SELECT * FROM reviewed_corrections WHERE scope_key=? AND scope_json=? AND raw_hash=? AND normalized_raw=?",
            arrayOf(scope.key, scope.serialized(), sha256(normalized), normalized)).use { cursor ->
            if (!cursor.moveToFirst()) null else Correction(cursor.string("raw"), cursor.string("corrected"),
                cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")), cursor.getInt(cursor.getColumnIndexOrThrow("confirmations")),
                Quality.valueOf(cursor.string("quality")), Provenance(cursor.string("source_url"), cursor.string("license_id")))
        }
    }

    private fun saveCorrection(db: SQLiteDatabase, scope: Scope, raw: String, corrected: String, confidence: Double,
                               confirmations: Int, quality: Quality, provenance: Provenance) {
        db.insertWithOnConflict("reviewed_corrections", null, ContentValues().apply {
            put("scope_key", scope.key); put("scope_json", scope.serialized()); put("raw_hash", sha256(normalizeSource(raw)))
            put("normalized_raw", normalizeSource(raw)); put("raw", raw.trim()); put("corrected", corrected.trim())
            put("confidence", confidence); put("confirmations", confirmations); put("quality", quality.name)
            put("source_url", provenance.sourceUrl); put("license_id", provenance.licenseId); put("updated_at", clock())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 256L * 1024 * 1024
        private const val MANUAL = "manual"
        private const val MIN_TRANSLATION_CONFIDENCE = .98
        private const val MIN_CORRECTION_CONFIDENCE = .99
        private const val IMPORT_BATCH_SIZE = 64
        private const val MAX_JSONL_CHARS = 32768
        private val IMPORT_FIELDS = setOf("source", "translation", "confidence", "scope", "quality", "topic", "source_url",
            "license_id", "model_version", "import_version", "usage_count", "created_at", "updated_at")
        fun normalizeSource(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        private fun sha256(text: String): String = Sha256Hex.utf8(text)
        private fun requireLabel(text: String, max: Int) {
            require(text.isNotBlank() && text.length <= max && text.none { it.isISOControl() }) { "Invalid metadata label" }
        }
        private fun requireText(text: String, max: Int) {
            require(text.isNotBlank() && text.length <= max && '\u0000' !in text) { "Invalid memory text" }
            require(normalizeSource(text).isNotEmpty()) { "Invalid normalized source" }
        }
        private fun requireVersion(version: String) { require(version.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Invalid import version" } }
        private fun requirePage(afterId: Long, limit: Int) { require(afterId >= 0 && limit in 1..128) { "Invalid page bounds" } }
        private fun validateCorrection(raw: String, corrected: String, confidence: Double) {
            requireText(raw, 2000); requireText(corrected, 2000)
            require(normalizeSource(raw) != normalizeSource(corrected)) { "A correction must change the source" }
            require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid correction confidence" }
        }
        private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
        private fun readBoundedLine(reader: BufferedReader): String? {
            val line = StringBuilder()
            while (true) {
                val char = reader.read()
                if (char < 0) return if (line.isEmpty()) null else line.toString().trimEnd('\r')
                if (char == '\n'.code) return line.toString().trimEnd('\r')
                require(line.length < MAX_JSONL_CHARS) { "JSONL record exceeds its size limit" }
                line.append(char.toChar())
            }
        }
        private fun toJson(entry: Entry): JSONObject = JSONObject().put("source", entry.source).put("translation", entry.translation)
            .put("scope", JSONObject(entry.scope.serialized())).put("quality", entry.quality.name).put("confidence", entry.confidence)
            .put("topic", entry.topic).put("source_url", entry.provenance.sourceUrl).put("license_id", entry.provenance.licenseId)
            .put("model_version", entry.scope.modelVersion).put("import_version", entry.importVersion).put("usage_count", entry.usageCount)
            .put("created_at", entry.createdAt).put("updated_at", entry.updatedAt)
    }
}
