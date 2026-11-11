package com.gemini.ocr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.ocr.network.ContextualMemoryRepository
import com.gemini.ocr.network.ContextualTranslationMemory
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.MemoryReviewWriteGuard
import com.gemini.ocr.network.MemoryImportConverter
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.ui.EditorDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID

private val ReviewMint = Color(0xFF6EE7C7)
private val ReviewMuted = Color(0xFF98ACB9)

/** Bounded review/admin UI. No inference, auto-approval, legacy data migration or API calls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryReviewScreen(
    running: Boolean,
    settings: AppSettings,
    injectedMemory: ContextualTranslationMemory? = null,
    candidateFlow: StateFlow<ContextualMemoryRepository.ReviewCandidate?> = ContextualMemoryRepository.candidate,
    initiallyReviewed: Boolean = false,
) {
    var reviewedTab by rememberSaveable { mutableStateOf(initiallyReviewed) }
    val savedTabs = rememberSaveableStateHolder()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !reviewedTab, onClick = { reviewedTab = false }, label = { Text("Kho chung") })
        FilterChip(selected = reviewedTab, onClick = { reviewedTab = true }, label = { Text("Theo phim · duyệt câu") },
            modifier = Modifier.testTag("reviewed_memory_tab"))
    }
    savedTabs.SaveableStateProvider(if (reviewedTab) "reviewed" else "general") {
        if (reviewedTab) ReviewedMemoryContent(running, settings, injectedMemory, candidateFlow)
        else TranslationBankScreen(running)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewedMemoryContent(running: Boolean, settings: AppSettings,
    injectedMemory: ContextualTranslationMemory?, candidateFlow: StateFlow<ContextualMemoryRepository.ReviewCandidate?>) {
    val context = LocalContext.current
    val memory = remember(injectedMemory, context) { injectedMemory ?: ContextualMemoryRepository.store(context) }
    val jobs = rememberCoroutineScope()
    val latestRunning by rememberUpdatedState(running)
    val canWrite = !running && !SessionState.running.collectAsState().value
    val candidate by candidateFlow.collectAsState()
    var film by rememberSaveable { mutableStateOf(settings.memoryFilmId) }
    var domain by rememberSaveable { mutableStateOf(settings.memoryDomain) }
    var activeScope by rememberSaveable(stateSaver = ReviewScopeSaver) { mutableStateOf<ContextualTranslationMemory.Scope?>(null) }
    var scopeLabel by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var translation by rememberSaveable { mutableStateOf("") }
    var confidence by rememberSaveable { mutableStateOf("") }
    var sourceUrl by rememberSaveable { mutableStateOf("") }
    var license by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("dialogue") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var qualityFilter by rememberSaveable { mutableStateOf("ALL") }
    var checkedTranslation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var technicalError by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<ContextualTranslationMemory.Page?>(null) }
    var rowAfter by rememberSaveable { mutableLongStateOf(0L) }
    var imports by remember { mutableStateOf(emptyList<ContextualTranslationMemory.ImportInfo>()) }
    var rawCorrection by rememberSaveable { mutableStateOf("") }
    var fixedCorrection by rememberSaveable { mutableStateOf("") }
    var checkedCorrection by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf<ContextualTranslationMemory.Correction?>(null) }
    var staged by rememberSaveable(stateSaver = ReviewImportPreviewSaver) { mutableStateOf<ContextualTranslationMemory.ImportPreview?>(null) }
    var stagedScope by rememberSaveable(stateSaver = ReviewScopeSaver) { mutableStateOf<ContextualTranslationMemory.Scope?>(null) }
    var preview by remember { mutableStateOf<ContextualTranslationMemory.Page?>(null) }
    var rightsChecked by remember { mutableStateOf(false) }
    var packReviewed by remember { mutableStateOf(false) }
    var pendingManifest by rememberSaveable(stateSaver = ReviewManifestSaver) { mutableStateOf<ContextualTranslationMemory.ImportManifest?>(null) }
    var importFormat by rememberSaveable { mutableStateOf(MemoryImportConverter.Format.JSONL) }
    var pendingImportFormat by rememberSaveable { mutableStateOf<MemoryImportConverter.Format?>(null) }
    var pendingExportScope by rememberSaveable(stateSaver = ReviewScopeSaver) { mutableStateOf<ContextualTranslationMemory.Scope?>(null) }

    fun provenance(): ContextualTranslationMemory.Provenance? = runCatching {
        ContextualTranslationMemory.Provenance(sourceUrl.trim(), license.trim())
    }.getOrNull()
    fun score(): Double? = confidence.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    fun runTask(mutation: Boolean = false, action: suspend () -> String) {
        if (busy) return
        jobs.launch {
            busy = true; statusError = false; technicalError = ""
            try {
                status = if (mutation) MemoryReviewWriteGuard.run({ latestRunning }, action) else action()
                refresh++
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                statusError = true
                technicalError = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(1000)}"
                status = if (mutation && !MemoryReviewWriteGuard.allowed(latestRunning)) "Dừng phiên dịch trước khi sửa hoặc nhập kho."
                    else "Thao tác chưa hoàn tất. Kiểm tra dữ liệu, phạm vi và quyền sử dụng."
                refresh++ // Failed staged packs must also become visible for explicit cleanup.
            }
            finally { busy = false }
        }
    }
    fun selectScope(value: ContextualTranslationMemory.Scope, label: String) {
        activeScope = value; scopeLabel = label; rowAfter = 0
        rows = null; imports = emptyList(); staged = null; preview = null; stagedScope = null
        rightsChecked = false; packReviewed = false; checkedTranslation = false; checkedCorrection = false
        statusError = false; technicalError = ""
    }
    fun edit(entry: ContextualTranslationMemory.Entry) {
        source = entry.source; translation = entry.translation; topic = entry.topic
        sourceUrl = entry.provenance.sourceUrl; license = entry.provenance.licenseId
        confidence = entry.confidence.takeIf { it > 0 }?.toString().orEmpty()
        checkedTranslation = false
        editorOpen = true
    }

    val openJsonl = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val manifest = pendingManifest
        val format = pendingImportFormat
        pendingManifest = null; pendingImportFormat = null
        if (uri != null && manifest != null && format != null) runTask(mutation = true) {
            val (imported, conversion) = withContext(Dispatchers.IO) {
                val temporary = File.createTempFile("memory-review-", ".jsonl", context.cacheDir)
                try {
                    val summary = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        temporary.outputStream().use { fileOutput ->
                            OutputStreamWriter(BoundedImportOutput(fileOutput), Charsets.UTF_8).use { output ->
                                MemoryImportConverter.convert(format, input, output,
                                    MemoryImportConverter.Metadata(manifest.provenance.sourceUrl,
                                        manifest.provenance.licenseId, manifest.topic))
                            }
                        }
                    }
                    require(summary.accepted > 0) { "No unambiguous bilingual records" }
                    MemoryReviewWriteGuard.run({ latestRunning }) {
                        memory.stageJsonl(manifest, temporary.inputStream())
                    } to summary
                } finally { temporary.delete() }
            }
            staged = imported; stagedScope = manifest.scope
            preview = memory.previewImport(imported.version, limit = 10)
            rightsChecked = false; packReviewed = false
            "${conversion.accepted} cặp rõ ràng, bỏ ${conversion.rejected} mục; ${imported.uniqueRows} câu riêng. Chưa xuất bản."
        }
    }
    val exportJsonl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val frozen = pendingExportScope
        pendingExportScope = null
        if (uri != null && frozen != null) runTask {
            val count = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer -> memory.exportJsonl(frozen, writer) }
                }
            }
            "Đã xuất $count câu của phạm vi đã chọn."
        }
    }

    LaunchedEffect(activeScope, rowAfter, refresh, query, qualityFilter) {
        val selected = activeScope
        if (selected != null) {
            rows = filteredReviewPage(memory, selected, rowAfter, query, qualityFilter)
            imports = memory.recentImports(selected, limit = 10)
        }
    }
    LaunchedEffect(activeScope, rawCorrection, refresh) {
        correction = if (activeScope != null && rawCorrection.isNotBlank())
            memory.previewCorrection(activeScope!!, rawCorrection) else null
    }
    LaunchedEffect(staged?.version, activeScope) {
        val pack = staged
        if (pack != null && stagedScope == activeScope && preview == null)
            preview = memory.previewImport(pack.version, limit = 10)
    }

    Text("Câu thoại theo phim", style = MaterialTheme.typography.headlineMedium)
    Text("Đối chiếu câu Trung và bản dịch Việt trước khi duyệt.", color = ReviewMuted, style = MaterialTheme.typography.bodyMedium)
    candidate?.let { accepted ->
        ReviewCard {
            Text("Câu vừa dịch · ${accepted.scope.filmId}", fontSize = 12.sp)
            Text(accepted.source); Text(accepted.translation, color = ReviewMint)
            OutlinedButton(onClick = {
                selectScope(accepted.scope, "Ngữ cảnh gốc của câu vừa dịch")
                source = accepted.source; translation = accepted.translation
                confidence = accepted.confidence?.toString().orEmpty()
                sourceUrl = "model://accepted-outcome"; license = "unreviewed-model-output"
                editorOpen = true
                status = "Đã mở bản nháp. Kiểm tra nguồn, bản dịch, quyền sử dụng và ngữ cảnh trước khi duyệt."
            }, enabled = !busy, modifier = Modifier.testTag("memory_load_candidate")) { Text("Mở câu vừa dịch để duyệt") }
        }
    }
    ExpandableSection("Phim và ngữ cảnh", subtitle = activeScope?.let { "${it.filmId} · ${it.domain}" }
        ?: settings.memoryFilmId.ifBlank { "Chọn phim để bắt đầu" }, initiallyExpanded = activeScope == null) {
        ReviewField(film, { film = it }, "Tên hoặc mã phim (tối đa 160 ký tự)", "memory_film")
        ReviewField(domain, { domain = it }, "Lĩnh vực / thể loại", "memory_domain")
        Button(onClick = {
            settings.memoryFilmId = film; settings.memoryDomain = domain
            statusError = false; technicalError = ""
            activeScope = null; scopeLabel = ""; rows = null; imports = emptyList(); staged = null; preview = null
            status = if (film.isBlank()) "Chưa chọn phim; kho ngữ cảnh chưa bật cho phiên dịch." else "Đã chọn phim. Chọn một phạm vi để xem hoặc duyệt."
        }, enabled = !running && !busy && film.length <= 160 && film.none { it.isISOControl() } &&
            domain.isNotBlank() && domain.length <= 160 && domain.none { it.isISOControl() },
            modifier = Modifier.testTag("memory_apply_film")) { Text("Lưu phim và lĩnh vực") }
        if (!canWrite) Text("Dừng phiên dịch trước khi đổi phim, sửa hoặc nhập kho.", color = ReviewMuted, fontSize = 12.sp)
        OutlinedButton(onClick = {
            val namespace = StandaloneTranslator.cacheNamespace(settings.profile, settings.localGlossary, emptyList(),
                settings.memoryFilmId, settings.memoryDomain)
            ContextualMemoryRepository.snapshotScope(settings, namespace)?.let {
                selectScope(it, "Phạm vi được chọn rõ ràng: không có lịch sử hội thoại")
                source = ""; translation = ""; confidence = ""; sourceUrl = ""; license = ""
            }
        }, enabled = !busy && settings.memoryFilmId.isNotBlank(), modifier = Modifier.testTag("memory_no_history_scope")) {
            Text("Xem / thêm câu của phim")
        }
        Text("Nút trên mở phạm vi không có lịch sử hội thoại. Câu xưng hô cần đúng ngữ cảnh đã lưu.", color = ReviewMuted, fontSize = 12.sp)
        if (scopeLabel.isNotBlank()) ExpandableSection("Chi tiết phạm vi") { Text(scopeLabel, style = MaterialTheme.typography.bodySmall) }
        activeScope?.let { Text("Phim: ${it.filmId} • Lĩnh vực: ${it.domain}", color = ReviewMint, fontSize = 12.sp) }
    }

    if (activeScope != null) {
        ReviewedEntryList(rows, query, { query = it; rowAfter = 0 }, qualityFilter,
            { qualityFilter = it; rowAfter = 0 }, busy, { edit(it) },
            { rowAfter = 0 }, { rowAfter = rows?.nextId ?: 0 })
        Button(onClick = { editorOpen = true }, enabled = !busy, modifier = Modifier.testTag("memory_open_editor")) { Text("Thêm / sửa câu") }
        if (editorOpen) EditorDialog("Kiểm tra bản dịch", onDismiss = { if (!busy) editorOpen = false }, closeEnabled = !busy) {
            ReviewField(source, { source = it; checkedTranslation = false }, "Nguồn tiếng Trung", "memory_source", enabled = !busy)
            ReviewField(translation, { translation = it; checkedTranslation = false }, "Bản dịch tiếng Việt", "memory_translation", enabled = !busy)
            ExpandableSection("Thông tin nguồn và điều kiện dùng lại", subtitle = if (provenance() == null) "Điền nguồn và quyền sử dụng để lưu" else "Đã có thông tin nguồn") {
            ReviewField(topic, { topic = it; checkedTranslation = false }, "Chủ đề", "memory_topic", enabled = !busy)
            ReviewField(sourceUrl, { sourceUrl = it; checkedTranslation = false }, "Nguồn: https://… hoặc user://…", "memory_provenance", enabled = !busy)
            ReviewField(license, { license = it; checkedTranslation = false }, "Giấy phép / quyền sử dụng đã biết", "memory_license", enabled = !busy)
            ReviewField(confidence, { confidence = it; checkedTranslation = false }, "Điểm tin cậy 0–1 nếu đã biết; bỏ trống nếu chưa có", "memory_confidence", enabled = !busy)
            Text("Điểm do bạn cung cấp, không phải phép đo độ chính xác. Bỏ trống lưu 0; dưới 0,98 không được tự dùng lại.",
                color = ReviewMuted, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val frozen = activeScope!!; val frozenSource = source
                    runTask {
                        val found = memory.previewExact(frozen, frozenSource)
                        if (found != null) { edit(found); "Đã mở bản lưu; chưa tính là một lần dùng lại." } else "Chưa có bản lưu đúng phạm vi."
                    }
                }, enabled = !busy && source.isNotBlank(), modifier = Modifier.testTag("memory_find_exact")) { Text("Tra câu đã lưu") }
                OutlinedButton(onClick = {
                    val entry = ContextualTranslationMemory.Entry(activeScope!!, source, translation, topic,
                        provenance()!!, score() ?: 0.0)
                    runTask(mutation = true) { if (memory.recordDraft(entry)) "Đã lưu bản nháp." else "Bản đã duyệt được giữ nguyên." }
                }, enabled = canWrite && !busy && validEntry(source, translation, topic, provenance(), confidence),
                    modifier = Modifier.testTag("memory_save_draft")) { Text("Lưu nháp") }
            }
            ReviewCheck(checkedTranslation, { checkedTranslation = it }, "Tôi đã kiểm tra câu nguồn, bản dịch và ngữ cảnh.", "memory_review_check")
            Button(onClick = {
                val entry = ContextualTranslationMemory.Entry(activeScope!!, source, translation, topic, provenance()!!, score() ?: 0.0)
                runTask(mutation = true) {
                    memory.saveReviewed(entry); checkedTranslation = false
                    if (entry.confidence >= .98) "Đã duyệt cho đúng phạm vi này." else "Đã duyệt; chưa tự dùng lại do chưa có điểm tin cậy đủ ngưỡng."
                }
            }, enabled = canWrite && !busy && checkedTranslation && validEntry(source, translation, topic, provenance(), confidence),
                modifier = Modifier.testTag("memory_approve")) { Text("Duyệt bản dịch đã kiểm tra") }
            if (status.isNotBlank()) StatusNotice(status, error = statusError)
            }

        ExpandableSection("Sửa lỗi nhận chữ", subtitle = "Tùy chọn nâng cao theo đúng câu và ngữ cảnh") {
            ReviewField(rawCorrection, { rawCorrection = it; checkedCorrection = false }, "OCR gốc bị sai", "memory_raw_ocr")
            ReviewField(fixedCorrection, { fixedCorrection = it; checkedCorrection = false }, "Câu nguồn đúng", "memory_fixed_ocr")
            Text("Cần 2 lần xác nhận rõ ràng và điểm ít nhất 0,99. Chỉ áp dụng cho đúng câu đã kiểm tra.", color = ReviewMuted, fontSize = 12.sp)
            ExpandableSection("Nguồn và mức tin cậy của bản sửa") {
                ReviewField(sourceUrl, { sourceUrl = it; checkedCorrection = false; checkedTranslation = false }, "Nguồn: https://… hoặc user://…", "memory_correction_provenance")
                ReviewField(license, { license = it; checkedCorrection = false; checkedTranslation = false }, "Quyền sử dụng", "memory_correction_license")
                ReviewField(confidence, { confidence = it; checkedCorrection = false; checkedTranslation = false }, "Điểm tin cậy 0–1 nếu đã biết", "memory_correction_confidence")
            }
            Text("Đã xác nhận: ${correction?.confirmations ?: 0}", modifier = Modifier.testTag("memory_correction_count"))
            ReviewCheck(checkedCorrection, { checkedCorrection = it }, "Tôi đã đối chiếu OCR gốc với câu đúng.", "memory_correction_check")
            Button(onClick = {
                val frozen = activeScope!!; val raw = rawCorrection; val fixed = fixedCorrection
                val confirmedScore = score() ?: 0.0; val origin = provenance()!!
                runTask(mutation = true) {
                    correction = memory.confirmCorrection(frozen, raw, fixed, confirmedScore, origin)
                    checkedCorrection = false
                    if (memory.lookupCorrection(frozen, raw) != null) "Đã đủ điều kiện sửa đúng câu trong phạm vi này."
                    else "Đã ghi nhận xác nhận; chưa tự sửa khi chưa đủ điều kiện."
                }
            }, enabled = canWrite && !busy && checkedCorrection && provenance() != null && rawCorrection.isNotBlank() &&
                fixedCorrection.isNotBlank() && rawCorrection.length <= 2000 && fixedCorrection.length <= 2000 &&
                ContextualTranslationMemory.normalizeSource(rawCorrection) != ContextualTranslationMemory.normalizeSource(fixedCorrection) &&
                (confidence.isBlank() || score() != null), modifier = Modifier.testTag("memory_confirm_correction")) {
                Text("Xác nhận bản sửa OCR")
            }
        }

        ExpandableSection("Nhập / xuất kho thoại", subtitle = "Nhập tệp song ngữ, xem trước và xác nhận") {
            ExpandableSection("Nguồn của gói dữ liệu", subtitle = "Điền trước khi chọn tệp") {
                ReviewField(topic, { topic = it; checkedTranslation = false }, "Chủ đề", "memory_import_topic")
                ReviewField(sourceUrl, { sourceUrl = it; checkedTranslation = false }, "Nguồn: https://… hoặc user://…", "memory_import_provenance")
                ReviewField(license, { license = it; checkedTranslation = false }, "Quyền sử dụng", "memory_import_license")
            }
            Text("Chọn đúng định dạng UTF-8. Mỗi mục phải có cả Trung và Việt; không tự ghép các câu ở hai tệp. Giới hạn tệp chuyển đổi 256 MiB. Tất cả là bản nháp để xem trước.", color = ReviewMuted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryImportConverter.Format.entries.forEach { format ->
                    FilterChip(selected = importFormat == format, onClick = { importFormat = format },
                        enabled = canWrite && !busy, label = { Text(format.name) })
                }
            }
            Button(onClick = {
                pendingImportFormat = importFormat
                pendingManifest = ContextualTranslationMemory.ImportManifest("ui-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
                    activeScope!!, topic, provenance()!!, rightsConfirmedByUser = false)
                openJsonl.launch(arrayOf("text/*", "application/json", "application/x-ndjson", "application/octet-stream"))
            }, enabled = canWrite && !busy && provenance() != null && topic.isNotBlank() && topic.length <= 160,
                modifier = Modifier.testTag("memory_import_jsonl")) { Text("Chọn tệp ${importFormat.name} để xem trước") }
            staged?.let { pack ->
                Text("Gói ${pack.version} • ${pack.uniqueRows} câu • chưa xuất bản", fontSize = 12.sp)
                preview?.entries?.forEach { row ->
                    Text("${row.source}\n${row.translation}", fontSize = 13.sp)
                    Text("Nguồn: ${row.provenance.sourceUrl}\nQuyền: ${row.provenance.licenseId} • Bản nháp", color = ReviewMuted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runTask { preview = memory.previewImport(pack.version, limit = 10); "Trang đầu của gói." } }, enabled = !busy) { Text("Trang đầu") }
                    OutlinedButton(onClick = {
                        val next = preview?.nextId ?: return@OutlinedButton
                        runTask { preview = memory.previewImport(pack.version, next, limit = 10); "Trang kế tiếp của gói." }
                    }, enabled = !busy && preview?.nextId != null) { Text("Trang sau") }
                }
                ReviewCheck(rightsChecked, { rightsChecked = it }, "Tôi có quyền sử dụng toàn bộ dữ liệu của gói này.", "memory_import_rights")
                ReviewCheck(packReviewed, { packReviewed = it }, "Tôi đã kiểm tra nội dung toàn bộ gói, không chỉ trang xem trước.", "memory_import_reviewed")
                Button(onClick = {
                    runTask(mutation = true) {
                        val count = memory.publishImport(pack.version, reviewedByUser = true, rightsConfirmedByUser = rightsChecked)
                        staged = null; preview = null; packReviewed = false; rightsChecked = false
                        "Đã xuất bản $count câu đã xác nhận; chỉ câu đủ điểm và đúng phạm vi mới được dùng lại."
                    }
                }, enabled = canWrite && !busy && rightsChecked && packReviewed && stagedScope == activeScope,
                    modifier = Modifier.testTag("memory_publish_import")) { Text("Xuất bản gói đã kiểm tra") }
            }
            OutlinedButton(onClick = {
                pendingExportScope = activeScope
                exportJsonl.launch("reviewed-memory.jsonl")
            }, enabled = !busy, modifier = Modifier.testTag("memory_export_jsonl")) { Text("Xuất JSONL của phạm vi này") }
            imports.forEach { pack ->
                Text("${pack.version} • ${pack.rowCount} câu • ${pack.state}", fontSize = 12.sp)
                if (pack.state == "published") OutlinedButton(onClick = {
                    runTask(mutation = true) { memory.rollbackImport(pack.version); "Đã thu hồi gói. Bản duyệt thủ công được giữ nguyên." }
                }, enabled = canWrite && !busy, modifier = Modifier.testTag("memory_rollback_${pack.version}")) { Text("Thu hồi gói ${pack.version}") }
                if (pack.state == "failed") OutlinedButton(onClick = {
                    runTask(mutation = true) {
                        val count = memory.discardFailedImport(pack.version)
                        "Đã dọn $count câu tạm của gói nhập lỗi này."
                    }
                }, enabled = canWrite && !busy, modifier = Modifier.testTag("memory_discard_failed_${pack.version}")) {
                    Text("Dọn dữ liệu tạm của gói lỗi ${pack.version}")
                }
            }
        }

    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) Column(Modifier.testTag("memory_status")) {
        StatusNotice(status, error = statusError)
        if (technicalError.isNotBlank()) ExpandableSection("Chi tiết kỹ thuật") { Text(technicalError, style = MaterialTheme.typography.bodySmall) }
    }
}

private val ReviewScopeSaver = Saver<ContextualTranslationMemory.Scope?, String>(
    save = { it?.serialized().orEmpty() },
    restore = { value -> if (value.isBlank()) null else runCatching { ContextualTranslationMemory.Scope.fromJson(JSONObject(value)) }.getOrNull() }
)

private val ReviewImportPreviewSaver = Saver<ContextualTranslationMemory.ImportPreview?, String>(
    save = { value -> value?.let { JSONObject().put("version", it.version).put("lines", it.inputLines)
        .put("rows", it.uniqueRows).put("duplicates", it.duplicateRows).toString() }.orEmpty() },
    restore = { value -> runCatching { JSONObject(value).let { ContextualTranslationMemory.ImportPreview(
        it.getString("version"), it.getInt("lines"), it.getInt("rows"), it.getInt("duplicates")) } }.getOrNull() }
)

private val ReviewManifestSaver = Saver<ContextualTranslationMemory.ImportManifest?, String>(
    save = { value -> value?.let { JSONObject().put("version", it.version).put("scope", JSONObject(it.scope.serialized()))
        .put("topic", it.topic).put("source", it.provenance.sourceUrl).put("license", it.provenance.licenseId)
        .put("rights", it.rightsConfirmedByUser).toString() }.orEmpty() },
    restore = { value -> runCatching { JSONObject(value).let { ContextualTranslationMemory.ImportManifest(it.getString("version"),
        ContextualTranslationMemory.Scope.fromJson(it.getJSONObject("scope")), it.getString("topic"),
        ContextualTranslationMemory.Provenance(it.getString("source"), it.getString("license")), it.getBoolean("rights")) } }.getOrNull() }
)

/** Search only the selected scope, with bounded memory and a continuation instead of a hidden cap. */
internal suspend fun filteredReviewPage(memory: ContextualTranslationMemory, scope: ContextualTranslationMemory.Scope,
    after: Long, query: String, quality: String, resultLimit: Int = 10, scanLimit: Int = 512): ContextualTranslationMemory.Page {
    require(resultLimit in 1..64 && scanLimit >= resultLimit)
    val found = mutableListOf<ContextualTranslationMemory.Entry>()
    var cursor = after
    var scanned = 0
    while (scanned < scanLimit) {
        currentCoroutineContext().ensureActive()
        val page = memory.exportPage(scope, cursor, minOf(64, scanLimit - scanned))
        page.entries.forEachIndexed { index, entry ->
            scanned++; cursor = entry.id
            if ((quality == "ALL" || entry.quality.name == quality) &&
                (entry.source.contains(query.trim(), true) || entry.translation.contains(query.trim(), true))) found += entry
            if (found.size == resultLimit) return ContextualTranslationMemory.Page(found,
                if (index < page.entries.lastIndex || page.nextId != null) cursor else null)
        }
        if (page.nextId == null) return ContextualTranslationMemory.Page(found, null)
        cursor = page.nextId
    }
    return ContextualTranslationMemory.Page(found, cursor)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewedEntryList(page: ContextualTranslationMemory.Page?, query: String, onQuery: (String) -> Unit,
    quality: String, onQuality: (String) -> Unit, busy: Boolean, onEdit: (ContextualTranslationMemory.Entry) -> Unit,
    onFirst: () -> Unit, onNext: () -> Unit) {
    OutlinedTextField(query, onQuery, label = { Text("Tìm câu Trung hoặc bản dịch Việt") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reviewed_memory_search"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ALL" to "Tất cả", "DRAFT" to "Bản nháp", "VERIFIED" to "Gói đã kiểm tra", "USER_APPROVED" to "Đã duyệt").forEach { (key, label) ->
            FilterChip(quality == key, { onQuality(key) }, label = { Text(label) })
        }
    }
    if (page != null && page.entries.isEmpty()) Text(if (page.nextId == null) "Không có câu phù hợp trong phạm vi đã chọn."
        else "Chưa tìm thấy trong phần đã đọc. Xem tiếp để tìm phần còn lại.", style = MaterialTheme.typography.bodyMedium)
    page?.entries?.forEach { entry ->
        ReviewCard {
            Text(entry.source, style = MaterialTheme.typography.titleMedium)
            Text(entry.translation, color = ReviewMint, style = MaterialTheme.typography.bodyLarge)
            Text(when (entry.quality) {
                ContextualTranslationMemory.Quality.DRAFT -> "Bản nháp"
                ContextualTranslationMemory.Quality.VERIFIED -> "Gói đã kiểm tra"
                ContextualTranslationMemory.Quality.USER_APPROVED -> "Đã duyệt"
            }, style = MaterialTheme.typography.labelSmall, color = ReviewMuted)
            TextButton(onClick = { onEdit(entry) }, enabled = !busy) { Text("Sửa / duyệt câu") }
            ExpandableSection("Chi tiết câu") {
                Text("Điểm: ${entry.confidence} · Đã dùng ${entry.usageCount} lần", style = MaterialTheme.typography.bodySmall)
                Text("Nguồn: ${entry.provenance.sourceUrl}\nQuyền sử dụng: ${entry.provenance.licenseId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onFirst, enabled = !busy) { Text("Về đầu danh sách") }
        OutlinedButton(onClick = onNext, enabled = !busy && page?.nextId != null) { Text("Xem tiếp") }
    }
}

private fun validEntry(source: String, translation: String, topic: String,
                       provenance: ContextualTranslationMemory.Provenance?, confidence: String): Boolean =
    source.isNotBlank() && source.length <= 2000 && '\u0000' !in source && translation.isNotBlank() &&
        translation.length <= 4000 && '\u0000' !in translation && topic.isNotBlank() && topic.length <= 160 &&
        topic.none { it.isISOControl() } && provenance != null && (confidence.isBlank() || confidence.toDoubleOrNull()?.let {
            it.isFinite() && it in 0.0..1.0
        } == true)

@Composable
private fun ReviewField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled)
}

@Composable
private fun ReviewCheck(value: Boolean, onChange: (Boolean) -> Unit, label: String, tag: String) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(value, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Text(label, modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp)
    }
}

@Composable
private fun ReviewCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14212B)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Bound staging disk use without loading the selected corpus into RAM. */
private class BoundedImportOutput(output: OutputStream) : FilterOutputStream(output) {
    private var bytes = 0L
    private fun reserve(count: Int) {
        require(bytes + count <= 256L * 1024 * 1024) { "Converted import exceeds 256 MiB" }
        bytes += count
    }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { reserve(length); out.write(buffer, offset, length) }
}
