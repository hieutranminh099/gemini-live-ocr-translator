@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoPreview
import com.gemini.ocr.video.VideoFullscreenWindow
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoActivity
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.ui.GeminiTheme
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private var writtenProgressSequence = 0L
    override fun onStart() { super.onStart(); SessionState.videoActivityVisible.value = true }
    override fun onStop() { SessionState.videoActivityVisible.value = false; super.onStop() }
    @OptIn(ExperimentalLayoutApi::class)
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
                var switchingEpisode by remember { mutableStateOf(false) }
                val scope=rememberCoroutineScope()
                val movies by library.movies.collectAsState()
                val currentMovie=movies.firstOrNull{it.id==movieId}
                val currentEpisode=currentMovie?.episodes?.firstOrNull{it.id==episodeId}
                val account by VideoCloudSync.get(this).state.collectAsState()
                val ownerAccount = loaded?.accountEmail.orEmpty()
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                var lastLandscape by rememberSaveable { mutableStateOf(false) }
                val landscape = loaded?.project?.let { it.width > it.height } ?: lastLandscape
                SideEffect { if (loaded != null) lastLandscape = landscape }
                // This owner survives the nullable player's source verification/cloud loading gap.
                VideoFullscreenWindow(fullscreen, landscape)
                fun adjacentEpisode(direction: Int, position: Long): Boolean {
                    val selection = loaded ?: return false
                    val record = currentMovie ?: return false
                    val next = movieAdjacentEpisode(record, episodeId, direction) ?: return false
                    if (switchingEpisode || editingBusy || !ownsAccount(selection.accountEmail)) return false
                    val outgoingId = episodeId
                    switchingEpisode = true
                    scope.launch {
                        try {
                            // Persist the outgoing player's exact position before invalidating its generation.
                            // Any previously queued, older observations are rejected by the sequence guard.
                            withContext(Dispatchers.IO) {
                                synchronized(progressLock) {
                                    require(selection.generation == playbackGeneration.get() && ownsAccount(selection.accountEmail)) {
                                        "Phiên phát hoặc tài khoản đã thay đổi."
                                    }
                                    val sequence = progressSequence.incrementAndGet()
                                    library.progress(movieId, outgoingId, position, System.currentTimeMillis(),
                                        selection.accountEmail, selection.revision)
                                    writtenProgressSequence = sequence
                                }
                                if (ownsAccount(selection.accountEmail))
                                    MovieCloudSync.get(this@MoviePlaybackActivity).enqueueProgress(movieId, replace = true)
                            }
                            if (selection.generation == playbackGeneration.get() && ownsAccount(selection.accountEmail)) {
                                editProjectId = ""; editingRevision = ""
                                loaded = null
                                episodeId = next.id
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Chưa chuyển được tập. Vị trí xem vẫn được giữ." }
                        finally { switchingEpisode = false }
                    }
                    return true
                }
                BackHandler(fullscreen) { fullscreen = false }
                LaunchedEffect(account.accountEmail, ownerAccount) {
                    if (ownerAccount.isNotBlank() && ownerAccount != account.accountEmail) finish()
                }
                LaunchedEffect(movieId, episodeId, currentEpisode?.source?.revision, currentMovie?.cloudId, loadAttempt) {
                    val generation = playbackGeneration.incrementAndGet()
                    loaded = null
                    error = ""
                    try {
                        val selection = withContext(Dispatchers.IO) {
                            loadMovie(library, movieId, episodeId, generation)
                        }
                        require(ownsAccount(selection.accountEmail)) { "Tài khoản Google đã thay đổi." }
                        loaded = selection
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Không đọc được video."
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.padding(12.dp))) {
                        if (!fullscreen) {
                            TextButton(onClick = { finish() }) { Text("Về Kho phim") }
                            if ((currentMovie?.episodes?.size ?: 0) > 1)
                                Text("Vuốt xuống để xem tập tiếp · vuốt lên để về tập trước", style = MaterialTheme.typography.bodySmall)
                            if(currentEpisode?.source!=null) {
                                Text("Video gốc · phụ đề riêng · không mã hóa lại",style=MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick={
                                        editingBusy=true;error=""
                                        scope.launch {
                                            try {
                                                val edit=if(editProjectId.isBlank())library.createSourceEditor(movieId,episodeId)
                                                    else withContext(Dispatchers.IO){VideoStore(this@MoviePlaybackActivity).load(editProjectId)}
                                                if(editProjectId.isBlank())editingRevision=currentEpisode.source!!.revision
                                                editProjectId=edit.id
                                                startActivity(Intent(this@MoviePlaybackActivity,VideoActivity::class.java)
                                                    .putExtra("project",edit.id).putExtra("ux_step","review"))
                                            } catch(e:CancellationException){throw e}
                                            catch(e:Exception){error=e.message?:"Chưa mở được trình sửa phụ đề."}
                                            finally{editingBusy=false}
                                        }
                                    },enabled=!editingBusy && loaded?.isLocal==true) {Text("Sửa phụ đề")}
                                    if(loaded?.isLocal==false)Text("Tải ngoại tuyến để sửa phụ đề.",style=MaterialTheme.typography.bodySmall)
                                    if(editProjectId.isNotBlank())TextButton(onClick={
                                        editingBusy=true;error=""
                                        scope.launch {
                                            try {
                                                val edit=withContext(Dispatchers.IO){VideoStore(this@MoviePlaybackActivity).load(editProjectId)}
                                                library.refreshSourceSnapshot(movieId,episodeId,edit,editingRevision)
                                                editProjectId="";editingRevision=""
                                            } catch(e:CancellationException){throw e}
                                            catch(e:Exception){error=e.message?:"Chưa cập nhật phụ đề; bản cũ vẫn được giữ."}
                                            finally{editingBusy=false}
                                        }
                                    },enabled=!editingBusy){Text("Áp dụng phụ đề đã kiểm tra")}
                                }
                            }
                        }
                        if (error.isNotBlank()) {
                            val retry: (() -> Unit)? = if (loaded == null && movieId.isNotBlank()) {{ loadAttempt++ }} else null
                            StatusNotice(error,error=true,onRetry=retry)
                        } else if (loaded == null) {
                            Text("Đang mở video…", style=MaterialTheme.typography.bodySmall)
                        }
                        loaded?.let { selection ->
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                VideoPreview(selection.project, null, false, false, {}, {}, fullscreen, { fullscreen = it },
                                    playbackOnly = true,
                                    initialPositionMs = selection.position.coerceIn(0, selection.project.durationMs),
                                    onPosition = { writeProgress(library, movieId, selection.episodeId, selection, it) },
                                    dataSourceFactory = selection.factory,
                                    onPositionFlushed = { syncFinalProgress(library, movieId, selection.episodeId, selection) },
                                    onEpisodeSwipe = if ((currentMovie?.episodes?.size ?: 0) > 1) ::adjacentEpisode else null,
                                    managesFullscreenWindow = false)
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
            val local = localProject(library, record, episode)
            return LoadedMovie(local, episode?.positionMs ?: 0, record?.accountEmail.orEmpty(),
                episode?.source?.revision, null, generation, episodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (record == null || episode == null || record.cloudId.isBlank()) throw e
        }
        val cloud = MovieCloudSync.get(this).preparePlayback(movieId, episodeId)
        return LoadedMovie(cloud.project, cloud.episode.positionMs, cloud.accountEmail,
            cloud.episode.source?.revision, cloud.dataSourceFactory, generation, episodeId)
    }

    private suspend fun localProject(library: MovieLibrary, record: MovieRecord?, episode: MovieEpisode?): VideoProject {
        if (episode?.source != null) {
            val source = episode.source
            require(library.isAvailable(episode)) { "Không còn đọc được video nguồn trong máy." }
            val uri = Uri.parse(source.uri)
            require(MovieSourceAccess.inspect(this, uri) == (source.sha256 to source.bytes)) {
                "Video nguồn đã bị thay thế. Phụ đề đã lưu vẫn được giữ."
            }
            val metadata = VideoDecoder.metadata(this, uri)
            require(kotlin.math.abs(metadata.durationMs - episode.durationMs) <= 1000) { "Thời lượng nguồn đã thay đổi." }
            return source.project(episode)
        }
        val path = episode?.path ?: intent.getStringExtra("preview").orEmpty()
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(filesDir.canonicalFile.toPath()) && file.isFile) {
            "Không có bản ngoại tuyến hợp lệ."
        }
        val uri = Uri.fromFile(file)
        val metadata = VideoDecoder.metadata(this, uri)
        require(episode == null || kotlin.math.abs(metadata.durationMs - episode.durationMs) <= 1000) {
            "Thời lượng bản ngoại tuyến đã thay đổi."
        }
        return VideoProject(episode?.id ?: file.name, episode?.title ?: record?.title ?: "Xem kết quả",
            uri.toString(), metadata.durationMs, metadata.width, metadata.height)
    }

    private fun writeProgress(library: MovieLibrary, movieId: String, episodeId: String, movie: LoadedMovie, position: Long) {
        if (movieId.isBlank()) return
        val sequence = progressSequence.incrementAndGet()
        val observedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            synchronized(progressLock) {
                // Reject writes queued by an old player or overtaken by a newer observation.
                if (movie.generation != playbackGeneration.get() || sequence <= writtenProgressSequence ||
                    !ownsAccount(movie.accountEmail)) return@synchronized
                library.progress(movieId, episodeId, position, observedAt, movie.accountEmail, movie.revision)
                writtenProgressSequence = sequence
            }
        }
    }

    private fun syncFinalProgress(library: MovieLibrary, movieId: String, episodeId: String, movie: LoadedMovie) {
        if (movieId.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (movie.generation != playbackGeneration.get() || !ownsAccount(movie.accountEmail)) return@launch
            val record = library.find(movieId) ?: return@launch
            val episode = record.episodes.firstOrNull { it.id == episodeId } ?: return@launch
            if (record.accountEmail == movie.accountEmail && episode.source?.revision == movie.revision) {
                MovieCloudSync.get(this@MoviePlaybackActivity).enqueueProgress(movieId, replace = true)
            }
        }
    }
}

/** The saved list defines playback order. Do not wrap to the beginning or skip an unavailable source. */
internal fun movieAdjacentEpisode(movie: MovieRecord, episodeId: String, direction: Int): MovieEpisode? {
    if (direction != -1 && direction != 1) return null
    val index = movie.episodes.indexOfFirst { it.id == episodeId }
    return if (index < 0) null else movie.episodes.getOrNull(index + direction)
}

private data class LoadedMovie(val project: VideoProject, val position: Long, val accountEmail: String,
    val revision: String?, val factory: DataSource.Factory?, val generation: Long, val episodeId: String) {
    val isLocal get() = factory == null
}
