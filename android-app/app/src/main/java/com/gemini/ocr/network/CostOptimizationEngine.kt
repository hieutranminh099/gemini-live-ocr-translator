package com.gemini.ocr.network

import kotlin.math.max
import kotlin.math.min

/**
 * Chinese Subtitle Real-Time OCR Latency & Cost Optimization Engine (ZH -> VI).
 *
 * Latency policies whose end-to-end effect must be measured with fresh translations:
 * 1. Fast model routing with the thinking controls supported by that model.
 * 2. Compact instructions preserving critical meaning.
 * 3. Context Window Compression: sends at most 1 immediately preceding subtitle line to preserve honorifics
 *    without inflating request payload.
 * 4. OCR Noise Regex Pre-filtering + Fuzzy Number/Template Matching.
 */
object CostOptimizationEngine {

    const val FLASH_LITE_MODEL = "gemini-3.5-flash-lite"
    const val FLASH_25_LITE_MODEL = "gemini-2.5-flash-lite"
    const val FLASH_MODEL = "gemini-2.0-flash"
    const val PRO_MODEL = FLASH_LITE_MODEL

    /**
     * Chinese movie/drama subtitle instructions. Token count is not inferred from word count.
     */
    const val ULTRA_SHORT_SYSTEM_PROMPT =
        "Dịch phụ đề phim Trung sang Việt tự nhiên, đúng ngữ cảnh và xưng hô. Giữ đủ mọi số lượng, đơn vị, tên và phủ định; không lược chủ thể có trong câu hoặc ngữ cảnh. Chỉ trả về câu dịch, không giải thích."

    const val COMPACT_SYSTEM_INSTRUCTION = ULTRA_SHORT_SYSTEM_PROMPT

    data class ModelRouteDecision(
        val model: String,
        val reason: String,
        val isProEscalated: Boolean
    )

    data class FuzzyMatchResult(
        val translation: String,
        val similarity: Double,
        val templateMatched: Boolean
    )

    // Compact Chinese & universal HUD UI terminology dictionary (< 1ms, 0 tokens)
    private val offlineUiDictionary: Map<String, String> = mapOf(
        "开始" to "Bắt đầu",
        "开始游戏" to "Bắt đầu trò chơi",
        "继续" to "Tiếp tục",
        "设置" to "Cài đặt",
        "背包" to "Túi đồ",
        "装备" to "Trang bị",
        "升级" to "Tăng cấp",
        "技能" to "Kỹ năng",
        "任务" to "Nhiệm vụ",
        "任务完成" to "Hoàn thành nhiệm vụ",
        "任务失败" to "Nhiệm vụ thất bại",
        "确认" to "Xác nhận",
        "取消" to "Hủy bỏ",
        "跳过" to "Bỏ qua",
        "返回" to "Quay lại",
        "下一步" to "Tiếp theo",
        "商店" to "Cửa hàng",
        "地图" to "Bản đồ",
        "状态" to "Trạng thái",
        "角色" to "Nhân vật",
        "队伍" to "Đội hình",
        "宗门" to "Tông môn",
        "帮派" to "Bang phái",
        "好友" to "Bạn bè",
        "邮件" to "Hộp thư",
        "领取" to "Nhận thưởng",
        "全部领取" to "Nhận tất cả",
        "奖励" to "Phần thưởng",
        "加载中" to "Đang tải...",
        "连接中" to "Đang kết nối...",
        // Universal HUD / emulator labels
        "start" to "Bắt đầu",
        "settings" to "Cài đặt",
        "options" to "Tùy chọn",
        "inventory" to "Túi đồ",
        "equipment" to "Trang bị",
        "level up" to "Tăng cấp",
        "level" to "Cấp độ",
        "skill" to "Kỹ năng",
        "quest" to "Nhiệm vụ",
        "confirm" to "Xác nhận",
        "cancel" to "Hủy bỏ",
        "skip" to "Bỏ qua",
        "loading" to "Đang tải..."
    )

