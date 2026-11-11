package com.gemini.ocr.ui.ocean

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Existing picker callback only; no file reading, metadata generation or import logic. */
@Composable
fun OceanVideoImport(title: String?, detail: String, enabled: Boolean, canRemove: Boolean,
    onPick: () -> Unit, onRemove: () -> Unit, thumbnail: @Composable () -> Unit) {
    OceanCard(elevated = true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val info: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (title != null) thumbnail()
                    else Icon(Icons.Outlined.VideoLibrary, null, tint = OceanColors.Cyan, modifier = Modifier.size(42.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title ?: "Thêm video để dịch", style = MaterialTheme.typography.titleLarge)
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = OceanColors.Muted)
                        if (title != null) TextButton(onClick = onRemove, enabled = canRemove) { Text("Bỏ video khỏi lựa chọn") }
                    }
                }
            }
            val picker: @Composable () -> Unit = {
                OceanPrimaryButton(if (title == null) "Chọn video" else "Chọn video khác", onPick,
                    Modifier.testTag("video_select"), enabled = enabled)
            }
            if (maxWidth >= 620.dp) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                info(Modifier.weight(1f)); picker()
            } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                info(Modifier.fillMaxWidth()); picker()
            }
        }
        Text("Có thể chọn một hoặc nhiều video", style = MaterialTheme.typography.bodySmall, color = OceanColors.Muted)
    }
}
