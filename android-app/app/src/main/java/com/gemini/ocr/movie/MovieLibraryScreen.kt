package com.gemini.ocr.movie

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.batch.BatchActivity
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchProject
import com.gemini.ocr.batch.BatchLibraryTarget
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.ui.ocean.*
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class MovieSort(val label: String) { RECENT("Cập nhật gần đây"), NAME("Tên phim"), EPISODES("Số tập") }

private val EmptyMovieBatchProjects = MutableStateFlow<List<BatchProject>>(emptyList())
private val NoActiveMovieBatch = MutableStateFlow<String?>(null)

internal fun sortedMovies(movies: List<MovieRecord>, query: String, sort: MovieSort): List<MovieRecord> =
    matchingMovieRows(movies, query, sort, MovieFilter.ALL).map { it.first }

internal fun matchingMovieRows(movies: List<MovieRecord>, query: String, sort: MovieSort, filter: MovieFilter):
    List<Pair<MovieRecord, List<MovieEpisode>>> {
    val matches = movies.mapNotNull { movie ->
        matchingMovieEpisodes(movie, query, filter).takeIf { it.isNotEmpty() }?.let { movie to it }
    }
    return when (sort) {
        MovieSort.RECENT -> matches.sortedByDescending { it.first.updatedAt }
        MovieSort.NAME -> matches.sortedBy { it.first.title.lowercase(Locale.ROOT) }
        MovieSort.EPISODES -> matches.sortedByDescending { it.first.episodes.size }
    }
}

internal fun continueEpisode(movie: MovieRecord): MovieEpisode? {
    val recent = mostRecentEpisode(movie.episodes)
        ?: return movie.episodes.firstOrNull()
    if (recent.watchState() == MovieWatchState.STARTED) return recent
    return movie.episodes.getOrNull(movie.episodes.indexOf(recent) + 1) ?: recent
}

private fun mostRecentEpisode(episodes: List<MovieEpisode>): MovieEpisode? =
    episodes.asSequence().filter { it.positionMs > 0 }.maxByOrNull { it.positionUpdatedAt }

