package com.gemini.ocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Display only: the capture service owns sampling. Opening this panel never starts a timer. */
@Composable
fun PerformancePanel(
    modifier: Modifier = Modifier,
    telemetry: PerformanceTelemetry = AndroidPerformanceTelemetry.counters,
) {
    val snapshot by telemetry.snapshot.collectAsState()
    Card(modifier.fillMaxWidth().testTag("performance_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Hiệu năng", style = MaterialTheme.typography.titleMedium)
                    Text("Đo tối đa 1 lần/giây khi bật", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = snapshot.enabled,
                    onCheckedChange = telemetry::setEnabled,
                    modifier = Modifier.testTag("performance_enabled").semantics { contentDescription = "Đo hiệu năng" },
                )
            }
            if (!snapshot.enabled) {
                Text("Chưa bật đo hiệu năng.", style = MaterialTheme.typography.bodySmall)
            } else if (snapshot.sampledAtMs == null) {
                Text("Đang chờ mẫu đo từ phiên dịch.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerformanceMetric("FPS nhận ảnh", rate(snapshot, PerformanceFrameStage.CAPTURE_ACQUIRED),
                    "performance_capture_fps", Modifier.weight(1f))
                PerformanceMetric("FPS OCR", rate(snapshot, PerformanceFrameStage.OCR_COMPLETED),
                    "performance_ocr_fps", Modifier.weight(1f))
                PerformanceMetric("FPS Canvas", rate(snapshot, PerformanceFrameStage.CANVAS_DRAWN),
                    "performance_canvas_fps", Modifier.weight(1f))
            }
            Text("Nhận ảnh: Image đã lấy; OCR: lượt native hoàn tất; Canvas: lượt vẽ overlay. " +
                "Đây không phải FPS video hay số câu dịch/giây.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PerformanceMetric("CPU tiến trình", snapshot.cpuOneCorePercent?.let { "${decimal(it)}%" } ?: "—",
                    "performance_cpu", Modifier.weight(1f))
                PerformanceMetric("RAM tiến trình (PSS)", snapshot.pssKiB?.let { "${decimal(it / 1024.0)} MiB" } ?: "—",
                    "performance_pss", Modifier.weight(1f))
            }
            Text("CPU: 100% = 1 lõi, có thể vượt 100%. PSS là mẫu RAM hiện tại, không phải peak.",
                style = MaterialTheme.typography.bodySmall)
            if (snapshot.resourceSampleFailed) {
                Text("Không đọc được số liệu tiến trình trong lần lấy mẫu này.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Tra cứu cache đã hoàn tất", style = MaterialTheme.typography.titleSmall)
            PerformanceCacheTier.entries.forEach { tier ->
                val metric = snapshot.caches[tier]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(cacheLabel(tier), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (metric?.hitRate == null) "Chưa có lượt tra cứu"
                        else "${decimal(metric.hitRate!! * 100)}% • ${metric.hits} trúng / ${metric.misses} trượt" +
                            " • đơn vị: ${if (tier.lookupUnit == "source_line") "dòng nguồn" else "lượt dịch"}",
                        modifier = Modifier.testTag("performance_cache_${tier.name.lowercase(Locale.ROOT)}"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("Tỷ lệ mỗi tầng = trúng / (trúng + trượt). Không cộng tầng đếm dòng với tầng đếm lượt dịch.",
                style = MaterialTheme.typography.bodySmall)
            Text("Nguồn bản dịch đang thấy", style = MaterialTheme.typography.titleSmall)
            Text(sourceLabel(snapshot.lastVisibleTranslationSource),
                modifier = Modifier.testTag("performance_visible_source"),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PerformanceMetric(label: String, value: String, tag: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium)
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
private fun rate(snapshot: PerformanceSnapshot, stage: PerformanceFrameStage): String =
    snapshot.frames[stage]?.perSecond?.let(::decimal) ?: "—"

private fun cacheLabel(tier: PerformanceCacheTier): String = when (tier) {
    PerformanceCacheTier.PHONE_EXACT -> "Cache chính xác trên điện thoại"
    PerformanceCacheTier.REVIEWED_MEMORY -> "Kho đã duyệt"
    PerformanceCacheTier.DIRECT_LRU -> "Gemini Direct LRU"
    PerformanceCacheTier.LEGACY_BANK -> "Kho cũ (theo lượt dịch)"
}

private fun sourceLabel(source: PerformanceTranslationSource?): String = when (source) {
    null -> "Chưa có bản dịch được vẽ"
    PerformanceTranslationSource.PHONE_CACHE -> "Cache trên điện thoại"
    PerformanceTranslationSource.REVIEWED_MEMORY -> "Kho đã duyệt"
    PerformanceTranslationSource.GLOSSARY -> "Thuật ngữ"
    PerformanceTranslationSource.LEGACY_BANK -> "Kho cũ"
    PerformanceTranslationSource.ON_DEVICE -> "Mô hình trên thiết bị"
    PerformanceTranslationSource.GEMINI -> "Gemini"
    PerformanceTranslationSource.WINDOWS -> "Windows Companion"
    PerformanceTranslationSource.MIXED -> "Nhiều nguồn"
    PerformanceTranslationSource.UNKNOWN -> "Chưa xác định"
}
