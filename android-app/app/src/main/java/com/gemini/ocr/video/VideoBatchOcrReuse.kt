package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.gemini.ocr.batch.BatchStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A raw, validated OCR result belongs to one batch and one exact video/ROI combination. */
internal class VideoBatchOcrReuse private constructor(
    private val context: Context,
    private val project: VideoProject,
    private val owner: String,
    private val hash: String,
    val cues: List<VideoCue>?
) {
    /** Call only after the native scan has validated EOS and the timeline. */
    suspend fun record(rows: List<VideoCue>, gate: suspend () -> Unit) {
        if (cues != null || rows.isEmpty() || rows.any { it.origin != "OCR" || it.protectedText }) return
        optional {
            // A content URI is allowed to change. Attest the completed scan only if it still
            // matches the bytes read before the scan started.
            if (VideoSpeechExtractor.sourceHash(context, Uri.parse(project.uri), gate) == hash) {
                val raw = rawTimeline(rows, project.durationMs)
                val bytes = JSONObject().put("schema", SCHEMA).put("owner", owner).put("sha256", hash)
                    .put("durationMs", project.durationMs).put("roi", JSONArray(project.roi))
                    .put("cues", JSONArray(raw.map(VideoCue::json))).toString().toByteArray(Charsets.UTF_8)
                if (bytes.size <= MAX_BYTES) {
                    val atomic = receipt(VideoStore(context), project.id)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(bytes)
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) {
                        atomic.failWrite(stream)
                        throw error
                    }
                }
            }
        }
    }

    companion object {
        // An older receipt may contain the fragmented pre-consolidation OCR timeline.
        private const val SCHEMA = 2
        private const val MAX_BYTES = 32 * 1024 * 1024

        /** Only fresh explicit OCR jobs with a same-size, same-duration peer spend time hashing. */
        suspend fun prepare(context: Context, project: VideoProject, gate: suspend () -> Unit): VideoBatchOcrReuse? {
            if (project.sourceMode != VideoSourceMode.OCR || project.scanMs != 0L || project.cues.isNotEmpty()) return null
            return optional {
                val store = VideoStore(context)
                val owner = owner(store, project.id) ?: return@optional null
                val batch = BatchStore(context).load(owner)
                val current = batch.items.firstOrNull { it.projectId == project.id } ?: return@optional null
                if (current.bytes <= 0L) return@optional null
                val peers = batch.items.filter {
                    it.projectId != project.id && it.bytes == current.bytes && it.durationMs == current.durationMs
                }
                if (peers.isEmpty()) return@optional null
                val hash = VideoSpeechExtractor.sourceHash(context, Uri.parse(project.uri), gate)
                val found = peers.firstNotNullOfOrNull { peer ->
                    if (owner(store, peer.projectId) != owner) null
                    else optional { read(store, peer.projectId, owner, hash, project) }
                }
                VideoBatchOcrReuse(context, project, owner, hash, found)
            }
        }

        private fun read(store: VideoStore, id: String, owner: String, hash: String, project: VideoProject): List<VideoCue>? {
            val atomic = receipt(store, id)
            if (atomic.baseFile.length() !in 1L..MAX_BYTES.toLong()) return null
            val saved = JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (saved.optInt("schema") != SCHEMA || saved.optString("owner") != owner ||
                saved.optString("sha256") != hash || saved.optLong("durationMs") != project.durationMs) return null
            val roi = saved.getJSONArray("roi")
            if (roi.length() != project.roi.size || project.roi.indices.any { roi.getDouble(it).toFloat() != project.roi[it] }) return null
            val rows = saved.getJSONArray("cues")
            if (rows.length() !in 1..VideoProject.MAX_CUES) return null
            return rawTimeline(List(rows.length()) { VideoCue.from(rows.getJSONObject(it)) }, project.durationMs)
        }

        private fun rawTimeline(rows: List<VideoCue>, durationMs: Long): List<VideoCue> =
            VideoTimeline.validate(rows, durationMs).mapIndexed { index, cue ->
                require(cue.origin == "OCR" && !cue.protectedText)
                val raw = cue.rawZh.ifBlank { cue.source }
                val display = if (cue.normalizedOcrText.isBlank()) raw else VideoOcrReading(raw).displayText
                VideoCue("ocr-draft-${index + 1}", cue.startMs, cue.endMs, display, origin = "OCR", rawZh = raw,
                    correctedZh = display.takeIf { it != raw }.orEmpty(),
                    ocrConfidence = cue.ocrConfidence, qualityFlags = cue.qualityFlags,
                    normalizedOcrText = cue.normalizedOcrText, ocrAlternatives = cue.ocrAlternatives)
            }

        private fun owner(store: VideoStore, id: String): String? {
            if (!id.matches(PROJECT_ID)) return null
            val marker = File(store.directory, "$id/batch-owner")
            return if (marker.length() == 36L) marker.readText(Charsets.UTF_8) else null
        }

        private fun receipt(store: VideoStore, id: String) = AtomicFile(File(store.directory, "$id/ocr-evidence-v1.json"))
        private val PROJECT_ID = Regex("[a-f0-9-]{36}")

        /** Cache misses, unavailable documents and corrupt optional receipts fall back to OCR. */
        private inline fun <T> optional(block: () -> T): T? = try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
