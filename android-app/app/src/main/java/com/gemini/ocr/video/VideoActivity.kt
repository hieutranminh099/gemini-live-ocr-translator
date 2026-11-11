@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tune
import com.gemini.ocr.ui.*
import com.gemini.ocr.ui.ocean.*
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.movie.MovieLibrary
import com.gemini.ocr.movie.MovieEpisode
import com.gemini.ocr.movie.MovieSourceInput
import com.gemini.ocr.video.sync.DrivePlaybackException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.Size
import com.gemini.ocr.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Video selection is independent of Live capture and never changes its translation settings. */
class VideoActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        SessionState.videoActivityVisible.value = true
    }

    override fun onStop() {
        SessionState.videoActivityVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OceanTheme {
                Surface(Modifier.fillMaxSize()) { VideoWorkflowScreen(intent.getStringExtra("project").orEmpty(),
                    intent.getStringExtra("ux_step").orEmpty(), onBack = { finish() }) }
            }
        }
    }
}

private fun videoTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
    ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)

/** Only recommend an embedded track when its language is unambiguous. */
internal fun preferredVideoTextTrack(tracks: List<VideoTextTrack>): VideoTextTrack? {
    val chinese = tracks.filter {
        it.language.lowercase(Locale.ROOT).replace('_', '-').substringBefore('-') in setOf("zh", "zho", "chi", "cmn", "yue")
    }
    if (chinese.isNotEmpty()) return chinese.singleOrNull()
    return tracks.singleOrNull()?.takeIf {
        it.language.trim().lowercase(Locale.ROOT) in setOf("", "und", "unknown", "không rõ")
    }
}

private fun VideoStage.title(): String = when (this) {
    VideoStage.READY -> "Sẵn sàng"
    VideoStage.EXTRACTING -> "Đang lấy phụ đề nguồn"
    VideoStage.TRANSLATING -> "Đang dịch"
    VideoStage.REVIEW -> "Chờ xem và sửa"
    VideoStage.EXPORTING -> "Đang tạo MP4"
    VideoStage.COMPLETE -> "Đã tạo MP4"
    VideoStage.PAUSED -> "Đã tạm dừng"
    VideoStage.WAITING_LIVE -> "Đang nhường tài nguyên cho Live"
    VideoStage.FAILED -> "Cần xử lý lỗi"
}

