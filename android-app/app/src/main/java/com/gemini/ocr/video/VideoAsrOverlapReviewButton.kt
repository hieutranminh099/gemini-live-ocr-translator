package com.gemini.ocr.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun asrReviewTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
    ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

@Composable
internal fun VideoAsrOverlapReviewButton(project: VideoProject) {
    val observations = remember(project.id, project.updatedAt, project.speech, project.cues) { videoAsrOverlapPreviews(project) }
    if (observations.isEmpty()) return
    var shown by remember(project.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { shown = true }, modifier = Modifier.testTag("asr_overlap_review")) {
        Text("Đối chiếu ${observations.size} đoạn giọng nói trùng thời gian")
    }
    if (shown) AlertDialog(onDismissRequest = { shown = false },
        title = { Text("Đoạn giọng nói cần đối chiếu") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Các đoạn dưới đây chưa được thêm vào phụ đề để tránh trùng câu. Phụ đề và câu đã duyệt vẫn được giữ. Nghe lại đoạn tương ứng trước khi sửa thủ công.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(observations) { index, observation ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${asrReviewTime(observation.startMs)} → ${asrReviewTime(observation.endMs)}", style = MaterialTheme.typography.labelLarge)
                        Text("Giọng nói nhận được", style = MaterialTheme.typography.labelMedium)
                        Text(observation.source)
                        Text("Phụ đề hiện có", style = MaterialTheme.typography.labelMedium)
                        Text(observation.existingSources.joinToString("\n").ifBlank { "Không còn câu tương ứng trong bản phụ đề hiện tại." })
                        if (index + 1 < observations.size) HorizontalDivider()
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { shown = false }) { Text("Đóng") } })
}
