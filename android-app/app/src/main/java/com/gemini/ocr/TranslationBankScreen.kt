package com.gemini.ocr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gemini.ocr.network.BankCategory
import com.gemini.ocr.network.ChineseDialogueBank
import com.gemini.ocr.network.SelfLearningDatabase
import com.gemini.ocr.network.TranslationBankRecord
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.ui.EditorDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationBankScreen(running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }
    var refresh by remember { mutableIntStateOf(0) }
    var shown by rememberSaveable(searchQuery, selectedCategory) { mutableIntStateOf(20) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingSource by rememberSaveable { mutableStateOf("") }
    var editingTranslation by rememberSaveable { mutableStateOf("") }
    var editingCategory by rememberSaveable { mutableStateOf(BankCategory.DIALOGUE) }
    var editingOcrErrors by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<TranslationBankRecord?>(null) }
    var ocrInput by rememberSaveable { mutableStateOf("帅兄，小心I") }
    var corrected by remember { mutableStateOf("") }
    var matched by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<TranslationBankRecord>>(emptyList()) }
    val bankSize by SessionState.bankSize.collectAsState()
    val correctedCount by SessionState.ocrCorrectedCount.collectAsState()
    fun mutate(message: String, onSuccess: () -> Unit = {}, operation: () -> Unit) {
        if (running || busy) return
        scope.launch {
            busy = true; statusError = false
            try {
                withContext(Dispatchers.IO) { check(!SessionState.running.value) { "Dừng phiên dịch trước khi sửa kho." }; operation() }
                status = message; refresh++; onSuccess()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = error.message ?: "Chưa lưu được thay đổi. Hãy thử lại."; statusError = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(searchQuery, selectedCategory, refresh) {
        records = withContext(Dispatchers.IO) { SelfLearningDatabase.getAllRecords(context, selectedCategory, searchQuery) }
    }
    LaunchedEffect(advanced, ocrInput, refresh) {
        if (advanced) {
            val result = withContext(Dispatchers.IO) {
                val text = SelfLearningDatabase.preprocessAndCorrectOcr(ocrInput, context)
                val hit = SelfLearningDatabase.lookup(context, text, incrementFrequency = false)?.translatedText
                    ?: ChineseDialogueBank.matchInstant(text, context)
                text to hit
            }
            corrected = result.first; matched = result.second
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Kho thoại", style = MaterialTheme.typography.headlineMedium)
            Text("Trung → Việt · ${bankSize.coerceAtLeast(ChineseDialogueBank.totalEntries)} câu", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = {
            editingSource = ""; editingTranslation = ""; editingCategory = BankCategory.DIALOGUE; editingOcrErrors = ""; showEditor = true
        }, enabled = !running && !busy, modifier = Modifier.testTag("add_bank_record_button")) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("Thêm câu")
        }
    }
    OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("Tìm câu Trung hoặc bản dịch Việt") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("bank_search_input"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ALL" to "Tất cả", BankCategory.DIALOGUE to "Câu thoại", BankCategory.PRONOUN to "Danh xưng", BankCategory.UI to "Thuật ngữ").forEach { (key, label) ->
            FilterChip(selectedCategory == key, { selectedCategory = key }, label = { Text(label) })
        }
    }
    if (running) Text("Dừng phiên dịch để thêm, sửa hoặc xóa câu.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) StatusNotice(status, error = statusError)
    if (records.isEmpty()) Text("Không tìm thấy câu phù hợp. Thử từ khóa khác hoặc thêm câu mới.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    records.take(shown).forEach { record ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(BankCategory.displayName(record.category).replace("UI / ", ""), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        editingSource = record.sourceText; editingTranslation = record.translatedText
                        editingCategory = record.category; editingOcrErrors = record.commonOcrErrors; showEditor = true
                    }, enabled = !running && !busy) { Icon(Icons.Outlined.Edit, "Sửa câu") }
                    IconButton(onClick = { deleteRecord = record }, enabled = !running && !busy) { Icon(Icons.Outlined.DeleteOutline, "Xóa câu") }
                }
                Text(record.sourceText, style = MaterialTheme.typography.titleMedium)
                Text(record.translatedText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (record.commonOcrErrors.isNotBlank()) ExpandableSection("Biến thể nhận chữ", subtitle = "Dành cho chỉnh sửa nâng cao") {
                    Text(record.commonOcrErrors, style = MaterialTheme.typography.bodySmall)
                    Text("Đã dùng ${record.usageFrequency} lần", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (shown < records.size) TextButton(onClick = { shown += 20 }) { Text("Xem thêm câu") }
    Text("Hiển thị tối đa 60 kết quả phù hợp. Dùng từ khóa để thu hẹp tìm kiếm.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("bank_advanced_toggle")) {
        Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text(if (advanced) "Thu gọn công cụ nâng cao" else "Công cụ nâng cao")
    }
    if (advanced) {
        ExpandableSection("Tra thử câu và nhận chữ", initiallyExpanded = true) {
            OutlinedTextField(ocrInput, { ocrInput = it }, label = { Text("Câu Trung cần tra thử") }, modifier = Modifier.fillMaxWidth().testTag("ocr_precorrection_test_input"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("帅兄，小心I", "这不叮能！", "道友請留步", "奉天承运，皇帝诏曰", "渡劫期老怪").forEach { text ->
                    FilterChip(ocrInput == text, { ocrInput = text }, label = { Text(text) })
                }
            }
            Text("Chữ sau xử lý: $corrected", style = MaterialTheme.typography.bodyMedium)
            Text(matched?.let { "Câu đã lưu: $it" } ?: "Chưa có câu phù hợp trong kho.", modifier = Modifier.testTag("dialogue_bank_test_result"),
                color = MaterialTheme.colorScheme.primary)
        }
        ExpandableSection("Thông tin và quản lý kho", subtitle = "Thống kê, bộ nhớ và khôi phục") {
            Text("${SelfLearningDatabase.ramLruSize()}/300 câu trong bộ nhớ nhanh · Đã sửa nhận chữ $correctedCount lần", style = MaterialTheme.typography.bodySmall)
            Text("Kho cục bộ SQLite có chỉ mục theo câu; không gọi dịch vụ dịch khi tra thử.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { resetDialog = true }, enabled = !running && !busy, modifier = Modifier.testTag("reset_bank_button")) { Text("Khôi phục kho có sẵn") }
        }
    }
    if (showEditor) EditorDialog("Lưu câu thoại", onDismiss = { if (!busy) showEditor = false }, closeEnabled = !busy,
        closeLabel = "Hủy", actions = { TextButton(onClick = {
            val source = editingSource; val translation = editingTranslation; val category = editingCategory; val variants = editingOcrErrors.trim()
            mutate("Đã lưu câu thoại.", onSuccess = { showEditor = false }) {
                SelfLearningDatabase.learnOrUpsert(context, source, translation, category, variants)
            }
        }, enabled = !running && !busy && editingSource.isNotBlank() && editingTranslation.isNotBlank(),
            modifier = Modifier.testTag("bank_editor_save_button")) { Text("Lưu câu") } }) {
                OutlinedTextField(editingSource, { editingSource = it }, label = { Text("Câu Trung") }, modifier = Modifier.fillMaxWidth().testTag("bank_editor_source_input"), maxLines = 3, enabled = !busy)
                OutlinedTextField(editingTranslation, { editingTranslation = it }, label = { Text("Bản dịch Việt") }, modifier = Modifier.fillMaxWidth().testTag("bank_editor_translation_input"), maxLines = 3, enabled = !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BankCategory.DIALOGUE to "Câu thoại", BankCategory.PRONOUN to "Danh xưng", BankCategory.UI to "Thuật ngữ").forEach { (key, label) ->
                        FilterChip(editingCategory == key, { editingCategory = key }, label = { Text(label) }, enabled = !busy)
                    }
                }
                ExpandableSection("Tùy chọn nâng cao") {
                    OutlinedTextField(editingOcrErrors, { editingOcrErrors = it }, label = { Text("Biến thể nhận chữ, ngăn cách bằng |") },
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_ocr_errors_input"), enabled = !busy)
                }
                if (statusError && status.isNotBlank()) StatusNotice(status, error = true)
            }
    deleteRecord?.let { record -> AlertDialog(onDismissRequest = { deleteRecord = null }, title = { Text("Xóa câu đã chọn?") },
        text = { Text(record.sourceText, maxLines = 4, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton(onClick = { deleteRecord = null; mutate("Đã xóa câu khỏi kho.") { SelfLearningDatabase.delete(context, record.sourceText) } },
            enabled = !running && !busy) { Text("Xóa câu") } }, dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("Giữ lại") } }) }
    if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false }, title = { Text("Khôi phục kho có sẵn?") },
        text = { Text("Các câu đã thêm vào kho chung sẽ bị xóa và nạp lại câu có sẵn. Kho duyệt theo phim được giữ nguyên.") },
        confirmButton = { TextButton(onClick = { resetDialog = false; mutate("Đã khôi phục kho có sẵn.") { SelfLearningDatabase.clearLearnedAndRestoreDefaults(context) } },
            enabled = !running && !busy) { Text("Khôi phục") } }, dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Giữ lại") } })
}
