package com.gemini.ocr.network

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Anti-OOM Chinese (Simplified & Traditional) Subtitle & Dialogue Matching Engine (ZH -> VI).
 *
 * Strictly complies with the Anti-OOM Architecture:
 * - FORBIDS loading the entire subtitle corpus into a large RAM HashMap/List on startup.
 * - Keeps at most [MAX_LRU_ENTRIES] (300) recently used phrases in RAM (< 2MB).
 * - Queries the B-Tree indexed SQLite table `subtitle_bank(normalized_hash)` (`idx_zh_hash`)
 *   via [SelfLearningDatabase] on `Dispatchers.IO`, with a lightweight file-offset index fallback
 *   for pure JVM unit tests where Android SQLite is not attached.
 */
object ChineseDialogueBank {

    const val MAX_LRU_ENTRIES = 300
    const val MAX_RAM_LRU_SIZE = MAX_LRU_ENTRIES
    const val ASSET_FILE_NAME = "zh_vi_subtitles.tsv"

    /**
     * Bounded RAM LRU cache capped strictly at 300 entries (< 2MB RAM).
     */
    private val ramLru300 = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_LRU_ENTRIES
    }

    /**
     * Compact Traditional -> Simplified character normalization table (64 characters, < 2KB).
     */
    private val traditionalToSimplified: Map<Char, Char> = mapOf(
        '麼' to '么', '們' to '们', '這' to '这', '個' to '个', '來' to '来', '時' to '时',
        '會' to '会', '過' to '过', '對' to '对', '開' to '开', '關' to '关', '見' to '见',
        '說' to '说', '話' to '话', '請' to '请', '謝' to '谢', '師' to '师', '門' to '门',
        '長' to '长', '輩' to '辈', '氣' to '气', '實' to '实', '體' to '体', '發' to '发',
        '現' to '现', '點' to '点', '頭' to '头', '臉' to '脸', '樣' to '样', '種' to '种',
        '東' to '东', '車' to '车', '馬' to '马', '鳥' to '鸟', '龍' to '龙', '劍' to '剑',
        '殺' to '杀', '進' to '进', '還' to '还', '沒' to '没', '別' to '别', '動' to '动',
        '聽' to '听', '讓' to '让', '給' to '给', '誰' to '谁', '為' to '为', '嗎' to '吗',
        '萬' to '万', '與' to '与', '無' to '无', '風' to '风', '雲' to '云', '電' to '电',
        '靈' to '灵', '陣' to '阵', '煉' to '炼', '藥' to '药', '獸' to '兽', '聖' to '圣',
        '戰' to '战', '鬥' to '斗', '勝' to '胜', '敗' to '败', '護' to '护', '駕' to '驾',
        '滾' to '滚', '閉' to '闭', '饒' to '饶', '幫' to '帮', '歡' to '欢', '愛' to '爱',
        '親' to '亲', '錯' to '错', '難' to '难', '題' to '题', '確' to '确', '認' to '认',
        '繼' to '继', '續' to '续', '結' to '结', '選' to '选', '擇' to '择', '夠' to '够',
        '趕' to '赶', '緊' to '紧', '離' to '离', '帶' to '带', '問' to '问', '覺' to '觉',
        '應' to '应', '該' to '该', '經' to '经', '從' to '从', '後' to '后', '裡' to '里',
        '邊' to '边', '處' to '处', '變' to '变', '驚' to '惊', '訝' to '讶', '惡' to '恶',
        '總' to '总', '統' to '统', '領' to '领', '將' to '将', '軍' to '军', '閣' to '阁',
        '樓' to '楼', '宮' to '宫', '國' to '国', '黃' to '黄', '陰' to '阴', '陽' to '阳',
        '巔' to '巅', '極' to '极', '滅' to '灭', '絕' to '绝', '頂' to '顶', '級' to '级',
        '階' to '阶', '層' to '层', '強' to '强', '負' to '负', '輸' to '输', '贏' to '赢',
        '買' to '买', '賣' to '卖', '錢' to '钱', '財' to '财', '寶' to '宝', '貝' to '贝',
        '儲' to '储', '訣' to '诀', '術' to '术'
    )

    // Compact Sino-Vietnamese terminology fixes for post-processing (< 25 pairs)
    private val terminologyFixes: List<Pair<String, String>> = listOf(
        "师尊" to "Sư tôn",
        "师父" to "Sư phụ",
        "大师兄" to "Đại sư huynh",
        "师兄" to "Sư huynh",
        "师姐" to "Sư tỷ",
        "小师妹" to "Tiểu sư muội",
        "师妹" to "Sư muội",
        "师弟" to "Sư đệ",
        "掌门" to "Chưởng môn",
        "太上长老" to "Thái thượng trưởng lão",
        "大长老" to "Đại trưởng lão",
        "长老" to "Trưởng lão",
        "宗主" to "Tông chủ",
        "储物戒" to "nhẫn trữ vật",
        "筑基" to "Trúc Cơ",
        "金丹" to "Kim Đan",
        "元婴" to "Nguyên Anh",
        "化神" to "Hóa Thần",
        "渡劫" to "Độ Kiếp",
        "道友" to "đạo hữu",
        "前辈" to "tiền bối",
        "晚辈" to "vãn bối",
        "本座" to "bản tọa",
        "宿主" to "ký chủ"
    )

    private val titleSuffixMap: Map<String, String> = linkedMapOf(
        "太子殿下" to "Thái tử điện hạ",
        "大师兄" to "Đại sư huynh",
        "小师妹" to "Tiểu sư muội",
        "大长老" to "Đại trưởng lão",
        "大将军" to "Đại tướng quân",
        "大小姐" to "Đại tiểu thư",
        "师尊" to "sư tôn",
        "师父" to "sư phụ",
        "师兄" to "sư huynh",
        "师姐" to "sư tỷ",
        "师弟" to "sư đệ",
        "师妹" to "sư muội",
        "掌门" to "chưởng môn",
        "宗主" to "tông chủ",
        "长老" to "trưởng lão",
        "前辈" to "tiền bối",
        "道友" to "đạo hữu",
        "公子" to "công tử",
        "少爷" to "thiếu gia",
        "小姐" to "tiểu thư",
        "姑娘" to "cô nương",
        "夫人" to "phu nhân",
        "陛下" to "bệ hạ",
        "殿下" to "điện hạ",
        "王爷" to "vương gia",
        "大人" to "đại nhân",
        "将军" to "tướng quân"
    )

    @Volatile
    private var cachedAssetEntryCount: Int = 345

    val totalEntries: Int
        get() = maxOf(cachedAssetEntryCount, SelfLearningDatabase.size(null))

    val ramCacheSize: Int
        @Synchronized get() = ramLru300.size

    @Synchronized
    fun ramLruSize(): Int = ramLru300.size

    private val leadingTrailingNoise = Regex("^[\\s\"'“”‘’「」『』《》〈〉()（）\\[\\]【】·•—\\-~～:：,，.。!！?？…]+|[\\s\"'“”‘’「」『』《》〈〉()（）\\[\\]【】·•—\\-~～:：,，.。!！?？…]+$")
    private val trailingBracketsAndQuotes = charArrayOf(' ', '\t', '\n', '"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '《', '》', '〈', '〉', ')', '）', ']', '】')
    private val internalPunctuation = Regex("[\\s,，.。!！?？…—\\-~～:：;；、\"'“”‘’「」『』]+")
    private val clauseSplitter = Regex("[，,、；;。.！!？?…\\s]+")
    private val modalParticles = charArrayOf('啊', '呀', '呢', '吧', '啦', '哦', '噢', '嘛')

    /**
     * Computes a deterministic 64-bit FNV-1a hash on the normalized Simplified Chinese key
     * for indexing in SQLite (`CREATE INDEX idx_zh_hash ON subtitle_bank(normalized_hash)`).
     */
    fun computeNormalizedHash(normalizedZh: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (i in normalizedZh.indices) {
            hash = hash xor normalizedZh[i].code.toLong()
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return hash
    }

    fun normalizeTraditional(input: String): String {
        if (input.isEmpty()) return input
        var changed = false
        val chars = CharArray(input.length)
        for (i in input.indices) {
            val ch = input[i]
            val mapped = traditionalToSimplified[ch]
            if (mapped != null && mapped != ch) {
                chars[i] = mapped
                changed = true
            } else {
                chars[i] = ch
            }
        }
        return if (changed) String(chars) else input
    }

    fun normalizeKey(input: String): String {
        val simplified = normalizeTraditional(input.trim())
        val stripped = simplified.replace(leadingTrailingNoise, "")
        return stripped.replace(internalPunctuation, "")
    }

    fun normalizeSubtitleKey(input: String): String = normalizeKey(input)

    @Synchronized
    fun getFromLru(normalizedKey: String): String? = ramLru300[normalizedKey]

    @Synchronized
    fun putInLru(normalizedKey: String, viText: String) {
        if (normalizedKey.isNotEmpty() && viText.isNotEmpty()) {
            ramLru300[normalizedKey] = viText
        }
    }

    @Synchronized
    fun putInRamLru(normalizedKey: String, viText: String) = putInLru(normalizedKey, viText)

    @Synchronized
    fun clearLru() {
        ramLru300.clear()
    }

    @Synchronized
    fun clearRamLru() = clearLru()

    /**
     * Streams `(sourceZh, targetVi)` pairs from `assets/zh_vi_subtitles.tsv` without holding
     * the collection in RAM. Used by [SelfLearningDatabase] on `Dispatchers.IO` to populate SQLite.
     */
    fun forEachAssetSubtitle(context: Context?, consumer: (String, String) -> Unit): Int {
        var count = 0
        try {
            val reader: BufferedReader? = when {
                context != null -> BufferedReader(InputStreamReader(context.assets.open(ASSET_FILE_NAME), Charsets.UTF_8))
                else -> openLocalAssetFileForTest()
            }
            reader?.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val tabIdx = trimmed.indexOf('\t')
                    if (tabIdx <= 0 || tabIdx + 1 >= trimmed.length) continue
                    val zh = trimmed.substring(0, tabIdx).trim()
                    val vi = trimmed.substring(tabIdx + 1).trim()
                    if (zh.isNotEmpty() && vi.isNotEmpty()) {
                        consumer(zh, vi)
                        count++
                    }
                }
            }
            if (count > 0) cachedAssetEntryCount = count
        } catch (_: Exception) {
        }
        return count
    }

    fun forEachAssetSubtitle(context: Context?, consumer: (String, String, String, Long) -> Unit): Int {
        return forEachAssetSubtitle(context) { zh, vi ->
            val normKey = normalizeKey(zh)
            if (normKey.isNotEmpty()) {
                val normHash = computeNormalizedHash(normKey)
                consumer(zh, vi, normKey, normHash)
            }
        }
    }

    private fun openLocalAssetFileForTest(): BufferedReader? {
        val candidates = listOf(
            File("src/main/assets/$ASSET_FILE_NAME"),
            File("app/src/main/assets/$ASSET_FILE_NAME"),
            File("android-app/app/src/main/assets/$ASSET_FILE_NAME")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile } ?: return null
        return file.bufferedReader(Charsets.UTF_8)
    }

    /**
     * Queries a single normalized Chinese key from:
     * 1. Bounded RAM `LruCache(300)` (< 0.05ms)
     * 2. SQLite `subtitle_bank` indexed by `idx_zh_hash` (< 2ms)
     */
    private fun lookupNormalizedKey(cleanKey: String, context: Context? = null): String? {
        if (cleanKey.isEmpty()) return null
        getFromLru(cleanKey)?.let { return it }
        val hash = computeNormalizedHash(cleanKey)
        val sqliteHit = SelfLearningDatabase.queryByNormalizedHash(context, hash, cleanKey)
        if (sqliteHit != null) {
            putInLru(cleanKey, sqliteHit)
            return sqliteHit
        }
        return null
    }

    private fun extractEndingPunctuation(raw: String): String {
        val trimmed = raw.trimEnd(*trailingBracketsAndQuotes)
        return when {
            trimmed.endsWith("？！") || trimmed.endsWith("?!") || trimmed.endsWith("！？") || trimmed.endsWith("!?") -> "?!"
            trimmed.endsWith("...") || trimmed.endsWith("…") || trimmed.endsWith("……") -> "..."
            trimmed.endsWith("？") || trimmed.endsWith("?") -> "?"
            trimmed.endsWith("！") || trimmed.endsWith("!") -> "!"
            else -> ""
        }
    }

    /**
     * Matches a Chinese subtitle/dialogue line in < 5ms using LruCache(300) + SQLite B-Tree (`idx_zh_hash`).
     * Supports:
     *  - Layer 1: Exact normalized match (Simplified & Traditional) + modal particle stripping.
     *  - Layer 2: Multi-clause split & join (e.g. "师兄，小心！", "等一下，我马上来", "快走，来不及了！").
     *  - Layer 3: High-frequency dialogue templates ("别[X]了", "我马上[X]", honorifics).
     */
    fun matchInstant(sourceText: String, context: Context? = null): String? {
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty() || trimmed.length > 48) return null

        val ending = extractEndingPunctuation(trimmed)
        val simplified = normalizeTraditional(trimmed)
        val cleanKey = normalizeKey(simplified)
        if (cleanKey.isEmpty()) return null

        // Layer 1A: Direct normalized match via LruCache(300) -> SQLite B-Tree (idx_zh_hash)
        lookupNormalizedKey(cleanKey, context)?.let { return appendEnding(it, ending) }

        // Layer 1B: Strip a single trailing modal particle (啊/呀/吧/呢/啦/哦) if the core phrase is in the bank
        if (cleanKey.length >= 3 && cleanKey.last() in modalParticles) {
            val withoutParticle = cleanKey.dropLast(1)
            lookupNormalizedKey(withoutParticle, context)?.let { return appendEnding(it, ending) }
        }

        // Layer 2: Multi-clause split & join (e.g., "师兄，小心！" or "等一下，我马上来")
        val rawClauses = simplified
            .replace(leadingTrailingNoise, "")
            .split(clauseSplitter)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawClauses.size in 2..4) {
            val translatedClauses = ArrayList<String>(rawClauses.size)
            for (clause in rawClauses) {
                val matched = matchSingleClauseOrTemplate(clause, context) ?: return null
                translatedClauses.add(matched)
            }
            val joined = translatedClauses.mapIndexed { idx, part ->
                if (idx == 0) part else part.replaceFirstChar { it.lowercase() }
            }.joinToString(", ")
            val finalJoined = appendEnding(joined, ending)
            putInLru(cleanKey, joined)
            return finalJoined
        }

        // Layer 3: Single-clause template pattern matching
        matchTemplate(cleanKey, context)?.let {
            putInLru(cleanKey, it)
            return appendEnding(it, ending)
        }

        return null
    }

    private fun matchSingleClauseOrTemplate(clause: String, context: Context?): String? {
        val key = normalizeKey(clause)
        if (key.isEmpty()) return null
        lookupNormalizedKey(key, context)?.let { return it }
        if (key.length >= 3 && key.last() in modalParticles) {
            lookupNormalizedKey(key.dropLast(1), context)?.let { return it }
        }
        return matchTemplate(key, context)
    }

    private fun matchTemplate(cleanKey: String, context: Context?): String? {
        // Template A: "别[X]了" -> "Đừng [X] nữa"
        if (cleanKey.startsWith("别") && cleanKey.endsWith("了") && cleanKey.length in 3..6) {
            val action = cleanKey.substring(1, cleanKey.length - 1)
            val actionVi = when (action) {
                "哭" -> "khóc"
                "笑" -> "cười"
                "闹" -> "quậy"
                "吵" -> "ồn ào"
                "打" -> "đánh"
                "看" -> "nhìn"
                "想" -> "nghĩ"
                "逃" -> "chạy trốn"
                "跑" -> "chạy"
                "管" -> "bận tâm"
                "装" -> "giả vờ"
                "傻" -> "ngốc"
                else -> lookupNormalizedKey(action, context)?.lowercase()
            }
            if (actionVi != null) return "Đừng $actionVi nữa"
        }

        // Template B: "我马上[X]" -> "Tôi [X] ngay"
        if (cleanKey.startsWith("我马上") && cleanKey.length in 4..6) {
            val action = cleanKey.removePrefix("我马上")
            val actionVi = when (action) {
                "来" -> "đến"
                "到" -> "tới"
                "走" -> "đi"
                "去" -> "đi"
                "回来" -> "quay lại"
                else -> null
            }
            if (actionVi != null) return "Tôi $actionVi ngay"
        }

        // Template C: Title / Honorific calls
        for ((suffixZh, suffixVi) in titleSuffixMap) {
            if (cleanKey == suffixZh) {
                return suffixVi.replaceFirstChar { it.uppercase() }
            }
        }

        return null
    }

    private fun appendEnding(base: String, ending: String): String {
        if (ending.isEmpty()) return base
        val trimmed = base.trimEnd('.', '!', '?')
        return "$trimmed$ending"
    }

    /**
     * Post-processes translations to ensure common cultivation/costume drama
     * titles are rendered with natural Sino-Vietnamese terms.
     */
    fun enrichTranslation(sourceText: String, translated: String): String {
        if (sourceText.isBlank() || translated.isBlank()) return translated
        val simplifiedSource = normalizeTraditional(sourceText)
        var out = translated
        for ((zhTerm, viTerm) in terminologyFixes) {
            if (simplifiedSource.contains(zhTerm) && out.contains(zhTerm)) {
                out = out.replace(Regex("(?<=\\p{L})${Regex.escape(zhTerm)}"), " $viTerm")
                    .replace(zhTerm, viTerm)
            }
        }
        return out
    }
}
