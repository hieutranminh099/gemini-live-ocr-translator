package com.gemini.ocr.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gemini.ocr.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Category classification for entries in the Chinese Subtitle SQLite Bank (`subtitle_bank`).
 */
object BankCategory {
    const val UI = "UI"
    const val PRONOUN = "PRONOUN"
    const val DIALOGUE = "DIALOGUE"

    fun displayName(category: String): String = when (category.uppercase()) {
        UI -> "UI / Thuật ngữ"
        PRONOUN -> "Danh xưng"
        else -> "Câu thoại phim / truyện"
    }
}

/**
 * Represents a single record in the local SQLite Chinese Subtitle Bank (`subtitle_bank`).
 */
data class TranslationBankRecord(
    val sourceText: String,
    val translatedText: String,
    val category: String,
    val commonOcrErrors: String = "",
    val usageFrequency: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val normalizedHash: Long = ChineseDialogueBank.computeNormalizedHash(
        ChineseDialogueBank.normalizeSubtitleKey(sourceText)
    )
)

/**
 * Anti-OOM SQLite Chinese Subtitle Bank (`subtitle_bank`) with B-Tree index `idx_zh_hash`
 * and a bounded 300-entry RAM LruCache (< 2MB RAM).
 *
 * Architectural guarantees:
 * 1. NEVER loads the entire subtitle corpus into a large `HashMap` or `List` in RAM at startup.
 * 2. Stores the subtitle bank in SQLite (`subtitle_bank`) indexed by B-Tree:
 *    `CREATE INDEX IF NOT EXISTS idx_zh_hash ON subtitle_bank(normalized_hash)`
 * 3. Keeps at most `MAX_RAM_LRU_ENTRIES = 300` recently used records in RAM (< 2MB).
 * 4. Performs initial asset seeding and SQLite queries on `Dispatchers.IO`.
 */
object SelfLearningDatabase {
    private const val DB_NAME = "gemini_ocr_self_learning.db"
    private const val DB_VERSION = 2
    const val TABLE_SUBTITLE_BANK = "subtitle_bank"

    const val MAX_RAM_LRU_ENTRIES = 300