    private val whitelistedShortWords = setOf(
        "ok", "no", "go", "up", "on", "hp", "mp", "lv", "xp", "sp", "ap", "ai", "pc", "ui", "id"
    )

    // Graphic / border characters commonly misread by screen OCR
    private val graphicNoiseChars = setOf(
        '|', '/', '\\', '_', ':', ';', '-', '=', '+', '*', '~', '^', '·', '•',
        '<', '>', '[', ']', '(', ')', '{', '}', '!', '?', '.', ',', '\'', '"',
        '`', '´', '¦', '¬', '°', '±', '×', '÷', '—', '–', '…', '「', '」', '『', '』', '【', '】'
    )

    private val numberRegex = Regex("\\d+(?:[.,:/]\\d+)*")

    // Classical Chinese, Xianxia, Wuxia & Historical markers that benefit from Gemini context
    private val complexDomainMarkers = listOf(
        "元婴", "化神", "渡劫", "大乘", "筑基", "金丹", "天道", "法则", "神识", "本命",
        "心魔", "乾坤", "阴阳", "阵法", "封印", "血脉", "灵根", "丹田", "经脉", "剑意",
        "领域", "苍穹", "轮回", "涅槃", "神魂", "妖兽", "魔族", "仙界", "秘境", "传承",
        "寡人", "陛下", "微臣", "圣旨", "钦此", "岂敢", "何以", "未尝", "天命", "社稷"
    )

    fun routeModel(
        @Suppress("UNUSED_PARAMETER") texts: List<String>,
        @Suppress("UNUSED_PARAMETER") sourceLang: String = "zh",
        @Suppress("UNUSED_PARAMETER") profile: String = "manga",
        @Suppress("UNUSED_PARAMETER") mode: String = "fast",
        @Suppress("UNUSED_PARAMETER") forceHighPrecision: Boolean = false
    ): ModelRouteDecision {
        return ModelRouteDecision(
            model = FLASH_LITE_MODEL,
            reason = "flash_lite_exclusive_engine",
            isProEscalated = false
        )
    }

