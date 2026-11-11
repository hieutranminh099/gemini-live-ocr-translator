package com.gemini.ocr.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoTranslationPlan
import com.gemini.ocr.video.VideoResponseFailureReason
import kotlinx.coroutines.delay

@Composable
internal fun BatchAutoRetrySetting(enabled: Boolean, editable: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tự tiếp tục khi gặp lỗi tạm thời", style = MaterialTheme.typography.titleSmall)
            Text("Thử lại phần còn thiếu sau 1, 2 rồi 4 phút, tối đa 3 lượt; chờ lâu hơn nếu Gemini yêu cầu. Lỗi cần chỉnh tay sẽ dừng để bạn kiểm tra.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChange, enabled = editable,
            modifier = Modifier.testTag("batch_auto_retry"))
    }
}

/** Countdowns observe persisted deadlines. Only the worker decides when to resume. */
@Composable
internal fun BatchRetryStatus(batch: BatchProject, projects: List<VideoProject>, running: Boolean) {
    val deadline = if (running) batchRetryDeadline(batch, projects)
        else if (batch.state == "PAUSED") batch.retryAfterMs else 0L
    if (deadline == 0L && (!running || !BatchWorkflowUi.retryWaiting(batch, projects))) return
    val seconds by produceState(batchRetrySeconds(deadline, System.currentTimeMillis()), batch.id, deadline) {
        value = batchRetrySeconds(deadline, System.currentTimeMillis())
        while (value > 0) {
            delay(250)
            value = batchRetrySeconds(deadline, System.currentTimeMillis())
        }
    }
    if (!running && seconds == 0L) return
    val remaining = BatchWorkflowUi.duration(seconds * 1000)
    val message = when {
        !running -> "Còn $remaining trước khi gửi yêu cầu Gemini mới. Hàng đợi đang tạm dừng."
        deadline == 0L -> "Đang tự khôi phục. Tiến độ đã được lưu."
        seconds == 0L -> "Đã hết thời gian chờ. Đang chuẩn bị tiếp tục từ mốc đã lưu."
        else -> "Tự tiếp tục sau $remaining. Có thể tạm dừng hoặc dừng hàng đợi ngay."
    }
    Column(Modifier.testTag("batch_retry_countdown")) { StatusNotice(message) }
}

internal fun batchRetryDeadline(batch: BatchProject, projects: List<VideoProject>): Long {
    if (batch.state == "RETRY_WAIT" && batch.autoRetryAtMs > 0) return batch.autoRetryAtMs
    val members = batch.items.mapTo(mutableSetOf()) { it.projectId }
    return projects.asSequence().filter { it.id in members }
        .map { it.pipeline.task(it.pipeline.currentStage) }
        .filter { it.status == "RETRY_WAIT" && it.nextRetryAt > 0 }
        .map { it.nextRetryAt }.minOrNull() ?: 0L
}

internal fun batchRetrySeconds(deadlineMs: Long, nowMs: Long): Long =
    if (deadlineMs <= nowMs) 0 else (deadlineMs - nowMs - 1) / 1000 + 1