/** Accept clock timestamps or seconds, including the decimal comma used by SRT. */
internal fun parseVideoTime(value: String): Long {
    val parts = value.trim().replace(',', '.').split(':')
    require(parts.size in 1..3) { "Nhập giây hoặc hh:mm:ss.mmm." }
    val numbers = parts.map { it.toDoubleOrNull() ?: throw IllegalArgumentException("Mốc thời gian không hợp lệ.") }
    require(numbers.all { it.isFinite() && it >= 0 }) { "Mốc thời gian không hợp lệ." }
    if (numbers.size > 1) require(numbers.last() < 60 && (numbers.size < 3 || numbers[1] < 60)) {
        "Giây và phút trong timestamp phải nhỏ hơn 60."
    }
    val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
    require(seconds <= 24 * 3600) { "Mốc thời gian vượt 24 giờ." }
    return (seconds * 1000).toLong()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(initialProjectId: String = "", onBack: () -> Unit) {
    VideoWorkflowScreen(initialProjectId, "", onBack)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VideoWorkflowScreen(initialProjectId: String, initialStep: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs = remember { VideoJobs.get(context) }
    val projects by jobs.projects.collectAsState()
    val library = remember { MovieLibrary.get(context) }
    val movies by library.movies.collectAsState()
    val busyId by jobs.busy.collectAsState()
    val batchReservation by jobs.batchBusy.collectAsState()
    val offlineState by jobs.offlineState.collectAsState()
    val liveRunning by SessionState.running.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf(initialProjectId) }
    var pendingSubtitleId by rememberSaveable { mutableStateOf("") }
    var replacementSubtitleId by rememberSaveable { mutableStateOf("") }
    var replacementSubtitleUri by rememberSaveable { mutableStateOf("") }
    var replacementSubtitleName by rememberSaveable { mutableStateOf("") }
    var replacementSubtitleVersion by rememberSaveable { mutableLongStateOf(0L) }
    var pendingExportId by rememberSaveable { mutableStateOf("") }
    var pendingSourceExportId by rememberSaveable { mutableStateOf("") }
    var pendingMp4SaveId by rememberSaveable { mutableStateOf("") }
    var pendingMp4SaveMethod by rememberSaveable { mutableStateOf("") }
    var localBusy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf("") }
    var tracks by remember(selectedId) { mutableStateOf(emptyList<VideoTextTrack>()) }
    var seekRequest by remember(selectedId) { mutableStateOf<Pair<Long, Long>?>(null) }
    var fullscreen by rememberSaveable(selectedId) { mutableStateOf(false) }
    var step by rememberSaveable(selectedId) { mutableIntStateOf(0) }
    var lastStage by rememberSaveable(selectedId) { mutableStateOf("") }
    var quickMode by rememberSaveable { mutableStateOf(VideoTranslationMode.OFFLINE.name) }
    var quickSource by rememberSaveable { mutableStateOf("AUTO") }
    var noticeError by rememberSaveable { mutableStateOf(false) }
    var configurationChange by remember { mutableStateOf<VideoProject?>(null) }
    var confirmMovie by remember { mutableStateOf(false) }
    var copyMovieSource by rememberSaveable { mutableStateOf(false) }
    var draftStyle by remember(selectedId, projects.firstOrNull { it.id == selectedId }?.style) { mutableStateOf<VideoSubtitleStyle?>(null) }
    val settings = remember { AppSettings(context) }
    var keyReady by remember { mutableStateOf(settings.hasGeminiVideoKey()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) keyReady = settings.hasGeminiVideoKey()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // Move the existing composition, including its player and surface, between the two hosts.
    val preview = remember { movableContentOf<VideoPreviewInput> { input ->
        VideoPreview(input.project, input.seekRequest, input.allowRoi, input.allowFrame,
            input.onFrame, input.onRoi, input.fullscreen, input.onFullscreen)
    } }
    var lastSavedUri by rememberSaveable(selectedId) { mutableStateOf("") }
    var lastSavedName by rememberSaveable(selectedId) { mutableStateOf("") }
    var lastSavedMime by rememberSaveable(selectedId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val project = projects.firstOrNull { it.id == selectedId }
    val editable = project != null && busyId == null && batchReservation == null && !localBusy

    fun action(block: suspend () -> Unit) {
        if (localBusy) return
        localBusy = true
        notice = ""
        noticeError = false
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { noticeError = true; notice = e.message ?: "Không thể hoàn tất thao tác." }
            finally { localBusy = false }
        }
    }
    fun save(next: VideoProject) = action { withContext(Dispatchers.IO) { jobs.save(next) }; Unit }
    fun configure(next: VideoProject) {
        val old = project
        val resets = old != null && (old.cues.isNotEmpty() || old.scanMs > 0L) && (old.sourceMode != next.sourceMode || old.trackId != next.trackId ||
            old.translationMode != next.translationMode || old.profile != next.profile || old.glossary != next.glossary)
        if (resets) configurationChange = next else save(next)
    }
    fun openSettings() {
        context.startActivity(Intent(context, com.gemini.ocr.MainActivity::class.java).putExtra("ux_tab", 3)
            .putExtra("ux_return_to_caller", true))
    }
    fun startProcessing(p: VideoProject) {
        notice = ""; noticeError = false; step = VideoWorkflowStep.PROCESS.ordinal
        runCatching {
            if (p.resumeStage == VideoStage.EXPORTING && p.stage in setOf(VideoStage.PAUSED, VideoStage.WAITING_LIVE, VideoStage.FAILED)) jobs.exportMp4(p.id)
            else jobs.start(p.id)
        }.onFailure { noticeError = true; notice = it.message ?: "Không thể bắt đầu dịch." }
    }
    fun keepReadAccess(uri: Uri) {
        // Some document providers deny persistence; VideoStore then streams a private copy.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun name(uri: Uri, fallback: String): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: fallback

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { keepReadAccess(it) }
            action {
                if (uris.size > 1) {
                    val batches = BatchJobs.get(context)
                    val created = batches.create("Phim mới", BatchKind.SERIES)
                    val mode = VideoTranslationMode.valueOf(quickMode)
                    if (quickSource == "AUTO") batches.configureTranslation(created.id, mode)
                    else batches.configure(created.id, mode, VideoSourceMode.valueOf(quickSource))
                    batches.add(created.id, uris)
                    context.startActivity(Intent(context, com.gemini.ocr.batch.BatchActivity::class.java).putExtra("batch", created.id))
                } else {
                    val uri = uris.single()
                    val created = jobs.create(uri, name(uri, "Video"))
                    val suggestedTrack = if (quickSource == "AUTO")
                        preferredVideoTextTrack(runCatching { jobs.tracks(created) }.getOrDefault(emptyList())) else null
                    val source = if (quickSource == "AUTO") {
                        if (suggestedTrack != null) VideoSourceMode.EMBEDDED else VideoSourceMode.AUTO
                    } else VideoSourceMode.valueOf(quickSource)
                    jobs.save(created.copy(sourceMode = source, trackId = suggestedTrack?.id ?: created.trackId,
                        translationMode = VideoTranslationMode.valueOf(quickMode)))
                    selectedId = created.id; step = VideoWorkflowStep.SETUP.ordinal
                    notice = "Đã thêm video. Kiểm tra thiết lập rồi bắt đầu dịch."
                }
            }
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingSubtitleId
        pendingSubtitleId = ""
        if (uri != null && id.isNotEmpty()) {
            keepReadAccess(uri)
            action {
                val current = withContext(Dispatchers.IO) { VideoStore(context).load(id) }
                val subtitleName = name(uri, "subtitles.srt")
                if (current.cues.isNotEmpty() || current.scanMs > 0L) {
                    replacementSubtitleId = id
                    replacementSubtitleUri = uri.toString()
                    replacementSubtitleName = subtitleName
                    replacementSubtitleVersion = current.updatedAt
                } else withContext(Dispatchers.IO) {
                    jobs.selectSubtitle(id, uri, subtitleName)
                }
            }
        }
    }
    val exportSourceSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val id = pendingSourceExportId; pendingSourceExportId = ""
        if (uri != null && id.isNotEmpty()) action {
            withContext(Dispatchers.IO) { jobs.exportSourceText(id, uri) }
            lastSavedUri=uri.toString();lastSavedName=name(uri,"phu-de-zh.srt");lastSavedMime="application/x-subrip"
            notice = "Đã lưu SRT tiếng Trung: $lastSavedName, giữ nguyên mốc thời gian."
        }
    }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val id = pendingExportId; pendingExportId = ""
        if (uri != null && id.isNotEmpty()) action {
            withContext(Dispatchers.IO) { jobs.exportText(id, uri, false) }
            lastSavedUri=uri.toString();lastSavedName=name(uri,"phu-de-vi.srt");lastSavedMime="application/x-subrip"
            notice = "Đã lưu SRT UTF-8: $lastSavedName tại nơi bạn chọn."
        }
    }
    val exportAss = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-ssa")) { uri ->
        val id = pendingExportId; pendingExportId = ""
        if (uri != null && id.isNotEmpty()) action {
            withContext(Dispatchers.IO) { jobs.exportText(id, uri, true) }
            lastSavedUri=uri.toString();lastSavedName=name(uri,"phu-de-vi.ass");lastSavedMime="text/x-ssa"
            notice = "Đã lưu ASS: $lastSavedName tại nơi bạn chọn."
        }
    }
    val exportMp4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val id = pendingExportId; pendingExportId = ""
        if (uri != null && id.isNotEmpty()) action {
            withContext(Dispatchers.IO) { jobs.copyMp4(id, uri) }
            lastSavedUri=uri.toString();lastSavedName=name(uri,"video-vi.mp4");lastSavedMime="video/mp4"
            notice = "Đã lưu MP4 có phụ đề Việt: $lastSavedName tại nơi bạn chọn. Video nguồn được giữ nguyên."
        }
    }

    fun chooseTextDestination(p:VideoProject,ass:Boolean) {
        pendingExportId=p.id
        val filename="${p.title.substringBeforeLast('.')}-vi.${if(ass)"ass" else "srt"}"
        if(ass)exportAss.launch(filename) else exportSrt.launch(filename)
    }
    fun saveText(p:VideoProject,ass:Boolean) {
        if(!VideoDownloads.supportsDirectSave) {
            notice="Android sẽ hỏi nơi lưu. Chọn Download hoặc thư mục bạn muốn."
            chooseTextDestination(p,ass)
        } else action {
            val fresh=withContext(Dispatchers.IO){VideoStore(context).load(p.id)}
            val saved=VideoDownloads.saveSubtitle(context,fresh,ass)
            lastSavedUri=saved.uri.toString();lastSavedName=saved.name
            lastSavedMime=if(ass)"text/x-ssa" else "application/x-subrip"
            notice="Đã tải ${saved.name} vào ${saved.relativePath}."
        }
    }
    fun saveMp4(p:VideoProject,chooseFolder:Boolean=false) {
        if(chooseFolder || !VideoDownloads.supportsDirectSave) {
            pendingExportId=p.id
            notice="Chọn Download hoặc thư mục bạn muốn để lưu MP4 đã tạo."
            exportMp4.launch("${p.title.substringBeforeLast('.')}-vi.mp4")
        } else action {
            val fresh=withContext(Dispatchers.IO){VideoStore(context).load(p.id)}
            val saved=VideoDownloads.saveRenderedMp4(context,fresh)
            lastSavedUri=saved.uri.toString();lastSavedName=saved.name;lastSavedMime="video/mp4"
            notice="Đã tải ${saved.name} vào ${saved.relativePath}. Video nguồn được giữ nguyên."
        }
    }
    fun renderAndSave(p:VideoProject) {
        notice="Đang tạo MP4. Khi xong, ${if(VideoDownloads.supportsDirectSave)"app sẽ lưu vào Download/Gemini OCR" else "Android sẽ hỏi thư mục lưu"}."
        runCatching {
            jobs.exportMp4(p.id)
            pendingMp4SaveId=p.id
            pendingMp4SaveMethod=if(VideoDownloads.supportsDirectSave)"downloads" else "picker"
        }.onFailure{notice=it.message?:"Không thể tạo MP4."}
    }
    LaunchedEffect(projects,busyId,localBusy,pendingMp4SaveId) {
        if(pendingMp4SaveId.isNotEmpty() && busyId==null && !localBusy) {
            val rendered=projects.firstOrNull{it.id==pendingMp4SaveId}
            if(rendered?.stage in setOf(VideoStage.FAILED,VideoStage.PAUSED)) {
                pendingMp4SaveId="";pendingMp4SaveMethod=""
                notice="MP4 chưa được tạo xong. Tiến độ vẫn được giữ; bấm tạo MP4 khi bạn sẵn sàng."
            } else if(rendered?.stage==VideoStage.COMPLETE && rendered.exportedMp4.isNotBlank() && File(rendered.exportedMp4).isFile) {
                val picker=pendingMp4SaveMethod=="picker"
                pendingMp4SaveId="";pendingMp4SaveMethod=""
                saveMp4(rendered,picker)
            }
        }
    }

    LaunchedEffect(Unit) { jobs.refresh() }
    LaunchedEffect(project?.id, project?.stage) {
        val current = project
        if (current == null) { step = 0; lastStage = "" }
        else if (lastStage.isBlank()) {
            step = videoWorkflowEntry(current, initialStep).ordinal
            lastStage = current.stage.name
        } else if (lastStage != current.stage.name) {
            val wasWorking = lastStage in setOf("EXTRACTING", "TRANSLATING", "EXPORTING", "WAITING_LIVE")
            if (wasWorking && current.stage == VideoStage.REVIEW) step = 3
            else if (current.stage == VideoStage.COMPLETE) step = 4
            else if (current.stage == VideoStage.FAILED) step = 2
            lastStage = current.stage.name
        }
    }
    fun back() { if (step > 0) step = 0 else onBack() }
    BackHandler(enabled = !fullscreen && step > 0) { back() }
    val previewInput = project?.let { p -> VideoPreviewInput(p.copy(style = draftStyle ?: p.style), seekRequest,
        editable && videoSourceUsesRoi(p.sourceMode) && p.cues.isEmpty() && p.scanMs == 0L, editable,
        { frame -> save(p.copy(style=(draftStyle ?: p.style).displayStyle().copy(frame=frame,legacyDefault=false),exportedMp4=""));draftStyle=null },
        { save(p.copy(roi = it)) },
        fullscreen, { fullscreen = it }) }
    val offlineReady = offlineState.contains("sẵn sàng")
    val issue = project?.let { videoStartIssue(it, keyReady, offlineReady) }
    val selecting = step == 0
    val reviewing = step == 3 && project != null && project.cues.isNotEmpty()
    if (fullscreen && previewInput != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { preview(previewInput) }
    } else Scaffold(topBar = {
        Column {
            AppTopBar("Dịch video", "Trung → Việt", onBack = { back() }, actions = {
                IconButton(onClick = { if (project != null) step = 1 else openSettings() }) {
                    Icon(Icons.Default.Tune, "Thiết lập dịch")
                }
            })
            OceanWorkflowSteps(step, { step = it }, enabled = { project != null && (it < 3 || project.cues.isNotEmpty()) },
                completed = buildSet {
                    if (project != null) {
                        add(0)
                        if (project.stage !in setOf(VideoStage.READY, VideoStage.FAILED)) add(1)
                        if (VideoExportReadiness.from(project).complete) add(2)
                        if (project.cues.isNotEmpty() && project.cues.all { it.approved } && VideoExportReadiness.from(project).complete) add(3)
                        if (project.stage == VideoStage.COMPLETE && project.exportedMp4.isNotBlank() && File(project.exportedMp4).isFile) add(4)
                    }
                })
        }
    }, bottomBar = {
        if (step < 4) Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (project != null && step in 1..2 && issue != null && busyId == null) Text(issue,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        project == null -> Text("Thêm video để bắt đầu", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        busyId == project.id -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${project.stage.title()} · ${project.progress}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { jobs.pause() }, modifier = Modifier.testTag("video_pause")) { Text("Tạm dừng") }
                        }
                        step == 0 -> Button(onClick = { step = 1 }, enabled = !localBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tiếp tục thiết lập") }
                        step == 1 || step == 2 -> {
                            if (project.stage == VideoStage.REVIEW && VideoExportReadiness.from(project).complete) {
                                Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Kiểm tra bản dịch") }
                            } else Button(onClick = { startProcessing(project) }, enabled = editable && issue == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("video_start")) {
                                Text(if (project.stage in setOf(VideoStage.PAUSED, VideoStage.FAILED, VideoStage.WAITING_LIVE)) "Tiếp tục dịch" else "Bắt đầu dịch")
                            }
                        }
                        step == 3 -> Button(onClick = { step = 4 }, enabled = !localBusy && busyId == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("video_jump_exports")) { Text("Xuất kết quả") }
                    }
                }
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val wideReview = maxWidth >= 840.dp && reviewing
            Row(Modifier.widthIn(max = if (wideReview) 1320.dp else 960.dp).fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (wideReview && previewInput != null) Column(Modifier.weight(1.05f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val wideProject = checkNotNull(project)
                    preview(previewInput)
                    ExpandableSection("Kiểu phụ đề", "Xem trước rồi lưu cấu hình") {
                        VideoStyleEditor(wideProject, editable, initialDraft = draftStyle, onPreview = { draftStyle = it }) { save(wideProject.copy(style = it, exportedMp4 = "")) }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("video_screen"), state = listState,
                    verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)) {
                    if (notice.isNotBlank()) item { StatusNotice(notice, error = noticeError) }
                    if (project != null && draftStyle != null && draftStyle != project.style) item {
                        StatusNotice("Đang xem trước kiểu phụ đề chưa lưu. Lưu cấu hình trước khi xuất để áp dụng thay đổi.")
                        Button(onClick = { draftStyle?.let { save(project.copy(style = it, exportedMp4 = "")) } },
                            enabled = editable, modifier = Modifier.testTag("video_save_style_draft")) { Text("Lưu cấu hình phụ đề") }
                    }
                    if (localBusy) item(key="video-local-busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (liveRunning) item { StatusNotice("Dịch trực tiếp đang chạy. Video sẽ chờ để ưu tiên màn hình của bạn.") }
                    if (busyId != null && busyId != selectedId) item {
                        StatusNotice("Một video khác đang xử lý.")
                        TextButton(onClick = { selectedId = busyId.orEmpty(); step = 2 }) { Text("Mở video đang xử lý") }
                    }
                    if (selecting || project == null) {
                        item {
                            OceanVideoImport(
                                title = project?.title,
                                detail = if (project != null) "${project.durationMs / 1000} giây · ${project.cues.size} câu"
                                    else "Chọn video trên máy để lấy phụ đề, dịch và xem lại. MP4, MKV và các định dạng Android hỗ trợ.",
                                enabled = !localBusy && busyId == null && batchReservation == null,
                                canRemove = busyId == null,
                                onPick = { videoPicker.launch(arrayOf("video/*")) },
                                onRemove = { selectedId = "" },
                                thumbnail = { if (project != null) VideoThumbnail(project, Modifier.size(112.dp, 68.dp)) },
                            )
                        }
                        if (project == null) item {
                            OceanCard {
                            Text("Thiết lập nhanh", style = MaterialTheme.typography.titleMedium)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val modeChoice: @Composable () -> Unit = {
                                    VideoChoiceRow("Chế độ dịch", quickMode, listOf(VideoTranslationMode.OFFLINE.name to "Trên thiết bị",
                                        VideoTranslationMode.GEMINI.name to "Gemini · cần mạng", VideoTranslationMode.HYBRID.name to "Local + Gemini · toàn bộ hội thoại"), !localBusy,
                                        { quickMode = it }, "video_quick_mode")
                                }
                                val sourceChoice: @Composable () -> Unit = {
                                    VideoChoiceRow("Nguồn phụ đề", quickSource, listOf("AUTO" to "Tự động đề xuất", "EMBEDDED" to "Phụ đề có sẵn",
                                        "SIDECAR" to "Phụ đề có sẵn · tệp riêng", "OCR" to "OCR hình ảnh", "ASR" to "Nhận diện giọng nói"), !localBusy,
                                        { quickSource = it }, "video_quick_source")
                                }
                                if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Column(Modifier.weight(1f)) { modeChoice() }
                                    Column(Modifier.weight(1f)) { sourceChoice() }
                                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { modeChoice(); sourceChoice() }
                            }
                            ExpandableSection("Tùy chọn nâng cao", "Kết nối dịch và gói dịch trên thiết bị") {
                                Text("Mỗi video được kiểm tra riêng: ưu tiên phụ đề có sẵn, sau đó quét chữ trong hình. Nếu quét đủ video mà nguồn vẫn thiếu, có thể dùng giọng nói khi đã chuẩn bị gói tiếng Trung. Không kết luận từ vài ảnh trống.", style = MaterialTheme.typography.bodySmall)
                                AsrModelManagerButton(enabled = !localBusy && busyId == null && batchReservation == null)
                                TextButton(onClick = { openSettings() }) { Text("Mở Tùy chỉnh") }
                            }
                        }
                        }
                        item {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (projects.isNotEmpty()) ProjectPicker(projects, selectedId, !localBusy) { selectedId = it; lastStage = "" }
                                TextButton(onClick = { context.startActivity(Intent(context, com.gemini.ocr.batch.BatchActivity::class.java)) }) { Text("Dự án hàng loạt") }
                            }
                        }
                        item { ExpandableSection("Google Drive", "Khôi phục dự án đã lưu") {
                            VideoSyncPanel(enabled = busyId == null && !localBusy, onRestored = { jobs.refresh(); selectedId = it.id; lastStage = "" })
                        } }
                    } else {
                        item(key="video-project-title") {
                            Text(project.title, style = MaterialTheme.typography.titleLarge)
                            Text("${project.durationMs / 1000} giây · ${project.cues.size} câu", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (project.cues.isNotEmpty()) Text("Nguồn đã dùng: ${videoResolvedSourceLabel(project)}", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("video_resolved_source"))
                            VideoAsrOverlapReviewButton(project)
                            videoUnclearSpeechNotice(project)?.let { StatusNotice(it) }
                            OutlinedButton(onClick = {
                                pendingSourceExportId = project.id
                                exportSourceSrt.launch("${project.title.substringBeforeLast('.')}-zh.srt")
                            }, enabled = editable && videoCanExportSource(project), modifier = Modifier.testTag("video_export_zh")) {
                                Text("Lưu SRT tiếng Trung")
                            }
                        }
                        when (step) {
                            1 -> item(key="video-setup") {
                                VideoSetupPanel(project, editable, keyReady, offlineState, tracks, ::configure,
                                    onPrepare = { action { jobs.prepareOffline() } },
                                    onSubtitle = { pendingSubtitleId = project.id; subtitlePicker.launch(arrayOf("*/*")) },
                                    onTracks = { action { tracks = jobs.tracks(project); if (tracks.isEmpty()) notice = "Không có phụ đề có sẵn. Thử đọc chữ trong hình hoặc chọn tệp phụ đề." } },
                                    onRescan = { action { val revision = jobs.createVisualRevision(project.id); selectedId = revision.id; lastStage = "";
                                        notice = "Đã tạo bản quét mới. Dự án cũ vẫn được giữ; Gemini chỉ chạy khi bạn bắt đầu." } },
                                    onSettings = ::openSettings, onCancelEconomy = { action { jobs.cancelEconomy(project.id); notice="Đã hủy tác vụ Gemini nền. Timeline đã lưu được giữ." } },
                                    preview = { if (previewInput != null) preview(previewInput) })
                            }
                            2 -> item {
                                OceanCard(elevated = true) {
                                    OceanStatusIndicator(project.stage.title(),
                                        tone = if (project.stage == VideoStage.FAILED) OceanStatusTone.Error
                                            else if (project.stage in setOf(VideoStage.PAUSED, VideoStage.WAITING_LIVE)) OceanStatusTone.Warning
                                            else OceanStatusTone.Neutral)
                                    Text("Tiến độ công đoạn", style = MaterialTheme.typography.titleLarge)
                                    if(project.translationMode==VideoTranslationMode.HYBRID){
                                        val sourcePercent=if(project.durationMs>0)(project.scanMs.coerceIn(0,project.durationMs)*100/project.durationMs).toInt()else 0
                                        Text("Phụ đề nguồn: $sourcePercent% thời lượng · Sau khi quét xong, tự chọn các phần lớn nhất vừa giới hạn Gemini.",
                                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("video_hybrid_source_progress"))
                                    }
                                    if (project.stage != VideoStage.READY) {
                                        LinearProgressIndicator(progress = { project.progress.coerceIn(0, 100) / 100f },
                                            modifier = Modifier.fillMaxWidth().height(6.dp), color = OceanColors.Mint,
                                            trackColor = OceanColors.Base)
                                        Text("${project.progress}% · ${project.cues.count { it.translation.isNotBlank() }}/${project.cues.size} câu đã có bản dịch")
                                    } else Text("Chưa bắt đầu xử lý", color = OceanColors.Muted)
                                    if (project.message.isNotBlank()) StatusNotice(project.message, error = project.stage == VideoStage.FAILED,
                                        onRetry = if (project.stage == VideoStage.FAILED && editable && issue == null) ({ startProcessing(project) }) else null)
                                    Text("Có thể đóng màn hình. Tiến độ sẽ được lưu để tiếp tục.", style = MaterialTheme.typography.bodySmall)
                                }
                                ExpandableSection("Chi tiết xử lý") {
                                    Text("Thời gian ${videoTime(project.elapsedMs)} · RAM ${project.maxPssKiB / 1024} MiB · ${project.cloudCalls} lần gửi cloud", style = MaterialTheme.typography.bodySmall)
                                    Text("Nhận phụ đề ${project.extractionElapsedMs / 1000}s · Dịch ${project.translationElapsedMs / 1000}s · Xuất ${project.exportElapsedMs / 1000}s",
                                        modifier = Modifier.testTag("video_phase_times"), style = MaterialTheme.typography.bodySmall)
                                    Text("${project.ocrMetrics.optString("engine", "Trên thiết bị")} · ${project.ocrMetrics.optLong("completed_frames")} ảnh đã xử lý",
                                        modifier = Modifier.testTag("video_ocr_engine"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            3 -> {
                                if (!wideReview && previewInput != null) item {
                                    preview(previewInput)
                                    ExpandableSection("Kiểu phụ đề", "Xem trước rồi lưu cấu hình") {
                                        VideoStyleEditor(project, editable, initialDraft = draftStyle, onPreview = { draftStyle = it }) { save(project.copy(style = it, exportedMp4 = "")) }
                                    }
                                }
                                item {
                                    Text("Kiểm tra bản dịch", style = MaterialTheme.typography.titleMedium)
                                    Text("${project.cues.count { it.approved }}/${project.cues.size} câu đã duyệt. Chạm Sửa để chỉnh nội dung hoặc thời gian.", style = MaterialTheme.typography.bodySmall)
                                }
                                items(project.cues, key = { it.id }) { cue ->
                                    OceanCard {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${videoTime(cue.startMs)} → ${videoTime(cue.endMs)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                            Text(if (cue.approved) "Đã duyệt" else "Cần kiểm tra", style = MaterialTheme.typography.labelSmall,
                                                color = if (cue.approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(cue.source)
                                        Text(cue.translation.ifBlank { "Chưa có bản dịch" }, color = MaterialTheme.colorScheme.primary)
                                        if (cue.error.isNotBlank()) StatusNotice(cue.error, error = true)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            TextButton(onClick = { seekRequest = cue.startMs to System.nanoTime(); if (!wideReview) scope.launch { listState.animateScrollToItem(0) } }) { Text("Xem đoạn") }
                                            TextButton(onClick = { editId = cue.id }, enabled = editable) { Text("Sửa / duyệt") }
                                        }
                                    }
                                }
                            }
                            4 -> item {
                                VideoExportCard(project, editable,
                                    hasRenderedMp4 = project.exportedMp4.isNotBlank() && File(project.exportedMp4).isFile,
                                    renderBusy = busyId == project.id && project.stage == VideoStage.EXPORTING,
                                    directDownloads = VideoDownloads.supportsDirectSave,
                                    onSrt = { saveText(project, false) }, onAss = { saveText(project, true) },
                                    onRender = { renderAndSave(project); step = 2 }, onSaveMp4 = { saveMp4(project) },
                                    onChooseFolder = { saveMp4(project, true) }, onContinue = { startProcessing(project) })
                                if (lastSavedUri.isNotBlank()) {
                                    Text("Đã lưu $lastSavedName", color = MaterialTheme.colorScheme.primary)
                                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(Uri.parse(lastSavedUri), lastSavedMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        .onFailure { notice = "Mở ứng dụng Tệp hoặc Download để xem $lastSavedName." } }, modifier = Modifier.testTag("video_open_saved")) { Text("Mở tệp vừa lưu") }
                                }
                                OceanCard {
                                Text("Lưu vào Kho phim", style = MaterialTheme.typography.titleMedium)
                                val batchOwned = remember(project.id) { File(VideoStore(context).folder(project.id), "batch-owner").isFile }
                                if (movies.any { it.id == project.id }) {
                                    Text("Đã lưu bản phim vào Kho phim.", color = MaterialTheme.colorScheme.primary)
                                    TextButton(onClick = { context.startActivity(Intent(context, com.gemini.ocr.MainActivity::class.java)
                                        .putExtra("ux_tab", 2).putExtra("ux_return_to_caller", true)) }) { Text("Mở Kho phim") }
                                } else if (!batchOwned) {
                                    Button(onClick = { confirmMovie = true }, enabled = editable && VideoExportReadiness.from(project).complete,
                                        modifier = Modifier.testTag("video_confirm_movie")) { Text("Lưu Kho phim · giữ nguyên bản gốc") }
                                    Text("Phát video nguồn cùng phụ đề Việt. Không cần tạo MP4 hoặc lưu vào Downloads.", style = MaterialTheme.typography.bodySmall)
                                } else Text("Trở về dự án hàng loạt để xác nhận đúng danh sách tập vào Kho phim.", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { step = 3 }) { Text("Quay lại kiểm tra bản dịch") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (configurationChange != null) AlertDialog(onDismissRequest = { configurationChange = null },
        title = { Text("Áp dụng thiết lập mới?") },
        text = { Text(if (project != null && configurationChange?.let { it.sourceMode != project.sourceMode || it.trackId != project.trackId } == true)
            "Đổi nguồn hoặc bản phụ đề sẽ xóa toàn bộ dòng phụ đề, bản dịch và tiến độ của dự án này, kể cả câu đã duyệt, rồi lấy lại nguồn. Giữ hiện tại nếu bạn cần giữ nội dung đã sửa."
            else "Đổi chế độ, ngữ cảnh hoặc từ điển cần dịch lại những câu chưa được bảo vệ. Các câu đã duyệt hoặc được bảo vệ vẫn được giữ.") },
        confirmButton = { TextButton(onClick = { val next = configurationChange; configurationChange = null; if (next != null) save(next) }) { Text("Áp dụng thay đổi") } },
        dismissButton = { TextButton(onClick = { configurationChange = null }) { Text("Giữ hiện tại") } })
    if (replacementSubtitleId.isNotBlank()) AlertDialog(onDismissRequest = { replacementSubtitleId = "" },
        title = { Text("Thay tệp phụ đề nguồn?") },
        text = { Text("Thay bằng $replacementSubtitleName sẽ xóa toàn bộ dòng phụ đề, bản dịch và tiến độ của dự án này, kể cả câu đã duyệt. Chọn Giữ hiện tại để bảo toàn nội dung đã sửa.") },
        confirmButton = { TextButton(onClick = {
            val id = replacementSubtitleId
            val uri = Uri.parse(replacementSubtitleUri)
            val subtitleName = replacementSubtitleName
            val expectedVersion = replacementSubtitleVersion
            replacementSubtitleId = ""
            action { withContext(Dispatchers.IO) {
                check(VideoStore(context).load(id).updatedAt == expectedVersion) { "Dự án đã thay đổi. Hãy chọn lại tệp phụ đề để kiểm tra trước khi thay nguồn; nội dung hiện tại vẫn được giữ." }
                jobs.selectSubtitle(id, uri, subtitleName)
            } }
        }, enabled = !localBusy && busyId == null && batchReservation == null) { Text("Thay tệp và lấy lại phụ đề") } },
        dismissButton = { TextButton(onClick = { replacementSubtitleId = "" }) { Text("Giữ hiện tại") } })
    if (confirmMovie && project != null) AlertDialog(onDismissRequest = { confirmMovie = false },
        title = { Text("Lưu video gốc vào Kho phim?") }, text = { Column {
            Text("Xác nhận đã kiểm tra bản dịch. Kho phim phát video gốc cùng phụ đề rời; không render lại. Nếu đã bật đồng bộ, nguồn và phụ đề sẽ được tải lên Drive.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copyMovieSource, { copyMovieSource = it }); Text("Sao chép nguồn để lưu độc lập") }
        } },
        confirmButton = { TextButton(onClick = { confirmMovie = false; action {
            val fresh = withContext(Dispatchers.IO) { VideoStore(context).load(project.id) }
            check(!File(VideoStore(context).folder(fresh.id), "batch-owner").exists()) { "Xác nhận video này từ dự án hàng loạt để giữ đúng nhóm tập." }
            check(VideoExportReadiness.from(fresh).complete) { "Cần dịch xong và kiểm tra trước khi xác nhận." }
            MovieLibrary.get(context).confirmSources(fresh.id, fresh.title, listOf(MovieSourceInput("1", fresh.title, fresh)), copyMovieSource)
            notice = "Đã lưu video vào Kho phim."
        } }) { Text("Xác nhận lưu") } }, dismissButton = { TextButton(onClick = { confirmMovie = false }) { Text("Kiểm tra lại") } })

    val editing = project?.cues?.firstOrNull { it.id == editId }
    if (project != null && editing != null) CueEditor(editing, project.cues, project.durationMs,
        onDismiss = { editId = "" }, onSave = { changed ->
            action {
                withContext(Dispatchers.IO) { jobs.updateCue(project.id, changed) }
                editId = ""
            }
        }, onRetry = {
            action {
                withContext(Dispatchers.IO) { jobs.retryCue(project.id, editing.id) }
                editId = ""
            }
        })
}

/** Export readiness follows actual cue contents, not the REVIEW label (which may hold drafts). */
internal data class VideoExportReadiness(val total:Int,val translated:Int,val drafts:Int,val pendingRefinements:Int,val sourcePending:Boolean=false) {
    val complete get()=!sourcePending && total>0 && translated==total
    companion object {
        fun from(project:VideoProject)=VideoExportReadiness(project.cues.size,
            project.cues.count{it.translation.isNotBlank()},project.cues.count{!it.approved},
            project.cues.count{it.refinementState=="pending"},project.sourceExtractionPending)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoExportCard(project:VideoProject,enabled:Boolean,hasRenderedMp4:Boolean,renderBusy:Boolean,
    directDownloads:Boolean,onSrt:()->Unit,onAss:()->Unit,onRender:()->Unit,onSaveMp4:()->Unit,
    onChooseFolder:()->Unit,onContinue:()->Unit) {
    val readiness=VideoExportReadiness.from(project)
    val canExport=enabled && readiness.complete
    OceanCard(Modifier.padding(vertical=8.dp).testTag("video_export_card")) {
            Text("Xuất kết quả",style=MaterialTheme.typography.titleMedium)
            if(readiness.sourcePending)Text("Chưa nhận hết phụ đề nguồn của video. Bấm tiếp tục để lấy phần còn lại trước khi tải.",color=MaterialTheme.colorScheme.error)
            Text(if(readiness.complete)"Đã có bản dịch ${readiness.translated}/${readiness.total} câu."
                else "Đã có bản dịch ${readiness.translated}/${readiness.total} câu; còn ${readiness.total-readiness.translated} câu trống.")
            if(readiness.drafts>0)Text("${readiness.drafts} câu cần kiểm tra trước khi chia sẻ. Xuất tệp không tự đánh dấu câu đã duyệt.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
            if(readiness.pendingRefinements>0) {
                Text("${readiness.pendingRefinements} bản nháp local chưa được Gemini hoàn thiện. Bạn có thể xuất bản nháp hoặc tiếp tục hoàn thiện khi có mạng.",
                    style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=onContinue,enabled=enabled,modifier=Modifier.testTag("video_continue_refinement")) {
                    Text("Tiếp tục hoàn thiện bằng Gemini")
                }
            } else if(!readiness.complete) {
                Text("Dịch tiếp hoặc sửa những câu còn trống để bật nút tải. Dữ liệu đã có vẫn được giữ.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=onContinue,enabled=enabled,modifier=Modifier.testTag("video_continue_missing")) {Text("Dịch các câu còn thiếu")}
            }
            HorizontalDivider(color = OceanColors.Outline.copy(alpha = .35f))
            Text("Tệp phụ đề", style = MaterialTheme.typography.titleSmall)
            Text("SRT lưu câu chữ; ASS giữ cả kiểu phụ đề. MP4 là video có sẵn chữ Việt trong hình.",style=MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=onSrt,enabled=canExport,modifier=Modifier.widthIn(min=136.dp,max=260.dp).testTag("video_download_srt")){Text("Tải SRT về máy")}
                OutlinedButton(onClick=onAss,enabled=canExport,modifier=Modifier.widthIn(min=136.dp,max=260.dp).testTag("video_download_ass")){Text("Tải ASS")}
            }
            HorizontalDivider(color = OceanColors.Outline.copy(alpha = .35f))
            Text("Video có phụ đề", style = MaterialTheme.typography.titleSmall)
            if(hasRenderedMp4) {
                OutlinedButton(onClick=onSaveMp4,enabled=canExport,modifier=Modifier.widthIn(min=220.dp,max=360.dp).testTag("video_save_mp4")) {
                    Text(if(directDownloads)"Tải MP4 vào Download" else "Lưu MP4 ra máy")
                }
                TextButton(onClick=onChooseFolder,enabled=canExport,modifier=Modifier.testTag("video_save_mp4_choose_folder")){Text("Chọn thư mục khác để lưu MP4")}
                TextButton(onClick=onRender,enabled=canExport,modifier=Modifier.testTag("video_render_mp4")){Text("Tạo lại MP4 và lưu")}
            } else {
                OutlinedButton(onClick=onRender,enabled=canExport,modifier=Modifier.widthIn(min=220.dp,max=360.dp).testTag("video_render_mp4")) {
                    Text(if(renderBusy)"Đang tạo MP4…" else "Tạo MP4 và lưu ra máy")
                }
            }
            Text(if(directDownloads)"Tệp được lưu vào Download/Gemini OCR. Sau khi lưu có nút mở tệp ngay."
                else "Android sẽ mở trình chọn nơi lưu. Chọn Download để dễ tìm trong ứng dụng Tệp / File Manager.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SourceChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProjectPicker(projects: List<VideoProject>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag("video_library")) { Text("Dự án đã lưu (${projects.size}) ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            projects.forEach { project ->
                DropdownMenuItem(text = { Text("${if (selected == project.id) "✓ " else ""}${project.title} · ${project.progress}%") },
                    onClick = { expanded = false; onSelect(project.id) })
            }
        }
    }
}

@Composable
internal fun VideoGlossary(project: VideoProject, enabled: Boolean, onSave: (String) -> Unit) {
    var glossary by rememberSaveable(project.id, project.glossary) { mutableStateOf(project.glossary) }
    OutlinedTextField(value = glossary, onValueChange = { if (it.length <= 20000) glossary = it }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), label = { Text("Từ điển riêng (tùy chọn)") }, minLines = 2,
        supportingText = { Text("Giữ tên riêng và xưng hô; lưu trước khi bắt đầu xử lý.") })
    if (glossary != project.glossary) TextButton(onClick = { onSave(glossary) }, enabled = enabled) { Text("Lưu từ điển") }
}

@Composable
internal fun VideoStyleEditor(project: VideoProject, enabled: Boolean,
    initialDraft: VideoSubtitleStyle? = null, onPreview: (VideoSubtitleStyle) -> Unit = {}, onSave: (VideoSubtitleStyle) -> Unit) {
    val initial = (initialDraft ?: project.style).displayStyle()
    var position by rememberSaveable(project.id, project.style) { mutableFloatStateOf(initial.position) }
    var size by rememberSaveable(project.id, project.style) { mutableFloatStateOf(initial.sizeFraction) }
    var background by rememberSaveable(project.id, project.style) { mutableStateOf(initial.background) }
    var outline by rememberSaveable(project.id, project.style) { mutableStateOf(initial.outline) }
    var blurOriginal by rememberSaveable(project.id, project.style) { mutableStateOf(initial.blurOriginal) }
    var yellowText by rememberSaveable(project.id, project.style) { mutableStateOf(initial.yellowText) }
    var resetFrame by rememberSaveable(project.id, project.style, initial.frame) { mutableStateOf(initial.frame == null && project.style.frame != null) }
    val style = project.style.copy(position = position, sizeFraction = size, background = background, outline = outline,
        blurOriginal = blurOriginal, yellowText = yellowText, legacyDefault = false,
        frame = if (resetFrame) null else initial.frame)
    LaunchedEffect(style) { onPreview(style) }
    if(style.frame==null) {
        Text("Vị trí chữ: ${(style.position * 100).toInt()}% chiều cao")
        Slider(value = position, onValueChange = { position = it }, valueRange = .05f.. .95f,
            enabled = enabled, modifier = Modifier.semantics { contentDescription = "Vị trí phụ đề" })
    } else Text("Khung bản dịch tùy chỉnh đang áp dụng. Chỉnh trên video phía trên; khung này dùng cho xem lại, ASS và MP4.")
    Text("Cỡ chữ: ${String.format(Locale.ROOT, "%.1f", style.sizeFraction * 100)}% chiều cao")
    Slider(value = size, onValueChange = { size = it }, valueRange = .02f.. .09f,
        enabled = enabled, modifier = Modifier.semantics { contentDescription = "Cỡ chữ phụ đề" })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(blurOriginal, onCheckedChange = { blurOriginal = it; if (it) background = false }, enabled = enabled)
        Text("Làm mờ gọn vùng phụ đề gốc")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(yellowText, onCheckedChange = { yellowText = it; if (it) outline = true }, enabled = enabled)
        Text("Chữ vàng viền đen")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(background, onCheckedChange = { background = it; if (it) blurOriginal = false }, enabled = enabled)
        Text("Nền tối phía sau chữ Việt")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(outline, onCheckedChange = { outline = it }, enabled = enabled)
        Text("Viền chữ")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { val defaults = VideoSubtitleStyle(); position = defaults.position; size = defaults.sizeFraction;
            background = defaults.background; outline = defaults.outline; blurOriginal = defaults.blurOriginal;
            yellowText = defaults.yellowText; resetFrame = true }, enabled = enabled) { Text("Đặt lại mặc định") }
        Button(onClick = { onSave(style) }, enabled = enabled && style != project.style) { Text("Lưu cấu hình") }
    }
}

@Composable
internal fun VideoPreview(project: VideoProject, seekRequest: Pair<Long, Long>?, allowRoi: Boolean,
    allowFrame:Boolean,onFrame:(VideoSubtitleFrame?)->Unit,onRoi: (List<Float>) -> Unit,
    fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, playbackOnly: Boolean = false,
    initialPositionMs: Long = 0, onPosition: (Long) -> Unit = {},
    dataSourceFactory: DataSource.Factory? = null, onPositionFlushed: () -> Unit = {},
    onEpisodeSwipe: ((Int, Long) -> Boolean)? = null, managesFullscreenWindow: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = (context as ComponentActivity).lifecycle
    var restart by remember(project.id, project.uri, dataSourceFactory) {
        mutableStateOf<VideoPreviewRestart?>(null)
    }
    val startPosition = restart?.positionMs ?: initialPositionMs
    val backdropPlan = remember(project.style, project.roi, project.cues, project.sourceMode, project.durationMs) {
        subtitleBackdropPlan(project)
    }
    // Media3's effect timestamps start at the first position of a prepared stream.
    val effectEpochMs = remember(project.id, project.uri, dataSourceFactory, restart) {
        AtomicLong(startPosition.coerceAtLeast(0))
    }
    val player = remember(effectEpochMs) { ExoPlayer.Builder(context).apply {
        if (dataSourceFactory != null) {
            setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)))
            // Streaming has a bounded RAM buffer and no persistent media cache.
            setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 15_000, 500, 1000)
                .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        }
    }.build().apply {
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        if (backdropPlan != null) setVideoEffects(subtitleBackdropEffects(project, effectEpochMs::get))
        setMediaItem(MediaItem.fromUri(Uri.parse(project.uri)), startPosition)
        playWhenReady = restart?.playWhenReady ?: (dataSourceFactory != null || playbackOnly)
    } }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var playbackEnded by remember(player) { mutableStateOf(player.playbackState==Player.STATE_ENDED) }
    var playerReady by remember(player) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var editing by rememberSaveable(project.id) { mutableStateOf("") }
    var editingTime by rememberSaveable(project.id) { mutableLongStateOf(0L) }
    var playerError by remember(player) { mutableStateOf("") }
    var subtitleError by remember(player) { mutableStateOf("") }
    var fill by rememberSaveable(project.id) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<FullscreenPlayerView?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val lastVideoViewport = remember(project.id, project.uri, dataSourceFactory) { arrayOf(IntSize.Zero) }
    val installedBackdrop = remember(player) { arrayOf(backdropPlan) }
    var displaySize by remember(project.id, project.uri, dataSourceFactory) {
        mutableStateOf(IntSize(project.width.coerceAtLeast(1), project.height.coerceAtLeast(1)))
    }
    val toggleFullscreen by rememberUpdatedState({ onFullscreen(!fullscreen) })
    if (managesFullscreenWindow) VideoFullscreenWindow(fullscreen, displaySize.width > displaySize.height)
    val commitRoi by rememberUpdatedState(onRoi)
    val commitFrame by rememberUpdatedState(onFrame)
    fun edit(mode:String){player.pause();editingTime=player.currentPosition;editing=mode}
    val positionReporter=remember(player){VideoPositionReporter(startPosition, onPosition)}
    SideEffect {
        positionReporter.callback = onPosition
        positionReporter.onFlush = onPositionFlushed
    }
    val subtitleClock=remember(player){VideoSubtitleClock(AndroidVideoFrameScheduler(),
        position={player.currentPosition},playing={player.isPlaying},ended={player.playbackState==Player.STATE_ENDED}){time,ended->
        position=time;playbackEnded=ended;positionReporter.report(time)
    }}
    LaunchedEffect(project.id, project.uri, dataSourceFactory, seekRequest) {
        seekRequest?.let { player.seekTo(it.first); player.pause(); subtitleClock.signal() }
    }
    fun restartAtCurrentPosition() {
        restart = VideoPreviewRestart(player.currentPosition.coerceAtLeast(0), player.playWhenReady,
            (restart?.generation ?: 0L) + 1L)
    }
    LaunchedEffect(player, backdropPlan) {
        val installed = installedBackdrop[0]
        if (installed == backdropPlan) return@LaunchedEffect
        if (installed == null || backdropPlan == null) {
            // A no-effect player never creates the GL sink. Switch pipelines only on blur opt-in/out.
            restartAtCurrentPosition()
        } else {
            installedBackdrop[0] = backdropPlan
            player.setVideoEffects(subtitleBackdropEffects(project, effectEpochMs::get))
        }
    }
    LaunchedEffect(fullscreen, playerView, player) {
        if (fullscreen) { playerView?.hideController(); controlsVisible = false }
    }
    LaunchedEffect(player, playerView, viewportSize, playerReady) {
        val view = playerView ?: return@LaunchedEffect
        val size = viewportSize
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val previous = lastVideoViewport[0]
        if (previous == IntSize.Zero) {
            lastVideoViewport[0] = size
            return@LaunchedEffect
        }
        if (previous == size || !playerReady) return@LaunchedEffect
        // A paused effects pipeline otherwise keeps its last framebuffer at the old surface size.
        // Wait for the final layout/texture-size callback, then decode that same position again.
        kotlinx.coroutines.delay(120)
        withFrameNanos { }
        if (view.player === player && view.isAttachedToWindow && view.width == size.width && view.height == size.height &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && player.playbackState == Player.STATE_READY) {
            lastVideoViewport[0] = size
            if (player.playWhenReady || backdropPlan == null) return@LaunchedEffect
            // The old effects sink can hang in BUFFERING after stop/prepare. A fresh player
            // decodes the same paused position against the new surface size.
            restartAtCurrentPosition()
        }
    }
    LaunchedEffect(project.sourceMode) { if (!videoSourceUsesRoi(project.sourceMode) && editing=="ocr") editing="" }
    DisposableEffect(player, lifecycle) {
        var attached=true
        val observer = LifecycleEventObserver { _, event -> when(event){
            Lifecycle.Event.ON_START->subtitleClock.resume()
            Lifecycle.Event.ON_STOP->{subtitleClock.suspend();player.pause();positionReporter.flush(player.currentPosition.coerceAtLeast(0))}
            else->Unit
        } }
        val listener = object : Player.Listener {
            override fun onEvents(source:Player,events:Player.Events){if(attached && source===player){
                com.gemini.ocr.batch.BatchPlaybackPriority.current.setPlaying(player,
                    source.playWhenReady && source.playbackState != Player.STATE_IDLE && source.playbackState != Player.STATE_ENDED)
                subtitleClock.signal()
            }}
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (attached) playerReady = playbackState == Player.STATE_READY
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (attached && videoSize.width > 0 && videoSize.height > 0) {
                    val width = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt().coerceAtLeast(1)
                    displaySize = if (videoSize.unappliedRotationDegrees % 180 == 0) IntSize(width, videoSize.height)
                        else IntSize(videoSize.height, width)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if(attached)playerError = if (dataSourceFactory != null)
                    generateSequence<Throwable>(error) { it.cause }.take(8)
                        .filterIsInstance<DrivePlaybackException>().firstOrNull()?.failure?.message
                        ?: "Chưa phát được phim trên Drive. Kiểm tra mạng và tài khoản Google rồi thử lại."
                    else "Không thể xem trước video này: ${error.errorCodeName}. Hãy chọn container/codec Android hỗ trợ."
            }
        }
        lifecycle.addObserver(observer)
        player.addListener(listener)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))subtitleClock.resume()
        VideoPreviewDiagnostics.player(project.id,player)
        onDispose {
            attached=false;subtitleClock.release()
            com.gemini.ocr.batch.BatchPlaybackPriority.current.setPlaying(player, false)
            positionReporter.flush(player.currentPosition.coerceAtLeast(0))
            lifecycle.removeObserver(observer);player.removeListener(listener)
            playerView?.takeIf { it.player === player }?.player = null
            VideoPreviewDiagnostics.player(project.id,null);player.release()
        }
    }
    // Wait until the old player has released its decoder and the view binds the new one.
    LaunchedEffect(player, playerView) {
        val view = playerView ?: return@LaunchedEffect
        withFrameNanos { }
        if (view.player === player && player.playbackState == Player.STATE_IDLE && player.playerError == null) {
            player.prepare()
        }
    }
    val aspect = displaySize.width.toFloat() / displaySize.height
    val panelLimit=(LocalConfiguration.current.screenHeightDp*.6f).coerceIn(120f,420f).dp
    val panelHeight=with(LocalDensity.current){(viewportSize.width.coerceAtLeast(1)/aspect).toDp()}.coerceIn(120.dp,panelLimit)
    val viewport = VideoViewport.calculate(displaySize.width, displaySize.height,
        viewportSize.width.coerceAtLeast(1), viewportSize.height.coerceAtLeast(1), fullscreen && fill)
    // Library playback has a bounded remaining height; fit the original inside it.
    // A width-driven aspect ratio can otherwise overflow over the header and controls.
    Column(if (fullscreen || playbackOnly) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
    if(!fullscreen && !playbackOnly) {
        if(allowRoi) {
            OutlinedButton(onClick={edit("ocr")},modifier=Modifier.testTag("video_roi_edit")){Text("Khoanh vùng chữ Trung")}
            Text("Vùng OCR đã lưu: ${project.roi.joinToString(" · "){"${(it*100).toInt()}%"}}",style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.testTag("video_roi_saved"))
        }
        OutlinedButton(onClick={edit("output")},enabled=allowFrame,modifier=Modifier.testTag("video_output_frame_toggle")) {
            Text("Chỉnh khung phụ đề sau dịch",modifier=Modifier.testTag("video_frame_edit"))
        }
    }
    Box((if (fullscreen || playbackOnly) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(panelHeight))
        .onSizeChanged { viewportSize = it }.clipToBounds().background(Color.Black).testTag("video_preview")) {
        AndroidView(factory = {
            (android.view.LayoutInflater.from(it).inflate(com.gemini.ocr.R.layout.video_fullscreen_player, null, false)
                as FullscreenPlayerView).apply {
            onDoubleTap = { toggleFullscreen() }
            this.player = player
            playerView = this
            controllerAutoShow = false
            controllerShowTimeoutMs = 2500
            controllerHideOnTouch = true
            setShowSubtitleButton(false)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                controlsVisible = visibility == View.VISIBLE
            })
            val captions = SavedTimelineSubtitleView(it)
            // Root coordinates include letterbox/crop; keep captions below Media3's controls.
            addView(captions, 1, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            tag = captions
        } }, onReset = null, onRelease = { (it.tag as? SavedTimelineSubtitleView)?.release() },
            update = {
                it.player = player
                it.onEpisodeSwipe = onEpisodeSwipe?.let { navigate -> { direction ->
                    if (navigate(direction, player.currentPosition.coerceAtLeast(0))) {
                        player.pause(); positionReporter.flush(player.currentPosition.coerceAtLeast(0)); true
                    } else false
                } }
                it.resizeMode = if (fullscreen && fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                it.useController = editing.isBlank()
                (it.tag as? SavedTimelineSubtitleView)?.apply {
                    visibility = View.VISIBLE
                    update(project, position, fullscreen && fill, displaySize, playbackEnded) { message -> subtitleError = message }
                }
            }, modifier = Modifier.fillMaxSize())
        // Preview always reads the saved timeline; playback never invokes OCR or a translation engine.
        if(allowRoi && !fullscreen) Canvas(Modifier.fillMaxSize()) {
            val roi=project.roi
            val rect = Rect(viewport.screenX(roi[0]), viewport.screenY(roi[1]), viewport.screenX(roi[2]), viewport.screenY(roi[3]))
            drawRect(Color(0xFF6EE7C7), topLeft = rect.topLeft, size = rect.size, style = Stroke(3.dp.toPx()))
        }
        if (!fullscreen || controlsVisible) {
            Row(Modifier.align(Alignment.TopEnd),
                verticalAlignment=Alignment.CenterVertically) {
                if (fullscreen) {
                    IconButton(onClick={fill=!fill}, modifier=Modifier.testTag("video_scale_mode")
                        .semantics { contentDescription = if(fill) "Fill" else "Fit" }) {
                        Text(if(fill) "⊡" else "□", color=Color.White)
                    }
                    if (!playbackOnly) IconButton(onClick={edit("output")},
                        enabled=allowFrame,modifier=Modifier.testTag("video_fullscreen_frame")
                            .semantics { contentDescription="Chỉnh phụ đề" }) {
                        Text("✎",color=Color.White)
                    }
                }
                IconButton(onClick={onFullscreen(!fullscreen)},modifier=Modifier
                    .testTag(if(fullscreen) "video_fullscreen_exit" else "video_fullscreen_enter")
                    .semantics { contentDescription=if(fullscreen) "Thoát toàn màn hình" else "Toàn màn hình" }) {
                    Text(if(fullscreen) "⤡" else "⛶",color=Color.White)
                }
            }
        }
    }
    if (!fullscreen) {
        Text("${videoTime(position)} / ${videoTime(project.durationMs)}", style = MaterialTheme.typography.bodySmall)
    }
    val previewError=playerError.ifBlank{subtitleError}
    if (previewError.isNotBlank() && (!fullscreen || dataSourceFactory != null)) {
        Text(previewError, color = MaterialTheme.colorScheme.error)
        if (dataSourceFactory != null && playerError.isNotBlank()) TextButton(onClick = {
            playerError = ""
            effectEpochMs.set(player.currentPosition.coerceAtLeast(0))
            player.prepare()
        }) { Text("Thử phát lại") }
    }
    }
    if(editing.isNotBlank())VideoRegionDialog(project,editingTime,editing=="ocr",onCancel={editing=""},onConfirm={frame->
        if(editing=="ocr")commitRoi(listOf(frame.left,frame.top,frame.right,frame.bottom)) else commitFrame(frame)
        editing=""
    })
}

private data class VideoPreviewRestart(val positionMs: Long, val playWhenReady: Boolean, val generation: Long)

private data class VideoPreviewInput(val project: VideoProject, val seekRequest: Pair<Long, Long>?,
    val allowRoi: Boolean, val allowFrame: Boolean, val onFrame: (VideoSubtitleFrame?) -> Unit,
    val onRoi: (List<Float>) -> Unit, val fullscreen: Boolean, val onFullscreen: (Boolean) -> Unit)

/** Uses the exact export renderer, scaled to the player viewport, including multiline bottom anchoring.
 * Accessibility font scaling cannot change the burn-in preview geometry. Only one cue strip is cached. */
internal class SavedTimelineSubtitleView(context: Context) : View(context) {
    private var overlay: TimelineBitmapOverlay? = null
    private var cues: List<VideoCue> = emptyList()
    private var inputCues:List<VideoCue>?=null
    private var renderRows:List<PreviewRenderCue> = emptyList()
    private var projectId=""
    private var durationMs=-1L
    private var validTimeline=false
    private var released=false
    private var style = VideoSubtitleStyle()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var positionMs = 0L
    private var fill = false
    private var displaySize = IntSize(1, 1)
    private var onError: (String) -> Unit = {}
    private var ended=false
    private var activeCue:VideoCue?=null
    private var visibleText=""
    private var invalidationCount=0L
    private var drawCount=0L
    private var layoutWidth=-1
    private var layoutHeight=-1
    private var lastRenderError:String?=null

    fun update(project: VideoProject, timeMs: Long, fill: Boolean, displaySize: IntSize, ended:Boolean, error: (String) -> Unit) {
        if(released)return
        onError=error
        var projectionChanged=false
        if(projectId!=project.id || inputCues!==project.cues || durationMs!=project.durationMs){
            inputCues=project.cues;projectId=project.id;durationMs=project.durationMs
            try {
                cues=VideoTimeline.validate(project.cues,project.durationMs)
                val rows=cues.map{PreviewRenderCue(it.startMs,it.endMs,it.translation)}
                projectionChanged=rows!=renderRows
                renderRows=rows;validTimeline=true
            }catch(failure:IllegalArgumentException){
                cues=emptyList();renderRows=emptyList();validTimeline=false
                onError(failure.message?:"Timeline phụ đề chưa hợp lệ; dữ liệu gốc vẫn được giữ.")
            }
        }
        var renderChanged=false
        if(!validTimeline){
            if(overlay!=null){overlay?.release();overlay=null;renderChanged=true}
        }else if (style != project.style || sourceWidth != project.width || sourceHeight != project.height ||
            (overlay==null && validTimeline && project.width>0 && project.height>0)) {
            overlay?.release()
            style = project.style
            sourceWidth = project.width
            sourceHeight = project.height
            overlay = if (validTimeline && sourceWidth > 0 && sourceHeight > 0) TimelineBitmapOverlay(cues, style).also {
                it.configure(Size(sourceWidth, sourceHeight))
            } else null
            if(validTimeline){lastRenderError=null;onError("")}
            renderChanged=true
        }else if(projectionChanged)overlay?.updateTimeline(cues)
        val nextCue=if(ended) null else VideoTimeline.active(cues,timeMs)
        val text=if(overlay!=null)nextCue?.translation.orEmpty()else ""
        val changed=renderChanged || text!=visibleText || this.fill!=fill || this.displaySize!=displaySize ||
            layoutWidth!=width || layoutHeight!=height
        positionMs = timeMs
        this.ended=ended
        activeCue=nextCue;visibleText=text
        this.fill = fill
        this.displaySize = displaySize
        layoutWidth=width;layoutHeight=height
        if(changed){invalidationCount++;invalidate()}
        observe()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if(released)return
        drawCount++
        val renderer = overlay ?: return
        if (ended){observe();return}
        try {
            val bitmap = renderer.getBitmap(positionMs * 1000)
            if(visibleText.isBlank()){observe();return}
            val viewport = VideoViewport.calculate(displaySize.width, displaySize.height, width.coerceAtLeast(1), height.coerceAtLeast(1), fill)
            val bounds=renderer.bitmapBounds()
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(
                viewport.screenX(bounds.left.toFloat()/sourceWidth), viewport.screenY(bounds.top.toFloat()/sourceHeight),
                viewport.screenX(bounds.right.toFloat()/sourceWidth), viewport.screenY(bounds.bottom.toFloat()/sourceHeight)), null)
            if(lastRenderError!=null){lastRenderError=null;onError("")}
        } catch (e: IllegalArgumentException) {
            val message=e.message ?: "Không thể xem trước kiểu phụ đề này; hãy giảm cỡ chữ hoặc tách câu."
            if(message!=lastRenderError){lastRenderError=message;onError(message)}
        }
        observe()
    }

    private fun observe(){VideoPreviewDiagnostics.frame(VideoPreviewFrame(projectId,positionMs,activeCue?.id,
        visibleText,ended,invalidationCount,drawCount))}
    fun release() { released=true;overlay?.release(); overlay = null }
}

private data class PreviewRenderCue(val startMs:Long,val endMs:Long,val text:String)

@Composable
private fun CueEditor(cue: VideoCue, allCues: List<VideoCue>, durationMs: Long, onDismiss: () -> Unit,
    onSave: (VideoCue) -> Unit, onRetry: () -> Unit) {
    var start by rememberSaveable(cue.id) { mutableStateOf(videoTime(cue.startMs)) }
    var end by rememberSaveable(cue.id) { mutableStateOf(videoTime(cue.endMs)) }
    var source by rememberSaveable(cue.id) { mutableStateOf(cue.source) }
    var translation by rememberSaveable(cue.id) { mutableStateOf(cue.translation) }
    var approved by rememberSaveable(cue.id) { mutableStateOf(cue.approved) }
    var error by remember { mutableStateOf("") }
    fun markDraft() { approved = false }
    fun saveCue() {
        try {
            val a = parseVideoTime(start); val b = parseVideoTime(end)
            require(a < b && b <= durationMs) { "Cần 0 ≤ bắt đầu < kết thúc ≤ thời lượng video." }
            require(source.isNotBlank()) { "Chữ nguồn không được rỗng." }
            require(!approved || translation.isNotBlank()) { "Cần có bản dịch trước khi xác nhận." }
            val candidate = cue.copy(startMs = a, endMs = b, source = source.trim(), translation = translation.trim(), approved = approved, error = "")
            VideoTimeline.validate(allCues.map { if (it.id == cue.id) candidate else it }, durationMs)
            onSave(candidate)
        } catch (e: IllegalArgumentException) { error = e.message ?: "Nội dung không hợp lệ." }
    }
    EditorDialog("Sửa phụ đề", onDismiss = onDismiss, closeLabel = "Đóng", actions = {
        TextButton(onClick = ::saveCue) { Text("Lưu") }
    }) {
            Text("${videoTime(cue.startMs)} → ${videoTime(cue.endMs)}", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(source, { if (it.length <= 2000) { source = it; markDraft() } }, label = { Text("Chữ Trung nguồn") }, minLines = 2)
            OutlinedTextField(translation, { if (it.length <= 8000) { translation = it; markDraft() } }, label = { Text("Bản dịch Việt") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(approved, onCheckedChange = { approved = it })
                Text("Tôi đã đối chiếu · Đã duyệt")
            }
            Text("Sau khi sửa, câu cần được kiểm tra lại trước khi đánh dấu đã duyệt.", style = MaterialTheme.typography.bodySmall)
            ExpandableSection("Thời gian và thao tác khác") {
                OutlinedTextField(start, { start = it; markDraft() }, label = { Text("Bắt đầu · hh:mm:ss.mmm hoặc giây") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(end, { end = it; markDraft() }, label = { Text("Kết thúc") })
                TextButton(onClick = onRetry) { Text("Dịch lại câu này bằng chế độ đã chọn") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