    fun estimateWordCount(text: String, @Suppress("UNUSED_PARAMETER") sourceLang: String = "zh"): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        var hanCount = 0
        // Android's ICU regex engine does not accept Java's IsHan alias on API 28.
        // Inspect Unicode code points, also preserving supplementary Han characters.
        val nonHanText = buildString {
            var index = 0
            while (index < trimmed.length) {
                val point = trimmed.codePointAt(index)
                if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                    hanCount++; append(' ')
                } else appendCodePoint(point)
                index += Character.charCount(point)
            }
        }
        val nonHanWords = nonHanText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val hanWordEquivalent = (hanCount * 2) / 3
        return nonHanWords + hanWordEquivalent
    }

    /**
     * Offline UI Terminology Dictionary lookup (< 1ms, 0 tokens).
     */
    fun matchOfflineUiTerm(sourceText: String): String? {
        val cleaned = sourceText.trim()
            .trim { it in graphicNoiseChars || it.isWhitespace() }
            .lowercase()
        if (cleaned.isEmpty()) return null
        val simplified = ChineseDialogueBank.normalizeTraditional(cleaned)
        return offlineUiDictionary[simplified] ?: offlineUiDictionary[cleaned]
    }

    /**
     * Conservative cache reuse: normalized equality or an otherwise identical numeric template.
     * Edit distance cannot establish semantic equivalence: one changed Han character can negate a
     * sentence, replace a person or alter a Chinese numeral. Those changes require a new translation.
     */
    fun findFuzzyMatch(
        sourceText: String,
        candidates: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") minSimilarity: Double = 0.90
    ): FuzzyMatchResult? {
        val normalizedSource = normalizeForFuzzy(sourceText)
        if (normalizedSource.length < 3 || candidates.isEmpty()) return null
        val sourceNumbers = extractNumbers(normalizedSource)
        val sourceTemplate = toNumberTemplate(normalizedSource)

        for ((rawKey, cachedTranslation) in candidates) {
            if (cachedTranslation.isBlank()) continue
            val candidateSource = extractSourceFromCacheKey(rawKey)
            if (candidateSource.isEmpty() || candidateSource == sourceText) continue
            val normalizedCandidate = normalizeForFuzzy(candidateSource)
            if (normalizedCandidate == normalizedSource) {
                return FuzzyMatchResult(cachedTranslation, 1.0, templateMatched = false)
            }
            if (sourceNumbers.isEmpty() || sourceTemplate.length < 4 ||
                sourceTemplate != toNumberTemplate(normalizedCandidate)) continue
            val substituted = substituteNumbers(cachedTranslation,
                extractNumbers(normalizedCandidate), sourceNumbers) ?: continue
            return FuzzyMatchResult(substituted, 0.99, templateMatched = true)
        }
        return null
    }

    private fun extractSourceFromCacheKey(cacheKey: String): String {
        val pipeCount = cacheKey.count { it == '|' }
        if (pipeCount >= 3) {
            var idx = -1
            repeat(3) { idx = cacheKey.indexOf('|', idx + 1) }
            if (idx >= 0 && idx + 1 < cacheKey.length) return cacheKey.substring(idx + 1)
        }
        val colonCount = cacheKey.count { it == ':' }
        if (colonCount >= 3) {
            var idx = -1
            repeat(3) { idx = cacheKey.indexOf(':', idx + 1) }
            if (idx >= 0 && idx + 1 < cacheKey.length) return cacheKey.substring(idx + 1)
        }
        return cacheKey
    }

    private fun normalizeForFuzzy(text: String): String =
        ChineseDialogueBank.normalizeTraditional(text.trim()).replace(Regex("\\s+"), " ")

    fun extractNumbers(text: String): List<String> =
        numberRegex.findAll(text).map { it.value }.toList()

    private fun toNumberTemplate(text: String): String {
        var idx = 0
        return numberRegex.replace(text) { "{#${idx++}}" }
    }

    private fun substituteNumbers(
        cachedTranslation: String,
        oldNumbers: List<String>,
        newNumbers: List<String>
    ): String? {
        if (oldNumbers.size != newNumbers.size) return null
        if (oldNumbers == newNumbers) return cachedTranslation
        val replacements = linkedMapOf<String, String>()
        for ((oldNumber, newNumber) in oldNumbers.zip(newNumbers)) {
            val previous = replacements.put(oldNumber, newNumber)
            // The same source value mapping to two different values is ambiguous when Vietnamese
            // reorders clauses. A fresh translation is safer than guessing positional alignment.
            if (previous != null && previous != newNumber) return null
        }
        val targetNumbers = extractNumbers(cachedTranslation)
        if (targetNumbers.groupingBy { it }.eachCount() != oldNumbers.groupingBy { it }.eachCount()) return null
        // Replace full numeric tokens in one pass. Sequential replaceFirst cascades 1->2->3 and
        // replacing substrings would turn 10 into 20 when only source value 1 changed.
        return numberRegex.replace(cachedTranslation) { match -> replacements.getValue(match.value) }
    }

    fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val n = lhs.length
        val m = rhs.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            val lhsChar = lhs[i - 1]
            for (j in 1..m) {
                val cost = if (lhsChar == rhs[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    /**
     * OCR Noise Pre-filtering.
     */
    fun isOcrNoise(
        text: String,
        language: String = "zh",
        retainSourceFragments: Boolean = false
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        if (retainSourceFragments &&
            trimmed.any { it.isDigit() || it == '_' } &&
            trimmed.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
        ) {
            return false
        }

        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount == 0) return true

        val nonSpaceLength = trimmed.count { !it.isWhitespace() }
        val noiseCount = trimmed.count { it in graphicNoiseChars }
        if (nonSpaceLength > 0 && noiseCount.toDouble() / nonSpaceLength.toDouble() > 0.65) {
            return true
        }

        val hasHan = trimmed.any { ch ->
            Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
        }

        if (!hasHan && language != "zh") {
            val cleanAlphaNum = trimmed.filter { it.isLetterOrDigit() }
            if (cleanAlphaNum.length < 3 && cleanAlphaNum.lowercase() !in whitelistedShortWords) {
                return true
            }
        } else if (!hasHan) {
            val cleanAlphaNum = trimmed.filter { it.isLetterOrDigit() }
            if (cleanAlphaNum.length < 3 && cleanAlphaNum.lowercase() !in whitelistedShortWords) {
                return true
            }
        }

        return false
    }

    fun formatNumberedBatch(texts: List<String>): String =
        texts.mapIndexed { idx, line -> "${idx + 1}. ${line.replace("\n", " ").trim()}" }
            .joinToString("\n")

    fun parseNumberedLines(rawText: String, expectedCount: Int): List<String>? {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.size != expectedCount || expectedCount < 1) return null
        val numbered = Regex("^(\\d+)[.):\\-]\\s+(.+)$")
        return lines.mapIndexed { index, line ->
            val match = numbered.matchEntire(line) ?: return null
            if (match.groupValues[1].toIntOrNull() != index + 1) return null
            match.groupValues[2].trim()
        }
    }

    fun computeMaxOutputTokens(texts: List<String>, isProEscalated: Boolean = false): Int {
        val totalChars = texts.sumOf { it.length }
        if (!isProEscalated && totalChars <= 160 && texts.size <= 6) {
            return (totalChars * 2 + texts.size * 24).coerceIn(100, 250)
        }
        return (totalChars * 3 + texts.size * 32).coerceIn(250, 1024)
    }

    /**
     * Requirement 4.3: Send at most 1 preceding dialogue line by default (or up to 2 when requested)
     * to maintain context & honorifics without bloating payload.
     */
    fun compressContextWindow(
        history: List<Pair<String, String>>,
        maxItems: Int = 1
    ): List<Pair<String, String>> = history.takeLast(maxItems.coerceIn(1, 2))

    fun shouldEscalateToGemini(
        texts: List<String>,
        sourceLang: String = "zh",
        profile: String = "manga",
        costSavingMode: Boolean = true,
        forceHighPrecision: Boolean = false,
        charThreshold: Int = 15,
        learnMissingShortPhrases: Boolean = true
    ): Boolean {
        if (forceHighPrecision) return true
        if (!costSavingMode) return true
        if (profile == "technical") return true

        val effectiveThreshold = charThreshold.coerceIn(5, 60)

        for (raw in texts) {
            val corrected = SelfLearningDatabase.preprocessAndCorrectOcr(raw)
            if (corrected.isEmpty()) continue

            val inBank = SelfLearningDatabase.lookup(null, corrected, incrementFrequency = false) != null ||
                ChineseDialogueBank.matchInstant(corrected) != null ||
                matchOfflineUiTerm(corrected) != null

            if (corrected.length > effectiveThreshold) {
                return true
            }

            if (inBank) {
                continue
            }

            if (learnMissingShortPhrases) {
                return true
            }

            val simplified = ChineseDialogueBank.normalizeTraditional(corrected)
            if (complexDomainMarkers.any { simplified.contains(it, ignoreCase = true) }) {
                return true
            }
            val clauseCount = simplified.split(Regex("[，,、；;。.！!？?…]+")).count { it.isNotBlank() }
            if (clauseCount >= 3) return true

            val hanChars = simplified.count { ch ->
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
            }
            if (hanChars > effectiveThreshold) return true
            val wordCount = estimateWordCount(simplified, sourceLang)
            if (wordCount > 12) return true
        }
        return false
    }
}
