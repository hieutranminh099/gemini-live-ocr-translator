package com.gemini.ocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun GeminiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF6EE7C7), onPrimary = Color(0xFF09231D),
        primaryContainer = Color(0xFF163E34), onPrimaryContainer = Color(0xFFB7F7E4),
        secondary = Color(0xFFA8CDBF), onSecondary = Color(0xFF112C22),
        secondaryContainer = Color(0xFF29473B), onSecondaryContainer = Color(0xFFC4EAD7),
        background = Color(0xFF0C1115), onBackground = Color(0xFFE4EBEE),
        surface = Color(0xFF151D23), onSurface = Color(0xFFE4EBEE),
        surfaceVariant = Color(0xFF202C33), onSurfaceVariant = Color(0xFFB3C1C8),
        surfaceContainerLowest = Color(0xFF090F12), surfaceContainerLow = Color(0xFF11191E),
        surfaceContainer = Color(0xFF172127), surfaceContainerHigh = Color(0xFF1C2830),
        surfaceContainerHighest = Color(0xFF25343D),
        outline = Color(0xFF45545D), error = Color(0xFFFFB4AB)
    ), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(18.dp)), content = content)
}

/** A bounded form with a fixed close action, including when the keyboard is open. */
@Composable
fun EditorDialog(title: String, onDismiss: () -> Unit, closeEnabled: Boolean = true, closeLabel: String = "Xong",
                 actions: @Composable RowScope.() -> Unit = {},
                 content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = closeEnabled) { Text(closeLabel) }
                        actions()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, subtitle: String = "", onBack: (() -> Unit)? = null,
              actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
        }
    }, actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun ExpandableSection(title: String, subtitle: String = "", initiallyExpanded: Boolean = false,
                      modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotBlank()) Text(subtitle, modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Thu gọn $title" else "Mở $title")
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

internal data class FriendlyError(val title: String, val guidance: String)

internal fun friendlyError(detail: String): FriendlyError {
    // Codes survive checkpoints in plain and bracketed messages. Read local source failures
    // before loose HTTP/timeout heuristics: a source timestamp can itself contain 429 or 401.
    val code = detail.trimStart().removePrefix("[")
        .takeWhile { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }
        .uppercase(java.util.Locale.ROOT)
    return when {
        code.startsWith("FRAME_DECODE") || code.startsWith("FRAME_CODEC") ->
            FriendlyError("Đọc video bị gián đoạn", "Tiến độ và phụ đề đã lưu vẫn được giữ. Tiếp tục từ mốc đã lưu; nếu lỗi lặp lại, thử bản video khác trong máy.")
        code == "FRAME_NULL" || code == "FRAME_INVALID" ->
            FriendlyError("Chưa lấy được khung hình", "Kiểm tra tệp video và vùng phụ đề. Phần đã xử lý vẫn được giữ.")
        code.startsWith("ROI_") ->
            FriendlyError("Vùng phụ đề cần kiểm tra", "Mở Thiết lập phụ đề để kiểm tra vùng chữ. Tiến độ hiện tại vẫn được giữ.")
        detail.contains("429") || detail.contains("hạn mức", true) -> FriendlyError("Đang chờ hạn mức dịch", "Tiến độ đã lưu. Chờ hết thời gian giới hạn rồi thử lại.")
        detail.contains("OAuth", true) || detail.contains("Google", true) && (detail.contains("quyền", true) || detail.contains("authorization", true)) ->
            FriendlyError("Chưa thể kết nối Google Drive", "Chọn lại tài khoản hoặc xem chi tiết để kiểm tra quyền kết nối.")
        detail.contains("khóa", true) || detail.contains("401") || detail.contains("403") ->
            FriendlyError("Cần kiểm tra kết nối dịch", "Mở Tùy chỉnh để kiểm tra khóa và quyền sử dụng dịch vụ.")
        detail.contains("video", true) && (detail.contains("đọc", true) || detail.contains("track", true) || detail.contains("Media", true)) ->
            FriendlyError("Không đọc được video này", "Kiểm tra định dạng hoặc thử chọn bản video đã tải xong trong máy.")
        detail.contains("TIMEOUT") || detail.contains("mạng", true) || detail.contains("NETWORK") ->
            FriendlyError("Kết nối đang gián đoạn", "Kiểm tra mạng rồi thử lại. Tiến độ đã lưu vẫn được giữ.")
        detail.contains("không có phụ đề", true) || detail.contains("không tìm thấy chữ", true) ->
            FriendlyError("Chưa tìm thấy phụ đề nguồn", "Thử chọn tệp phụ đề hoặc khoanh lại vùng chữ trong video.")
        else -> FriendlyError("Thao tác chưa hoàn tất", "Xem chi tiết và thử lại khi đã xử lý nguyên nhân. Dữ liệu đã lưu vẫn được giữ.")
    }
}

@Composable
fun StatusNotice(message: String, error: Boolean = false, onRetry: (() -> Unit)? = null) {
    if (message.isBlank()) return
    val friendly = if (error) friendlyError(message) else null
    Card(Modifier.fillMaxWidth().testTag(if (error) "status_error" else "status_notice"),
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (error) Icons.Default.WarningAmber else Icons.Default.Info, null,
                    tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Text(friendly?.title ?: message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (friendly != null) {
                Text(friendly.guidance, style = MaterialTheme.typography.bodySmall)
                ExpandableSection("Chi tiết kỹ thuật") {
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(message, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Thử lại") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSteps(current: Int, onSelect: (Int) -> Unit, enabled: (Int) -> Boolean = { true }) {
    val labels = listOf("Chọn video", "Thiết lập", "Xử lý", "Kiểm tra", "Xuất")
    ScrollableTabRow(current.coerceIn(0, 4), edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.background, divider = {}) {
        labels.forEachIndexed { index, label ->
            Tab(selected = current == index, onClick = { onSelect(index) }, enabled = enabled(index),
                modifier = Modifier.testTag("workflow_step_$index"),
                text = { Text("${index + 1} · $label", maxLines = 1) })
        }
    }
}