    // Strictly bounded 300-entry LRU cache in RAM (< 2MB)
    private val ramRecords = object : LinkedHashMap<String, TranslationBankRecord>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationBankRecord>?): Boolean =
            size > MAX_RAM_LRU_ENTRIES
    }

    // Maps an OCR-misread variant directly to its canonical normalized key (bounded)
    private val variantToCanonical = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_RAM_LRU_ENTRIES * 2
    }

    // Substring replacement pairs (wrongOcrSubstring -> correctedSubstring)
    private val confusionSubstringRules = LinkedHashMap<String, String>()

    @Volatile
    private var dbHelper: BankSqliteHelper? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var assetSeededInSqlite = false

    private class BankSqliteHelper(context: Context) :
        SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SUBTITLE_BANK (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    normalized_hash INTEGER NOT NULL,
                    normalized_zh TEXT NOT NULL UNIQUE,
                    source_text TEXT NOT NULL,
                    translated_text TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'DIALOGUE',
                    common_ocr_errors TEXT NOT NULL DEFAULT '',
                    usage_frequency INTEGER NOT NULL DEFAULT 1,
                    is_user_learned INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            // Mandatory B-Tree index on normalized_hash for < 5ms indexed lookup
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_zh_hash ON $TABLE_SUBTITLE_BANK(normalized_hash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_freq ON $TABLE_SUBTITLE_BANK(usage_frequency DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    // Preloaded Chinese OCR confusion pairs commonly encountered on phone & BlueStacks
    private val defaultConfusionPairs: List<Pair<String, String>> = listOf(
        "己经" to "已经",
        "自已" to "自己",
        "进人" to "进入",
        "大好了" to "太好了",
        "大慢了" to "太慢了",
        "大强了" to "太强了",
        "木有" to "没有",
        "来不乃" to "来不及",
        "不叮能" to "不可能",
        "师兄I" to "师兄！",
        "小心I" to "小心！",
        "找死I" to "找死！",
        "快走I" to "快走！",
        "住手I" to "住手！",
        "L0ADING" to "LOADING",
        "LeveI Up" to "Level Up",
        "LEVEI UP" to "LEVEL UP",
        "S7ART" to "START",
        "C0NFIRM" to "CONFIRM",
        "INVENT0RY" to "INVENTORY"
    )

    // Compact hot seed records (< 15 records) for OCR error correction and immediate inspection
    private val seedRecords: List<TranslationBankRecord> = listOf(
        TranslationBankRecord(
            sourceText = "师兄，小心！",
            translatedText = "Sư huynh, cẩn thận!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "师兄,小心I|帅兄，小心！|师兄，小心!",
            usageFrequency = 15
        ),
        TranslationBankRecord(
            sourceText = "快走，来不及了！",
            translatedText = "Mau đi thôi, không kịp nữa rồi!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "快走，来不乃了！|快走,来不及了I",
            usageFrequency = 12
        ),
        TranslationBankRecord(
            sourceText = "这不可能！",
            translatedText = "Chuyện này không thể nào!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "這不可能！|这不叮能！|这不可能I",
            usageFrequency = 14
        ),
        TranslationBankRecord(
            sourceText = "等一下，我马上来",
            translatedText = "Đợi một chút, tôi đến ngay",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "等ー下，我马上来|等一下,我马上来",
            usageFrequency = 9
        ),
        TranslationBankRecord(
            sourceText = "师尊",
            translatedText = "Sư tôn",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "帅尊|师樽",
            usageFrequency = 20
        ),
        TranslationBankRecord(
            sourceText = "小师妹",
            translatedText = "Tiểu sư muội",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "小帅妹",
            usageFrequency = 11
        ),
        TranslationBankRecord(
            sourceText = "陛下",
            translatedText = "Bệ hạ",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "陛卞",
            usageFrequency = 10
        ),
        TranslationBankRecord(
            sourceText = "道友请留步",
            translatedText = "Đạo hữu xin dừng bước",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "道友請留步|道友请留歩",
            usageFrequency = 8
        ),
        TranslationBankRecord(
            sourceText = "Level Up",
            translatedText = "Tăng cấp",
            category = BankCategory.UI,
            commonOcrErrors = "LeveI Up|LEVEI UP|Level up!",
            usageFrequency = 18
        ),
        TranslationBankRecord(
            sourceText = "Inventory",
            translatedText = "Túi đồ",
            category = BankCategory.UI,
            commonOcrErrors = "INVENT0RY|lnventory",
            usageFrequency = 16
        ),
        TranslationBankRecord(
            sourceText = "Settings",
            translatedText = "Cài đặt",
            category = BankCategory.UI,
            commonOcrErrors = "Sett1ngs|[Settings]",
            usageFrequency = 13
        ),
        TranslationBankRecord(
            sourceText = "任务完成",
            translatedText = "Hoàn thành nhiệm vụ",
            category = BankCategory.UI,
            commonOcrErrors = "任務完成|任劳完成",
            usageFrequency = 14
        )
    )

    private val pronounMarkers = setOf(
        "师兄", "师姐", "师父", "师尊", "师妹", "师弟", "小师妹", "大师兄",
        "陛下", "殿下", "前辈", "道友", "宗主", "长老", "公子", "小姐",
        "夫人", "少爷", "姑娘", "大人", "老祖", "仙子", "阁下", "朕", "寡人", "本座", "本王"
    )

    private val uiKeywords = setOf(
        "start", "settings", "options", "inventory", "equipment", "level up", "level",
        "skill", "quest", "confirm", "cancel", "reward", "claim", "shop", "map", "status",
        "loading", "开始", "设置", "背包", "装备", "升级", "技能", "任务", "确认", "取消", "奖励", "商店"
    )

    /**
     * Initializes SQLite `subtitle_bank` and seeds from `assets/zh_vi_subtitles.tsv` on `Dispatchers.IO`.
     * Never loads the full subtitle bank into RAM.
     */
    suspend fun initializeOnIo(context: Context?) = withContext(Dispatchers.IO) {
        ensureInitialized(context)
    }

    @Synchronized
    fun ensureInitialized(context: Context? = null) {
        if (!initialized) {
            confusionSubstringRules.clear()
            for ((wrong, right) in defaultConfusionPairs) {
                confusionSubstringRules[wrong] = right
            }
            for (seed in seedRecords) {
                putInRam(seed)
            }
            initialized = true
        }
        if (context != null) {
            try {
                val helper = dbHelper ?: BankSqliteHelper(context).also { dbHelper = it }
                if (!assetSeededInSqlite) {
                    val db = helper.writableDatabase
                    seedSqliteFromAssetIfNeeded(context, db)
                    assetSeededInSqlite = true
                    loadTopRecordsFromSqlite(db, limit = 60)
                    SessionState.bankSize.value = querySqliteCount(db).coerceAtLeast(ramRecords.size)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun seedSqliteFromAssetIfNeeded(context: Context, db: SQLiteDatabase) {
        try {
            val currentCount = querySqliteCount(db)
            if (currentCount >= 200) return
            db.beginTransaction()
            try {
                for (seed in seedRecords) {
                    upsertSqlite(db, seed, isUserLearned = false)
                }
                ChineseDialogueBank.forEachAssetSubtitle(context) { zh, vi, normKey, normHash ->
                    val values = ContentValues().apply {
                        put("normalized_hash", normHash)
                        put("normalized_zh", normKey)
                        put("source_text", zh)
                        put("translated_text", vi)
                        put("category", classifyCategory(zh, vi))
                        put("common_ocr_errors", "")
                        put("usage_frequency", 1)
                        put("is_user_learned", 0)
                        put("updated_at", 0L)
                    }
                    db.insertWithOnConflict(
                        TABLE_SUBTITLE_BANK,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun loadTopRecordsFromSqlite(db: SQLiteDatabase, limit: Int) {
        try {
            val safeLimit = limit.coerceIn(1, MAX_RAM_LRU_ENTRIES)
            db.rawQuery(
                """
                SELECT source_text, translated_text, category, common_ocr_errors, usage_frequency, updated_at, normalized_hash
                FROM $TABLE_SUBTITLE_BANK
                ORDER BY is_user_learned DESC, usage_frequency DESC
                LIMIT $safeLimit
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = TranslationBankRecord(
                        sourceText = cursor.getString(0),
                        translatedText = cursor.getString(1),
                        category = cursor.getString(2),
                        commonOcrErrors = cursor.getString(3) ?: "",
                        usageFrequency = cursor.getInt(4),
                        updatedAt = cursor.getLong(5),
                        normalizedHash = cursor.getLong(6)
                    )
                    putInRam(record)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun querySqliteCount(db: SQLiteDatabase): Int {
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_SUBTITLE_BANK", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun upsertSqlite(db: SQLiteDatabase, record: TranslationBankRecord, isUserLearned: Boolean = true) {
        try {
            val normZh = ChineseDialogueBank.normalizeSubtitleKey(record.sourceText)
                .ifEmpty { normalizeKey(record.sourceText).lowercase() }
            val normHash = ChineseDialogueBank.computeNormalizedHash(normZh)
            val values = ContentValues().apply {
                put("normalized_hash", normHash)
                put("normalized_zh", normZh)
                put("source_text", record.sourceText)
                put("translated_text", record.translatedText)
                put("category", record.category)
                put("common_ocr_errors", record.commonOcrErrors)
                put("usage_frequency", record.usageFrequency)
                put("is_user_learned", if (isUserLearned) 1 else 0)
                put("updated_at", record.updatedAt)
            }
            db.insertWithOnConflict(TABLE_SUBTITLE_BANK, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {
        }
    }

    /**
     * Queries the subtitle bank by B-Tree indexed `normalized_hash` and `normalized_zh`.
     * Never loads the full asset file into RAM; caches only hits into the 300-entry LRU.
     */
    @Synchronized
    fun queryByNormalizedHash(
        context: Context?,
        hash: Long,
        normalizedZh: String
    ): String? {
        if (normalizedZh.isEmpty()) return null
        ensureInitialized(context)

        // 1. Check 300-entry RAM LRU first
        ramRecords[normalizedZh]?.let { return it.translatedText }

        // 2. Query SQLite B-Tree index `idx_zh_hash` when SQLite helper is available
        val helper = dbHelper
        if (helper != null) {
            try {
                helper.readableDatabase.rawQuery(
                    """
                    SELECT source_text, translated_text, category, common_ocr_errors, usage_frequency, updated_at
                    FROM $TABLE_SUBTITLE_BANK
                    WHERE normalized_hash = ? AND normalized_zh = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(hash.toString(), normalizedZh)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val record = TranslationBankRecord(
                            sourceText = cursor.getString(0),
                            translatedText = cursor.getString(1),
                            category = cursor.getString(2),
                            commonOcrErrors = cursor.getString(3) ?: "",
                            usageFrequency = cursor.getInt(4),
                            updatedAt = cursor.getLong(5),
                            normalizedHash = hash
                        )
                        putInRam(record)
                        return record.translatedText
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback for pure JVM tests (where Context/SQLite is null): stream asset line-by-line
        //    without keeping a full map in RAM, and insert only the matched item into the 300-entry LRU.
        var matchedSource = ""
        var matchedVi: String? = null
        ChineseDialogueBank.forEachAssetSubtitle(context) { zh, vi, normKey, normHash ->
            if (matchedVi == null && normHash == hash && normKey == normalizedZh) {
                matchedSource = zh
                matchedVi = vi
            }
        }
        val foundVi = matchedVi ?: return null
        val record = TranslationBankRecord(
            sourceText = matchedSource.ifEmpty { normalizedZh },
            translatedText = foundVi,
            category = classifyCategory(matchedSource, foundVi),
            normalizedHash = hash
        )
        putInRam(record)
        return foundVi
    }

    @Synchronized
    private fun putInRam(record: TranslationBankRecord) {
        val exactKey = normalizeKey(record.sourceText)
        if (exactKey.isEmpty()) return
        ramRecords[exactKey] = record
        val normSubKey = ChineseDialogueBank.normalizeSubtitleKey(record.sourceText)
        if (normSubKey.isNotEmpty() && normSubKey != exactKey) {
            ramRecords[normSubKey] = record
        }
        indexOcrErrors(exactKey, record.commonOcrErrors)
    }

    @Synchronized
    private fun indexOcrErrors(canonicalKey: String, rawErrors: String) {
        if (rawErrors.isBlank()) return
        val tokens = rawErrors.split(Regex("[|,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (token in tokens) {
            val arrowIdx = token.indexOf("->").takeIf { it > 0 } ?: token.indexOf("=>").takeIf { it > 0 }
            if (arrowIdx != null) {
                val wrong = token.substring(0, arrowIdx).trim()
                val right = token.substring(arrowIdx + 2).trim()
                if (wrong.isNotEmpty() && right.isNotEmpty()) {
                    confusionSubstringRules[wrong] = right
                }
            } else {
                val variantKey = normalizeKey(token)
                if (variantKey.isNotEmpty()) {
                    variantToCanonical[variantKey] = canonicalKey
                }
            }
        }
    }

    fun normalizeKey(text: String): String =
        text.trim()
            .trim { it == '|' || it == '/' || it == '\\' || it == '_' || it == '`' }
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Pre-processes and auto-corrects common OCR mistakes before lookup or translation.
     */
    @Synchronized
    fun preprocessAndCorrectOcr(rawText: String, context: Context? = null): String {
        ensureInitialized(context)
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        text = text.trim { it == '|' || it == '/' || it == '\\' || it == '_' || it == '`' }.trim()
        if (text.isEmpty()) return ""

        var corrected = text
        for ((wrong, right) in confusionSubstringRules) {
            if (corrected.contains(wrong)) {
                corrected = corrected.replace(wrong, right)
            }
        }

        val key = normalizeKey(corrected)
        val canonicalKey = variantToCanonical[key]
        if (canonicalKey != null) {
            ramRecords[canonicalKey]?.let { canonicalRecord ->
                corrected = canonicalRecord.sourceText
            }
        }

        if (corrected != rawText.trim()) {
            SessionState.ocrCorrectedCount.value++
        }
        return corrected
    }

    /**
     * Automatically classifies a phrase into UI, PRONOUN, or DIALOGUE.
     */
    fun classifyCategory(sourceText: String, translatedText: String = ""): String {
        val cleaned = normalizeKey(sourceText)
        val lower = cleaned.lowercase()
        if (CostOptimizationEngine.matchOfflineUiTerm(cleaned) != null ||
            uiKeywords.any { lower == it || lower.startsWith("$it ") || cleaned.contains(it, ignoreCase = true) && cleaned.length <= 12 }
        ) {
            return BankCategory.UI
        }
        val simplified = ChineseDialogueBank.normalizeTraditional(cleaned)
        if (cleaned.length <= 8 && pronounMarkers.any { simplified.contains(it) }) {
            return BankCategory.PRONOUN
        }
        val lowerVi = translatedText.lowercase()
        if (cleaned.length <= 8 && listOf(
                "sư huynh", "sư tỷ", "sư phụ", "sư tôn", "tiểu sư muội",
                "bệ hạ", "điện hạ", "tiền bối", "đạo hữu", "công tử", "tiểu thư"
            ).any { lowerVi == it }
        ) {
            return BankCategory.PRONOUN
        }
        return BankCategory.DIALOGUE
    }

    fun inferDefaultOcrErrors(sourceText: String, rawOcrBeforeCorrection: String = ""): String {
        val variants = linkedSetOf<String>()
        val cleaned = normalizeKey(sourceText)
        if (rawOcrBeforeCorrection.isNotBlank() && normalizeKey(rawOcrBeforeCorrection) != cleaned) {
            variants.add(normalizeKey(rawOcrBeforeCorrection))
        }
        val simplified = ChineseDialogueBank.normalizeTraditional(cleaned)
        if (simplified != cleaned) {
            variants.add(simplified)
        }
        if (cleaned.endsWith("！") || cleaned.endsWith("!")) {
            variants.add(cleaned.dropLast(1) + "I")
        }
        return variants.joinToString("|")
    }

    /**
     * Looks up a phrase in the Self-Learning SQLite Bank (< 5ms via 300-entry RAM LRU + B-Tree SQLite index).
     */
    @Synchronized
    fun lookup(
        context: Context?,
        rawSourceText: String,
        incrementFrequency: Boolean = true
    ): TranslationBankRecord? {
        ensureInitialized(context)
        val corrected = preprocessAndCorrectOcr(rawSourceText, context)
        val key = normalizeKey(corrected)
        if (key.isEmpty()) return null

        val simplifiedKey = normalizeKey(ChineseDialogueBank.normalizeTraditional(corrected))
        val normSubKey = ChineseDialogueBank.normalizeSubtitleKey(corrected)
        val matchedKey = when {
            ramRecords.containsKey(key) -> key
            ramRecords.containsKey(simplifiedKey) -> simplifiedKey
            normSubKey.isNotEmpty() && ramRecords.containsKey(normSubKey) -> normSubKey
            variantToCanonical.containsKey(key) -> variantToCanonical[key]
            variantToCanonical.containsKey(simplifiedKey) -> variantToCanonical[simplifiedKey]
            else -> null
        }

        var existing = if (matchedKey != null) ramRecords[matchedKey] else null
        if (existing == null && normSubKey.isNotEmpty()) {
            val hash = ChineseDialogueBank.computeNormalizedHash(normSubKey)
            val sqlHit = queryByNormalizedHash(context, hash, normSubKey)
            if (sqlHit != null) {
                existing = ramRecords[normSubKey] ?: TranslationBankRecord(
                    sourceText = corrected,
                    translatedText = sqlHit,
                    category = classifyCategory(corrected, sqlHit),
                    normalizedHash = hash
                ).also { putInRam(it) }
            }
        }
        if (existing == null) return null
        if (!incrementFrequency) return existing

        val updated = existing.copy(
            usageFrequency = existing.usageFrequency + 1,
            updatedAt = System.currentTimeMillis()
        )
        putInRam(updated)
        dbHelper?.let { helper ->
            try {
                upsertSqlite(helper.writableDatabase, updated, isUserLearned = true)
            } catch (_: Exception) {
            }
        }
        return updated
    }

    /**
     * Saves or updates a translation learned from Gemini (Self-Learning Cache) or added by the user.
     */
    @Synchronized
    fun learnOrUpsert(
        context: Context?,
        sourceText: String,
        translatedText: String,
        category: String? = null,
        commonOcrErrors: String? = null,
        rawOcrBeforeCorrection: String = ""
    ): TranslationBankRecord {
        ensureInitialized(context)
        val cleanSource = normalizeKey(sourceText)
        val cleanTarget = translatedText.trim()
        val existing = ramRecords[cleanSource]

        val resolvedCategory = category?.takeIf { it.isNotBlank() }
            ?: existing?.category
            ?: classifyCategory(cleanSource, cleanTarget)

        val inferredErrors = commonOcrErrors
            ?: existing?.commonOcrErrors?.takeIf { it.isNotBlank() }
            ?: inferDefaultOcrErrors(cleanSource, rawOcrBeforeCorrection)

        val normKey = ChineseDialogueBank.normalizeSubtitleKey(cleanSource)
        val record = TranslationBankRecord(
            sourceText = cleanSource,
            translatedText = cleanTarget,
            category = resolvedCategory,
            commonOcrErrors = inferredErrors,
            usageFrequency = (existing?.usageFrequency ?: 0) + 1,
            updatedAt = System.currentTimeMillis(),
            normalizedHash = ChineseDialogueBank.computeNormalizedHash(normKey.ifEmpty { cleanSource })
        )
        putInRam(record)
        if (normKey.isNotEmpty()) {
            ChineseDialogueBank.putInRamLru(normKey, cleanTarget)
        }
        dbHelper?.let { helper ->
            try {
                val db = helper.writableDatabase
                upsertSqlite(db, record, isUserLearned = true)
                SessionState.bankSize.value = querySqliteCount(db).coerceAtLeast(ramRecords.size)
            } catch (_: Exception) {
            }
        } ?: run {
            SessionState.bankSize.value = ramRecords.size
        }
        return record
    }

    /**
     * Suspend helper to insert learned translations on `Dispatchers.IO`.
     */
    suspend fun learnOrUpsertOnIo(
        context: Context?,
        sourceText: String,
        translatedText: String,
        rawOcrBeforeCorrection: String = ""
    ): TranslationBankRecord = withContext(Dispatchers.IO) {
        learnOrUpsert(
            context = context,
            sourceText = sourceText,
            translatedText = translatedText,
            rawOcrBeforeCorrection = rawOcrBeforeCorrection
        )
    }

    @Synchronized
    fun delete(context: Context?, sourceText: String) {
        ensureInitialized(context)
        val key = normalizeKey(sourceText)
        val normKey = ChineseDialogueBank.normalizeSubtitleKey(sourceText)
        val removed = ramRecords.remove(key)
        if (normKey.isNotEmpty()) ramRecords.remove(normKey)
        if (removed != null) {
            variantToCanonical.entries.removeIf { it.value == key }
        }
        dbHelper?.let { helper ->
            try {
                val db = helper.writableDatabase
                db.delete(
                    TABLE_SUBTITLE_BANK,
                    "source_text = ? OR normalized_zh = ?",
                    arrayOf(key, normKey)
                )
                SessionState.bankSize.value = querySqliteCount(db).coerceAtLeast(ramRecords.size)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Clears user-learned entries and re-seeds the default Chinese subtitle bank from assets.
     */
    @Synchronized
    fun clearLearnedAndRestoreDefaults(context: Context?) {
        ramRecords.clear()
        variantToCanonical.clear()
        ChineseDialogueBank.clearRamLru()
        initialized = false
        assetSeededInSqlite = false
        dbHelper?.let { helper ->
            try {
                val db = helper.writableDatabase
                db.delete(TABLE_SUBTITLE_BANK, null, null)
            } catch (_: Exception) {
            }
        }
        ensureInitialized(context)
    }

    @Synchronized
    fun getAllRecords(
        context: Context? = null,
        categoryFilter: String = "ALL",
        searchQuery: String = ""
    ): List<TranslationBankRecord> {
        ensureInitialized(context)
        val query = searchQuery.trim().lowercase()
        val helper = dbHelper
        if (helper != null) {
            try {
                val list = ArrayList<TranslationBankRecord>(60)
                val whereClauses = mutableListOf<String>()
                val args = mutableListOf<String>()
                if (categoryFilter != "ALL") {
                    whereClauses.add("UPPER(category) = ?")
                    args.add(categoryFilter.uppercase())
                }
                if (query.isNotEmpty()) {
                    whereClauses.add("(LOWER(source_text) LIKE ? OR LOWER(translated_text) LIKE ? OR LOWER(common_ocr_errors) LIKE ?)")
                    val pattern = "%$query%"
                    args.add(pattern)
                    args.add(pattern)
                    args.add(pattern)
                }
                val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
                helper.readableDatabase.rawQuery(
                    """
                    SELECT source_text, translated_text, category, common_ocr_errors, usage_frequency, updated_at, normalized_hash
                    FROM $TABLE_SUBTITLE_BANK
                    $whereSql
                    ORDER BY is_user_learned DESC, usage_frequency DESC, updated_at DESC
                    LIMIT 60
                    """.trimIndent(),
                    args.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        list.add(
                            TranslationBankRecord(
                                sourceText = cursor.getString(0),
                                translatedText = cursor.getString(1),
                                category = cursor.getString(2),
                                commonOcrErrors = cursor.getString(3) ?: "",
                                usageFrequency = cursor.getInt(4),
                                updatedAt = cursor.getLong(5),
                                normalizedHash = cursor.getLong(6)
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }

        // Deduplicate by sourceText from the bounded 300-entry RAM LRU
        return ramRecords.values
            .distinctBy { it.sourceText }
            .filter { record ->
                (categoryFilter == "ALL" || record.category.equals(categoryFilter, ignoreCase = true)) &&
                    (query.isEmpty() ||
                        record.sourceText.lowercase().contains(query) ||
                        record.translatedText.lowercase().contains(query) ||
                        record.commonOcrErrors.lowercase().contains(query))
            }
            .sortedWith(compareByDescending<TranslationBankRecord> { it.usageFrequency }.thenByDescending { it.updatedAt })
    }

    @Synchronized
    fun getSnapshotMap(context: Context? = null): Map<String, String> {
        ensureInitialized(context)
        val map = LinkedHashMap<String, String>(ramRecords.size)
        for ((_, v) in ramRecords) {
            map[v.sourceText] = v.translatedText
        }
        return map
    }

    @Synchronized
    fun ramLruSize(): Int = ramRecords.size

    @Synchronized
    fun size(context: Context? = null): Int {
        ensureInitialized(context)
        val total = dbHelper?.let { querySqliteCount(it.readableDatabase) }?.takeIf { it > 0 }
            ?: ramRecords.values.distinctBy { it.sourceText }.size
        SessionState.bankSize.value = total
        return total
    }

    @Synchronized
    fun resetForTest() {
        ramRecords.clear()
        variantToCanonical.clear()
        confusionSubstringRules.clear()
        ChineseDialogueBank.clearRamLru()
        initialized = false
        ensureInitialized(null)
    }
}
