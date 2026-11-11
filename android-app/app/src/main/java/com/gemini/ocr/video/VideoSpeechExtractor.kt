package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import com.gemini.ocr.asr.ChineseAsrSession
import com.gemini.ocr.asr.AsrSpeechWindow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

/** Project-private ASR checkpoints; completed source text is reused when only translation changes. */
internal object VideoSpeechExtractor {
    suspend fun sourceHash(context: Context, uri: Uri, gate: suspend () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Không thể đọc video cho nhận diện giọng nói." }.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { gate(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun scan(context: Context, project: VideoProject, session: ChineseAsrSession,
                     gate: suspend () -> Unit,
                     checkpoint: suspend (Long, List<VideoCue>, JSONObject) -> Unit): List<VideoCue> {
        val source = sourceHash(context, Uri.parse(project.uri), gate)
        val fingerprint = "${session.fingerprint}:pcm16k-v2:vad18s-pad300ms-core-v2"
        val previous = project.speech
        val same = previous.optString("sourceSha") == source && previous.optString("modelFingerprint") == fingerprint
        require(same || project.cues.none { it.origin == "ASR" && it.protectedText }) {
            "Nguồn hoặc model ASR đã đổi. Tạo dự án mới để giữ các câu đã duyệt."
        }
        if (same && previous.optBoolean("complete")) return project.cues
        var rows = if (same) project.cues else project.cues.filter { it.origin != "ASR" }
        var state = if (same) JSONObject(previous.toString()) else JSONObject().apply {
            if (previous.optBoolean("ocrComplete")) put("ocrComplete", true)
        }
        state.put("sourceSha", source).put("modelFingerprint", fingerprint).put("asrActive", true)
            .put("complete", false).put("resolvedSource", if (rows.any { it.origin != "ASR" }) "OCR_ASR" else "ASR")
            .put("timing", "vad-speech-interval-not-word-alignment").put("threads", session.threads)
        var committed = if (same) state.optLong("checkpointMs").coerceIn(0, project.durationMs) else 0L
        val restartMs = (committed - 500).coerceAtLeast(0)
        var nextInputSample = restartMs * 16
        var decodedMs = restartMs
        var savedAtMs = restartMs
        val review = state.optJSONArray("reviewOverlaps") ?: JSONArray()
        val unclear = state.optJSONArray("unclearIntervals") ?: JSONArray()
        val detector = session.newVad(restartMs * 16)

        suspend fun save(complete: Boolean = false) {
            state.put("checkpointMs", committed).put("decodedMs", decodedMs).put("complete", complete)
                .put("reviewOverlaps", review).put("unclearIntervals", unclear)
                .put("needsReview", review.length() > 0 || unclear.length() > 0)
                .put("noSpeech", complete && rows.isEmpty() && state.optInt("unclearWindows") == 0)
            checkpoint(if (complete) project.durationMs else decodedMs.coerceAtMost(project.durationMs), rows, JSONObject(state.toString()))
        }

        suspend fun recognize(window: AsrSpeechWindow) {
            gate()
            val from = (window.coreStartSample / 16).coerceAtLeast(0)
            val until = ((window.coreEndSample + 15) / 16).coerceAtMost(project.durationMs)
            if (until <= from || until <= committed) return
            val result = session.transcribe(window.samples)
            gate()
            val raw = Normalizer.normalize(result.text, Normalizer.Form.NFKC).trim()
            if (raw.isNotBlank() && raw.any { it in '\u3400'..'\u9fff' }) {
                require(raw.length <= 2000) { "ASR_TEXT_TOO_LONG: Đoạn thoại cần kiểm tra; checkpoint được giữ." }
                // Padding gives the model context but never defines occurrence identity.
                // A resume window can cross a previous boundary by a few samples.
                val start = maxOf(from, rows.lastOrNull { it.origin == "ASR" && it.startMs < until && it.endMs > from }?.endMs ?: from)
                if (until > start) {
                    val cue = VideoCue("asr-${source.take(12)}-${window.coreStartSample}-${window.coreEndSample}", start, until, raw,
                        origin = "ASR", model = "sensevoice-small-int8", rawZh = raw,
                        qualityFlags = (result.qualityFlags + "ASR_TIMING_APPROXIMATE").toList())
                    val merged = VideoSourceSelection.merge(rows, cue)
                    rows = merged.first
                    if (merged.second) {
                        if (review.length() < 64) review.put(cue.json()) else state.put("additionalOverlapCount", state.optInt("additionalOverlapCount") + 1)
                        rows = rows.map { old -> if (!old.protectedText && old.startMs < until && start < old.endMs)
                            old.copy(qualityFlags = (old.qualityFlags + "ASR_OVERLAP_REVIEW").distinct()) else old }
                    }
                }
            } else if ("ASR_NON_SPEECH" !in result.qualityFlags && "ASR_NO_SPEECH" !in result.qualityFlags) {
                state.put("unclearWindows", state.optInt("unclearWindows") + 1)
                if (unclear.length() < 64) unclear.put(JSONObject().put("startMs", from).put("endMs", until)
                    .put("qualityFlags", JSONArray((result.qualityFlags + "ASR_UNCLEAR_AUDIO").toList())))
            }
            committed = maxOf(committed, until)
            state.put("recognitions", state.optInt("recognitions") + 1)
            save()
        }

        suspend fun feed(samples: FloatArray) {
            detector.accept(samples)
            nextInputSample += samples.size
            decodedMs = nextInputSample / 16
            for (window in detector.drain()) recognize(window)
            if (decodedMs - savedAtMs >= 5000) {
                // A pending VAD utterance can occupy at most 20 seconds. Replaying this tail
                // after process death preserves words that had not reached a closed window.
                committed = maxOf(committed, (decodedMs - 21000).coerceAtLeast(0))
                savedAtMs = decodedMs
                save()
            }
        }

        try {
            save()
            VideoAudioDecoder.scan(context, Uri.parse(project.uri), restartMs, gate) { block ->
                while (nextInputSample < block.firstSample) {
                    gate()
                    feed(FloatArray(minOf(4096L, block.firstSample - nextInputSample).toInt()))
                }
                val skip = (nextInputSample - block.firstSample).coerceAtLeast(0).coerceAtMost(block.samples.size.toLong()).toInt()
                if (skip < block.samples.size) feed(if (skip == 0) block.samples else block.samples.copyOfRange(skip, block.samples.size))
            }
            detector.finish()
            for (window in detector.drain()) recognize(window)
            committed = project.durationMs
            decodedMs = project.durationMs
            rows = VideoTimeline.validate(rows, project.durationMs)
            save(complete = true)
            return rows
        } finally { detector.close() }
    }
}
