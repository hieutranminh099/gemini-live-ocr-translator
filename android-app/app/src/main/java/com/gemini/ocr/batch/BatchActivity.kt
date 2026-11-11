package com.gemini.ocr.batch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gemini.ocr.movie.MovieEpisode
import com.gemini.ocr.SessionState
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.movie.MoviePlaybackActivity
import com.gemini.ocr.video.VideoActivity
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoSmartBatchSettings
import com.gemini.ocr.video.AsrModelManagerButton
import com.gemini.ocr.video.VideoAsrOverlapReviewButton
import com.gemini.ocr.video.videoSourceLabel
import com.gemini.ocr.video.videoSourceUsesRoi
import com.gemini.ocr.video.videoResolvedSourceLabel
import com.gemini.ocr.video.videoUnclearSpeechNotice
import com.gemini.ocr.video.VideoTranslationPlan
import com.gemini.ocr.video.VideoStageProgress
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.ui.ocean.OceanTheme
import com.gemini.ocr.ui.ocean.OceanCard
import com.gemini.ocr.ui.ocean.OceanColors
import com.gemini.ocr.video.VideoRegionDialog
import com.gemini.ocr.video.VideoSubtitleFrame
import com.gemini.ocr.ui.AppTopBar
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class BatchActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); SessionState.batchActivityVisible.value = true }
    override fun onStop() { SessionState.batchActivityVisible.value = false; super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OceanTheme {
                Surface(Modifier.fillMaxSize()) { BatchScreen(intent.getStringExtra("batch").orEmpty(), intent.getBooleanExtra("add_videos", false)) { finish() } }
            }
        }
    }
}

private data class PendingBatchRoiStart(val batchId: String, val identities: Set<String>)

