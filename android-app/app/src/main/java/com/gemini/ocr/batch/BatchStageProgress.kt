package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ocean.OceanCard
import com.gemini.ocr.video.VideoProject

/** Read-only projection of durable worker state; opening a screen never advances a stage. */
@Composable
internal fun BatchStageProgress(batch: BatchProject, projects: List<VideoProject>) {
    OceanCard(Modifier.testTag("batch_stage_progress"), elevated = true) {
        Text(BatchWorkflowUi.stageLabel(batch.currentStage), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatchPipelineStage.entries.forEach { stage ->
                SuggestionChip(onClick = {}, enabled = false,
                    label = { Text(BatchWorkflowUi.stageLabel(stage)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        disabledLabelColor = if (stage == batch.currentStage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        Text("Trích xuất → Đối chiếu → Dịch → Hiệu đính → Hoàn thiện", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { BatchWorkflowUi.progress(batch, projects) }, modifier = Modifier.fillMaxWidth())
        Text("Mỗi công đoạn đã xong được giữ lại khi tiếp tục.", style = MaterialTheme.typography.bodySmall)
    }
}