private fun watchTime(positionMs: Long): String {
    val seconds = positionMs / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val library = remember { MovieLibrary.get(context) }
    val cloud = remember { MovieCloudSync.get(context) }
    val auth = remember { VideoCloudSync.get(context) }
    val batches by produceState<BatchJobs?>(null, context.applicationContext) {
        value = withContext(Dispatchers.IO) { BatchJobs.get(context.applicationContext) }
    }
    val movies by library.movies.collectAsState()
    val projects by (batches?.batches ?: EmptyMovieBatchProjects).collectAsState()
    val activeBatch by (batches?.busy ?: NoActiveMovieBatch).collectAsState()
    val account by auth.state.collectAsState()
    val catalogLoaded by library.catalogLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(MovieSort.RECENT.name) }
    var filterName by rememberSaveable { mutableStateOf(MovieFilter.ALL.name) }
    var sortMenu by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var driveError by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importError by remember { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf("") }
    var deleteDraftId by rememberSaveable { mutableStateOf("") }
    var offlineId by rememberSaveable { mutableStateOf("") }
    var removingMediaFor by remember { mutableStateOf<String?>(null) }
    var showAllProcessing by rememberSaveable { mutableStateOf(false) }
    fun action(drive: Boolean = false, removeMediaFor: String? = null, block: suspend () -> Unit): Job? {
        if (working) return null
        working = true
        removingMediaFor = removeMediaFor
        notice = ""
        noticeError = false
        importError = ""
        if (drive) driveError = ""
        return scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "Chưa hoàn tất thao tác. Bạn có thể thử lại."
                if (drive) driveError = message
                else { noticeError = true; notice = message }
            }
            finally {
                removingMediaFor = null
                working = false
            }
        }
    }
    val moviePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !working) {
            importJob = action {
                try {
                    val movie = library.importMovie(uri)
                    query = ""
                    filterName = MovieFilter.ALL.name
                    notice = "Đã nhập phim ${movie.title}."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    importError = e.message ?: "Chưa nhập được phim. Hãy chọn lại tệp."
                } finally {
                    importJob = null
                }
            }
        }
    }
    fun selectMovieFile() {
        importError = ""
        try {
            moviePicker.launch(arrayOf("video/*"))
        } catch (_: ActivityNotFoundException) {
            importError = "Thiết bị chưa có trình chọn tệp. Hãy bật ứng dụng Tệp của Android."
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        action(drive = true) { auth.completeAuthorization(result.data, result.resultCode); cloud.refreshCloud() }
    }
    val connect: () -> Unit = {
        action(drive = true) {
            val pending = auth.authorize(selectAccount = true)
            if (pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            else cloud.refreshCloud()
        }
    }
    val refreshDrive: () -> Unit = { action(drive = true) { cloud.refreshCloud() } }
    val sort = MovieSort.entries.firstOrNull { it.name == sortName } ?: MovieSort.RECENT
    val filter = MovieFilter.entries.firstOrNull { it.name == filterName } ?: MovieFilter.ALL
    val visible = remember(movies, account.accountEmail, query, sort, filter) {
        matchingMovieRows(movies.filter { it.accountEmail.isBlank() || it.accountEmail == account.accountEmail }, query, sort, filter)
    }
    val processing = remember(projects, movies, query, filter, catalogLoaded) {
        if (!catalogLoaded || filter != MovieFilter.ALL && filter != MovieFilter.PROCESSING) emptyList()
        else projects.filter { p -> !BatchLibraryTarget.saved(p, movies) &&
            (p.title.contains(query.trim(), true) || p.items.any { it.name.contains(query.trim(), true) }) }
    }
    val continueWatching = remember(visible, filter) {
        if (filter != MovieFilter.ALL) emptyList()
        else visible.mapNotNull { (movie, episodes) ->
            episodes.asSequence().filter { it.watchState() == MovieWatchState.STARTED }.maxByOrNull { it.positionUpdatedAt }
                ?.let { movie to it }
        }.sortedByDescending { it.second.positionUpdatedAt }.take(8)
    }
    fun openBatch(id: String = "") {
        val intent = Intent(context, BatchActivity::class.java).putExtra("batch", id)
        if (batches != null) context.startActivity(intent)
        else action {
            withContext(Dispatchers.IO) { BatchJobs.get(context) }
            context.startActivity(intent)
        }
    }
    fun play(movie: MovieRecord, episode: MovieEpisode) {
        if (removingMediaFor == movie.id) return
        context.startActivity(Intent(context, MoviePlaybackActivity::class.java)
            .putExtra("movie", movie.id).putExtra("episode", episode.id))
    }
    OceanTheme {
    Surface(modifier.fillMaxSize(), color = OceanColors.Base) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = GridCells.Fixed(if (maxWidth >= 760.dp) 2 else 1)
        val fullLine: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
        LazyVerticalGrid(columns, Modifier.widthIn(max = 1080.dp).fillMaxSize().testTag("movie_library"),
            contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "library_header", span = fullLine) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Kho phim", style = MaterialTheme.typography.headlineMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OceanPrimaryButton("Dịch video mới", { openBatch() }, Modifier.testTag("movie_new_batch"))
                        OceanSecondaryButton("Nhập phim từ tệp", { selectMovieFile() },
                            Modifier.testTag("movie_import"), enabled = !working)
                    }
                }
            }
            if (importJob != null) item(key = "import_progress", span = fullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đang nhập phim vào máy…", Modifier.weight(1f))
                        TextButton(onClick = { importJob?.cancel() }) { Text("Hủy nhập") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (importError.isNotBlank()) item(key = "import_error", span = fullLine) {
                Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (notice.isNotBlank()) item(key = "library_notice", span = fullLine) { StatusNotice(notice, error = noticeError) }
            item(key = "library_search", span = fullLine) {
                Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Tìm tên phim hoặc tên tập") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = OceanColors.Cyan) },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = OceanColors.Card,
                            focusedContainerColor = OceanColors.Card, unfocusedBorderColor = OceanColors.Outline.copy(alpha = .5f)),
                        modifier = Modifier.weight(1f).testTag("movie_search"))
                    Box {
                        FilledTonalIconButton(onClick = { sortMenu = true }, modifier = Modifier.size(52.dp).testTag("movie_sort")) {
                            Icon(Icons.Outlined.FilterList, "Sắp xếp phim")
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) { MovieSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { sortName = option.name; sortMenu = false })
                        } }
                    }
                }
            }
            item(key = "library_filters", span = fullLine) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MovieFilter.entries.forEach { option ->
                        FilterChip(filter == option, { filterName = option.name }, label = { Text(option.label) })
                    }
                }
            }
            if (continueWatching.isNotEmpty()) item(key = "continue_header", span = fullLine) {
                Text("Xem tiếp", style = MaterialTheme.typography.titleMedium)
            }
            items(continueWatching, key = { "continue-${it.first.id}" }, contentType = { "continue" }) { (movie, episode) ->
                val playable = episodePlayable(library, episode, movie.cloudId.isNotBlank() && account.accountEmail.isNotBlank())
                OutlinedButton(onClick = { play(movie, episode) }, enabled = playable == true && removingMediaFor != movie.id,
                    modifier = Modifier.fillMaxWidth().testTag("movie_continue_${movie.id}")) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(movie.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(episode.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(watchTime(episode.positionMs))
                }
            }
            item(key = "drive_status", span = fullLine) {
                OceanCard(Modifier.testTag("movie_drive_status")) {
                if (BuildConfig.VERIFICATION_OFFLINE_NETWORK) {
                    Text("Google Drive chỉ khả dụng trên bản Gemini OCR chính.", style = MaterialTheme.typography.bodyMedium)
                } else {
                ExpandableSection("Google Drive", subtitle = when {
                        account.authorizationRequired -> "Cần kết nối lại"
                        account.busy -> "Đang đồng bộ"
                        driveError.isNotBlank() -> "Đồng bộ chưa hoàn tất"
                        account.accountEmail.isBlank() -> "Chưa kết nối"
                        else -> "Đã kết nối"
                }) {
                    if (account.accountEmail.isNotBlank()) Text(account.accountEmail)
                    if (!account.authorizationRequired && driveError.isBlank()) StatusNotice(account.message)
                    Text("Xem ngay từ Drive hoặc tải ngoại tuyến. Khi có bản trong máy hợp lệ, ứng dụng ưu tiên bản đó.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = connect, enabled = !working && !account.busy) { Text("Chọn tài khoản Google") }
                        OutlinedButton(onClick = refreshDrive, enabled = !working && !account.busy &&
                            !account.authorizationRequired && account.accountEmail.isNotBlank()) {
                            Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(6.dp)); Text("Đồng bộ danh mục")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(account.wifiOnly, { auth.setWifiOnly(it) }, enabled = !working)
                        Text("Chỉ tải lên qua Wi-Fi / mạng không tính phí", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (account.accountEmail.isNotBlank()) TextButton(onClick = {
                        auth.disconnect()
                        driveError = ""
                    }, enabled = !working) { Text("Ngắt kết nối trên thiết bị này") }
                }
                val reconnect = account.authorizationRequired || account.accountEmail.isBlank()
                if (account.authorizationRequired || driveError.isNotBlank()) {
                    StatusNotice(driveError.ifBlank { account.message }, error = true,
                        onRetry = if (working || account.busy) null else if (reconnect) connect else refreshDrive)
                }
                }
                }
                if ((working && importJob == null) || account.busy)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (processing.isNotEmpty()) item(key = "processing_header", span = fullLine) {
                Text("Đang chuẩn bị / xử lý · ${processing.size}", style = MaterialTheme.typography.titleMedium)
            }
            items(if (showAllProcessing) processing else processing.take(3), key = { "processing-${it.id}" },
                contentType = { "processing" }) { batch ->
                ProcessingMovieCard(batch, Modifier.fillMaxWidth(), deleteEnabled = !working && activeBatch != batch.id,
                    onOpen = { openBatch(batch.id) }, onDelete = { deleteDraftId = batch.id })
            }
            if (processing.size > 3) item(key = "show_processing", span = fullLine) {
                TextButton(onClick = { showAllProcessing = !showAllProcessing }) {
                    Text(if (showAllProcessing) "Thu gọn dự án" else "Xem tất cả ${processing.size} dự án")
                }
            }
            if (filter == MovieFilter.PROCESSING && processing.isEmpty()) item(key = "no_processing", span = fullLine) {
                Text(if (batches == null || !catalogLoaded) "Đang tải dự án…" else "Không có dự án phù hợp.")
            }
            if (filter != MovieFilter.PROCESSING) item(key = "movies_header", span = fullLine) {
                val section = when (filter) {
                    MovieFilter.TRANSLATED -> "Đã dịch xong"
                    MovieFilter.WATCHED -> "Đã xem"
                    MovieFilter.IMPORTED -> "Tệp nhập chưa xử lý"
                    else -> "Bộ phim của tôi"
                }
                Text(if (catalogLoaded) "$section · ${visible.size}" else section, style = MaterialTheme.typography.titleMedium)
            }
            if (!catalogLoaded && filter != MovieFilter.PROCESSING) item(key = "loading_movies", span = fullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Đang tải danh sách phim…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (catalogLoaded && visible.isEmpty() && filter != MovieFilter.PROCESSING) item(key = "no_movies", span = fullLine) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (query.isBlank() && filter == MovieFilter.ALL) "Kho phim đang trống" else "Không tìm thấy phim phù hợp")
                        Text(if (query.isBlank() && filter == MovieFilter.ALL) "Nhập phim từ tệp hoặc lưu phim sau khi duyệt bản dịch."
                            else "Thử từ khóa khác hoặc chọn mục Tất cả.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(visible, key = { "movie-${it.first.id}" }, contentType = { "movie" }) { (movie, episodes) ->
                MovieCard(movie, episodes, !working, removingMediaFor != movie.id, account.accountEmail.isNotBlank(), Modifier.fillMaxWidth(),
                    onPlay = { play(movie, it) }, onDownload = { action { cloud.download(movie.id) } },
                    onSync = { action(drive = true) { withContext(Dispatchers.IO) { cloud.enqueue(movie.id) } } },
                    onAddEpisodes = { action {
                        val target = withContext(Dispatchers.IO) { BatchJobs.get(context).createForMovie(movie.id) }
                        context.startActivity(Intent(context, BatchActivity::class.java).putExtra("batch", target.id).putExtra("add_videos", true))
                    } },
                    onRename = { renameId = movie.id }, onRemoveOffline = { offlineId = movie.id }, onDelete = { deleteId = movie.id },
                    onReorder = { order -> action { library.reorderEpisodes(movie.id, order) } })
            }
        }
    }
    }
    val draftToDelete = projects.firstOrNull {
        it.id == deleteDraftId && !BatchLibraryTarget.saved(it, movies)
    }
    if (draftToDelete != null) AlertDialog(
        onDismissRequest = { deleteDraftId = "" },
        title = { Text("Xóa dự án hàng loạt?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draftToDelete.title, style = MaterialTheme.typography.titleMedium)
                val updated = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(Date(draftToDelete.updatedAt))
                Text("${draftToDelete.items.size} video · Cập nhật $updated")
                draftToDelete.items.firstOrNull()?.let { Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text("Xóa dự án và thứ tự tập đã lưu. Video gốc, phụ đề riêng và phim trong Kho phim được giữ.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                deleteDraftId = ""
                action {
                    withContext(Dispatchers.IO) { BatchJobs.get(context).deleteDraft(draftToDelete.id) }
                    notice = "Đã xóa dự án ${draftToDelete.title}."
                }
            }, enabled = !working && activeBatch != draftToDelete.id,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Xóa dự án")
            }
        },
        dismissButton = { TextButton(onClick = { deleteDraftId = "" }) { Text("Giữ lại") } }
    )
    movies.firstOrNull { it.id == renameId }?.let { movie ->
        MovieRenameDialog(movie, library) { renameId = "" }
    }
    if (deleteId.isNotBlank()) AlertDialog(onDismissRequest = { deleteId = "" }, title = { Text("Xóa phim khỏi thiết bị?") },
        text = { Text("Xóa bản trong Kho phim trên máy. Bản trên Google Drive và dự án dịch được giữ.") },
        confirmButton = { TextButton(onClick = {
            val id = deleteId; deleteId = ""
            action(removeMediaFor = id) { withContext(Dispatchers.IO) { library.remove(id) } }
        }) { Text("Xóa") } },
        dismissButton = { TextButton(onClick = { deleteId = "" }) { Text("Giữ lại") } })
    if (offlineId.isNotBlank()) AlertDialog(onDismissRequest = { offlineId = "" }, title = { Text("Giải phóng video ngoại tuyến?") },
        text = { Text("Chỉ xóa bản video của Kho phim đã đồng bộ. Bạn có thể tải lại từ Google Drive.") },
        confirmButton = { TextButton(onClick = {
            val id = offlineId; offlineId = ""
            action(removeMediaFor = id) { withContext(Dispatchers.IO) { library.removeOffline(id) } }
        }) { Text("Giải phóng") } },
        dismissButton = { TextButton(onClick = { offlineId = "" }) { Text("Giữ lại") } })
    }
}