@Composable
internal fun BatchRetryErrorNotice(item: BatchItem, project: VideoProject?) {
    if (item.error.isBlank() && item.errorCode.isBlank()) return
    val guidance = batchRetryGuidance(item, project)
    Card(Modifier.fillMaxWidth().testTag("batch_error_guidance"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(guidance.title, style = MaterialTheme.typography.titleSmall)
            Text(guidance.action, style = MaterialTheme.typography.bodySmall)
            if (guidance.unfinishedCues > 0) Text("Còn ${guidance.unfinishedCues} câu chưa hoàn tất. Các câu đã xong được giữ lại.",
                style = MaterialTheme.typography.bodySmall)
            if (guidance.code.isNotBlank()) ExpandableSection("Chi tiết lỗi") {
                Text(guidance.code, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal data class BatchRetryGuidance(val title: String, val action: String, val code: String,
    val unfinishedCues: Int)

internal fun batchRetryGuidance(item: BatchItem, project: VideoProject?): BatchRetryGuidance {
    val message = item.error
    val savedCode = item.errorCode.ifBlank { project?.pipeline?.task(item.stage)?.errorCode.orEmpty() }
    val code = savedCode.ifBlank { messageCode(message) }
    val responseReason = VideoResponseFailureReason.entries.firstOrNull {
        it != VideoResponseFailureReason.UNKNOWN && message.contains(it.label)
    }
    val (title, action) = when {
        code == "API_AUTH" || message.contains("chưa cấu hình khóa", true) ->
            "Cần kiểm tra kết nối Gemini" to "Mở Tùy chỉnh để kiểm tra khóa và quyền sử dụng, rồi bấm Tiếp tục."
        code == "API_QUOTA_EXHAUSTED" ->
            "Đã hết hạn mức Gemini" to "Kiểm tra hạn mức hoặc đợi hạn mức được cấp lại, rồi bấm Tiếp tục."
        code == "API_RATE_LIMIT" ->
            "Gemini đang giới hạn lượt gọi" to "Chờ hết thời gian giới hạn. Tiến độ được giữ ở mốc đã lưu."
        code == "API_NETWORK" || code == "API_TIMEOUT" ->
            "Kết nối tới Gemini bị gián đoạn" to "Kiểm tra mạng. Có thể tiếp tục từ các câu chưa hoàn tất."
        code.startsWith("API_SERVICE_") ->
            "Gemini tạm thời chưa đáp ứng" to "Đợi dịch vụ hoạt động trở lại. Các câu đã lưu được giữ nguyên."
        code == "API_HTTP_404" ->
            "Không tìm thấy dịch vụ Gemini đã chọn" to "Kiểm tra lựa chọn mô hình và quyền truy cập trong Tùy chỉnh, rồi bấm Tiếp tục."
        code == "OFFLINE_MODEL_MISSING" || message.contains("thiếu model offline", true) ->
            "Thiếu gói dịch trên máy" to "Vào Thiết lập phụ đề, chuẩn bị gói dịch rồi bấm Tiếp tục."
        code == "ASR_MODEL_MISSING" ->
            "Thiếu gói nhận diện giọng nói" to "Vào Nguồn phụ đề để tải gói tiếng Trung, rồi tiếp tục từ mốc đã lưu."
        code == "ASR_ABI_UNSUPPORTED" ->
            "Thiết bị chưa hỗ trợ nhận diện giọng nói" to "Chọn OCR hoặc phụ đề có sẵn trong Thiết lập video."
        code == "ASR_LOW_MEMORY" ->
            "Thiết bị thiếu bộ nhớ để nhận diện giọng nói" to "Đóng bớt ứng dụng rồi bấm Tiếp tục."
        code == "ASR_NO_AUDIO" || code == "ASR_UNCLEAR_AUDIO" ->
            "Âm thanh chưa có lời thoại rõ" to "Nghe lại video và chọn OCR hoặc tệp phụ đề nếu có."
        code.startsWith("ASR_AUDIO_") ->
            "Chưa đọc được âm thanh" to "Kiểm tra tệp video hoặc chọn phụ đề có sẵn trong Thiết lập video."
        code == "STORAGE_FULL" ->
            "Thiết bị thiếu chỗ trống" to "Giải phóng bộ nhớ lưu trữ rồi bấm Tiếp tục. Tiến độ đã lưu được giữ."
        code == "SOURCE_PERMISSION" ->
            "Ứng dụng chưa đọc được tệp nguồn" to "Kiểm tra quyền truy cập tệp và tệp còn ở vị trí đã chọn, rồi bấm Tiếp tục."
        code.startsWith("ROI_") || code == "OCR_NO_SUBTITLES" ->
            "Cần kiểm tra nguồn phụ đề" to "Mở Thiết lập video để kiểm tra vùng chữ hoặc chọn nguồn phụ đề khác."
        code.startsWith("FRAME_") ->
            "Đọc hình ảnh video bị gián đoạn" to "Kiểm tra video mở được và tệp đã tải đủ. Có thể tiếp tục từ mốc đã lưu."
        code == "TEXT_BLOCKED" ->
            "Gemini chưa thể xử lý nhóm câu này" to "Mở Xem và sửa phụ đề để kiểm tra các câu còn thiếu. Có thể chọn dịch trên máy cho tập này."
        code == "API_REQUEST_400" || code == "API_REQUEST_413" ->
            "Gemini chưa chấp nhận nhóm câu" to "Kiểm tra phụ đề nguồn và ngữ cảnh phim. Nếu dùng nhóm câu lớn, giảm số câu mỗi nhóm trong Thiết lập phụ đề."
        code == "TEXT_INVALID_RESPONSE" ->
            (responseReason?.label ?: "Gemini trả kết quả không đọc được") to
                "Tiến độ đã lưu. Mở Xem và sửa phụ đề để kiểm tra phần chưa xong. Lượt tiếp tục dùng nhóm câu nhỏ có giới hạn phục hồi."
        code == "TEXT_EMPTY_RESPONSE" ->
            "Gemini chưa trả bản dịch cho phần còn thiếu" to "Đã thử nhóm nhỏ hơn ba lần. Mở Xem và sửa phụ đề để kiểm tra nguồn, rồi bấm Tiếp tục khi sẵn sàng."
        code == "TEXT_INCOMPLETE_RESPONSE" || code == "TEXT_TRUNCATED" || code == "RESPONSE_NEEDS_REVIEW" ->
            "Một số nhóm câu chưa có kết quả đầy đủ" to "Mở Xem và sửa phụ đề để xem câu còn thiếu. Bấm Tiếp tục để xử lý phần còn lại."
        item.stage == BatchPipelineStage.POLISH ->
            "Hiệu đính chưa hoàn tất" to "Kiểm tra bản nháp. Có thể tắt hiệu đính trong Thiết lập phụ đề để hoàn tất bản dịch trên máy."
        else -> "Công đoạn chưa hoàn tất" to "Mở Thiết lập video hoặc Xem và sửa phụ đề để kiểm tra phần đang dở."
    }
    val unfinished = if (project != null && item.stage == BatchPipelineStage.TRANSLATE) {
        val hybrid = project.translationMode == VideoTranslationMode.HYBRID
        project.cues.count { !VideoTranslationPlan.isComplete(it, hybrid) }
    } else 0
    return BatchRetryGuidance(title, action, safeErrorCode(code), unfinished)
}

private val batchErrorCodePattern = Regex("(?:API_(?:AUTH|NETWORK|TIMEOUT|RATE_LIMIT|QUOTA_EXHAUSTED|(?:REQUEST|SERVICE|HTTP)_[1-5][0-9]{2})|" +
    "(?:ASR|FRAME|OCR|ROI|TEXT|POLISH|RESPONSE|STAGE|SOURCE|STORAGE|OFFLINE|EXPORT)_[A-Z0-9_]{2,43})")

private fun safeErrorCode(value: String): String = value.takeIf(batchErrorCodePattern::matches).orEmpty()

private fun messageCode(message: String): String = safeErrorCode(message.trimStart().removePrefix("[")
    .takeWhile { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
