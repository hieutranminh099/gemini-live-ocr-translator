package com.gemini.ocr.ui.ocean

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A different presentation of the existing five routes; callers retain navigation validation. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OceanWorkflowSteps(current: Int, onSelect: (Int) -> Unit, enabled: (Int) -> Boolean,
    completed: Set<Int> = emptySet()) {
    val labels = listOf("Chọn video", "Thiết lập", "Xử lý", "Kiểm tra", "Xuất")
    val requesters = remember { List(labels.size) { BringIntoViewRequester() } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val expanded = maxWidth >= 760.dp
        LaunchedEffect(current, expanded) {
            withFrameNanos { }
            requesters[current.coerceIn(0, labels.lastIndex)].bringIntoView()
        }
        Row(if (expanded) Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            else Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                val active = current == index
                val allowed = enabled(index)
                Surface(onClick = { onSelect(index) }, enabled = allowed,
                    modifier = (if (expanded) Modifier.weight(1f) else Modifier.widthIn(min = 142.dp))
                        .heightIn(min = 54.dp).bringIntoViewRequester(requesters[index]).testTag("workflow_step_$index").semantics {
                            selected = active
                            contentDescription = "Bước ${index + 1} · $label"
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = if (active) OceanColors.Elevated else OceanColors.Card,
                    border = BorderStroke(1.dp, if (active) OceanColors.Cyan else OceanColors.Outline.copy(alpha = .35f)),
                    contentColor = if (!allowed) OceanColors.Muted.copy(alpha = .55f)
                        else if (active) OceanColors.Cyan else OceanColors.Muted) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (index in completed && !active) Surface(shape = CircleShape, color = OceanColors.Mint.copy(alpha = .14f)) {
                            Icon(Icons.Outlined.Check, "Đã hoàn thành", Modifier.padding(3.dp).size(16.dp), tint = OceanColors.Mint)
                        }
                        Text("${index + 1} · $label", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