@Composable
private fun ProcessingMovieCard(batch: BatchProject, modifier: Modifier, deleteEnabled: Boolean,
    onOpen: () -> Unit, onDelete: () -> Unit) {
    val completed = batch.items.count { it.state == "DONE" }
    val activeStages = batch.items.filter { it.stageStatus == "RUNNING" }.groupingBy { it.stage }.eachCount()
    val state = when (batch.state) { "RUNNING" -> "Đang xử lý"; "PAUSED" -> "Đã tạm dừng"; "FAILED" -> "Cần kiểm tra"
        "REVIEW" -> "Sẵn sàng kiểm tra và lưu"; "CANCELLED" -> "Đã dừng · có thể tiếp tục"; else -> "Chờ thiết lập" }
    Card(onClick = onOpen, modifier = modifier.testTag("movie_processing_${batch.id}"),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = OceanColors.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = OceanColors.Elevated) {
                Icon(Icons.Outlined.MovieCreation, null, Modifier.padding(12.dp).size(28.dp), tint = OceanColors.Cyan)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(batch.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state, style = MaterialTheme.typography.bodyMedium,
                    color = if (batch.state == "FAILED") OceanColors.Warning else OceanColors.Muted)
                Text("$completed/${batch.items.size} video hoàn tất", style = MaterialTheme.typography.labelMedium, color = OceanColors.Cyan)
                if (activeStages.isNotEmpty()) Text(activeStages.entries.joinToString(" · ") { (stage, count) ->
                    "${stage.label} ($count)"
                }, style = MaterialTheme.typography.labelSmall)
                if (batch.items.isNotEmpty() && completed > 0) LinearProgressIndicator(progress = { completed.toFloat() / batch.items.size },
                    modifier = Modifier.fillMaxWidth().height(4.dp), color = OceanColors.Mint, trackColor = OceanColors.Elevated)
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled,
                modifier = Modifier.testTag("movie_delete_draft_${batch.id}")) {
                Icon(Icons.Outlined.DeleteOutline, "Xóa dự án ${batch.title}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieCard(movie: MovieRecord, visibleEpisodes: List<MovieEpisode>, enabled: Boolean, playbackEnabled: Boolean,
    connected: Boolean, modifier: Modifier,
    onPlay: (MovieEpisode) -> Unit, onDownload: () -> Unit, onSync: () -> Unit, onAddEpisodes: () -> Unit,
    onRename: () -> Unit, onRemoveOffline: () -> Unit, onDelete: () -> Unit, onReorder: (List<String>) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val library = remember { MovieLibrary.get(context) }
    val order = remember(movie.episodes.map { it.id to it.title }) { suggestedMovieOrder(movie.episodes) }
    val recommended = continueEpisode(movie)?.takeIf { it in visibleEpisodes } ?: visibleEpisodes.firstOrNull()
    val recent = mostRecentEpisode(movie.episodes) ?: recommended
    val next = recent?.let { movie.episodes.getOrNull(movie.episodes.indexOf(it) + 1) }?.takeIf { it in visibleEpisodes }
    val canStream = connected && movie.cloudId.isNotBlank()
    val canPlay = episodePlayable(library, recommended, canStream)
    val nextShortcut = next?.takeIf { it.id != recommended?.id }
    val canPlayNext = episodePlayable(library, nextShortcut, canStream)
    Card(modifier.testTag("movie_card_${movie.id}"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = OceanColors.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MovieCover(movie.id, movie.coverPath)
            if (recent?.watchState() == MovieWatchState.STARTED) {
                LinearProgressIndicator(progress = { recent.positionMs.toFloat() / recent.durationMs },
                    modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(movie.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${movie.episodes.size} tập · ${movie.episodes.sumOf { it.durationMs } / 60000} phút", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Tùy chọn phim") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Đổi tên phim") }, enabled = enabled, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Thêm tập mới") }, enabled = enabled, onClick = { menu = false; onAddEpisodes() })
                        if (order != null) DropdownMenuItem(text = { Text("Xếp theo số tập") }, enabled = enabled,
                            onClick = { menu = false; onReorder(order) })
                        DropdownMenuItem(text = { Text("Thử lại đồng bộ") }, enabled = enabled && connected, onClick = { menu = false; onSync() })
                        if (movie.cloudId.isNotBlank()) DropdownMenuItem(text = { Text("Giải phóng bản ngoại tuyến") }, enabled = enabled,
                            onClick = { menu = false; onRemoveOffline() })
                        DropdownMenuItem(text = { Text("Xóa khỏi thiết bị") }, enabled = enabled, onClick = { menu = false; onDelete() })
                    }
                }
            }
            val label = when (movie.syncState) { "SYNCED" -> "Đã đồng bộ"; "QUEUED" -> "Chờ đồng bộ"; "UPLOADING" -> "Đang đồng bộ"
                "CLOUD" -> "Trên Google Drive"; "FAILED" -> "Đồng bộ thất bại"; "CONFLICT" -> "Có phiên bản phụ đề khác trên Drive"; else -> "Chỉ lưu trên máy" }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (movie.syncState == "SYNCED") Icons.Outlined.CloudDone else Icons.Outlined.CloudQueue, null, Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            if (movie.episodes.any { it.isSourceQuality }) Text("Video gốc · Phụ đề rời", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (movie.message.isNotBlank() && (movie.syncState in setOf("FAILED", "CONFLICT") || movie.episodes.any { it.positionConflict }))
                StatusNotice(movie.message, error = true, onRetry = if (enabled && connected && movie.syncState == "FAILED") onSync else null)
            if (recommended != null) {
                val action = when {
                    recommended.watchState() == MovieWatchState.STARTED -> "Xem tiếp · ${watchTime(recommended.positionMs)}"
                    recommended.watchState() == MovieWatchState.FINISHED -> "Xem lại"
                    recommended.id == next?.id && recent?.watchState() == MovieWatchState.FINISHED -> "Xem tập tiếp"
                    else -> "Xem ngay"
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (movie.episodes.size > 1) Text(recommended.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { onPlay(recommended) }, enabled = playbackEnabled && canPlay == true,
                        modifier = Modifier.fillMaxWidth().testTag("movie_play_${movie.id}")) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(action)
                    }
                    if (canPlay != true) Text(if (canPlay == null) "Đang kiểm tra nguồn phim…" else "Chưa có video để phát.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nextShortcut != null && canPlayNext == true)
                    OutlinedButton(onClick = { onPlay(nextShortcut) }, enabled = playbackEnabled) { Text("Tập tiếp theo") }
                if (movie.cloudId.isNotBlank()) OutlinedButton(onClick = onDownload, enabled = enabled && connected) {
                    Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Tải ngoại tuyến")
                }
                TextButton(onClick = onAddEpisodes, enabled = enabled, modifier = Modifier.testTag("movie_add_episodes_${movie.id}")) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Thêm tập")
                }
            }
            ExpandableSection("Danh sách tập", subtitle = "${visibleEpisodes.size} tập") {
                val row: @Composable (MovieEpisode) -> Unit = { episode ->
                    val playable = when (episode.id) {
                        recommended?.id -> canPlay
                        nextShortcut?.id -> canPlayNext
                        else -> episodePlayable(library, episode, canStream)
                    }
                    TextButton(onClick = { onPlay(episode) }, enabled = playbackEnabled && playable == true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val state = episode.watchState()
                            Text(if (state == MovieWatchState.STARTED) "${state.label} · ${watchTime(episode.positionMs)}" else state.label,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val episodesModifier = Modifier.fillMaxWidth().testTag("movie_episodes_${movie.id}")
                if (visibleEpisodes.size <= 5) {
                    Column(episodesModifier) {
                        visibleEpisodes.forEach { episode -> key(episode.id) { row(episode) } }
                    }
                } else {
                    LazyColumn(episodesModifier.heightIn(max = 300.dp)) {
                        items(visibleEpisodes, key = { it.id }) { row(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun episodePlayable(library: MovieLibrary, episode: MovieEpisode?, canStream: Boolean): Boolean? {
    if (episode == null) return false
    if (canStream) return true
    val uri = episode.mediaUri
    if (uri.isBlank()) return false
    var available by remember(episode.id, uri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(episode.id, uri) {
        available = withContext(Dispatchers.IO) { library.isAvailable(episode) }
    }
    return available
}

@Composable
private fun MovieCover(movieId: String, path: String) {
    val cover by produceState<android.graphics.Bitmap?>(null, movieId, path) {
        value = withContext(Dispatchers.IO) { MovieCoverCache.read(movieId, path) }
    }
    Surface(Modifier.fillMaxWidth().height(132.dp), shape = RoundedCornerShape(14.dp), color = OceanColors.Elevated) {
        cover?.let { Image(it.asImageBitmap(), contentDescription = "Ảnh bìa phim", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(OceanColors.Elevated, OceanColors.Base))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(42.dp), tint = OceanColors.Cyan.copy(alpha = .8f))
            }
    }
}
