package com.gemini.ocr.movie

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal enum class MovieSort(val label: String) { RECENT("Mới nhất"), NAME("Tên phim"), EPISODES("Số tập") }

internal fun sortedMovies(movies: List<MovieRecord>, query: String, sort: MovieSort): List<MovieRecord> {
    val filtered = movies.filter { it.title.contains(query.trim(), ignoreCase = true) }
    return when (sort) {
        MovieSort.RECENT -> filtered.sortedByDescending { it.confirmedAt }
        MovieSort.NAME -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
        MovieSort.EPISODES -> filtered.sortedByDescending { it.episodes.size }
    }
}

internal fun continueEpisode(movie: MovieRecord): MovieEpisode? {
    val recent = movie.episodes.filter { it.positionMs > 0 }.maxByOrNull { it.positionUpdatedAt }
        ?: return movie.episodes.firstOrNull()
    val endMargin = minOf(1000L, recent.durationMs / 20)
    if (recent.positionMs < recent.durationMs - endMargin) return recent
    return movie.episodes.getOrNull(movie.episodes.indexOf(recent) + 1) ?: recent
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val library = remember { MovieLibrary.get(context) }
    val cloud = remember { MovieCloudSync.get(context) }
    val auth = remember { VideoCloudSync.get(context) }
    val batches = remember { BatchJobs.get(context) }
    val movies by library.movies.collectAsState()
    val projects by batches.batches.collectAsState()
    val account by auth.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(MovieSort.RECENT.name) }
    var sortMenu by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf("") }
    var offlineId by rememberSaveable { mutableStateOf("") }
    var showAllProcessing by rememberSaveable { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true; notice = ""; noticeError = false
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { noticeError = true; notice = e.message ?: "Chưa hoàn tất thao tác. Bạn có thể thử lại." }
            finally { working = false }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        action { auth.completeAuthorization(result.data, result.resultCode); cloud.refreshCloud() }
    }
    val connect: () -> Unit = { action {
        val pending = auth.authorize(selectAccount = true)
        if (pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) else cloud.refreshCloud()
    } }
    val sort = MovieSort.entries.firstOrNull { it.name == sortName } ?: MovieSort.RECENT
    val visible = sortedMovies(movies.filter { it.accountEmail.isBlank() || it.accountEmail == account.accountEmail }, query, sort)
    val processing = projects.filter { p -> !BatchLibraryTarget.saved(p, movies) && p.title.contains(query.trim(), true) }
    fun openBatch(id: String = "") = context.startActivity(Intent(context, BatchActivity::class.java).putExtra("batch", id))
    fun play(movie: MovieRecord, episode: MovieEpisode) = context.startActivity(Intent(context, MoviePlaybackActivity::class.java)
        .putExtra("movie", movie.id).putExtra("episode", episode.id))
    OceanTheme {
    Surface(modifier.fillMaxSize(), color = OceanColors.Base) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 760.dp) 2 else 1
        LazyColumn(Modifier.widthIn(max = 1080.dp).fillMaxSize().testTag("movie_library"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("GEMINI CINEMA", style = MaterialTheme.typography.labelLarge, color = OceanColors.Cyan)
                        Text("Kho phim", style = MaterialTheme.typography.headlineMedium)
                        Text("Phim đã lưu và dự án đang dịch", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    OceanPrimaryButton("Dịch video mới", { openBatch() }, Modifier.testTag("movie_new_batch"))
                }
            }
            item {
                Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Tìm phim hoặc dự án") }, singleLine = true,
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
            item {
                OceanCard(Modifier.testTag("movie_drive_status")) {
                ExpandableSection("Google Drive", subtitle = when {
                        account.authorizationRequired -> "Cần kết nối lại"
                        account.busy -> "Đang đồng bộ"
                        account.accountEmail.isBlank() -> "Chưa kết nối"
                        else -> "Đã kết nối"
                }) {
                    if (account.accountEmail.isNotBlank()) Text(account.accountEmail)
                    StatusNotice(account.message, error = account.authorizationRequired,
                        onRetry = if (!working && !account.busy && account.authorizationRequired) connect else null)
                    Text("Xem ngay từ Drive hoặc tải ngoại tuyến. Khi có bản trong máy hợp lệ, ứng dụng ưu tiên bản đó.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = connect, enabled = !working && !account.busy) { Text("Chọn tài khoản Google") }
                        OutlinedButton(onClick = { action { cloud.refreshCloud() } }, enabled = !working && account.accountEmail.isNotBlank()) {
                            Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(6.dp)); Text("Đồng bộ danh mục")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(account.wifiOnly, { auth.setWifiOnly(it) }, enabled = !working)
                        Text("Chỉ tải lên qua Wi-Fi / mạng không tính phí", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (account.accountEmail.isNotBlank()) TextButton(onClick = { auth.disconnect() }, enabled = !working) { Text("Ngắt kết nối trên thiết bị này") }
                }
                }
                if (account.authorizationRequired) StatusNotice(account.message, error = true, onRetry = if (!working && !account.busy) connect else null)
                if (working || account.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (notice.isNotBlank()) StatusNotice(notice, error = noticeError)
            }
            if (processing.isNotEmpty()) item { Text("Đang chuẩn bị / xử lý · ${processing.size}", style = MaterialTheme.typography.titleMedium) }
            items((if (showAllProcessing) processing else processing.take(3)).chunked(columns), key = { "processing-${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    row.forEach { batch ->
                        ProcessingMovieCard(batch, if (columns == 1) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.weight(1f)) { openBatch(batch.id) }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
            if (processing.size > 3) item {
                TextButton(onClick = { showAllProcessing = !showAllProcessing }) {
                    Text(if (showAllProcessing) "Thu gọn dự án" else "Xem tất cả ${processing.size} dự án")
                }
            }
            item { Text("Phim đã hoàn thành · ${visible.size}", style = MaterialTheme.typography.titleMedium) }
            if (visible.isEmpty()) item {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (query.isBlank()) "Kho phim đang trống" else "Không tìm thấy phim")
                        Text(if (query.isBlank()) "Dịch video, xem lại rồi xác nhận lưu vào đây." else "Thử tên khác hoặc xóa từ khóa.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(visible.chunked(columns), key = { it.first().id }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    row.forEach { movie -> MovieCard(movie, !working, account.accountEmail.isNotBlank(),
                        if (columns == 1) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.weight(1f),
                        onPlay = { play(movie, it) }, onDownload = { action { cloud.download(movie.id) } }, onSync = { cloud.enqueue(movie.id) },
                        onAddEpisodes = { action {
                            val target = batches.createForMovie(movie.id)
                            context.startActivity(Intent(context, BatchActivity::class.java).putExtra("batch", target.id).putExtra("add_videos", true))
                        } },
                        onRemoveOffline = { offlineId = movie.id }, onDelete = { deleteId = movie.id }) }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    }
    if (deleteId.isNotBlank()) AlertDialog(onDismissRequest = { deleteId = "" }, title = { Text("Xóa phim khỏi thiết bị?") },
        text = { Text("Xóa bản trong Kho phim trên máy. Bản trên Google Drive và dự án dịch được giữ.") },
        confirmButton = { TextButton(onClick = { val id = deleteId; deleteId = ""; action { library.remove(id) } }) { Text("Xóa") } },
        dismissButton = { TextButton(onClick = { deleteId = "" }) { Text("Giữ lại") } })
    if (offlineId.isNotBlank()) AlertDialog(onDismissRequest = { offlineId = "" }, title = { Text("Giải phóng video ngoại tuyến?") },
        text = { Text("Chỉ xóa bản video của Kho phim đã đồng bộ. Bạn có thể tải lại từ Google Drive.") },
        confirmButton = { TextButton(onClick = { val id = offlineId; offlineId = ""; action { library.removeOffline(id) } }) { Text("Giải phóng") } },
        dismissButton = { TextButton(onClick = { offlineId = "" }) { Text("Giữ lại") } })
    }
}

@Composable
private fun ProcessingMovieCard(batch: BatchProject, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val completed = batch.items.count { it.state == "DONE" }
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
                if (batch.items.isNotEmpty() && completed > 0) LinearProgressIndicator(progress = { completed.toFloat() / batch.items.size },
                    modifier = Modifier.fillMaxWidth().height(4.dp), color = OceanColors.Mint, trackColor = OceanColors.Elevated)
            }
            Icon(Icons.Outlined.ChevronRight, "Mở dự án")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieCard(movie: MovieRecord, enabled: Boolean, connected: Boolean, modifier: Modifier,
    onPlay: (MovieEpisode) -> Unit, onDownload: () -> Unit, onSync: () -> Unit, onAddEpisodes: () -> Unit, onRemoveOffline: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val library = remember { MovieLibrary.get(context) }
    var availableIds by remember(movie.id) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(movie.episodes.map { it.id to it.mediaUri }) {
        availableIds = withContext(Dispatchers.IO) { movie.episodes.filter(library::isAvailable).map { it.id }.toSet() }
    }
    val recommended = continueEpisode(movie)
    val recent = movie.episodes.filter { it.positionMs > 0 }.maxByOrNull { it.positionUpdatedAt } ?: recommended
    val next = recent?.let { movie.episodes.getOrNull(movie.episodes.indexOf(it) + 1) }
    fun available(episode: MovieEpisode) = availableIds?.contains(episode.id) == true
    val canStream=connected && movie.cloudId.isNotBlank()
    Card(modifier.testTag("movie_card_${movie.id}"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = OceanColors.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MovieCover(movie.coverPath)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(movie.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${movie.episodes.size} tập · ${movie.episodes.sumOf { it.durationMs } / 60000} phút", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Tùy chọn phim") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Thêm tập mới") }, enabled = enabled, onClick = { menu = false; onAddEpisodes() })
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
            if (movie.episodes.any { it.isSourceQuality }) Text("Video gốc · phụ đề rời · không mã hóa lại", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (movie.message.isNotBlank() && (movie.syncState in setOf("FAILED", "CONFLICT") || movie.episodes.any { it.positionConflict }))
                StatusNotice(movie.message, error = true, onRetry = if (enabled && connected && movie.syncState == "FAILED") onSync else null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddEpisodes, enabled = enabled, modifier = Modifier.testTag("movie_add_episodes_${movie.id}")) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Thêm tập mới")
                }
                if (availableIds == null) Text("Đang kiểm tra nguồn phim…", style = MaterialTheme.typography.bodySmall)
                if (recommended != null && (available(recommended) || canStream)) Button(onClick = { onPlay(recommended) }, enabled = enabled) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Xem ngay")
                }
                if (movie.cloudId.isNotBlank()) OutlinedButton(onClick = onDownload, enabled = enabled && connected) {
                    Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Tải ngoại tuyến")
                }
                if (next != null && (available(next) || canStream)) OutlinedButton(onClick = { onPlay(next) }, enabled = enabled) { Text("Tập tiếp theo") }
            }
            ExpandableSection("Danh sách tập", subtitle = "${movie.episodes.size} tập đã hoàn thành") {
                movie.episodes.forEach { episode ->
                    TextButton(onClick = { onPlay(episode) }, enabled = enabled && (available(episode) || canStream), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(episode.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (episode.positionMs > 0) Text("${(episode.positionMs * 100 / episode.durationMs).coerceIn(0, 100)}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieCover(path: String) {
    val cover by produceState<android.graphics.Bitmap?>(null, path) {
        value = if (path.isBlank()) null else withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    Surface(Modifier.fillMaxWidth().height(132.dp), shape = RoundedCornerShape(14.dp), color = OceanColors.Elevated) {
        cover?.let { Image(it.asImageBitmap(), contentDescription = "Ảnh bìa phim", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(OceanColors.Elevated, OceanColors.Base))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(42.dp), tint = OceanColors.Cyan.copy(alpha = .8f))
            }
    }
}
