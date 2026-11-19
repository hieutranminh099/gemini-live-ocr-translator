@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.MoviePreviewControls
import com.gemini.ocr.video.VideoPreview
import com.gemini.ocr.video.VideoFullscreenWindow
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoActivity
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoTimeline
import com.gemini.ocr.video.VideoTranslationClient
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.ui.GeminiTheme
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import androidx.media3.datasource.DataSource

/** Original media + frozen timeline, or legacy rendered MP4. Never starts OCR/translation. */
class MoviePlaybackActivity : ComponentActivity() {
    private val playbackGeneration = AtomicLong()
    private val progressSequence = AtomicLong()
    private val progressLock = Any()
    private val writtenProgressSequence = mutableMapOf<String, Long>()
    private val pendingPositions = java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Long>>()
    override fun onStart() { super.onStart(); SessionState.videoActivityVisible.value = true }
    override fun onStop() { SessionState.videoActivityVisible.value = false; super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = MovieLibrary.get(this)
        val movieId = intent.getStringExtra("movie").orEmpty()
        val initialEpisodeId = intent.getStringExtra("episode").orEmpty()
        setContent {
            GeminiTheme {
                var episodeId by rememberSaveable { mutableStateOf(initialEpisodeId) }
                var loaded by remember { mutableStateOf<LoadedMovie?>(null) }
                var error by remember { mutableStateOf("") }
                var loadAttempt by remember { mutableIntStateOf(0) }
                var editProjectId by rememberSaveable { mutableStateOf("") }
                var editingRevision by rememberSaveable { mutableStateOf("") }
                var editingBusy by remember { mutableStateOf(false) }
                var playbackSettings by remember { mutableStateOf(MoviePlaybackSettings.load(this@MoviePlaybackActivity)) }
                var showPlaybackSettings by rememberSaveable { mutableStateOf(false) }
                var caption by remember { mutableStateOf<PlaybackCaption?>(null) }
                var captionText by remember { mutableStateOf("") }
                var captionMessage by remember { mutableStateOf("") }
                val scope=rememberCoroutineScope()
                val positionReader = remember { arrayOfNulls<() -> Long>(1) }
                val catalogLoaded by library.catalogLoaded.collectAsState()
                val canLoadSource = movieId.isBlank() || catalogLoaded
                val movies by library.movies.collectAsState()
                val currentMovie = remember(movies, movieId) { movies.firstOrNull { it.id == movieId } }
                val episodes = currentMovie?.episodes.orEmpty()
                val episodeIndex = remember(episodes, episodeId) { episodes.indexOfFirst { it.id == episodeId } }
                val currentEpisode = episodes.getOrNull(episodeIndex)
                val previousEpisode = episodes.getOrNull(episodeIndex - 1)
                val nextEpisode = if (episodeIndex >= 0) episodes.getOrNull(episodeIndex + 1) else null
                val account by VideoCloudSync.get(this).state.collectAsState()
                val ownerAccount = loaded?.accountEmail.orEmpty()
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                var lastLandscape by rememberSaveable { mutableStateOf(false) }
                val landscape = loaded?.project?.let { it.width > it.height } ?: lastLandscape
                SideEffect { if (loaded != null) lastLandscape = landscape }
                // This owner survives the nullable player's source verification/cloud loading gap.
                VideoFullscreenWindow(fullscreen, landscape)
                val localNeighbors by key(previousEpisode?.id, previousEpisode?.mediaUri, nextEpisode?.id, nextEpisode?.mediaUri) {
                    produceState<Set<String>>(emptySet()) {
                        value = withContext(Dispatchers.IO) {
                            listOfNotNull(previousEpisode, nextEpisode).filter(library::isAvailable).map { it.id }.toSet()
                        }
                    }
                }
                fun ready(episode: MovieEpisode?): Boolean =
                    episode != null && (episode.id in localNeighbors ||
                        currentMovie?.let { it.cloudId.isNotBlank() && ownsAccount(it.accountEmail) } == true)
                fun adjacentEpisode(direction: Int, position: Long, onlyLocal: Boolean = false): Boolean {
                    val record = currentMovie ?: return false
                    // Multiple choices can arrive before the header recomposes.
                    val next = movieAdjacentEpisode(record, episodeId, direction) ?: return false
                    if (editingBusy || caption != null || !ownsAccount(record.accountEmail) ||
                        (onlyLocal && next.id !in localNeighbors)) return false
                    val selection = loaded?.takeIf { it.episodeId == episodeId }
                    if (selection != null) {
                        writeProgress(library, movieId, selection, position, flush = true)
                    }
                    playbackGeneration.incrementAndGet()
                    positionReader[0] = null
                    editProjectId = ""; editingRevision = ""
                    loaded = null
                    episodeId = next.id
                    return true
                }
                BackHandler(fullscreen) { fullscreen = false }
                LaunchedEffect(account.accountEmail, ownerAccount) {
                    if (ownerAccount.isNotBlank() && ownerAccount != account.accountEmail) finish()
                }
                val sourceRevision = currentEpisode?.source?.revision
                val mediaUri = currentEpisode?.mediaUri
                val duration = currentEpisode?.durationMs
                val catalogAccount = currentMovie?.accountEmail
                val cloudId = currentMovie?.cloudId.orEmpty()
                LaunchedEffect(movieId, episodeId, canLoadSource, sourceRevision, mediaUri, duration, catalogAccount, cloudId, loadAttempt) {
                    if (!canLoadSource) return@LaunchedEffect
                    val existing = loaded
                    val source = currentEpisode?.source
                    if (existing != null && currentEpisode != null && catalogAccount != null &&
                        ownsAccount(existing.accountEmail) && ownsAccount(catalogAccount) &&
                        existing.canKeep(currentEpisode, cloudId)
                    ) {
                        if (source != null && existing.revision != source.revision) {
                            loaded = existing.copy(accountEmail = catalogAccount, revision = source.revision,
                                project = existing.project.copy(cues = source.cues, style = source.style,
                                    roi = source.sourceRoi, sourceMode = source.sourceMode))
                        } else if (catalogAccount != existing.accountEmail) {
                            loaded = existing.copy(accountEmail = catalogAccount)
                        }
                        return@LaunchedEffect
                    }
                    val generation = playbackGeneration.incrementAndGet()
                    positionReader[0] = null
                    loaded = null
                    error = ""
                    try {
                        val selection = withContext(Dispatchers.IO) {
                            loadMovie(library, movieId, episodeId, generation)
                        }
                        require(ownsAccount(selection.accountEmail)) { "Tài khoản Google đã thay đổi." }
                        if (generation == playbackGeneration.get()) loaded = selection
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Không đọc được video."
                    }
                }
                fun currentPosition(): Long =
                    positionReader[0]?.invoke() ?: pendingPositions[episodeId]?.second ?: currentEpisode?.positionMs ?: 0

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().then(
                        if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                        if (!fullscreen) {
                            val source = currentEpisode?.source
                            fun editSubtitles() {
                                if (source == null || editingBusy || loaded?.isLocal != true) return
                                editingBusy = true
                                error = ""
                                scope.launch {
                                    try {
                                        val edit = if (editProjectId.isBlank()) library.createSourceEditor(movieId, episodeId)
                                            else withContext(Dispatchers.IO) { VideoStore(this@MoviePlaybackActivity).load(editProjectId) }
                                        if (editProjectId.isBlank()) editingRevision = source.revision
                                        editProjectId = edit.id
                                        startActivity(Intent(this@MoviePlaybackActivity, VideoActivity::class.java)
                                            .putExtra("project", edit.id).putExtra("ux_step", "review"))
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Chưa mở được trình sửa phụ đề."
                                    } finally {
                                        editingBusy = false
                                    }
                                }
                            }
                            fun applySubtitles() {
                                if (editingBusy) return
                                editingBusy = true
                                error = ""
                                scope.launch {
                                    try {
                                        val edit = withContext(Dispatchers.IO) { VideoStore(this@MoviePlaybackActivity).load(editProjectId) }
                                        library.refreshSourceSnapshot(movieId, episodeId, edit, editingRevision)
                                        editProjectId = ""
                                        editingRevision = ""
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Chưa cập nhật phụ đề; bản cũ vẫn được giữ."
                                    } finally {
                                        editingBusy = false
                                    }
                                }
                            }
                            MoviePlayerHeader(
                                title = currentMovie?.title ?: loaded?.project?.title ?: if (movieId.isBlank()) "Xem kết quả" else "Xem phim",
                                episodeTitle = currentEpisode?.title.orEmpty(),
                                episodeNumber = episodeIndex + 1,
                                episodeCount = episodes.size,
                                previousEnabled = previousEpisode != null && !editingBusy,
                                nextEnabled = nextEpisode != null && !editingBusy,
                                onBack = ::finish,
                                onPrevious = { adjacentEpisode(-1, currentPosition()) },
                                onNext = { adjacentEpisode(1, currentPosition()) },
                                subtitlesLocal = loaded?.isLocal?.takeIf { source != null },
                                onEdit = ::editSubtitles,
                                onApply = if (source != null && editProjectId.isNotBlank()) ::applySubtitles else null,
                                editingBusy = editingBusy
                            )
                        }
                        if (loaded == null) {
                            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (error.isBlank()) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                                        Text("Đang mở video…", style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else {
                                    val retry: (() -> Unit)? = if (movieId.isNotBlank()) ({ loadAttempt++ }) else null
                                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        StatusNotice(error, error = true, onRetry = retry)
                                    }
                                }
                            }
                        } else if (error.isNotBlank()) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { StatusNotice(error, error = true) }
                        }
                        loaded?.let { selection ->
                            val modes = remember(selection.project) { movieSubtitleModes(selection.project) }
                            val mode = playbackSettings.mode.takeIf { it in modes } ?: modes.firstOrNull() ?: MovieSubtitleMode.OFF
                            val display = remember(selection.project, mode, playbackSettings.style(selection.project.style)) {
                                selection.project.copy(cues = movieDisplayCues(selection.project.cues, mode),
                                    style = playbackSettings.style(selection.project.style))
                            }
                            if (showPlaybackSettings) MoviePlaybackSettingsDialog(
                                playbackSettings, modes, selection.project.style.displayStyle(),
                                onChange = { playbackSettings = it }, onDismiss = {
                                    playbackSettings.save(this@MoviePlaybackActivity)
                                    showPlaybackSettings = false
                                })
                            caption?.let { edit ->
                                val canUndo = currentEpisode?.captionUndo?.let { it.revision == edit.revision &&
                                    it.previous.id == edit.cue.id } == true
                                val settings = remember { AppSettings(this@MoviePlaybackActivity) }
                                fun saveCaption(text: String?) {
                                    editingBusy = true
                                    captionMessage = "Đang lưu…"
                                    scope.launch {
                                        try {
                                            require(playbackGeneration.get() == edit.generation && ownsAccount(selection.accountEmail))
                                            library.savePlaybackCaption(movieId, edit.episodeId, edit.revision,
                                                edit.cue.id, text, edit.position)
                                            caption = null
                                        } catch (cancel: CancellationException) { throw cancel }
                                        catch (failure: Exception) { captionMessage = failure.message ?: "Chưa lưu được câu." }
                                        finally { editingBusy = false }
                                    }
                                }
                                MovieCaptionDialog(edit.cue, captionText, { captionText = it }, editingBusy, captionMessage,
                                    canRetry = settings.hasGeminiVideoKey(), canUndo = canUndo,
                                    onRetry = {
                                        editingBusy = true
                                        captionMessage = "Đang lấy gợi ý…"
                                        scope.launch {
                                            try {
                                                val request = movieCaptionRequest(selection.project, edit.cue.id)
                                                val reply = VideoTranslationClient(this@MoviePlaybackActivity).translate(
                                                    settings.effectiveStandaloneApiKey(), request, settings.profile, settings.localGlossary
                                                ) {
                                                    currentCoroutineContext().ensureActive()
                                                    check(!SessionState.running.value) { "Dừng Live trước khi yêu cầu dịch lại." }
                                                    check(playbackGeneration.get() == edit.generation && ownsAccount(selection.accountEmail))
                                                }
                                                require(caption === edit && loaded?.revision == edit.revision) { "Phụ đề đã đổi; gợi ý cũ bị bỏ qua." }
                                                captionText = requireNotNull(reply.accepted[edit.cue.id]) { "Gemini chưa trả lời cho câu này." }
                                                captionMessage = "Xem lại gợi ý rồi bấm Lưu để áp dụng."
                                            } catch (cancel: CancellationException) { throw cancel }
                                            catch (failure: Exception) { captionMessage = failure.message ?: "Chưa nhận được gợi ý." }
                                            finally { editingBusy = false }
                                        }
                                    }, onUndo = { saveCaption(null) }, onSave = { saveCaption(captionText) },
                                    onDismiss = { caption = null })
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                VideoPreview(selection.project, null, false, false, {}, {}, fullscreen, { fullscreen = it },
                                    playbackOnly = true,
                                    initialPositionMs = movieResumePosition(selection.position, selection.project.durationMs),
                                    onPosition = { writeProgress(library, movieId, selection, it) },
                                    dataSourceFactory = selection.factory,
                                    onPositionFlushed = {
                                        pendingPositions[selection.episodeId]?.let { (_, position) ->
                                            writeProgress(library, movieId, selection, position, flush = true)
                                        }
                                    },
                                    onEpisodeSwipe = if (previousEpisode != null || nextEpisode != null) { direction, position ->
                                        adjacentEpisode(direction, position)
                                    } else null,
                                    onPositionReader = { reader ->
                                        if (selection.generation == playbackGeneration.get()) positionReader[0] = reader
                                    },
                                    managesFullscreenWindow = false,
                                    movieControls = MoviePreviewControls(display, playbackSettings.appearance(), playbackSettings.controlTimeout,
                                        ready(previousEpisode), ready(nextEpisode),
                                        onSettings = { showPlaybackSettings = true },
                                        onEnded = { position ->
                                            if (playbackSettings.autoNext) adjacentEpisode(1, position, onlyLocal = true)
                                        },
                                        onCaptionEdit = selection.revision?.takeIf { selection.isLocal && movieId.isNotBlank() }
                                            ?.let { revision -> { position ->
                                                VideoTimeline.active(selection.project.cues, position)?.let { cue ->
                                                    caption = PlaybackCaption(selection.episodeId, revision, cue, position, selection.generation)
                                                    captionText = cue.translation
                                                    captionMessage = ""
                                                }
                                            } }))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ownsAccount(account: String): Boolean =
        account.isBlank() || account == VideoCloudSync.get(this).state.value.accountEmail

    private suspend fun loadMovie(library: MovieLibrary, movieId: String, episodeId: String, generation: Long): LoadedMovie {
        val record = library.movies.value.firstOrNull { it.id == movieId }
        require(record == null || ownsAccount(record.accountEmail)) { "Chọn đúng tài khoản Google của phim này." }
        val episode = record?.episodes?.firstOrNull { it.id == episodeId }
        if (movieId.isNotBlank()) require(episode != null) { "Không tìm thấy tập phim trong tài khoản hiện tại." }
        try {
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            val local = localProject(library, record, episode)
            MoviePlaybackTrace.emit("source_ready", started)
            return LoadedMovie(local, resumePosition(episode), record?.accountEmail.orEmpty(),
                episode?.source?.revision, null, generation, episodeId, episode?.source?.sha256,
                episode?.mediaUri.orEmpty(), episode?.durationMs, "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (record == null || episode == null || record.cloudId.isBlank()) throw e
        }
        val cloud = MovieCloudSync.get(this).preparePlayback(movieId, episodeId)
        return LoadedMovie(cloud.project, resumePosition(cloud.episode), cloud.accountEmail,
            cloud.episode.source?.revision, cloud.dataSourceFactory, generation, episodeId, cloud.episode.source?.sha256,
            "", cloud.episode.durationMs, requireNotNull(record).cloudId)
    }

    private suspend fun localProject(library: MovieLibrary, record: MovieRecord?, episode: MovieEpisode?): VideoProject {
        if (episode?.source != null) {
            val source = episode.source
            require(library.isAvailable(episode)) { "Không còn đọc được video nguồn trong máy." }
            val uri = Uri.parse(source.uri)
            val hashStarted = android.os.SystemClock.elapsedRealtimeNanos()
            val fingerprint = MovieSourceAccess.inspect(this, uri)
            MoviePlaybackTrace.emit("source_hash", hashStarted)
            require(fingerprint == (source.sha256 to source.bytes)) {
                "Video nguồn đã bị thay thế. Phụ đề đã lưu vẫn được giữ."
            }
            val metadataStarted = android.os.SystemClock.elapsedRealtimeNanos()
            val metadata = MovieVerifiedMetadata.process.get(fingerprint) { VideoDecoder.metadata(this, uri) }
            MoviePlaybackTrace.emit("metadata", metadataStarted)
            require(kotlin.math.abs(metadata.durationMs - episode.durationMs) <= 1000) { "Thời lượng nguồn đã thay đổi." }
            return source.project(episode)
        }
        val path = episode?.path ?: intent.getStringExtra("preview").orEmpty()
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(filesDir.canonicalFile.toPath()) && file.isFile) {
            "Không có bản ngoại tuyến hợp lệ."
        }
        val uri = Uri.fromFile(file)
        val metadataStarted = android.os.SystemClock.elapsedRealtimeNanos()
        val metadata = VideoDecoder.metadata(this, uri)
        MoviePlaybackTrace.emit("metadata", metadataStarted)
        require(episode == null || kotlin.math.abs(metadata.durationMs - episode.durationMs) <= 1000) {
            "Thời lượng bản ngoại tuyến đã thay đổi."
        }
        return VideoProject(episode?.id ?: file.name, episode?.title ?: record?.title ?: "Xem kết quả",
            uri.toString(), metadata.durationMs, metadata.width, metadata.height)
    }

    private fun resumePosition(episode:MovieEpisode?):Long {
        if(episode==null)return 0
        val pending=pendingPositions[episode.id]
        return if(pending!=null && pending.first>=episode.positionUpdatedAt) pending.second else episode.positionMs
    }

    private fun writeProgress(library: MovieLibrary, movieId: String, movie: LoadedMovie, position: Long, flush: Boolean = false) {
        if (movieId.isBlank() || movie.generation != playbackGeneration.get()) return
        val sequence = progressSequence.incrementAndGet()
        val observedAt = System.currentTimeMillis()
        pendingPositions[movie.episodeId] = observedAt to position
        val app = applicationContext
        progressScope.launch {
            try {
                synchronized(progressLock) {
                    // An observation captured while leaving the player must survive Activity destruction.
                    if ((!flush && movie.generation != playbackGeneration.get()) ||
                        sequence <= (writtenProgressSequence[movie.episodeId] ?: 0L) || !ownsAccount(movie.accountEmail)) {
                        return@synchronized
                    }
                    library.progress(movieId, movie.episodeId, position, observedAt, movie.accountEmail, movie.revision)
                    writtenProgressSequence[movie.episodeId] = sequence
                    if (flush) MovieCloudSync.get(app).enqueueProgress(movieId, replace = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MoviePlayback", "Could not save playback progress (${e.javaClass.simpleName})")
            }
        }
    }

    private companion object {
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
private fun MoviePlayerHeader(
    title: String,
    episodeTitle: String,
    episodeNumber: Int,
    episodeCount: Int,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    subtitlesLocal: Boolean?,
    onEdit: () -> Unit,
    onApply: (() -> Unit)?,
    editingBusy: Boolean
) {
    var subtitleMenuOpen by remember(episodeNumber, subtitlesLocal) { mutableStateOf(false) }
    val hasEpisodeControls = episodeCount > 1 && episodeNumber in 1..episodeCount
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val inlineEpisodeControls = maxWidth >= 560.dp && LocalDensity.current.fontScale <= 1.25f
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Về Kho phim")
                    }
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (episodeTitle.isNotBlank() && episodeTitle != title) {
                            Text(episodeTitle, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (hasEpisodeControls && inlineEpisodeControls) {
                        MovieEpisodeStepper(episodeNumber, episodeCount, previousEnabled, nextEnabled, onPrevious, onNext)
                    }
                    if (subtitlesLocal != null) {
                        Box {
                            IconButton(onClick = { subtitleMenuOpen = true }, enabled = !editingBusy) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Tùy chọn phụ đề")
                            }
                            DropdownMenu(expanded = subtitleMenuOpen, onDismissRequest = { subtitleMenuOpen = false }) {
                                if (subtitlesLocal) {
                                    DropdownMenuItem(text = { Text("Sửa phụ đề") }, onClick = {
                                        subtitleMenuOpen = false
                                        onEdit()
                                    })
                                } else {
                                    Text("Tải ngoại tuyến để sửa phụ đề.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                if (hasEpisodeControls && !inlineEpisodeControls) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MovieEpisodeStepper(episodeNumber, episodeCount, previousEnabled, nextEnabled, onPrevious, onNext)
                    }
                }
                if (onApply != null) {
                    TextButton(onClick = onApply, enabled = !editingBusy, modifier = Modifier.align(Alignment.End)) {
                        Text(if (editingBusy) "Đang xử lý…" else "Áp dụng phụ đề đã kiểm tra")
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieEpisodeStepper(number: Int, total: Int, previousEnabled: Boolean, nextEnabled: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = previousEnabled) {
            Icon(Icons.Outlined.SkipPrevious, contentDescription = "Tập trước")
        }
        Text("Tập $number / $total", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        IconButton(onClick = onNext, enabled = nextEnabled) {
            Icon(Icons.Outlined.SkipNext, contentDescription = "Tập tiếp")
        }
    }
}

/** The saved list defines playback order. Do not wrap to the beginning or skip an unavailable source. */
internal fun movieAdjacentEpisode(movie: MovieRecord, episodeId: String, direction: Int): MovieEpisode? {
    if (direction != -1 && direction != 1) return null
    val index = movie.episodes.indexOfFirst { it.id == episodeId }
    return if (index < 0) null else movie.episodes.getOrNull(index + direction)
}

internal data class LoadedMovie(val project: VideoProject, val position: Long, val accountEmail: String,
    val revision: String?, val factory: DataSource.Factory?, val generation: Long, val episodeId: String,
    val sourceHash: String?, val mediaUri: String, val catalogDuration: Long?, val cloudId: String) {
    val isLocal get() = factory == null

    fun canKeep(episode: MovieEpisode, currentCloudId: String): Boolean {
        if (episode.id != episodeId || episode.durationMs != catalogDuration) return false
        val source = episode.source
        if (!isLocal) return currentCloudId == cloudId && source?.revision == revision
        if (episode.mediaUri != mediaUri || source?.sha256 != sourceHash) return false
        return source == null || (source.width == project.width && source.height == project.height)
    }
}

private data class PlaybackCaption(val episodeId: String, val revision: String, val cue: VideoCue,
    val position: Long, val generation: Long)
