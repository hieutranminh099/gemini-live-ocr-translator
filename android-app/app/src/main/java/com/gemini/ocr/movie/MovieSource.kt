package com.gemini.ocr.movie

import android.content.Context
import android.net.Uri
import com.gemini.ocr.video.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Caller must explicitly confirm the freshly reviewed project; no render or public export required. */
data class MovieSourceInput(val episodeId: String, val title: String, val project: VideoProject)

/** A frozen private timeline. Portable form deliberately carries neither local URI nor project ID. */
data class MovieSourceSnapshot(
    val projectId: String, val uri: String, val sha256: String, val bytes: Long,
    val width: Int, val height: Int, val cues: List<VideoCue>, val style: VideoSubtitleStyle,
    val revision: String, val revisedAt: Long,
    val sourceRoi: List<Float> = listOf(.06f, .72f, .94f, .96f),
    val sourceMode: VideoSourceMode = VideoSourceMode.AUTO
) {
    fun project(episode: MovieEpisode): VideoProject = VideoProject(
        projectId.ifBlank { episode.id }, episode.title, uri, episode.durationMs, width, height,
        sourceMode = sourceMode, roi = sourceRoi, style = style, stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
        progress = 100, cues = cues, scanMs = episode.durationMs, updatedAt = revisedAt,
        message = "Bản phụ đề riêng trong Kho phim; không mã hóa lại video.")

    private fun schemaVersion(): Int = if (style.legacyDefault &&
        sourceRoi == listOf(.06f, .72f, .94f, .96f) && sourceMode == VideoSourceMode.AUTO) 1 else 2

    internal fun json(portable: Boolean) = JSONObject().put("schema", schemaVersion())
        .put("projectId", if (portable) "" else projectId).put("uri", if (portable) "" else uri)
        .put("sha256", sha256).put("bytes", bytes).put("width", width).put("height", height)
        .put("cues", JSONArray(cues.map { it.json() })).put("style", style.json())
        .put("revision", revision).put("revisedAt", revisedAt)
        .put("sourceRoi", JSONArray(sourceRoi)).put("sourceMode", sourceMode.name)

    internal fun validate(duration: Long) {
        require(bytes in 1..MovieSourceAccess.MAX_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        require(width in 1..16384 && height in 1..16384 && duration in 1..86_400_000L && revisedAt > 0)
        require(projectId.isBlank() || projectId.matches(Regex("[a-f0-9-]{36}")))
        require(uri.isBlank() || Uri.parse(uri).scheme in setOf("file", "content"))
        require(cues.isNotEmpty() && cues.all { it.translation.isNotBlank() }) { "Hoàn tất bản dịch trước khi lưu Kho phim." }
        require(cues.sumOf { it.source.length.toLong() + it.translation.length + it.localDraft.length } <= 2_000_000L)
        require(VideoTimeline.validate(cues, duration) == cues) { "Timeline chưa được sắp xếp hợp lệ." }
        require(sourceRoi.size == 4 && sourceRoi.all { it.isFinite() && it in 0f..1f } &&
            sourceRoi[2] > sourceRoi[0] && sourceRoi[3] > sourceRoi[1]) { "Vùng chữ nguồn chưa hợp lệ." }
        require(revision == revision(sha256, bytes, width, height, cues, style, sourceRoi, sourceMode)) { "Snapshot phụ đề đã thay đổi hoặc không hợp lệ." }
    }

    companion object {
        internal fun freeze(project: VideoProject, sha: String, bytes: Long, uri: String = project.uri,
                            revisedAt: Long = System.currentTimeMillis()): MovieSourceSnapshot {
            require(!project.sourceExtractionPending) { "Chưa nhận hết phụ đề nguồn; tiếp tục xử lý trước khi lưu Kho phim." }
            val cues = VideoTimeline.validate(project.cues, project.durationMs).toList()
            return MovieSourceSnapshot(project.id, uri, sha, bytes, project.width, project.height, cues,
                project.style, revision(sha, bytes, project.width, project.height, cues, project.style, project.roi, project.sourceMode),
                revisedAt, project.roi.toList(), project.sourceMode)
                .also { it.validate(project.durationMs) }
        }
        internal fun from(j: JSONObject): MovieSourceSnapshot {
            val schema = j.getInt("schema")
            require(schema in 1..2) { "Phiên bản phụ đề Kho phim ($schema) chưa được hỗ trợ. Hãy cập nhật ứng dụng để mở bản này." }
            val rows = j.getJSONArray("cues"); require(rows.length() <= VideoProject.MAX_CUES)
            val roi = j.optJSONArray("sourceRoi")?.let { value ->
                require(value.length() == 4)
                (0..3).map { value.getDouble(it).toFloat() }
            } ?: listOf(.06f, .72f, .94f, .96f)
            return MovieSourceSnapshot(j.optString("projectId"), j.optString("uri"), j.getString("sha256"),
                j.getLong("bytes"), j.getInt("width"), j.getInt("height"),
                (0 until rows.length()).map { VideoCue.from(rows.getJSONObject(it)) },
                VideoSubtitleStyle.from(j.getJSONObject("style")), j.getString("revision"), j.getLong("revisedAt"),
                roi, VideoSourceMode.valueOf(j.optString("sourceMode", "AUTO"))).also {
                    require(schema == it.schemaVersion()) { "Phiên bản snapshot không khớp kiểu phụ đề và vùng nguồn; bản gốc vẫn được giữ." }
                }
        }
        private fun revision(sha: String, bytes: Long, width: Int, height: Int, cues: List<VideoCue>, style: VideoSubtitleStyle,
                             sourceRoi: List<Float>, sourceMode: VideoSourceMode): String {
            // Length-prefixed fields make the identity independent of JSONObject key order/platform.
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: Any) {
                val data = value.toString().toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); digest.update(data)
            }
            listOf(sha, bytes, width, height, style.position, style.sizeFraction, style.background, style.outline,
                style.frame?.left ?: "", style.frame?.top ?: "", style.frame?.right ?: "", style.frame?.bottom ?: "").forEach(::field)
            // Preserve every schema-1 revision created before the appearance fields existed.
            if (!style.legacyDefault) {
                listOf("subtitle-appearance-v2", style.blurOriginal, style.yellowText).forEach(::field)
            }
            if (sourceRoi != listOf(.06f, .72f, .94f, .96f) || sourceMode != VideoSourceMode.AUTO) {
                listOf("source-region-v1", sourceMode.name).forEach(::field)
                sourceRoi.forEach(::field)
            }
            cues.forEach { c -> listOf(c.id, c.startMs, c.endMs, c.source, c.translation, c.approved,
                c.origin, c.model, c.error, c.localDraft, c.refinementState).forEach(::field) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Only opens explicit local references. Never resolves a network URL or deletes source media. */
internal object MovieSourceAccess {
    const val MAX_BYTES = 20L * 1024 * 1024 * 1024
    fun open(context: Context, uri: Uri): InputStream {
        require(uri.scheme in setOf("file", "content")) { "Nguồn phim không hợp lệ; chọn lại video trong máy." }
        return try {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
            else requireNotNull(context.contentResolver.openInputStream(uri))
        } catch (e: Exception) {
            throw IllegalArgumentException("Video nguồn đã bị di chuyển, xóa hoặc mất quyền đọc. Chọn lại nguồn hoặc tải bản Drive; phụ đề đã lưu vẫn được giữ.", e)
        }
    }
    suspend fun inspect(context: Context, uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        open(context, uri).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                bytes += n; require(bytes <= MAX_BYTES) { "Video nguồn vượt 20 GiB." }; digest.update(buffer, 0, n)
            }
        }
        require(bytes > 0) { "Video nguồn trống; chưa lưu Kho phim." }
        return digest.digest().joinToString("") { "%02x".format(it) } to bytes
    }
}
