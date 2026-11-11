package com.gemini.ocr.video

/** A completed OCR scan is evidence; a few empty preview frames or a decoder error are not. */
internal object VideoSourceSelection {
    fun coverageMs(cues: List<VideoCue>): Long {
        var end = 0L
        var total = 0L
        for (cue in cues.sortedBy { it.startMs }) {
            total += (cue.endMs - maxOf(cue.startMs, end)).coerceAtLeast(0)
            end = maxOf(end, cue.endMs)
        }
        return total
    }
    fun assessSpeech(project: VideoProject, cues: List<VideoCue>): Boolean {
        require(project.scanMs >= project.durationMs) { "Hoàn tất OCR trước khi đánh giá nguồn âm thanh." }
        val readable = cues.filter { it.source.any { c -> c in '\u3400'..'\u9fff' } }
        return readable.isEmpty() || (project.durationMs >= 30_000 && coverageMs(readable) < project.durationMs * .08)
    }

    /** OCR/Approved remains authoritative. Acoustic overlaps are retained separately for review. */
    fun merge(kept: List<VideoCue>, candidate: VideoCue): Pair<List<VideoCue>, Boolean> {
        if (kept.any { it.id == candidate.id }) return kept to false
        if (kept.any { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) return kept to true
        return (kept + candidate).sortedBy { it.startMs } to false
    }
}
