package com.gemini.ocr.video

import org.json.JSONObject

/** HYBRID reads the complete source locally before making a dialogue translation request. */
internal object VideoHybridWorkflow {
    fun requiresExtraction(project: VideoProject): Boolean =
        project.translationMode == VideoTranslationMode.HYBRID && project.scanMs < project.durationMs

    /**
     * Older HYBRID checkpoints used the Vision accumulator. Transfer its unfinished occurrence
     * without reinterpreting earlier closed/approved cues as new OCR results. Unknown history
     * remains explicitly uncertain; it cannot be joined to a new local observation.
     */
    fun prepareLocalExtraction(project: VideoProject): VideoProject {
        val saved = project.carry
        if (!requiresExtraction(project) || project.sourceMode != VideoSourceMode.OCR ||
            !saved.optBoolean("visualScan") || saved.optInt("draftVersion") >= 2 ||
            saved.optJSONObject("draftRun") != null) return project

        require(project.durationMs > 0 && project.scanMs in 0..project.durationMs) {
            "Checkpoint nguồn chưa hợp lệ; dữ liệu trước được giữ."
        }
        VideoTimeline.validate(project.cues, project.durationMs)
        val source = saved.optString("source")
        val last = saved.optLong("last", -1)
        require(last in -1..project.scanMs) { "Mốc OCR cũ vượt checkpoint; dữ liệu trước được giữ." }
        val migrated = JSONObject(saved.toString())
            .put("draftVersion", 2).put("scanLast", last).put("draftUnknownGap", true)
            .put("draftNextId", project.cues.size + 1).put("draftRun", JSONObject.NULL)
        if (source.isBlank()) return project.copy(carry = migrated.put("source", ""))

        val start = saved.optLong("start", -1)
        require(source.length <= 2000 && start >= 0 && last >= start &&
            start >= (project.cues.maxOfOrNull { it.endMs } ?: 0)) {
            "Khoảng câu nguồn đang mở chưa hợp lệ; dữ liệu trước được giữ."
        }
        val blank = saved.optLong("blank", -1)
        require(blank == -1L || blank in start..last) {
            "Mốc trống OCR cũ chưa hợp lệ; dữ liệu trước được giữ."
        }
        if (blank >= 0) {
            // The old accumulator would close at its first pending blank, not at a later
            // null/blank frame. Keep that actual boundary instead of extending through it.
            val closed = if (blank > start) {
                val ids = project.cues.map { it.id }.toSet()
                var sequence = project.cues.size + 1
                var id: String
                do { id = "ocr-draft-${sequence++}" } while (id in ids)
                require(project.cues.size < VideoProject.MAX_CUES) { "Có quá nhiều câu nguồn; dữ liệu trước được giữ." }
                migrated.put("draftNextId", sequence)
                project.cues + VideoCue(id, start, blank, source, origin = "OCR", rawZh = source,
                    qualityFlags = listOf("OCR_UNCERTAIN_INTERVAL", "OCR_SHORT_QUORUM", "OCR_UNSTABLE_SINGLE_FRAME"))
            } else project.cues
            return project.copy(cues = closed, carry = migrated.put("source", ""))
        }
        migrated.put("draftRun", JSONObject().put("raw", source).put("start", start)
            .put("last", last).put("count", 1).put("uncertain", true))
        return project.copy(carry = migrated)
    }
}
