package com.gemini.ocr.movie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoTranslationPlan
import org.json.JSONObject

/** The previous row lives with the local episode record and is omitted from Drive manifests. */
data class MovieCaptionUndo(val revision: String, val previous: VideoCue) {
    internal fun json() = JSONObject().put("revision", revision).put("previous", previous.json())

    internal companion object {
        fun from(json: JSONObject) = MovieCaptionUndo(json.getString("revision"), VideoCue.from(json.getJSONObject("previous")))
    }
}

internal fun reviseMovieCaption(
    episode: MovieEpisode, expectedRevision: String, cueId: String, editedText: String?, changedAt: Long
): MovieEpisode {
    val snapshot = requireNotNull(episode.source) { "Video này không có phụ đề rời để sửa." }
    require(snapshot.revision == expectedRevision) { "Phụ đề đã đổi. Mở lại câu để xem bản mới nhất." }
    val previous = requireNotNull(snapshot.cues.firstOrNull { it.id == cueId }) { "Câu này không còn trong phụ đề." }
    val replacement = if (editedText == null) {
        val undo = episode.captionUndo?.takeIf { it.revision == snapshot.revision && it.previous.id == cueId }
        val row = requireNotNull(undo) { "Câu này không còn bản trước để khôi phục." }.previous
        require(row.startMs == previous.startMs && row.endMs == previous.endMs && row.source == previous.source)
        row.copy(version = previous.version + 1)
    } else {
        val text = editedText.trim()
        require(text.isNotEmpty() && text.length <= 8000) { "Nhập bản dịch từ 1 đến 8.000 ký tự." }
        if (text == previous.translation) return episode
        previous.copy(translation = text, approved = true, model = "user-edited", error = "",
            draftVi = previous.draftVi.ifBlank { previous.translation }, polishedVi = text, version = previous.version + 1)
    }
    val cues = snapshot.cues.map { if (it.id == cueId) replacement else it }
    val revised = snapshot.withUpdatedCues(cues, episode.durationMs, changedAt)
    return episode.copy(source = revised,
        captionUndo = if (editedText == null) null else MovieCaptionUndo(revised.revision, previous))
}

internal fun movieCaptionRequest(project: VideoProject, cueId: String): VideoTranslationPlan.Batch {
    val targets = project.cues.map { cue ->
        if (cue.id == cueId) cue.copy(translation = "", approved = false, model = "", error = "") else cue
    }
    val batch = requireNotNull(VideoTranslationPlan.nextCloudBatch(targets, hybrid = false, maxCues = 1))
    require(batch.cueIds == listOf(cueId))
    return batch
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MovieCaptionDialog(cue: VideoCue, text: String, onText: (String) -> Unit, busy: Boolean,
    message: String, canRetry: Boolean, canUndo: Boolean, onRetry: () -> Unit,
    onUndo: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Sửa phụ đề đang xem") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${cue.startMs}–${cue.endMs} ms", style = MaterialTheme.typography.labelMedium)
            Text(cue.source, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(text, onText, label = { Text("Bản dịch tiếng Việt") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (canRetry || canUndo) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetry) TextButton(onClick = onRetry, enabled = !busy) { Text("Gợi ý lại bằng Gemini") }
                if (canUndo) TextButton(onClick = onUndo, enabled = !busy) { Text("Khôi phục câu trước") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = onSave, enabled = !busy && text.isNotBlank() && text.trim() != cue.translation) { Text("Lưu") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Đóng") } })
}
