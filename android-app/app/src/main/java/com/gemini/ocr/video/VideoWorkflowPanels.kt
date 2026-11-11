package com.gemini.ocr.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.ui.ocean.OceanCard
import com.gemini.ocr.ui.ocean.OceanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun VideoThumbnail(project: VideoProject, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(project.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project.uri) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = runCatching { VideoDecoder.preview(context, Uri.parse(project.uri), minOf(1000, project.durationMs / 4)) }.getOrNull() }
            bitmap = decoded
        } finally { if (!currentCoroutineContext().isActive) decoded?.recycle() }
    }
    DisposableEffect(project.uri) { onDispose { bitmap?.recycle(); bitmap = null } }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Ảnh xem trước video", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun <T> VideoChoiceRow(title: String, selected: T, choices: List<Pair<T, String>>, enabled: Boolean,
                                onSelect: (T) -> Unit, tag: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: selected.toString(), modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 560.dp)) {
                choices.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(value) })
                }
            }
        }
    }
}

@Composable
internal fun VideoSetupPanel(project: VideoProject, enabled: Boolean, keyReady: Boolean,
    offlineState: String, tracks: List<VideoTextTrack>, onSave: (VideoProject) -> Unit,
    onPrepare: () -> Unit, onSubtitle: () -> Unit, onTracks: () -> Unit, onRescan: () -> Unit,
    onSettings: () -> Unit, onCancelEconomy: () -> Unit = {}, preview: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Thiết lập nhanh", style = MaterialTheme.typography.titleMedium)
        Text("Chọn cách dịch và nguồn phụ đề cho video này.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val modePanel: @Composable (Modifier) -> Unit = { panelModifier ->
                OceanCard(panelModifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Translate, null, tint = OceanColors.Cyan)
                        Text("Cách dịch video", style = MaterialTheme.typography.titleMedium)
                    }
                    VideoChoiceRow("Chế độ dịch", project.translationMode, listOf(
                        VideoTranslationMode.OFFLINE to "Trên thiết bị · không cần mạng sau khi chuẩn bị",
                        VideoTranslationMode.GEMINI to "Gemini · cần mạng",
                        VideoTranslationMode.HYBRID to "Local + Gemini · toàn bộ hội thoại"
                    ), enabled, { onSave(project.copy(translationMode = it)) }, "video_mode_choice")
                    if (project.translationMode != VideoTranslationMode.OFFLINE) {
                        Text(if (project.translationMode == VideoTranslationMode.HYBRID)
                            "Quét hết video trên thiết bị (100% thời lượng), lưu nguồn và mốc thời gian, rồi gửi Gemini. Hội thoại vừa giới hạn dùng một lượt; phần dài được tự chia thành ít lượt nhất theo ngân sách, giữ kết quả đã xong. Kết quả OCR vẫn cần đối chiếu."
                            else "Dùng mạng và hạn mức Gemini. Với chữ trong hình, chỉ vùng phụ đề được gửi để đọc; câu nguồn được gửi để dịch.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!keyReady && (project.translationMode != VideoTranslationMode.HYBRID ||
                            project.cues.any { !VideoTranslationPlan.isComplete(it, true) })) {
                            StatusNotice(if (VideoHybridWorkflow.requiresExtraction(project))
                                "Có thể lấy phụ đề trên thiết bị trước. Thiết lập Gemini để dịch sau khi lấy đủ nguồn."
                                else if (project.translationMode == VideoTranslationMode.HYBRID)
                                    "Nguồn đã lấy đủ (100%). Thiết lập kết nối Gemini để dịch toàn bộ hội thoại."
                                else "Chưa thiết lập kết nối Gemini trong Tùy chỉnh.")
                            TextButton(onClick = onSettings) { Text("Mở Tùy chỉnh") }
                        }
                    }
                    if (project.translationMode == VideoTranslationMode.OFFLINE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (offlineState.contains("sẵn sàng")) "Gói dịch trên thiết bị đã sẵn sàng" else "Cần chuẩn bị gói dịch trên thiết bị",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.testTag("video_prepare_offline")) { Text("Chuẩn bị") }
                        }
                    }
                }
            }
            val sourcePanel: @Composable (Modifier) -> Unit = { panelModifier ->
                OceanCard(panelModifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Subtitles, null, tint = OceanColors.Mint)
                        Text("Nguồn nội dung", style = MaterialTheme.typography.titleMedium)
                    }
                    VideoChoiceRow("Nguồn phụ đề", project.sourceMode, listOf(
                        VideoSourceMode.AUTO to "Tự động đề xuất",
                        VideoSourceMode.EMBEDDED to "Phụ đề có sẵn trong video",
                        VideoSourceMode.SIDECAR to "Phụ đề có sẵn · tệp riêng",
                        VideoSourceMode.OCR to "OCR hình ảnh",
                        VideoSourceMode.ASR to "Nhận diện giọng nói"
                    ), enabled, { onSave(project.copy(sourceMode = it)) }, "video_source_choice")
                    when (project.sourceMode) {
                        VideoSourceMode.SIDECAR -> OutlinedButton(onClick = onSubtitle, enabled = enabled,
                            modifier = Modifier.testTag("video_select_subtitle")) { Text(project.subtitleName.ifBlank { "Chọn tệp SRT, ASS hoặc VTT" }) }
                        VideoSourceMode.EMBEDDED -> {
                            TextButton(onClick = onTracks, enabled = enabled) { Text("Kiểm tra phụ đề có sẵn") }
                            if (tracks.isNotEmpty()) VideoChoiceRow("Bản phụ đề", project.trackId,
                                listOf(-1 to "Tự chọn bản đầu tiên") + tracks.map { it.id to "Bản ${it.id + 1} · ${it.language.ifBlank { "Chưa rõ ngôn ngữ" }}" },
                                enabled, { onSave(project.copy(trackId = it)) }, "video_track_choice")
                        }
                        VideoSourceMode.AUTO, VideoSourceMode.OCR -> ExpandableSection("Chỉnh vùng chữ", "Mở nếu phụ đề nằm ngoài vùng mặc định") {
                            preview()
                            Text("Chọn “Khoanh vùng chữ”, rồi kéo quanh phụ đề Trung.", style = MaterialTheme.typography.bodySmall)
                        }
                        VideoSourceMode.ASR -> Text("Nghe lời thoại tiếng Trung trên thiết bị để tạo phụ đề có thời gian. Đoạn không rõ được đánh dấu để kiểm tra.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (project.sourceMode == VideoSourceMode.AUTO) Text("Ưu tiên phụ đề có sẵn, rồi OCR toàn video. Chỉ bổ sung từ âm thanh sau khi kiểm tra độ đầy đủ; ảnh mẫu trống không được coi là OCR thất bại.", style = MaterialTheme.typography.bodySmall)
                    if (project.sourceMode in setOf(VideoSourceMode.AUTO, VideoSourceMode.ASR)) AsrModelManagerButton(enabled)
                }
            }
            if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                modePanel(Modifier.weight(1f))
                sourcePanel(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                modePanel(Modifier.fillMaxWidth())
                sourcePanel(Modifier.fillMaxWidth())
            }
        }
        OceanCard {
            VideoChoiceRow("Ngữ cảnh", project.profile,
                listOf("manga" to "Cổ trang / Tu tiên", "document" to "Phim hiện đại", "game" to "Game / Kiếm hiệp"),
                enabled, { onSave(project.copy(profile = it)) }, "video_context_choice")
        }
        OceanCard {
            ExpandableSection("Tùy chọn nâng cao", "Từ điển riêng, độ chi tiết và thông tin xử lý") {
                val context = androidx.compose.ui.platform.LocalContext.current
                val smartSettings = remember { VideoSmartBatchSettings(context) }
                var groupSize by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(smartSettings.groupSize) }
                var economy by rememberSaveable { mutableStateOf(smartSettings.economyMode) }
                if (project.translationMode == VideoTranslationMode.GEMINI) {
                    VideoChoiceRow("Số câu mỗi nhóm Gemini", groupSize, VideoSmartBatchSettings.GROUP_SIZES.map { it to "$it câu" }, enabled,
                        { groupSize = it; smartSettings.groupSize = it }, "video_batch_size")
                }
                if(project.translationMode!=VideoTranslationMode.OFFLINE) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(economy,{economy=it;smartSettings.economyMode=it},enabled=enabled)
                        Text("Dịch nền tiết kiệm · Gemini Batch API",Modifier.weight(1f))
                    }
                    if(economy) {
                        Text("Chỉ bật khi tài khoản/model hỗ trợ. Google xử lý bất đồng bộ; bấm Tiếp tục để lấy kết quả. Tạm dừng ứng dụng giữ mã tác vụ, không tự gửi lại hoặc đổi sang dịch nhanh.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick=onCancelEconomy,enabled=enabled){Text("Hủy tác vụ Gemini nền của video này")}
                    }
                }
                VideoGlossary(project, enabled) { onSave(project.copy(glossary = it)) }
                if (project.sourceMode == VideoSourceMode.OCR && project.translationMode == VideoTranslationMode.GEMINI) {
                    VideoChoiceRow("Độ chi tiết khi đọc chữ", project.visualSampleMs,
                        listOf(250L to "Chi tiết · 250 ms", 500L to "Giảm số ảnh · 500 ms"),
                        enabled && project.cues.isEmpty() && project.scanMs == 0L,
                        { onSave(project.copy(visualSampleMs = it)) }, "video_sampling_choice")
                    Text("Giảm số ảnh có thể bỏ sót câu ngắn. Lựa chọn không tự thay đổi khi xử lý.", style = MaterialTheme.typography.bodySmall)
                }
                if (project.sourceMode == VideoSourceMode.OCR && (project.cues.isNotEmpty() || project.scanMs > 0)) {
                    OutlinedButton(onClick = onRescan, enabled = enabled, modifier = Modifier.testTag("video_visual_revision")) {
                        Text("Tạo bản quét mới bằng Gemini · gửi ảnh")
                    }
                    Text("Giữ dự án và câu đã duyệt hiện tại. Bản mới dùng chế độ Gemini để đọc ảnh vùng chữ khi bạn bắt đầu xử lý.", style = MaterialTheme.typography.bodySmall)
                }
                if (project.translationMode == VideoTranslationMode.OFFLINE) Text(offlineState, style = MaterialTheme.typography.bodySmall)
                Text("Video không có chữ hoặc tệp phụ đề chưa hỗ trợ nhận dạng giọng nói.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
