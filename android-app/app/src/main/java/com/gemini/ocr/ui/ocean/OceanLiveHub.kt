package com.gemini.ocr.ui.ocean

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.StatusNotice

/** Presentation only: all state and actions belong to the existing Activity/service. */
data class OceanLiveState(
    val running: Boolean,
    val paused: Boolean,
    val stage: String,
    val status: String,
    val attention: Boolean,
    val connected: Boolean,
    val backendBadge: String,
    val available: Boolean?,
    val captureMode: String,
    val regionLabel: String,
    val translationModeLabel: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OceanLiveHub(
    state: OceanLiveState,
    onSession: () -> Unit,
    onStop: () -> Unit,
    onRegion: () -> Unit,
    onCapture: () -> Unit,
    onScanMode: (String) -> Unit,
    onSettings: () -> Unit,
    onConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("GEMINI OCR", color = OceanColors.Cyan, style = MaterialTheme.typography.labelLarge)
                Text("Dịch trực tiếp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Trung → Việt", color = OceanColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("live_settings")) {
                Icon(Icons.Outlined.Tune, contentDescription = "Mở tùy chỉnh", tint = OceanColors.Cyan)
            }
        }
        OceanStatusIndicator(
            text = if (state.connected) state.backendBadge else "Chưa kết nối dịch",
            tone = when {
                state.connected && state.available == true -> OceanStatusTone.Active
                state.connected && state.available == false -> OceanStatusTone.Warning
                else -> OceanStatusTone.Neutral
            }, modifier = Modifier.testTag("ocean_backend_status"),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 680.dp
            val hero: @Composable (Modifier) -> Unit = { cardModifier ->
                OceanCard(cardModifier, elevated = true) {
                    Column(Modifier.fillMaxWidth().background(Brush.radialGradient(
                        listOf(OceanColors.Blue.copy(alpha = .16f), OceanColors.Elevated))),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.stage, color = if (state.attention) OceanColors.Warning else OceanColors.Mint,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("live_session_state"))
                        OceanSessionButton(
                            text = if (!state.running) "Bắt đầu dịch" else if (state.paused) "Tiếp tục" else "Tạm dừng",
                            running = state.running, paused = state.paused, onClick = onSession,
                            modifier = Modifier.testTag("primary_session_button"),
                        )
                        Text(if (state.running) "Điều khiển phiên dịch bằng nút nổi khi xem nội dung."
                            else "Cấp quyền màn hình, rồi mở nội dung bạn muốn dịch.",
                            color = OceanColors.Muted, style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (state.running) TextButton(onClick = onStop, modifier = Modifier.testTag("live_stop")) {
                            Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text("Kết thúc")
                        }
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
                Column(controlsModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OceanCard {
                        Text("Cách dịch màn hình", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Tự động", "frame" to "Một lần").forEach { (mode, label) ->
                                FilterChip(selected = state.captureMode == mode,
                                    onClick = { onScanMode(mode) }, enabled = !state.running,
                                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)
                                        .testTag(if (mode == "auto") "ocean_scan_auto" else "ocean_scan_frame"))
                            }
                        }
                        if (state.running && state.captureMode == "frame") OceanSecondaryButton(
                            "Dịch một khung", onCapture, Modifier.fillMaxWidth().testTag("live_capture_frame"))
                        Text(state.translationModeLabel, color = OceanColors.Muted,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("selected_translation_mode_badge"))
                    }
                    OceanCard(Modifier.testTag("quick_roi_choice")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Crop, null, tint = OceanColors.Cyan)
                            Column(Modifier.weight(1f)) {
                                Text("Vùng phụ đề", style = MaterialTheme.typography.titleSmall)
                                Text(state.regionLabel, color = OceanColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OceanSecondaryButton("Chọn vùng phụ đề", onRegion,
                            Modifier.fillMaxWidth().testTag("live_select_region"))
                        Text(if (state.running) "Khoanh vùng bằng công cụ hiện có trên màn hình."
                            else "Chọn vùng có sẵn; khoanh chính xác sau khi bật dịch.",
                            color = OceanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OceanSecondaryButton("Kết nối", onConnection,
                            Modifier.weight(1f).testTag("live_connection"))
                        OceanSecondaryButton("Cài đặt", onSettings, Modifier.weight(1f))
                    }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                hero(Modifier.weight(1f)); controls(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                hero(Modifier.fillMaxWidth()); controls(Modifier.fillMaxWidth())
            }
        }
        if (state.attention) StatusNotice(state.status, error = true)
        else OceanNotice(state.status, modifier = Modifier.testTag("ocean_session_notice"))
    }
}
