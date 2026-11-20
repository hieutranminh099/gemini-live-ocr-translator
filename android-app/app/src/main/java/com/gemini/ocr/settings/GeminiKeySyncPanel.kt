package com.gemini.ocr.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GeminiKeySyncPanel(settings: AppSettings, enabled: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { VideoCloudSync.get(context) }
    val account by sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    fun transfer(restore: Boolean) {
        if (busy) return
        val secret = password.toCharArray()
        password = ""
        busy = true
        scope.launch {
            try {
                if (restore) {
                    sync.restoreGeminiKeys(settings, secret)
                    onChanged()
                    status = "Đã khôi phục các khóa Gemini vào bộ nhớ bảo mật."
                } else {
                    sync.saveGeminiKeys(settings, secret)
                    status = "Đã lưu bản sao khóa mã hóa trên Google Drive."
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Chưa đồng bộ được. Kiểm tra kết nối Drive, tài khoản và mật khẩu sao lưu." }
            finally { secret.fill('\u0000'); busy = false }
        }
    }
    ExpandableSection("Đồng bộ khóa Gemini", "Bản sao mã hóa theo tài khoản Google Drive") {
        Text(account.accountEmail.ifBlank { "Kết nối Google Drive trong Kho phim trước." })
        Text("Dùng cùng tài khoản và mật khẩu sao lưu trên thiết bị khác để khôi phục ba ô khóa. Mật khẩu cần ít nhất 12 ký tự.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu sao lưu") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            enabled = enabled && !busy, modifier = Modifier.fillMaxWidth())
        val ready = enabled && !busy && password.length in 12..256 &&
            account.accountEmail.isNotBlank() && !account.authorizationRequired
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { transfer(false) }, enabled = ready) { Text("Lưu lên Drive") }
            OutlinedButton(onClick = { confirmRestore = true }, enabled = ready) { Text("Khôi phục từ Drive") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text("Khôi phục khóa Gemini?") },
        text = { Text("Ba ô khóa trên thiết bị sẽ được thay bằng bản sao mới nhất của ${account.accountEmail}.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; transfer(true) }, enabled = enabled && !busy) { Text("Khôi phục") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Hủy") } })
}
