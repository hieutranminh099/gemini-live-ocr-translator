package com.gemini.ocr.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.asr.ChineseAsrModels
import com.gemini.ocr.asr.ChineseAsrLicense
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explicit package preparation shared by single-video and batch setup. Opening it never downloads. */
@Composable
internal fun AsrModelManagerButton(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val models = remember(context) { ChineseAsrModels(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(models.status()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        while (shown) { status = models.status(); delay(350) }
    }
    OutlinedButton(onClick = { status = models.status(); message = ""; shown = true }, enabled = enabled,
        modifier = modifier.testTag("asr_model_manager")) {
        Text("Gói nhận diện giọng nói tiếng Trung")
    }
    if (shown) AlertDialog(
        onDismissRequest = { if (!working && !status.downloading) shown = false },
        title = { Text("Nhận diện giọng nói offline") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tải gói tiếng Trung một lần, sau đó nghe lời thoại mà không gửi âm thanh lên mạng. Gói nhận diện khoảng ${status.totalBytes / 1_000_000} MB; cần thêm bộ nhớ để xử lý video.")
            Text(if (status.ready) "Gói tiếng Trung đã sẵn sàng." else status.message.ifBlank { "Chưa có gói nhận diện trên thiết bị." })
            if (status.downloading) {
                val fraction = if (status.totalBytes > 0) (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("Đã tải ${status.downloadedBytes / 1_000_000} / ${status.totalBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            Text("${ChineseAsrModels.ATTRIBUTION} Gói được tải từ nguồn phát hành và kiểm tra trước khi sử dụng.",
                style = MaterialTheme.typography.bodySmall)
            Text("Mô hình: FunASR Model License v1.1. Bộ chạy: Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLicense = true }) { Text("Xem giấy phép và nguồn mô hình") }
            if (status.ready) TextButton(onClick = { confirmRemoval = true }, enabled = enabled && !working && !status.downloading) {
                Text("Gỡ gói nhận diện để giải phóng bộ nhớ")
            }
        } },
        confirmButton = {
            if (status.supported && !status.ready && !status.downloading) TextButton(onClick = {
                working = true; message = ""
                scope.launch {
                    try { models.install(); message = "Đã chuẩn bị gói tiếng Trung. Có thể nhận diện offline." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Chưa tải được gói nhận diện. Kiểm tra mạng rồi thử lại." }
                    finally { working = false; status = models.status() }
                }
            }, enabled = enabled && !working, modifier = Modifier.testTag("asr_model_download")) { Text("Tải gói tiếng Trung") }
            else TextButton(onClick = { if (!working && !status.downloading) shown = false }, enabled = !working && !status.downloading) { Text("Xong") }
        },
        dismissButton = {
            if (status.downloading) TextButton(onClick = { models.cancelInstall(); message = "Đã yêu cầu dừng tải. Có thể tải lại sau." }) { Text("Dừng tải") }
            else TextButton(onClick = { shown = false }, enabled = !working) { Text("Đóng") }
        }
    )
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text("Gỡ gói nhận diện tiếng Trung?") },
        text = { Text("Phụ đề và tiến độ đã lưu được giữ. Bạn cần tải lại gói trước lần nhận diện giọng nói tiếp theo.") },
        confirmButton = { TextButton(onClick = {
            confirmRemoval = false; working = true
            scope.launch {
                try { models.remove(); message = "Đã gỡ gói nhận diện. Phụ đề đã lưu được giữ." }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Chưa gỡ được gói nhận diện." }
                finally { working = false; status = models.status() }
            }
        }, enabled = enabled && !working) { Text("Gỡ gói") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Giữ lại") } })
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false },
        title = { Text("Giấy phép gói nhận diện") },
        text = { Text(ChineseAsrLicense.TEXT, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Đóng") } })
}
