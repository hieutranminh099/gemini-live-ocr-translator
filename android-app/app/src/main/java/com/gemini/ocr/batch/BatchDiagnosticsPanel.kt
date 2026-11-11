package com.gemini.ocr.batch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.video.VideoBatchDiagnosticSnapshot
import com.gemini.ocr.video.VideoBatchDiagnostics
import com.gemini.ocr.video.VideoDiagnosticCauses
import com.gemini.ocr.video.VideoResponseFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** No disk work while collapsed, and all measurements are read away from the UI thread. */
@Composable
internal fun BatchDiagnosticsPanel(projectIds: List<String>, running: Boolean, modifier: Modifier = Modifier) {
    ExpandableSection("Thống kê dịch và lỗi", "Lượt gửi, phản hồi và thời gian chờ đã ghi",
        modifier = modifier.testTag("batch_diagnostics")) {
        BatchDiagnosticContent(projectIds, running)
    }
}

@Composable
private fun BatchDiagnosticContent(projectIds: List<String>, running: Boolean) {
    val context = LocalContext.current.applicationContext
    val ids = remember(projectIds) { projectIds.distinct() }
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot by produceState<VideoBatchDiagnosticSnapshot?>(null, ids, running, refresh) {
        do {
            value = withContext(Dispatchers.IO) { VideoBatchDiagnostics.read(context, ids) }
            if (!running && (value?.retryAtMs ?: 0L) <= System.currentTimeMillis()) break
            delay(3_000)
        } while (true)
    }
    val data = snapshot
    if (data == null) Text("Đang đọc số đo…", style = MaterialTheme.typography.bodySmall)
    else {
        val format = remember { NumberFormat.getIntegerInstance(Locale("vi", "VN")) }
        fun number(value: Long) = format.format(value)
        if (data.requests == 0L) Text("Chưa có lượt dịch nào được ghi.")
        else {
            Text("Đã gửi ${number(data.requests)} lượt · ${number(data.responses)} phản hồi đọc được")
            Text("Phản hồi thiếu: ${number(data.partialResponses)} · rỗng: ${number(data.emptyResponses)} · bị cắt: ${number(data.truncatedResponses)}")
            Text("Cộng dồn phản hồi: ${number(data.acceptedCues)} lượt câu hợp lệ · ${number(data.missingCues)} lượt câu bị thiếu (có thể lặp)",
                style = MaterialTheme.typography.bodySmall)
            Text("Số câu chưa xong hiện tại hiển thị ở từng video.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Lượt chờ/thử lại: ${number(data.retryWaits)} · yêu cầu dừng vì lỗi: ${number(data.failedRequests)}")
        diagnosticCauseLabel(data.retryCauses)?.let { Text("Lý do chờ: $it", style = MaterialTheme.typography.bodySmall) }
        diagnosticCauseLabel(data.failureCauses)?.let { Text("Lý do dừng: $it", style = MaterialTheme.typography.bodySmall) }
        if (data.responseFailureReason > 0) Text("Phản hồi lỗi gần nhất: ${VideoResponseFailureReason.fromId(data.responseFailureReason).label}",
            style = MaterialTheme.typography.bodySmall)
        if (data.lastRequestCues > 0) Text("Nhóm gửi gần nhất: ${number(data.lastRequestCues)} câu", style = MaterialTheme.typography.bodySmall)
        if (data.requestCapacity > 0) Text("Giới hạn gửi ghi nhận gần nhất: ${data.requestCapacity}/${data.requestCapacityCeiling} yêu cầu đồng thời",
            style = MaterialTheme.typography.bodySmall)
        val now = System.currentTimeMillis()
        if (data.retryAtMs > now) {
            val seconds = (data.retryAtMs - now - 1) / 1000 + 1
            Text("Mốc chờ đã lưu: còn ${number(seconds)} giây", style = MaterialTheme.typography.bodySmall)
        }
        Text("Số liệu cộng dồn từ các lần xử lý. Phân loại lỗi chi tiết được ghi từ bản cập nhật này; lượt chờ không phải số lần gửi thực tế.",
            style = MaterialTheme.typography.bodySmall)
        if (data.limited || data.measuredProjects < data.requestedProjects) Text(
            "Có số đo của ${data.measuredProjects}/${data.requestedProjects} video${if (data.limited) "; chỉ đọc tối đa 200 video mỗi lần" else ""}.",
            style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { refresh++ }) { Text("Cập nhật số liệu") }
}

private fun diagnosticCauseLabel(causes: VideoDiagnosticCauses): String? = listOf(
    "giới hạn lượt gọi" to causes.rateLimit, "dịch vụ gián đoạn" to causes.service,
    "mạng/thời gian chờ" to causes.network, "khóa hoặc quyền truy cập" to causes.authorization,
    "hết hạn mức" to causes.quota, "kết quả chưa hợp lệ" to causes.response, "khác" to causes.other
).filter { it.second > 0 }.joinToString(" · ") { "${it.first}: ${it.second}" }.takeIf { it.isNotEmpty() }