private data class PendingBatchSourceChange(val batch: BatchProject,
    val source: VideoSourceMode, val versions: Map<String, Long>, val affected: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(initialBatchId: String = "", addOnOpen: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs = remember { BatchJobs.get(context) }
    val videoJobs = remember { VideoJobs.get(context) }
    val library = remember { MovieLibrary.get(context) }
    val batches by jobs.batches.collectAsState()
    val busy by jobs.busy.collectAsState()
    val videoBusy by videoJobs.busy.collectAsState()
    val parallelProjects by videoJobs.parallelProjects.collectAsState()
    val videos by videoJobs.projects.collectAsState()
    val offlineState by videoJobs.offlineState.collectAsState()
    val movies by library.movies.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf(initialBatchId.ifBlank { busy.orEmpty() }) }
    var pendingImportId by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable(selectedId) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    var orderDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDialog by rememberSaveable { mutableStateOf(false) }
    var bulkApprovalDialog by rememberSaveable { mutableStateOf(false) }
    var copyLibrarySources by rememberSaveable { mutableStateOf(false) }
    val smartSettings = remember { VideoSmartBatchSettings(context) }
    var groupSize by rememberSaveable { mutableIntStateOf(smartSettings.groupSize) }
    var economyMode by rememberSaveable { mutableStateOf(smartSettings.economyMode) }
    var analyzeDialog by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<BatchOrderSuggestion?>(null) }
    var analyzedBatch by remember { mutableStateOf<BatchProject?>(null) }
    val batch = batches.firstOrNull { it.id == selectedId }
    var filmContext by rememberSaveable(selectedId, batch?.contextProfile) { mutableStateOf(batch?.contextProfile.orEmpty()) }
    val confirmed = batch?.let { BatchLibraryTarget.saved(it, movies) } ?: false
    val editable = !working && busy == null && videoBusy == null && !confirmed
    var step by rememberSaveable(selectedId, key = "batch-four-screens-step-v1") { mutableIntStateOf(BatchWorkflowUi.initialStep(batch, confirmed, busy == selectedId && selectedId.isNotBlank())) }
    var projectMenu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var prepareDialog by remember { mutableStateOf(false) }
    var sourceChange by remember { mutableStateOf<PendingBatchSourceChange?>(null) }
    var roiSampleId by rememberSaveable { mutableStateOf("") }
    var sharedRoiDraft by remember { mutableStateOf<List<Float>?>(null) }
    var roiValidation by remember { mutableStateOf<List<BatchRoiCheck>>(emptyList()) }
    var validatedRoiIdentities by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingRoiStart by remember { mutableStateOf<PendingBatchRoiStart?>(null) }
    var sourceExportId by rememberSaveable { mutableStateOf("") }
    val sourceExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val id = sourceExportId; sourceExportId = ""
        if (uri != null && id.isNotBlank()) scope.launch {
            try { videoJobs.exportSourceText(id, uri); notice = "Đã lưu SRT tiếng Trung, giữ nguyên mốc thời gian." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Chưa lưu được SRT tiếng Trung." }
        }
    }
    var episodeExportBatchId by rememberSaveable { mutableStateOf("") }
    var episodeExportId by rememberSaveable { mutableStateOf("") }
    var episodeExportKind by rememberSaveable { mutableStateOf("") }
    var episodeExportBusy by remember { mutableStateOf(false) }
    var mergedExportBatchId by rememberSaveable { mutableStateOf("") }
    var mergedExportPath by rememberSaveable { mutableStateOf("") }
    var mergedExportFingerprint by rememberSaveable { mutableStateOf("") }
    fun clearEpisodeExport() {
        episodeExportBatchId = ""
        episodeExportId = ""
        episodeExportKind = ""
    }
    fun saveEpisodeExport(kind: BatchEpisodeFile, uri: Uri?) {
        if (episodeExportKind != kind.name) return
        val batchId = episodeExportBatchId
        val projectId = episodeExportId
        clearEpisodeExport()
        if (uri == null || batchId.isBlank() || projectId.isBlank()) return
        episodeExportBusy = true
        scope.launch {
            try {
                BatchEpisodeExport.save(context, videoJobs, batchId, projectId, kind, uri)
                notice = kind.savedMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: "Chưa lưu được tệp của tập này."
            } finally {
                episodeExportBusy = false
            }
        }
    }
    val episodeSrtExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) {
        saveEpisodeExport(BatchEpisodeFile.SRT, it)
    }
    val episodeMp4Exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) {
        saveEpisodeExport(BatchEpisodeFile.MP4, it)
    }
    fun clearMergedExport() {
        mergedExportBatchId = ""
        mergedExportPath = ""
        mergedExportFingerprint = ""
    }
    val mergedMp4Exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val batchId = mergedExportBatchId
        val path = mergedExportPath
        val fingerprint = mergedExportFingerprint
        clearMergedExport()
        if (uri != null && batchId.isNotBlank() && path.isNotBlank() && fingerprint.isNotBlank()) {
            episodeExportBusy = true
            scope.launch {
                try {
                    jobs.copyMergedOutput(batchId, path, fingerprint, uri)
                    notice = "Đã lưu MP4 cả bộ."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice = e.message ?: "Chưa lưu được MP4 cả bộ."
                } finally {
                    episodeExportBusy = false
                }
            }
        }
    }
    val canChooseMergedExport = !working && busy == null && videoBusy == null && !episodeExportBusy &&
        episodeExportId.isBlank() && mergedExportBatchId.isBlank()
    fun chooseMergedExport(batchId: String, output: BatchOutput) {
        if (!canChooseMergedExport) return
        mergedExportBatchId = batchId
        mergedExportPath = output.path
        mergedExportFingerprint = output.inputFingerprint
        try {
            val title = output.title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(150).ifBlank { "Phim" }
            mergedMp4Exporter.launch("$title-cả-bộ.mp4")
        } catch (e: Exception) {
            clearMergedExport()
            notice = e.message ?: "Không mở được trình chọn nơi lưu."
        }
    }
    fun chooseEpisodeExport(batchId: String, item: BatchItem, kind: BatchEpisodeFile) {
        if (episodeExportId.isNotBlank() || episodeExportBusy || mergedExportBatchId.isNotBlank() || working) return
        episodeExportBatchId = batchId
        episodeExportId = item.projectId
        episodeExportKind = kind.name
        try {
            val name = BatchEpisodeExport.fileName(item, kind)
            when (kind) {
                BatchEpisodeFile.SRT -> episodeSrtExporter.launch(name)
                BatchEpisodeFile.MP4 -> episodeMp4Exporter.launch(name)
            }
        } catch (e: Exception) {
            clearEpisodeExport()
            notice = e.message ?: "Không mở được trình chọn nơi lưu."
        }
    }
    var keyConfigured by remember { mutableStateOf<Boolean?>(null) }
    val canReview = batch?.let { BatchWorkflowUi.reviewAvailable(it, videos) } == true
    val progress = batch?.let { BatchWorkflowUi.progress(it, videos) } ?: 0f
    val running = batch != null && busy == batch.id
    val retryProjects = videos.filter { it.id == videoBusy || it.id in parallelProjects }
    val retryWaiting = running && batch?.let { BatchWorkflowUi.retryWaiting(it, retryProjects) } == true
    val unresolvedCues = batch?.let { BatchWorkflowUi.unresolvedCueCount(it, videos) } ?: 0
    val mergedOutput = batch?.takeIf { it.mergeEpisodes && it.state == "REVIEW" }?.outputs?.singleOrNull()
        ?.takeIf { it.episode == BatchPlan.ALL_EPISODES && it.inputFingerprint.isNotBlank() }
    val mergePending = batch?.mergeEpisodes == true && mergedOutput == null
    val allApproved = step == 3 && batch?.items?.takeIf { it.isNotEmpty() }?.all { item ->
        videos.firstOrNull { it.id == item.projectId }?.let { p ->
            p.cues.isNotEmpty() && !p.sourceExtractionPending && p.cues.all { it.approved && it.translation.isNotBlank() }
        } == true
    } == true
    val needsKey = batch != null && batch.translationMode != VideoTranslationMode.OFFLINE
    val offlineReady = offlineState.contains("đã sẵn sàng")
    val pendingAutomaticSources = batch?.items?.any { item ->
        videos.firstOrNull { it.id == item.projectId }?.let { project ->
            project.sourceMode in setOf(VideoSourceMode.AUTO, VideoSourceMode.ASR) && project.sourceExtractionPending
        } == true
    } == true
    val needsOffline = batch != null && batch.translationMode == VideoTranslationMode.OFFLINE && batch.items.any { item ->
        videos.firstOrNull { it.id == item.projectId }?.let { p -> p.cues.isEmpty() || p.cues.any {
            VideoTranslationPlan.needsLocalDraft(it, batch.translationMode == VideoTranslationMode.HYBRID)
        } } ?: true
    }
    LaunchedEffect(selectedId, step) {
        keyConfigured = withContext(Dispatchers.IO) { AppSettings(context).hasGeminiVideoKey() }
    }
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scope.launch {
            keyConfigured = withContext(Dispatchers.IO) { AppSettings(context).hasGeminiVideoKey() }
        } }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(batch?.state) { if (batch?.state == "REVIEW" && step == 2) step = 3 }
    LaunchedEffect(busy) { if (selectedId.isBlank() && busy != null) { selectedId = busy.orEmpty(); step = 2 } }
    fun back() { if (step > 0) step-- else onBack() }
    BackHandler(enabled = step > 0) { back() }
    fun action(block: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true; notice = ""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Chưa hoàn tất thao tác. Tiến độ đã lưu vẫn được giữ." }
            finally { working = false }
        }
    }
    suspend fun validateRoi(projects: List<VideoProject>): List<BatchRoiCheck> {
        val checks = withContext(Dispatchers.IO) { BatchRoiValidation.check(context, projects) }
        roiValidation = checks
        validatedRoiIdentities = validatedRoiIdentities + projects.filter { project ->
            checks.any { it.projectId == project.id && it.confirmed }
        }.map { BatchRoiValidation.identity(it) }
        return checks
    }
    fun startBatch() {
        val current = batch ?: return
        action {
            val pending = withContext(Dispatchers.IO) {
                val latest = BatchStore(context).load(current.id)
                latest.items.map { BatchRoiValidation.withSharedRoi(VideoStore(context).load(it.projectId), latest) }.filter {
                    videoSourceUsesRoi(it.sourceMode) && !com.gemini.ocr.video.VideoStagedPipeline.extracted(it)
                }
            }
            val toCheck = pending.filter { !BatchRoiValidation.hasSavedOcrProgress(it) &&
                BatchRoiValidation.identity(it) !in validatedRoiIdentities }
            val checks = if (toCheck.isEmpty()) emptyList() else validateRoi(toCheck)
            if (checks.any { !it.confirmed }) pendingRoiStart = PendingBatchRoiStart(current.id, pending.map { BatchRoiValidation.identity(it) }.toSet())
            else { jobs.start(current.id); step = 2 }
        }
    }
    fun configureBatchSource(source: VideoSourceMode) {
        val current = batch ?: return
        if (!editable) return
        action {
            val frozen = withContext(Dispatchers.IO) { BatchStore(context).load(current.id) }
            check(frozen.updatedAt == current.updatedAt) { "Dự án đã thay đổi. Mở lại thiết lập trước khi áp dụng; dữ liệu hiện tại vẫn được giữ." }
            val members = withContext(Dispatchers.IO) { frozen.items.map { VideoStore(context).load(it.projectId) } }
            val affected = members.count { it.sourceMode != source && (it.cues.isNotEmpty() || it.scanMs > 0L) }
            if (affected > 0) sourceChange = PendingBatchSourceChange(frozen, source,
                members.associate { it.id to it.updatedAt }, affected)
            else jobs.configureSource(frozen.id, source)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pendingImportId.ifBlank { selectedId }; pendingImportId = ""
        if (uris.isNotEmpty() && target.isNotBlank()) action { jobs.add(target, uris); step = 0 }
    }
    suspend fun createBatch(): BatchProject {
        val hasKey = withContext(Dispatchers.IO) { AppSettings(context).hasGeminiVideoKey() }
        keyConfigured = hasKey
        val created = jobs.create("Phim mới", BatchKind.SERIES)
        return if (hasKey) created else jobs.configureTranslation(created.id, VideoTranslationMode.OFFLINE)
    }
    fun addVideos() { action {
        val current = batch ?: createBatch().also { selectedId = it.id }
        pendingImportId = current.id; picker.launch(arrayOf("video/*"))
    } }
    var importOpened by rememberSaveable(initialBatchId) { mutableStateOf(false) }
    LaunchedEffect(initialBatchId) {
        if (addOnOpen && !importOpened && initialBatchId.isNotBlank()) {
            importOpened = true
            pendingImportId = initialBatchId
            picker.launch(arrayOf("video/*"))
        }
    }
    fun openVideo(id: String, targetStep: String) {
        context.startActivity(Intent(context, VideoActivity::class.java).putExtra("project", id).putExtra("ux_step", targetStep))
    }
    @Composable
    fun EpisodeOutputs(batchId: String, item: BatchItem, project: VideoProject?) {
        if (project == null || !BatchEpisodeExport.canSave(project, videoBusy)) return
        val exportEnabled = !working && !episodeExportBusy && episodeExportId.isBlank() && mergedExportBatchId.isBlank()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseEpisodeExport(batchId, item, BatchEpisodeFile.SRT) },
                enabled = exportEnabled, modifier = Modifier.testTag("batch_export_vi_${item.projectId}")) {
                Text("Lưu SRT tiếng Việt")
            }
            if (project.exportedMp4.isNotBlank()) {
                OutlinedButton(onClick = { chooseEpisodeExport(batchId, item, BatchEpisodeFile.MP4) },
                    enabled = exportEnabled, modifier = Modifier.testTag("batch_export_mp4_${item.projectId}")) {
                    Text("Lưu MP4 có sẵn")
                }
            } else if (busy == null && videoBusy == null) {
                TextButton(onClick = { openVideo(item.projectId, "export") }, enabled = exportEnabled,
                    modifier = Modifier.testTag("batch_render_episode_${item.projectId}")) {
                    Text("Tạo MP4 cho tập này")
                }
            }
        }
    }
    LaunchedEffect(batch?.id, batch?.title) { title = batch?.title.orEmpty() }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("batch_screen"),
        topBar = { Column {
            AppTopBar("Dịch hàng loạt", "Trung → Việt · tiến độ được lưu", onBack = ::back) {
                IconButton(onClick = { action { selectedId = createBatch().id; step = 0 } }, enabled = busy == null && !working) {
                    Icon(Icons.Default.Add, "Tạo dự án mới")
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { projectMenu = true }, enabled = !working) {
                            Text(batch?.title ?: "Dự án video", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(projectMenu, { projectMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            DropdownMenuItem(text = { Text("Tạo dự án mới") }, enabled = busy == null,
                                onClick = { projectMenu = false; action { selectedId = createBatch().id; step = 0 } })
                            batches.forEach { saved -> DropdownMenuItem(text = { Column {
                                Text(saved.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${saved.items.size} video · ${batchStateLabel(saved.state)}", style = MaterialTheme.typography.bodySmall)
                            } }, onClick = { projectMenu = false; selectedId = saved.id; notice = "" }) }
                        }
                    }
                    if (batch != null) IconButton(onClick = { renameDialog = true }, enabled = editable) { Icon(Icons.Default.Edit, "Đổi tên dự án") }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchWorkflowUi.steps.forEachIndexed { index, label -> FilterChip(step == index, { step = index },
                    enabled = index == 0 || (batch?.items?.isNotEmpty() == true && (index < 3 || canReview)),
                    label = { Text("${index + 1}. $label") }, modifier = Modifier.testTag("batch_step_$index")) }
            }
        } },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step > 0) TextButton(onClick = { step-- }, enabled = !working) { Text("Quay lại") }
                    val label = when {
                        batch == null || batch.items.isEmpty() -> "Chọn video"
                        confirmed && step == 3 && !mergePending -> "Về Kho phim"
                        step == 0 -> "Tiếp tục"
                        step == 1 && pendingAutomaticSources && needsOffline && !offlineReady -> "Lấy phụ đề nguồn"
                        step == 1 && needsOffline && !offlineReady -> "Chuẩn bị dịch trên máy"
                        step == 1 -> "Bắt đầu dịch"
                        step == 2 && running -> "Tạm dừng"
                        step == 2 && batch.state == "REVIEW" -> "Kiểm tra và xuất"
                        step == 2 -> "Tiếp tục"
                        mergePending -> if (allApproved) "Tạo MP4 cả bộ" else "Duyệt phụ đề trước khi ghép"
                        batch.state == "REVIEW" && batch.delivery != BatchDelivery.MP4_ONLY -> "Xác nhận lưu Kho phim"
                        mergedOutput != null -> "Lưu MP4 cả bộ"
                        batch.state == "REVIEW" -> "Lưu kết quả"
                        else -> "Về tiến độ xử lý"
                    }
                    Button(onClick = {
                        when {
                            batch == null || batch.items.isEmpty() -> addVideos()
                            confirmed && step == 3 && !mergePending -> onBack()
                            step == 0 -> step = 1
                            step == 1 && !pendingAutomaticSources && needsOffline && !offlineReady -> prepareDialog = true
                            step == 1 -> startBatch()
                            step == 2 && running -> jobs.pause()
                            step == 2 && batch.state == "REVIEW" -> step = 3
                            step == 2 -> startBatch()
                            mergePending -> startBatch()
                            batch.state == "REVIEW" && batch.delivery != BatchDelivery.MP4_ONLY -> confirmDialog = true
                            mergedOutput != null -> chooseMergedExport(batch.id, mergedOutput)
                            batch.state == "REVIEW" -> batch.items.firstOrNull()?.let { openVideo(it.projectId, "export") }
                            else -> step = 2
                        }
                    }, enabled = !working && when {
                        batch == null || batch.items.isEmpty() -> busy == null && !confirmed
                        step == 2 && running -> true
                        step == 1 || step == 2 -> busy == null && !confirmed
                        step == 3 && mergePending -> busy == null && videoBusy == null && allApproved
                        step == 3 && mergedOutput != null && batch.delivery == BatchDelivery.MP4_ONLY -> canChooseMergedExport
                        step == 3 -> busy == null && videoBusy == null
                        else -> true
                    }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(if (step == 3) "batch_confirm_library" else if (step == 1 || step == 2) "batch_start" else "batch_next")) {
                        if (working) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(label)
                    }
                }
            }
        } }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 900.dp
            Row(Modifier.widthIn(max = 1120.dp).fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("batch_content_list"), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (notice.isNotBlank()) item { StatusNotice(notice, error = true) }
                    if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (busy != null && busy != selectedId) item {
                        Text("Một dự án khác đang được xử lý. Các thao tác thay đổi tạm thời được khóa.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { selectedId = busy.orEmpty(); step = 2 }) { Text("Xem dự án đang xử lý") }
                    }
                    if (batch == null || batch.items.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Movie, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Chọn video để bắt đầu", style = MaterialTheme.typography.titleLarge)
                            Text("Thêm một hoặc nhiều video, rồi kiểm tra danh sách trước khi dịch.")
                            Text("MP4, MKV và định dạng thiết bị hỗ trợ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (batch?.message?.isNotBlank() == true) StatusNotice(batch.message, error = true)
                        }
                    }
                    if (batch != null && batch.items.isNotEmpty()) when (step) {
                        0 -> {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Danh sách video", style = MaterialTheme.typography.titleLarge)
                                        Text("${batch.items.size} video · ${BatchWorkflowUi.duration(batch.items.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(onClick = ::addVideos, enabled = editable, modifier = Modifier.testTag("batch_select_videos")) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text("Thêm video") }
                                }
                                if (batch.message.startsWith("Đã nhập") || batch.message.startsWith("Đã giữ") || batch.message.contains("không nhập được"))
                                    StatusNotice(batch.message, error = batch.message.contains("không nhập được"))
                            }
                            itemsIndexed(batch.items, key = { _, item -> item.projectId }) { index, item ->
                                BatchVideoCard(item, index, batch.items.size, videos.firstOrNull { it.id == item.projectId }, editable, showOrdering = true,
                                    onOpen = { openVideo(item.projectId, "setup") }, onRemove = { action { jobs.remove(batch.id, item.projectId) } },
                                    onMove = { to -> action { jobs.reorder(batch.id, index, to) } }, onEpisode = { value -> action { jobs.setEpisode(batch.id, item.projectId, value) } })
                            }
                            item { ExpandableSection("Thứ tự và nhóm tập", "Sắp xếp thứ tự xem và thứ tự ghép cả bộ.") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(batch.kind == BatchKind.SERIES, { action { jobs.changeKind(batch.id, BatchKind.SERIES) } }, label = { Text("Danh sách tập") }, enabled = editable)
                                    FilterChip(batch.kind == BatchKind.MOVIE_PARTS, { action { jobs.changeKind(batch.id, BatchKind.MOVIE_PARTS) } }, label = { Text("Các phần cùng phim") }, enabled = editable)
                                }
                                OutlinedButton(onClick = { analyzeDialog = true }, enabled = editable && batch.items.size in 2..BatchOrderAnalysis.MAX_ITEMS) { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Gợi ý thứ tự tập") }
                                Text("Kiểm tra gợi ý trước khi áp dụng. Tập chưa xác định được giữ riêng.", style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        1 -> {
                            item { Text("Thiết lập phụ đề", style = MaterialTheme.typography.titleLarge); Text("Áp dụng cho ${batch.items.size} video. Bạn có thể chỉnh từng video bên dưới.") }
                            item { OceanCard {
                                Text("Vùng phụ đề dùng chung", style = MaterialTheme.typography.titleMedium)
                                Text("Chọn vùng trên một tập mẫu dùng OCR. Vùng chung không đổi phụ đề rời hoặc track chữ có sẵn.", style = MaterialTheme.typography.bodySmall)
                                val ocrItems = batch.items.filter { item -> videos.any { it.id == item.projectId && videoSourceUsesRoi(it.sourceMode) } }
                                val sample = videos.firstOrNull { p -> ocrItems.any { it.projectId == p.id } }
                                if (sample != null) {
                                    BatchChoice("Tập mẫu", sample.title, editable, ocrItems, { it.name }) { roiSampleId = it.projectId }
                                    OutlinedButton(onClick = { roiSampleId = sample.id }, enabled = editable,
                                        modifier = Modifier.testTag("batch_shared_roi")) { Icon(Icons.Default.Crop, null); Spacer(Modifier.width(8.dp)); Text("Chọn vùng chữ Trung") }
                                }
                                OutlinedButton(onClick = { action {
                                    val projects = withContext(Dispatchers.IO) {
                                        batch.items.map { BatchRoiValidation.withSharedRoi(VideoStore(context).load(it.projectId), batch) }
                                    }
                                    validateRoi(projects)
                                } }, enabled = editable, modifier = Modifier.testTag("batch_validate_roi")) { Text("Kiểm tra vùng trên các tập") }
                                roiValidation.forEach { check ->
                                    Text("${batch.items.firstOrNull { it.projectId == check.projectId }?.name.orEmpty()}: ${check.message}",
                                        color = if (check.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Không thấy chữ trong vài ảnh chưa chứng minh video không có phụ đề. Tập chưa đủ bằng chứng cần bạn kiểm tra vùng.", style = MaterialTheme.typography.bodySmall)
                            } }
                            item { ExpandableSection("Nguồn và cách dịch nâng cao", "Tự đề xuất riêng cho từng video; giữ chế độ dịch bạn đã chọn.") {
                                BatchChoice("Nguồn phụ đề", sourceLabel(batch.sourceMode), editable,
                                    listOf(VideoSourceMode.AUTO, VideoSourceMode.EMBEDDED, VideoSourceMode.SIDECAR, VideoSourceMode.OCR, VideoSourceMode.ASR),
                                    ::sourceLabel, ::configureBatchSource)
                                if (batch.detectNewSources || batch.sourceMode == VideoSourceMode.AUTO) Text("Mỗi video ưu tiên phụ đề có sẵn, rồi OCR toàn video và nhận diện giọng nói khi nguồn còn thiếu. Tệp phụ đề và lựa chọn riêng của từng tập vẫn được giữ khi chỉ đổi cách dịch.", style = MaterialTheme.typography.bodySmall)
                                if (batch.sourceMode in setOf(VideoSourceMode.AUTO, VideoSourceMode.ASR) || videos.any { it.sourceMode in setOf(VideoSourceMode.AUTO, VideoSourceMode.ASR) }) AsrModelManagerButton(editable)
                                BatchChoice("Chế độ dịch", modeLabel(batch.translationMode), editable, VideoTranslationMode.entries.toList(), ::modeLabel) { mode ->
                                    action { jobs.configureTranslation(batch.id, mode) }
                                }
                                if (needsKey) {
                                    Text(if(batch.translationMode==VideoTranslationMode.HYBRID)
                                        "Quét đủ 100% thời lượng trên thiết bị, lưu chữ Trung và mốc thời gian. Hội thoại vừa giới hạn gửi một lượt; phần dài được tự chia thành các phần lớn nhất vừa ngân sách Gemini."
                                        else "OCR cục bộ lấy chữ Trung trước. Gemini nhận văn bản để dịch; hình chỉ được dùng nếu bạn chủ động chọn nhận chữ bằng AI.", style = MaterialTheme.typography.bodySmall)
                                    if (keyConfigured == false) StatusNotice("Chưa có khóa Gemini. Bạn vẫn có thể trích xuất và kiểm tra chữ Trung; thêm khóa trước công đoạn Dịch, hoặc chủ động chọn dịch trên máy.", error = true)
                                } else {
                                    Text("Dịch bản nháp trên máy. Cần tải công cụ ngôn ngữ một lần trước khi dùng.", style = MaterialTheme.typography.bodySmall)
                                    BatchChoice("Hiệu đính Gemini sau bản nháp", polishLabel(batch.offlinePolishPolicy), editable,
                                        VideoPolishPolicy.entries, ::polishLabel) { policy ->
                                        action { jobs.setOfflinePolishPolicy(batch.id, policy) }
                                    }
                                    if (batch.offlinePolishPolicy != VideoPolishPolicy.NONE) {
                                        Text("Gemini chỉ nhận văn bản phụ đề để hiệu đính. Nếu dịch vụ gián đoạn, bản nháp vẫn được giữ. Bạn có thể tắt hiệu đính rồi tiếp tục hoàn thiện.", style = MaterialTheme.typography.bodySmall)
                                        if (keyConfigured == false) StatusNotice("Bạn có thể dịch bản nháp ngay. Cần khóa Gemini cho bước hiệu đính đã chọn.", error = true)
                                    }
                                }
                                if (needsOffline && !offlineReady) StatusNotice("Cần chuẩn bị công cụ dịch trên máy trước khi bắt đầu.", onRetry = { prepareDialog = true })
                                if (batch.sourceMode == VideoSourceMode.SIDECAR) StatusNotice("Chọn tệp SRT, ASS hoặc VTT tương ứng trong thiết lập của từng video.")
                            } }
                            item { ExpandableSection("Thiết lập từng video", "Vùng chữ, ngữ cảnh, tệp phụ đề và từ điển riêng.", initiallyExpanded = batch.sourceMode == VideoSourceMode.SIDECAR) {
                                batch.items.forEach { item -> TextButton(onClick = { openVideo(item.projectId, "setup") }, enabled = busy == null && !working) { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                            } }
                            item {
                                BatchChoice("Dịch đồng thời", batch.executionMode.label, editable,
                                    BatchExecutionMode.entries.toList(), { it.label }) { value ->
                                    action { jobs.setExecutionMode(batch.id, value) }
                                }
                                val slots = BatchParallelism.workerLimit(context, batch.executionMode)
                                Text(if (batch.executionMode == BatchExecutionMode.SEQUENTIAL)
                                    "Xử lý lần lượt từng video. Chọn 8 GB hoặc 16 GB để dịch nhiều video cùng lúc."
                                    else "Dịch và hiệu đính tối đa $slots video cùng lúc trên máy này. Số luồng tự giảm khi thiếu bộ nhớ.",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("Mức 8 GB dùng tối đa 2 luồng; mức 16 GB dùng tối đa 8 luồng theo số lõi CPU và RAM còn trống. Tốc độ Gemini còn phụ thuộc mạng và hạn mức tài khoản.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (economyMode) Text("Dịch tiết kiệm chạy ở hàng đợi nền của Gemini theo lượt. Tắt Dịch tiết kiệm để dùng dịch đồng thời.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            item {
                                BatchChoice("Lưu kết quả", deliveryLabel(batch.delivery), editable, BatchDelivery.entries.toList(), ::deliveryLabel) { value -> action { jobs.setDelivery(batch.id, value) } }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = batch.mergeEpisodes,
                                        onCheckedChange = { value -> action { jobs.setMergeEpisodes(batch.id, value) } },
                                        enabled = editable, modifier = Modifier.testTag("batch_merge_series"))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Tự ghép các tập thành một phim", style = MaterialTheme.typography.titleSmall)
                                        Text("Tạo MP4 cả bộ theo thứ tự danh sách, sau khi dịch xong và duyệt phụ đề.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(if (batch.mergeEpisodes)
                                    "Đã bật MP4 cả bộ. Bạn vẫn có thể lưu riêng các tập đã xong; các tệp gốc được giữ."
                                    else "Kho phim phát video gốc cùng phụ đề rời, không giảm chất lượng hình. Các tập/phần được giữ riêng theo thứ tự.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            item { ExpandableSection("Tùy chọn nâng cao", "Ngữ cảnh phim, âm thanh, Kho thoại và công cụ.") {
                                BatchAutoRetrySetting(batch.autoRetryEnabled, editable) { value -> action { jobs.setAutoRetry(batch.id, value) } }
                                OutlinedTextField(filmContext, { filmContext = it.take(8000) },
                                    label = { Text("Tên nhân vật, xưng hô, thuật ngữ và ngữ cảnh") },
                                    supportingText = { Text("Chỉ nhập dữ kiện đã biết. Áp dụng riêng cho bộ phim; không thay cấu hình chung.") },
                                    minLines = 3, maxLines = 8, enabled = editable, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = { action { jobs.setContextProfile(batch.id, filmContext) } },
                                    enabled = editable && filmContext != batch.contextProfile) { Text("Lưu ngữ cảnh bộ phim") }
                                if(batch.translationMode==VideoTranslationMode.GEMINI)BatchChoice("Số câu mỗi nhóm Gemini", "$groupSize câu", editable, VideoSmartBatchSettings.GROUP_SIZES, { "$it câu" }) {
                                    groupSize = it; smartSettings.groupSize = it
                                }
                                Text("Các nhóm trong cùng video giữ đúng thứ tự ngữ cảnh. Dịch đồng thời áp dụng giữa các video và nhường tài nguyên khi Live hoạt động.", style = MaterialTheme.typography.bodySmall)
                                if(batch.translationMode!=VideoTranslationMode.OFFLINE) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Checkbox(economyMode,{ economyMode=it;smartSettings.economyMode=it },enabled=editable)
                                        Text("Dịch nền tiết kiệm · Gemini Batch API",Modifier.weight(1f))
                                    }
                                    if(economyMode) Text(if(batch.translationMode==VideoTranslationMode.HYBRID)
                                        "Mỗi phần lớn nhất vừa giới hạn được gửi thành một tác vụ nền. Kết quả từng phần được giữ; cần tài khoản/model hỗ trợ."
                                        else "Gửi từng nhóm theo ngữ cảnh và chờ Google xử lý. Bấm Tiếp tục để lấy kết quả; không tự chuyển sang dịch nhanh. Cần tài khoản/model hỗ trợ.",style=MaterialTheme.typography.bodySmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(batch.useReviewedMemory, { enabled -> action { jobs.useReviewedMemory(batch.id, enabled) } }, enabled = editable)
                                    Text("Dùng câu đã duyệt trong phim đã chọn", Modifier.weight(1f))
                                }
                                Text(if (batch.useReviewedMemory) "Phim đã chọn: ${batch.reviewedFilmId}. Chỉ dùng câu khớp nguyên văn và ngữ cảnh." else "Chọn phim trong Kho thoại trước khi bật. Các câu mới vẫn được dịch bình thường.", style = MaterialTheme.typography.bodySmall)
                                Text("Đối chiếu âm thanh: chưa có bộ ASR tiếng Trung hoạt động trong Android. Các câu nghi vấn vẫn được đánh dấu; quy trình không chờ ASR.", style = MaterialTheme.typography.bodySmall)
                                Text(offlineState, style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        2 -> {
                            item { BatchDiagnosticsPanel(batch.items.map { it.projectId }, running) }
                            item {
                                BatchStageProgress(batch, videos)
                                if (batch.executionMode != BatchExecutionMode.SEQUENTIAL)
                                    Text("Dịch đồng thời · ${batch.executionMode.label}", style = MaterialTheme.typography.bodySmall)
                                Text(when {
                                    retryWaiting -> "Đang tự khôi phục"
                                    running && batch.items.any { it.state == "EXPORTING" } -> "Đang xuất MP4"
                                    running -> "Đang xử lý video"
                                    else -> batchStateLabel(batch.state)
                                }, style = MaterialTheme.typography.titleLarge)
                                BatchRetryStatus(batch, retryProjects, running)
                                if (retryWaiting) TextButton(onClick = { jobs.cancel() }, modifier = Modifier.testTag("batch_cancel_retry")) { Text("Dừng hàng đợi") }
                                if (batch.state == "PAUSED" && batch.currentStage == com.gemini.ocr.video.BatchPipelineStage.POLISH &&
                                    batch.offlinePolishPolicy != VideoPolishPolicy.NONE) {
                                    Text("Bản nháp đã được lưu. Để hoàn thiện trên máy, tắt hiệu đính trong Thiết lập phụ đề rồi bấm Tiếp tục.", style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                                val counts = BatchWorkflowUi.completionCounts(batch, videos)
                                Text("Đã lấy nguồn: ${counts.sources}/${counts.total} · Đã dịch: ${counts.translated}/${counts.total}",
                                    Modifier.padding(top = 8.dp).testTag("batch_source_translation_counts"), style = MaterialTheme.typography.titleMedium)
                                Text("Hoàn tất các bước: ${counts.finished}/${counts.total} video", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("batch_finish_count"))
                                Text("Tiến độ được lưu theo từng video. Bạn có thể tạm dừng hoặc đóng màn hình.", style = MaterialTheme.typography.bodySmall)
                                if (batch.message.isNotBlank()) {
                                    if (batch.state in setOf("NEEDS_REVIEW", "PAUSED", "FAILED"))
                                        StatusNotice(BatchWorkflowUi.friendlyMessage(batch.message), error = batch.state in setOf("NEEDS_REVIEW", "FAILED"))
                                    ExpandableSection("Chi tiết xử lý", "Tiến độ, thông báo và thao tác bổ sung") {
                                        Text(batch.message, style = MaterialTheme.typography.bodySmall)
                                        if (running && !retryWaiting) TextButton(onClick = { jobs.cancel() }) { Text("Dừng hàng đợi") }
                                    }
                                }
                                if (needsKey && keyConfigured == false) StatusNotice("Chưa có khóa Gemini. Chữ Trung đã lấy được vẫn được giữ; thêm khóa trước công đoạn Dịch hoặc chủ động chọn dịch trên máy.", error = true)
                            }
                            itemsIndexed(batch.items, key = { _, item -> item.projectId }) { index, item ->
                                val project = videos.firstOrNull { it.id == item.projectId }
                                BatchVideoCard(item, index, batch.items.size, project, false, false,
                                    { openVideo(item.projectId, "review") }, openEnabled = busy == null && videoBusy == null && !working,
                                    episodeOutputs = { EpisodeOutputs(batch.id, item, project) })
                            }
                        }
                        3 -> {
                            item { BatchDiagnosticsPanel(batch.items.map { it.projectId }, running) }
                            item {
                                Text("Kiểm tra và xuất", style = MaterialTheme.typography.titleLarge)
                                Text("Xem video, đối chiếu câu Trung và chỉnh câu Việt trước khi xuất.")
                                if (unresolvedCues > 0) OceanCard(Modifier.fillMaxWidth().testTag("batch_review_warning")) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                                        Text("Còn $unresolvedCues câu được đánh dấu chưa duyệt.", style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                    }
                                    Text("Đối chiếu các câu này trước khi xuất hoặc lưu Kho phim.", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (batch.state == "REVIEW" && !confirmed) {
                                    OutlinedButton(onClick = { bulkApprovalDialog = true },
                                        enabled = !working && busy == null && videoBusy == null,
                                        modifier = Modifier.testTag("batch_approve_all_to_library")) {
                                        Text("Duyệt toàn bộ và lưu Kho phim")
                                    }
                                }
                            }
                            itemsIndexed(batch.items, key = { _, item -> item.projectId }) { index, item ->
                                val project = videos.firstOrNull { it.id == item.projectId }
                                BatchVideoCard(item, index, batch.items.size, project, false, false,
                                    { openVideo(item.projectId, "review") }, openEnabled = busy == null && videoBusy == null && !working,
                                    episodeOutputs = { EpisodeOutputs(batch.id, item, project) })
                            }
                            item { ExpandableSection("Đối chiếu các phiên bản phụ đề", "Giữ OCR gốc, chữ Trung đã kiểm tra, bản dịch nháp và sau hiệu đính.") {
                                batch.items.forEach { item ->
                                    val project = videos.firstOrNull { it.id == item.projectId }
                                    if (project != null && project.cues.isNotEmpty()) {
                                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                                        val flagged = project.cues.filter { BatchWorkflowUi.reviewFlags(it.qualityFlags).isNotEmpty() }
                                        Text("${flagged.size} câu được đánh dấu · ${project.cues.size} câu đã lưu", style = MaterialTheme.typography.bodySmall)
                                        (flagged.ifEmpty { project.cues.take(1) }).take(20).forEach { cue ->
                                            HorizontalDivider()
                                            Text("${BatchWorkflowUi.duration(cue.startMs)} → ${BatchWorkflowUi.duration(cue.endMs)}", style = MaterialTheme.typography.labelSmall)
                                            Text("OCR gốc: ${cue.rawZh.ifBlank { cue.source }}")
                                            Text("Trung đã kiểm tra: ${cue.correctedZh.ifBlank { cue.source }}")
                                            Text("Việt nháp: ${cue.draftVi.ifBlank { cue.translation }.ifBlank { "Chưa dịch" }}")
                                            Text("Việt hiệu đính: ${cue.polishedVi.ifBlank { "Chưa có lượt hiệu đính" }}")
                                            val reviewFlags = BatchWorkflowUi.reviewFlags(cue.qualityFlags)
                                            if (reviewFlags.isNotEmpty()) Text("Cần kiểm tra: ${reviewFlags.joinToString(", ") { BatchWorkflowUi.flagLabel(it) }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            if ("ASR_UNAVAILABLE" in cue.qualityFlags) Text("Chưa đối chiếu âm thanh; giữ phụ đề OCR làm nguồn.", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { openVideo(item.projectId, "review") }, enabled = busy == null && !working) { Text("Mở đầy đủ và sửa câu") }
                                    }
                                }
                            } }
                            if (batch.outputs.isNotEmpty()) item {
                                Text("Xem bản video đã xuất", style = MaterialTheme.typography.titleMedium)
                                batch.outputs.forEach { output -> OutlinedButton(onClick = { context.startActivity(Intent(context, MoviePlaybackActivity::class.java).putExtra("preview", output.path)) }, enabled = busy == null) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text(output.title, maxLines = 2) } }
                                if (mergedOutput != null) OutlinedButton(
                                    onClick = { chooseMergedExport(batch.id, mergedOutput) }, enabled = canChooseMergedExport,
                                    modifier = Modifier.testTag("batch_save_merged_mp4")) { Text("Lưu MP4 cả bộ") }
                            }
                            item {
                                Text("Lưu Kho phim hoặc xuất MP4", style = MaterialTheme.typography.titleLarge)
                                Text("Lưu phụ đề hoặc video có chữ Việt, sau đó xác nhận phim đã kiểm tra.")
                                if (confirmed) StatusNotice("Đã lưu vào Kho phim. Bạn có thể xem và theo dõi đồng bộ tại đó.")
                                else if (batch.state != "REVIEW") StatusNotice("Cần dịch xong trước khi lưu Kho phim. Timeline đã xong vẫn được giữ.")
                                else StatusNotice("Đã dịch xong, chưa lưu Kho phim. Kiểm tra phụ đề rồi xác nhận lưu.")
                                if (mergePending) {
                                    Text("Duyệt phụ đề từng video, sau đó bấm Tạo MP4 cả bộ. Khi ghép xong, bạn có thể chọn nơi lưu bản ghép hoặc xác nhận đưa các tập vào Kho phim.",
                                        style = MaterialTheme.typography.bodySmall)
                                } else if (batch.delivery != BatchDelivery.SOURCE_LIBRARY && !confirmed) {
                                    OutlinedButton(onClick = { runCatching { jobs.start(batch.id); step = 2 }.onFailure { notice = it.message.orEmpty() } },
                                        enabled = editable && allApproved) { Text("Tạo MP4 chất lượng cao cho danh sách") }
                                    if (!allApproved) Text("Duyệt phụ đề từng video trước khi tạo MP4 hàng loạt. Bạn vẫn có thể xuất riêng sau khi kiểm tra cảnh báo.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            itemsIndexed(batch.items, key = { _, item -> "export-${item.projectId}" }) { _, item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("SRT · văn bản    ASS · có kiểu chữ    MP4 · video có phụ đề", style = MaterialTheme.typography.bodySmall)
                                val sourceProject = videos.firstOrNull { it.id == item.projectId }
                                if (sourceProject != null) Text("Nguồn đã dùng: ${videoResolvedSourceLabel(sourceProject)}", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { sourceExportId = item.projectId; sourceExporter.launch("${item.name.substringBeforeLast('.')}-zh.srt") },
                                    enabled = busy == null && !working && sourceProject?.let { com.gemini.ocr.video.VideoStagedPipeline.extracted(it) } == true,
                                    modifier = Modifier.testTag("batch_export_zh_${item.projectId}")) { Text("Lưu SRT tiếng Trung") }
                                OutlinedButton(onClick = { openVideo(item.projectId, "export") }, enabled = busy == null && !working) { Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Lưu phụ đề / video") }
                            } } }
                            if (batch.outputs.isNotEmpty()) item { ExpandableSection("Kết quả đã xuất", "${batch.outputs.size} tập / phim sẵn sàng xem lại") {
                                batch.outputs.forEach { output -> TextButton(onClick = { context.startActivity(Intent(context, MoviePlaybackActivity::class.java).putExtra("preview", output.path)) }, enabled = busy == null) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text(output.title) } }
                            } }
                        }
                    }
                }
                if (wide && batch != null) Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 24.dp)) { Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(batch.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${batch.items.size} video · ${BatchWorkflowUi.duration(batch.items.sumOf { it.durationMs })}")
                    Text(if (confirmed) "Đã có trong Kho phim" else batchStateLabel(batch.state), color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    Text("Nguồn: ${sourceLabel(batch.sourceMode)}", style = MaterialTheme.typography.bodySmall)
                    Text("Cách dịch: ${modeLabel(batch.translationMode)}", style = MaterialTheme.typography.bodySmall)
                    Text("${BatchPlan.groups(batch).size} tập / nhóm", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Bước ${step.coerceIn(0, 3) + 1}: ${BatchWorkflowUi.steps[step.coerceIn(0, 3)]}", style = MaterialTheme.typography.titleSmall)
                } } }
            }
        }
    }
    val awaitingRoi = pendingRoiStart
    if (awaitingRoi != null) AlertDialog(onDismissRequest = { pendingRoiStart = null },
        title = { Text("Kiểm tra vùng phụ đề trước khi dịch") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Các ảnh mẫu chưa đủ xác nhận vùng chữ ở một số tập. Hãy mở vùng chọn để kiểm tra, hoặc xác nhận bạn đã kiểm tra trực tiếp. Không coi ảnh không có chữ là phụ đề rỗng.")
            roiValidation.filter { !it.confirmed }.forEach { check -> Text("${batch?.items?.firstOrNull { it.projectId == check.projectId }?.name.orEmpty()}: ${check.message}", style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = {
            pendingRoiStart = null
            action {
                val keys = withContext(Dispatchers.IO) {
                    val latest = BatchStore(context).load(awaitingRoi.batchId)
                    latest.items.map { BatchRoiValidation.withSharedRoi(VideoStore(context).load(it.projectId), latest) }
                        .filter { videoSourceUsesRoi(it.sourceMode) && !com.gemini.ocr.video.VideoStagedPipeline.extracted(it) }
                        .map { BatchRoiValidation.identity(it) }.toSet()
                }
                check(keys == awaitingRoi.identities) { "Danh sách hoặc vùng chữ đã thay đổi. Kiểm tra vùng lại trước khi bắt đầu." }
                validatedRoiIdentities = validatedRoiIdentities + keys
                jobs.start(awaitingRoi.batchId); step = 2
            }
        }, enabled = editable) { Text("Đã kiểm tra, tiếp tục") } },
        dismissButton = { TextButton(onClick = { pendingRoiStart = null; step = 1 }) { Text("Kiểm tra vùng chữ") } })
    val sampleProject = videos.firstOrNull { it.id == roiSampleId }
    if (sampleProject != null && batch != null) VideoRegionDialog(sampleProject,
        minOf(1_000, sampleProject.durationMs / 3), true,
        onCancel = { roiSampleId = "" }, onConfirm = { frame ->
            roiSampleId = ""
            val roi = listOf(frame.left, frame.top, frame.right, frame.bottom)
            val hasSavedWork = batch.items.any { item -> videos.firstOrNull { it.id == item.projectId }?.let {
                videoSourceUsesRoi(it.sourceMode) && it.roi != roi && (it.cues.isNotEmpty() || it.scanMs > 0L)
            } == true }
            if (hasSavedWork) sharedRoiDraft = roi
            else action {
                jobs.applySharedRoi(batch.id, roi)
                validateRoi(withContext(Dispatchers.IO) { batch.items.map { VideoStore(context).load(it.projectId) } })
            }
        })
    val pendingRoi = sharedRoiDraft
    if (pendingRoi != null && batch != null) AlertDialog(onDismissRequest = { sharedRoiDraft = null },
        title = { Text("Thay vùng OCR dùng chung?") },
        text = { Text("Đổi vùng yêu cầu nhận chữ lại ở các video đã có tiến độ; phụ đề và bản dịch của các video đổi vùng sẽ được làm lại. Giữ vùng hiện tại để bảo toàn công việc đã sửa.") },
        confirmButton = { TextButton(onClick = { sharedRoiDraft = null; action {
            jobs.applySharedRoi(batch.id, pendingRoi, resetConfirmed = true)
            validateRoi(withContext(Dispatchers.IO) { batch.items.map { VideoStore(context).load(it.projectId) } })
        } }, enabled = editable) { Text("Đổi vùng và nhận chữ lại") } },
        dismissButton = { TextButton(onClick = { sharedRoiDraft = null }) { Text("Giữ hiện tại") } })
    val pendingSourceChange = sourceChange
    if (pendingSourceChange != null) AlertDialog(onDismissRequest = { sourceChange = null },
        title = { Text("Áp dụng nguồn cho cả danh sách?") },
        text = { Text("Thiết lập này áp dụng nguồn ${sourceLabel(pendingSourceChange.source)} cho tất cả video. ${pendingSourceChange.affected} video đang có phụ đề hoặc checkpoint sẽ đổi nguồn: toàn bộ dòng phụ đề, bản dịch, câu đã duyệt và tiến độ của các video đó sẽ bị xóa để lấy lại nguồn. Chọn Giữ hiện tại để bảo toàn nội dung đã sửa.",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            sourceChange = null
            action {
                withContext(Dispatchers.IO) {
                    val fresh = BatchStore(context).load(pendingSourceChange.batch.id)
                    check(fresh.updatedAt == pendingSourceChange.batch.updatedAt &&
                        fresh.items.map { it.projectId } == pendingSourceChange.batch.items.map { it.projectId }) {
                        "Danh sách hoặc thiết lập đã thay đổi. Kiểm tra lại trước khi đổi nguồn; dữ liệu hiện tại vẫn được giữ."
                    }
                    check(pendingSourceChange.versions.all { (id, version) -> VideoStore(context).load(id).updatedAt == version }) {
                        "Một video đã thay đổi. Kiểm tra lại trước khi đổi nguồn; phụ đề và checkpoint hiện tại vẫn được giữ."
                    }
                }
                jobs.configureSource(pendingSourceChange.batch.id, pendingSourceChange.source)
            }
        }, enabled = !working && busy == null && videoJobs.busy.value == null && batch?.id == pendingSourceChange.batch.id) { Text("Đổi nguồn và lấy lại phụ đề") } },
        dismissButton = { TextButton(onClick = { sourceChange = null }) { Text("Giữ hiện tại") } })
    if (renameDialog && batch != null) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("Tên phim / bộ phim") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Tên dự án") }) },
        confirmButton = { TextButton(onClick = { renameDialog = false; action { jobs.rename(batch.id, title) } }, enabled = title.isNotBlank()) { Text("Lưu tên") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Hủy") } })
    if (prepareDialog) AlertDialog(onDismissRequest = { prepareDialog = false }, title = { Text("Chuẩn bị dịch trên máy") },
        text = { Text("Cần mạng để tải công cụ tiếng Trung và tiếng Việt một lần. Video không được gửi lên Gemini trong bước này. Sau đó bạn có thể dịch trên máy.") },
        confirmButton = { TextButton(onClick = { prepareDialog = false; action { videoJobs.prepareOffline() } }) { Text("Tải công cụ") } },
        dismissButton = { TextButton(onClick = { prepareDialog = false }) { Text("Để sau") } })
    if (analyzeDialog && batch != null) AlertDialog(onDismissRequest = { analyzeDialog = false },
        title = { Text("Phân tích thứ tự tập") },
        text = { Text("Ưu tiên nhãn tập trong tên tệp. Nếu chưa rõ, tối đa 2 ảnh vùng chữ/video được OCR cục bộ; tên tệp và vài đoạn chữ nguồn được gửi qua 1 yêu cầu Gemini. Gợi ý cần bạn kiểm tra, không tự đổi mã tập hoặc ghép phim.") },
        confirmButton = { TextButton(onClick = {
            analyzeDialog = false
            val snapshot = batch
            action {
                val result = BatchOrderAnalysis(context).analyze(snapshot, allowCloud = true, gate = {
                    currentCoroutineContext().ensureActive()
                    while (SessionState.running.value) delay(300)
                })
                analyzedBatch = snapshot; suggestion = result
            }
        }) { Text("Phân tích và xem gợi ý") } },
        dismissButton = { TextButton(onClick = { analyzeDialog = false }) { Text("Tự chỉnh thủ công") } })
    val preview = suggestion
    val snapshot = analyzedBatch
    if (preview != null && snapshot != null) AlertDialog(onDismissRequest = { suggestion = null },
        title = { Text("Kiểm tra gợi ý thứ tự") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(when (preview.confidence) {
                "EXPLICIT" -> "Dựa trên nhãn tập/phần trong tên tệp"
                "TENTATIVE" -> "Gợi ý sơ bộ từ vài đoạn chữ"
                "LOW" -> "Độ tin cậy thấp; cần đối chiếu thủ công"
                else -> "Chưa đủ bằng chứng; giữ thứ tự hiện tại"
            })
            Text(preview.summary)
            preview.projectIds.forEachIndexed { i, id ->
                Text("${i+1}. ${snapshot.items.firstOrNull { it.projectId == id }?.name.orEmpty()}")
                Text(preview.reasons[id].orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            suggestion = null
            action { jobs.applyOrder(snapshot, preview.projectIds) }
        }, enabled = !working && busy == null && batch?.id == snapshot.id && preview.confidence != "UNKNOWN") {
            Text("Xác nhận áp dụng thứ tự")
        } }, dismissButton = { TextButton(onClick = { suggestion = null }) { Text("Giữ thứ tự hiện tại") } })
    if (orderDialog && batch != null) AlertDialog(onDismissRequest = { orderDialog = false },
        title = { Text("Xác nhận thứ tự xử lý") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { Text("${batch.title} · ${batch.items.size} video, ${BatchPlan.groups(batch).size} tập / phim.")
                batch.items.forEachIndexed { i, item -> Text("${i + 1}. ${item.name} → tập ${item.episode}") }
                Text("Quay lại nếu cần đổi thứ tự hoặc nhóm tập. Khi bắt đầu, app xử lý lần lượt và giữ checkpoint.") }
        }, confirmButton = { TextButton(onClick = { orderDialog = false; runCatching { jobs.start(batch.id) }.onFailure { notice = it.message.orEmpty() } }) {
            Text("Đúng thứ tự · bắt đầu")
        } }, dismissButton = { TextButton(onClick = { orderDialog = false }) { Text("Chỉnh lại") } })
    if (confirmDialog && batch != null && !mergePending) AlertDialog(onDismissRequest = { confirmDialog = false },
        title = { Text(if(batch.targetMovieId.isNotBlank()) "Thêm các tập mới vào bộ phim?" else "Đưa phim đã kiểm tra vào Kho phim?") },
        text = { Column {
            if (unresolvedCues > 0) Text("Còn $unresolvedCues câu được đánh dấu chưa duyệt. Xác nhận lưu chỉ khi bạn đã kiểm tra và chấp nhận các câu này.", color = MaterialTheme.colorScheme.error)
            Text("Lưu video gốc cùng phụ đề Việt của ${batch.items.size} video theo đúng thứ tự. Không render lại hoặc tải vào Downloads. Nếu đã cấp quyền đồng bộ, video và phụ đề sẽ được đưa lên Drive.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copyLibrarySources, { copyLibrarySources = it }); Text("Sao chép nguồn để lưu độc lập (tốn thêm dung lượng)") } } },
        confirmButton = { TextButton(onClick = { confirmDialog = false; action {
            if (batch.mergeEpisodes) jobs.validatedOutputs(batch.id)
            val sources = jobs.validatedSources(batch.id)
            library.confirmSources(BatchLibraryTarget.movieId(batch), batch.title, sources, copySources = copyLibrarySources,
                appendToExisting = batch.targetMovieId.isNotBlank())
            notice = "Đã đưa vào Kho phim. Trạng thái đồng bộ hiển thị trong Kho phim."
        } }) { Text("XÁC NHẬN ĐƯA VÀO KHO PHIM") } },
        dismissButton = { TextButton(onClick = { confirmDialog = false }) { Text("Xem lại") } })

    if (bulkApprovalDialog && batch != null) AlertDialog(
        onDismissRequest = { bulkApprovalDialog = false },
        title = { Text(if(batch.targetMovieId.isNotBlank()) "Duyệt và thêm tập vào bộ phim?" else "Duyệt toàn bộ và lưu Kho phim?") },
        text = { Column {
            Text("Chấp nhận các bản dịch hiện có của ${batch.items.size} video. Kho phim sẽ lưu từng tập cùng phụ đề Việt đã duyệt. Nếu đã bật đồng bộ Kho phim, bản lưu sẽ được đồng bộ lên Drive.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(copyLibrarySources, { copyLibrarySources = it })
                Text("Sao chép nguồn để lưu độc lập (tốn thêm dung lượng)")
            }
        } },
        confirmButton = {
            TextButton(onClick = {
                bulkApprovalDialog = false
                action {
                    val sources = jobs.validatedSources(batch.id, approveAll = true)
                    library.confirmSources(BatchLibraryTarget.movieId(batch), batch.title, sources, copySources = copyLibrarySources,
                        appendToExisting = batch.targetMovieId.isNotBlank())
                    notice = "Đã duyệt phụ đề trong bản lưu và đưa vào Kho phim."
                }
            }) { Text("Duyệt và lưu") }
        },
        dismissButton = { TextButton(onClick = { bulkApprovalDialog = false }) { Text("Xem lại") } }
    )
}

private fun batchStateLabel(state: String): String = when (state) {
    "READY", "PENDING" -> "Sẵn sàng"
    "RUNNING" -> "Đang xử lý"
    "RETRY_WAIT" -> "Đang tự khôi phục, tiến độ đã được lưu"
    "TRANSLATING" -> "Đang dịch"
    "EXPORTING" -> "Đang xuất MP4"
    "PAUSED" -> "Đã tạm dừng"
    "CANCELLED" -> "Đã hủy hàng đợi"
    "FAILED", "NEEDS_REVIEW" -> "Cần kiểm tra"
    "REVIEW" -> "Chờ kiểm tra kết quả"
    "DONE" -> "Hoàn thành"
    else -> "Đã lưu"
}

@Composable
private fun <T> BatchChoice(title: String, value: String, enabled: Boolean, options: List<T>, label: (T) -> String, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, enabled = enabled, color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
                Icon(Icons.Default.ExpandMore, null)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { expanded = false; onChoose(option) }) }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun BatchVideoCard(item: BatchItem, index: Int, total: Int, project: VideoProject?, editable: Boolean,
    showOrdering: Boolean, onOpen: () -> Unit, openEnabled: Boolean = true,
    onRemove: () -> Unit = {}, onMove: (Int) -> Unit = {}, onEpisode: (String) -> Unit = {},
    episodeOutputs: (@Composable () -> Unit)? = null) {
    var episode by rememberSaveable(item.projectId, item.episode) { mutableStateOf(item.episode) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OceanColors.Card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatchThumbnail(project?.uri.orEmpty(), item.durationMs)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${item.name}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${BatchWorkflowUi.duration(item.durationMs)} · ${batchStateLabel(item.state)}", style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (project != null && !showOrdering) {
                        val stageLabel = if (item.state == "EXPORTING") "Xuất MP4"
                            else if (project.stage == com.gemini.ocr.video.VideoStage.EXTRACTING && project.message.contains("nhận diện giọng nói", true)) "Đang nhận diện giọng nói"
                            else BatchWorkflowUi.stageLabel(item.stage)
                        val stagePercent = if (item.state == "EXPORTING") project.progress.coerceIn(0, 100)
                            else VideoStageProgress.percent(project, item.stage)
                        Text("$stageLabel · ${project.cues.size} câu · $stagePercent%", style = MaterialTheme.typography.bodySmall)
                        if (item.stage == com.gemini.ocr.video.BatchPipelineStage.TRANSLATE) {
                            Text("Đã dịch hợp lệ: ${VideoStageProgress.translatedCueCount(project)}/${project.cues.size} câu",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("batch_valid_translation_count"))
                        }
                        Text("Nguồn: ${videoResolvedSourceLabel(project)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showOrdering) IconButton(onClick = onRemove, enabled = editable) {
                    Icon(Icons.Default.Close, "Bỏ ${item.name} khỏi danh sách")
                }
            }
            if (project != null && !showOrdering) VideoAsrOverlapReviewButton(project)
            if (project != null && !showOrdering) videoUnclearSpeechNotice(project)?.let { StatusNotice(it) }
            BatchRetryErrorNotice(item, project)
            if (showOrdering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tập ${item.episode.takeUnless { it.startsWith("video-") } ?: (index + 1)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onMove(index - 1) }, enabled = editable && index > 0) { Icon(Icons.Default.ArrowUpward, "Chuyển video lên") }
                    IconButton(onClick = { onMove(index + 1) }, enabled = editable && index + 1 < total) { Icon(Icons.Default.ArrowDownward, "Chuyển video xuống") }
                }
                ExpandableSection("Nhóm ghép của video", "Chỉ đổi khi các phần thuộc cùng một tập.") {
                    OutlinedTextField(episode, { episode = it }, label = { Text("Mã tập / nhóm ghép") }, singleLine = true,
                        enabled = editable, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { onEpisode(episode) }, enabled = editable && episode.isNotBlank() && episode != item.episode) { Text("Lưu nhóm tập") }
                    Text("Dung lượng: ${"%.1f".format(item.bytes / 1024.0 / 1024.0)} MiB", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onOpen, enabled = if (showOrdering) editable else openEnabled) {
                Icon(if (showOrdering) Icons.Default.Tune else Icons.Default.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (showOrdering) "Thiết lập video" else "Xem và sửa phụ đề")
            }
            episodeOutputs?.invoke()
        }
    }
}

private val thumbnailSlots = Semaphore(2)

@Composable
private fun BatchThumbnail(uri: String, durationMs: Long) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        if (uri.isNotBlank()) value = withContext(Dispatchers.IO) { thumbnailSlots.withPermit {
            runCatching { VideoDecoder.preview(context, Uri.parse(uri), minOf(1_000, durationMs / 3)) }.getOrNull()
        } }
    }
    Box(Modifier.size(width = 80.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceLabel(mode: VideoSourceMode): String = videoSourceLabel(mode)

private fun modeLabel(mode: VideoTranslationMode): String = when (mode) {
    VideoTranslationMode.OFFLINE -> "Dịch trên máy · không gửi video"
    VideoTranslationMode.GEMINI -> "Gemini · cần mạng"
    VideoTranslationMode.HYBRID -> "Local + Gemini · toàn bộ hội thoại"
}

private fun polishLabel(policy: VideoPolishPolicy): String = when (policy) {
    VideoPolishPolicy.NONE -> "Tắt · dùng bản dịch trên máy"
    VideoPolishPolicy.FLAGGED -> "Chỉ các câu cần kiểm tra"
    VideoPolishPolicy.ALL -> "Rà soát toàn bộ câu chưa duyệt"
}

private fun deliveryLabel(delivery: BatchDelivery): String = when (delivery) {
    BatchDelivery.SOURCE_LIBRARY -> "Chỉ lưu Kho phim · giữ nguyên bản gốc"
    BatchDelivery.LIBRARY_AND_MP4 -> "Kho phim + tạo MP4 chất lượng cao"
    BatchDelivery.MP4_ONLY -> "Chỉ tạo MP4 để xuất ra thiết bị"
}
