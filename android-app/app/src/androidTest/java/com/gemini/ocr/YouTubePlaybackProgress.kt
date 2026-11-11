package com.gemini.ocr

/** Parse the observed player clock; changing pictures alone cannot prove playback. */
internal object YouTubePlaybackProgress {
    /** Player controls can disappear between lookup and reading their accessibility node. */
    fun <T> latestOrNull(read: () -> T): T? = try { read() }
        catch (_: androidx.test.uiautomator.StaleObjectException) { null }

    fun elapsedSeconds(description: String?): Long? {
        if (description == null) return null
        val current = description.substringBefore(" trên ").substringBefore(" of ")
        if (!current.startsWith("Đã phát ") && !current.startsWith("Played ")) return null
        return units(current)
    }
    fun durationSeconds(description: String?): Long? {
        if (description == null) return null
        val total = when {
            " trên " in description -> description.substringAfter(" trên ")
            " of " in description -> description.substringAfter(" of ")
            else -> return null
        }
        return units(total)
    }
    fun seekFraction(start: Int, total: Long): Double {
        require(start >= 0 && total > 0 && start < total)
        return start.toDouble() / total
    }
    fun seekLeadInSeconds(requested: Int): Int = (requested - 12).coerceAtLeast(0)
    fun needsComparisonSeek(current: Long, requested: Int): Boolean {
        require(current >= 0 && requested >= 0)
        return current < seekLeadInSeconds(requested) || current > requested + 1L
    }
    fun verifiedObservationStart(initial: Long, current: Long, requested: Int): Long? {
        require(initial >= 0 && current >= 0 && requested >= 0)
        if (current <= initial || current < requested) return null
        require(current <= requested + 3L) { "Player passed the comparison segment before observation" }
        return current
    }
    private fun units(text: String): Long? {
        val units = Regex("(\\d{1,6})\\s*(giờ|phút|giây|hours?|minutes?|seconds?)")
            .findAll(text).toList()
        if (units.isEmpty()) return null
        return units.sumOf {
            val value = it.groupValues[1].toLongOrNull() ?: return null
            value * when (it.groupValues[2]) {
                "giờ", "hour", "hours" -> 3600L
                "phút", "minute", "minutes" -> 60L
                else -> 1L
            }
        }
    }
}
