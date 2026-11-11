package com.gemini.ocr.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoStagedPipeline
import com.gemini.ocr.video.VideoPipelineCheckpoint
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoExporter
import com.gemini.ocr.video.VideoApiRecovery
import com.gemini.ocr.video.VideoSmartBatchSettings
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoTranslationPlan
import com.gemini.ocr.video.VideoHybridWorkflow
import com.gemini.ocr.movie.MovieSourceInput
import com.gemini.ocr.video.preferredVideoTextTrack
import com.gemini.ocr.video.withOfflinePolishPolicy
import com.gemini.ocr.video.videoSourceUsesRoi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private class BatchStagePaused : RuntimeException()

/** One batch reservation owns sequential media work and bounded per-video translation workers. */
class BatchJobs internal constructor(
    private val context: Context,
    private val videos: VideoJobs = VideoJobs.get(context),
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) }
) {
    private val store = BatchStore(context)
    private val videoStore = VideoStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val edits = Mutex()
    private val writes = Any()
    private val mutableBatches = MutableStateFlow<List<BatchProject>>(emptyList())
    private val mutableBusy = MutableStateFlow<String?>(null)
    val batches: StateFlow<List<BatchProject>> = mutableBatches
    val busy: StateFlow<String?> = mutableBusy
    private var worker: Job? = null
    private var stopState = "PAUSED"
    @Volatile private var stopRequested = false
    init { refresh() }

    fun refresh() { mutableBatches.value = store.list().map { if (it.id != busy.value) BatchPlan.interrupted(it) else it } }
    private fun persist(batch: BatchProject): BatchProject = synchronized(writes) {
        // A late worker checkpoint must not overwrite a durable user stop intent with RUNNING.
        val next = if (stopRequested && busy.value == batch.id)
            batch.copy(state = stopState, autoRetryAtMs = 0) else batch
        store.save(next).also { refresh() }
    }
    private suspend fun edit(id: String, transform: suspend (BatchProject) -> BatchProject): BatchProject = withContext(Dispatchers.IO) {
        edits.withLock {
            check(busy.value == null && videos.busy.value == null) { "Tạm dừng xử lý trước khi chỉnh dự án hàng loạt." }
            persist(transform(store.load(id)))
        }
    }
    suspend fun create(title: String, kind: BatchKind): BatchProject = withContext(Dispatchers.IO) {
        edits.withLock { check(busy.value == null); store.save(store.create(title, kind).copy(delivery = BatchDelivery.SOURCE_LIBRARY, detectNewSources = true)).also { refresh() } }
    }
    suspend fun createForMovie(movieId: String): BatchProject = withContext(Dispatchers.IO) {
        edits.withLock {
            check(busy.value == null && videos.busy.value == null) { "Tạm dừng xử lý trước khi thêm tập mới." }
            val library = com.gemini.ocr.movie.MovieLibrary.get(context)
            val movie = requireNotNull(library.find(movieId)) {
                "Bộ phim không còn trong tài khoản hiện tại."
            }
            require(movie.episodes.all(library::isAvailable)) { "Tải bộ phim ngoại tuyến trước khi thêm tập mới để giữ đầy đủ các tập khi đồng bộ." }
            val pending = store.list().firstOrNull { it.targetMovieId == movieId && !BatchLibraryTarget.saved(it, listOf(movie)) }
            if (pending != null) return@withLock pending
            store.save(store.create(movie.title, BatchKind.SERIES).copy(
                targetMovieId = movieId, delivery = BatchDelivery.SOURCE_LIBRARY, detectNewSources = true,
                message = "Các tập mới sẽ được thêm vào bộ ${movie.title}. Các tập đã lưu được giữ nguyên.")).also { refresh() }
        }
    }
    suspend fun add(id: String, uris: List<Uri>): BatchProject = edit(id) { old ->
        var batch = old
        val failures = mutableListOf<String>()
        val imported = mutableListOf<BatchItem>()
        val occupiedEpisodes = old.items.mapTo(mutableSetOf()) { it.episode }
        val existing = old.items.mapNotNull { runCatching { videoStore.load(it.projectId) }.getOrNull() }
            .flatMap { listOf(it.originalSourceUri, it.uri) }.filter { it.isNotBlank() }.toMutableSet()
        if(old.targetMovieId.isNotBlank()) {
            val movie=requireNotNull(com.gemini.ocr.movie.MovieLibrary.get(context).find(old.targetMovieId)) { "Bộ phim không còn trong tài khoản hiện tại." }
            existing.addAll(movie.episodes.map { it.mediaUri }.filter { it.isNotBlank() })
        }
        for (uri in uris.distinct()) {
            currentCoroutineContext().ensureActive()
            if (uri.toString() in existing) continue
            var name = "Video"
            try {
                require(uri.scheme == "content" || uri.scheme == "file") { "Chọn video bằng trình chọn tệp Android." }
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                var bytes = 0L
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                            if (s >= 0 && !cursor.isNull(s)) bytes = cursor.getLong(s).coerceAtLeast(0)
                        }
                    }
                } else { val file = File(requireNotNull(uri.path)); name = file.name; bytes = file.length() }
                val project = videos.create(uri, name)
                // This is written before any worker can run or any completed backup can be queued.
                File(videoStore.folder(project.id), "batch-owner").writeText(id, Charsets.UTF_8)
                val embedded = if (batch.detectNewSources) preferredVideoTextTrack(runCatching { videos.tracks(project) }.getOrDefault(emptyList())) else null
                val configured = videos.save(project.copy(sourceMode = if (embedded != null) VideoSourceMode.EMBEDDED else batch.sourceMode,
                    trackId = embedded?.id ?: project.trackId, translationMode = batch.translationMode,
                    offlinePolishPolicy = batch.offlinePolishPolicy))
                val withSharedRoi = BatchRoiValidation.withSharedRoi(configured, batch)
                if (withSharedRoi != configured) {
                    videoStore.save(withSharedRoi)
                    videos.refresh()
                }
                val episode = BatchPlan.importedEpisode(project.id, name, occupiedEpisodes)
                val item = BatchItem(project.id, name, bytes, episode, durationMs = project.durationMs)
                imported += item; existing += uri.toString()
                occupiedEpisodes += episode
                // Each imported item is durable even if a later document/provider fails.
                batch = persist(batch.copy(items = old.items + imported, outputs = emptyList(), state = "READY"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "$name: ${e.message ?: e.javaClass.simpleName}" }
        }
        // Repeated imports are append-only so a manually confirmed earlier order is retained.
        val ordered = if (old.items.isEmpty()) BatchOrder.suggested(imported) else old.items + BatchOrder.suggested(imported)
        batch.copy(items = ordered, outputs = if (imported.isEmpty()) old.outputs else emptyList(),
            message = if (failures.isEmpty()) "Đã nhập ${imported.size} video. Kiểm tra thứ tự và nhóm tập trước khi bắt đầu."
            else "Đã giữ ${imported.size} video; không nhập được: ${failures.joinToString("; ").take(1000)}")
    }
    suspend fun configure(id: String, mode: VideoTranslationMode, sourceMode: VideoSourceMode = VideoSourceMode.AUTO): BatchProject =
        configureModes(id, mode, sourceMode)

    suspend fun configureTranslation(id: String, mode: VideoTranslationMode): BatchProject = configureModes(id, mode, null)

    suspend fun configureSource(id: String, sourceMode: VideoSourceMode): BatchProject = configureModes(id, null, sourceMode)

    suspend fun enableSourceDetection(id: String): BatchProject = edit(id) {
        it.copy(sourceMode = VideoSourceMode.AUTO, detectNewSources = true,
            message = "Video mới ưu tiên phụ đề có sẵn, rồi OCR và giọng nói khi cần. Nguồn của các tập đã thêm được giữ.")
    }

    private suspend fun configureModes(id: String, mode: VideoTranslationMode?, sourceMode: VideoSourceMode?): BatchProject = edit(id) { old ->
        val selectedMode = mode ?: old.translationMode
        val policy = if (selectedMode == VideoTranslationMode.OFFLINE) old.offlinePolishPolicy else VideoPolishPolicy.NONE
        require(sourceMode == null || old.items.none { item ->
            val member = videoStore.load(item.projectId)
            member.sourceMode != sourceMode && member.cues.any { it.protectedText }
        }) { "Có câu đã duyệt hoặc sửa tay ở video đổi nguồn. Tạo dự án mới để nhận diện lại; các câu hiện tại được giữ." }
        // Invalidate completion first: a crash during configuration cannot advertise stale output.
        val batch = persist(old.copy(translationMode = selectedMode, sourceMode = sourceMode ?: old.sourceMode,
            detectNewSources = sourceMode == VideoSourceMode.AUTO || (sourceMode == null && old.detectNewSources), offlinePolishPolicy = policy,
            outputs = emptyList(), state = "READY", items = old.items.map { it.copy(state = "PENDING", error = "", errorCode = "") }))
        for (item in batch.items) {
            val p = videoStore.load(item.projectId)
            val childMode = mode ?: p.translationMode
            videos.save(p.copy(translationMode = childMode, sourceMode = sourceMode ?: p.sourceMode,
                offlinePolishPolicy = if (childMode == VideoTranslationMode.OFFLINE) policy else VideoPolishPolicy.NONE))
        }
        batch.copy(message = "Đã đặt cách xử lý cho các video. Câu đã duyệt và bản sửa thủ công được giữ.")
    }

    suspend fun setOfflinePolishPolicy(id: String, policy: VideoPolishPolicy): BatchProject = edit(id) { old ->
        require(old.translationMode == VideoTranslationMode.OFFLINE) { "Chọn dịch trên máy trước khi đặt hiệu đính Gemini." }
        if (old.offlinePolishPolicy == policy) return@edit old
        val changed = persist(old.copy(offlinePolishPolicy = policy, outputs = emptyList(), state = "READY",
            items = old.items.map { item ->
                if (item.stage.ordinal < BatchPipelineStage.POLISH.ordinal) item
                else item.copy(state = "PENDING", stage = BatchPipelineStage.POLISH, stageStatus = "PENDING", error = "", errorCode = "")
            }))
        for (item in changed.items) {
            val project = videoStore.load(item.projectId)
            val configured = project.withOfflinePolishPolicy(policy)
            if (configured != project) videoStore.save(configured)
        }
        videos.refresh()
        changed.copy(message = if (policy == VideoPolishPolicy.NONE) "Đã tắt hiệu đính Gemini. Tiếp tục để hoàn thiện bản dịch trên máy."
            else "Đã chọn hiệu đính Gemini sau khi hoàn thành bản dịch trên máy.")
    }
    suspend fun setContextProfile(id:String,value:String)=edit(id){old->
        require(value.length<=8000){"Hồ sơ phim tối đa 8.000 ký tự."}
        val changed=persist(old.copy(contextProfile=value,contextRevision=old.contextRevision+1,state="READY",outputs=emptyList()))
        for(item in old.items){
            val project=videoStore.load(item.projectId)
            // HYBRID consumes film context in its single translation pass. Keep previous
            // text as a draft, but do not mark it complete for the new context.
            val hybrid=project.translationMode==VideoTranslationMode.HYBRID
            videoStore.save(project.copy(pipeline=project.pipeline.invalidateFrom(if(hybrid)BatchPipelineStage.TRANSLATE else BatchPipelineStage.POLISH).copy(contextProfile=value,contextRevision=changed.contextRevision),
                cues=project.cues.map{if(it.protectedText)it else it.copy(polishedVi="",
                    refinementState=if(hybrid)"pending"else it.refinementState,qualityFlags=(it.qualityFlags+"CONTEXT_CHANGED").distinct())}))
        }
        videos.refresh();changed.copy(message="Đã lưu hồ sơ phim dùng chung; bản dịch cũ vẫn được giữ để so sánh.")
    }
    suspend fun applySharedRoi(id: String, roi: List<Float>, resetConfirmed: Boolean = false) = edit(id) { old ->
        require(roi.size==4 && roi.all{it.isFinite() && it in 0f..1f} && roi[2]-roi[0]>=.02f && roi[3]-roi[1]>=.02f){"Vùng OCR cần nằm trong khung video và đủ rộng/cao."}
        val projects=old.items.map{videoStore.load(it.projectId)}.filter{videoSourceUsesRoi(it.sourceMode)}
        val changed = projects.filter { it.roi != roi }
        require(changed.none { project -> project.cues.any { it.protectedText } }) {
            "Có câu đã duyệt hoặc sửa tay ở video đổi vùng. Tạo dự án mới để nhận diện lại; các câu hiện tại được giữ."
        }
        require(resetConfirmed || changed.none{it.cues.isNotEmpty() || it.scanMs>0}){
            "Đổi vùng OCR sẽ tạo lại phụ đề của những tập đổi vùng. Xác nhận trước; bản đã lưu Kho phim không bị xóa."
        }
        // Invalidate batch completion before the first project write, so interruption cannot expose stale exports.
        val changedIds = changed.mapTo(mutableSetOf()) { it.id }
        val invalidated=persist(old.copy(sharedRoi=roi.toList(),sharedRoiRevision=old.sharedRoiRevision+1,
            currentStage=if (changedIds.isEmpty()) old.currentStage else BatchPipelineStage.EXTRACT,
            outputs=if (changedIds.isEmpty()) old.outputs else emptyList(),state=if (changedIds.isEmpty()) old.state else "READY",
            items=old.items.map{if (it.projectId in changedIds) it.copy(state="PENDING",stage=BatchPipelineStage.EXTRACT,stageStatus="PENDING",error="",errorCode="") else it}))
        for(project in projects)videoStore.save(VideoStagedPipeline.applyRoi(project,roi,invalidated.sharedRoiRevision))
        videos.refresh()
        invalidated.copy(message="Đã áp dụng vùng chung cho ${projects.size} video dùng OCR; các tập dùng phụ đề văn bản được giữ.")
    }
    suspend fun rename(id: String, title: String) = edit(id) { old ->
        val name = title.trim().take(200).ifBlank { "Phim mới" }
        val named = old.copy(title = name)
        named.copy(outputs = old.outputs.map { it.copy(title = outputTitle(named, it.episode)) })
    }
    suspend fun setDelivery(id: String, delivery: BatchDelivery) = edit(id) {
        val stopMerging = delivery == BatchDelivery.SOURCE_LIBRARY && it.mergeEpisodes
        it.copy(delivery = delivery, mergeEpisodes = if (stopMerging) false else it.mergeEpisodes,
            outputs = if (stopMerging) emptyList() else it.outputs, state = if (stopMerging) "READY" else it.state,
            message = if (stopMerging) "Đã chọn video gốc và phụ đề riêng; tắt ghép MP4 cả bộ. Các tập và bản xuất hiện có vẫn được giữ."
            else "Đã chọn cách lưu kết quả; bản xuất và timeline hiện có được giữ.")
    }

    /** Explicit opt-in assembles one long MP4 after the existing review/export guards succeed. */
    suspend fun setMergeEpisodes(id: String, enabled: Boolean) = edit(id) { old ->
        if (old.mergeEpisodes == enabled) old
        else old.copy(mergeEpisodes = enabled,
            delivery = if (enabled && old.delivery == BatchDelivery.SOURCE_LIBRARY) BatchDelivery.LIBRARY_AND_MP4 else old.delivery,
            outputs = emptyList(), state = "READY",
            message = if (enabled) "Sẽ tạo một MP4 cả bộ theo thứ tự hiện tại sau khi tất cả tập hoàn thành và phụ đề được duyệt. Các tập riêng vẫn được giữ."
            else "Đã tắt ghép cả bộ. Giữ thứ tự, nhóm tập, phụ đề và các tệp đã xuất.")
    }

    /** Read fresh reviewed timelines; this route never invokes an encoder or joins episodes. */
    suspend fun validatedSources(id: String, approveAll: Boolean = false): List<MovieSourceInput> = withContext(Dispatchers.IO) {
        edits.withLock {
            check(busy.value == null && videos.busy.value == null) { "Đợi xử lý xong trước khi lưu phim." }
            val batch = store.load(id)
            require(batch.items.isNotEmpty())
            batch.items.map { item ->
                val project = videoStore.load(item.projectId)
                require((project.pipeline.version==0 || VideoStagedPipeline.completed(project,BatchPipelineStage.FINISH)) &&
                    project.cues.isNotEmpty() && !project.sourceExtractionPending &&
                    project.cues.all { VideoTranslationPlan.isComplete(it, project.translationMode == VideoTranslationMode.HYBRID) }) {
                    "Video ${item.name} chưa dịch xong. Kiểm tra phụ đề và tiếp tục phần còn thiếu."
                }
                require(!approveAll || project.cues.all { it.translation.isNotBlank() }) {
                    "Video ${item.name} còn câu thiếu bản dịch. Bổ sung trước khi duyệt toàn bộ."
                }
                // Stable project identity preserves confirmed order even for separate parts of one episode.
                // Approve the snapshot published by MovieLibrary, leaving the editing timeline intact.
                val source = if (approveAll) project.copy(cues = project.cues.map { it.copy(approved = true) }) else project
                MovieSourceInput(item.projectId, "${outputTitle(batch, item.episode)} · ${item.name}", source)
            }
        }
    }
    /** Opt-in freezes the selected reviewed-film scope; later global settings cannot change this batch. */
    suspend fun useReviewedMemory(id: String, enabled: Boolean) = edit(id) { old ->
        if (!enabled) old.copy(useReviewedMemory = false)
        else {
            val settings = AppSettings(context)
            require(settings.memoryFilmId.isNotBlank()) { "Chọn mã phim trong Kho thoại trước khi bật dùng bản dịch đã duyệt." }
            old.copy(useReviewedMemory = true, reviewedFilmId = settings.memoryFilmId, reviewedDomain = settings.memoryDomain,
                reviewedProfile = settings.profile, reviewedGlossary = settings.localGlossary, reviewedModel = StandaloneTranslator.cacheModelIdentity(),
                message = "Đã bật câu đã duyệt chung trong phim đã chọn, khớp nguyên văn và cấu hình; câu khác vẫn được dịch bình thường.")
        }
    }
    suspend fun changeKind(id: String, kind: BatchKind) = edit(id) { old ->
        old.copy(kind = kind, outputs = emptyList(), state = "READY", message = "Kiểm tra nhóm tập; đổi loại không tự gộp các tập đã nhận diện.")
    }
    suspend fun reorder(id: String, from: Int, to: Int) = edit(id) {
        it.copy(items = BatchPlan.move(it.items, from, to), outputs = emptyList(), state = "READY")
    }
    /** Apply only the exact preview explicitly confirmed for this unchanged project. */
    suspend fun applyOrder(expected: BatchProject, projectIds: List<String>) = edit(expected.id) { old ->
        val recovered = BatchPlan.interrupted(old)
        require(recovered == expected) { "Danh sách hoặc cấu hình đã thay đổi. Phân tích và kiểm tra lại thứ tự trước khi áp dụng." }
        val existing = recovered.items.associateBy { it.projectId }
        require(projectIds.size == existing.size && projectIds.toSet() == existing.keys) { "Gợi ý thứ tự thiếu hoặc trùng video." }
        recovered.copy(items = projectIds.map { existing.getValue(it) }, outputs = emptyList(), state = "READY",
            message = "Đã áp dụng thứ tự bạn xác nhận. Mã tập và nhóm ghép được giữ; kiểm tra lại trước khi dịch.")
    }
    suspend fun setEpisode(id: String, projectId: String, episode: String) = edit(id) {
        val key = episode.trim(); require(key.isNotEmpty() && key.length <= 80) { "Tên nhóm tập cần từ 1 đến 80 ký tự." }
        require(it.items.any { item -> item.projectId == projectId })
        it.copy(items = it.items.map { item -> if (item.projectId == projectId) item.copy(episode = key) else item }, outputs = emptyList(), state = "READY")
    }
    suspend fun remove(id: String, projectId: String) = edit(id) {
        // Removing an input never deletes its source, project/checkpoint or already-confirmed movie.
        it.copy(items = it.items.filterNot { item -> item.projectId == projectId }, outputs = emptyList(), state = "READY")
    }

    suspend fun setExecutionMode(id: String, mode: BatchExecutionMode) = edit(id) {
        it.copy(executionMode = mode)
    }

    suspend fun setAutoRetry(id: String, enabled: Boolean) = edit(id) {
        it.copy(autoRetryEnabled = enabled, autoRetryAtMs = 0, autoRetryAttempts = 0)
    }

    @Synchronized fun start(id: String) {
        check(mutableBusy.value == null && worker?.isActive != true) { "Đang xử lý một dự án hàng loạt." }
        check(edits.tryLock()) { "Đang lưu thay đổi. Hãy thử lại sau." }
        try {
            val batch = store.load(id); require(batch.items.isNotEmpty()) { "Thêm video trước khi bắt đầu." }
            val canExtractLocally=batch.translationMode==VideoTranslationMode.HYBRID && batch.items.any{
                VideoHybridWorkflow.requiresExtraction(videoStore.load(it.projectId))}
            check(batch.translationMode == VideoTranslationMode.OFFLINE || canExtractLocally || System.currentTimeMillis() >= batch.retryAfterMs) {
                "Gemini đang trong thời gian chờ hạn mức. Còn ${((batch.retryAfterMs-System.currentTimeMillis()).coerceAtLeast(0)+999)/1000} giây; checkpoint vẫn được giữ."
            }
            videos.reserveBatch(id); stopState = "PAUSED"; stopRequested = false; mutableBusy.value = id
            try {
                persist(batch.copy(state = "RUNNING", autoRetryAtMs = 0, autoRetryAttempts = 0,
                    message = "Đã xác nhận thứ tự; đang khởi động xử lý tuần tự."))
                ContextCompat.startForegroundService(context, Intent(context, BatchProcessingService::class.java).putExtra("batch", id))
            }
            catch (e: Exception) {
                mutableBusy.value = null; videos.releaseBatch(id)
                persist(batch.copy(state = "PAUSED", message = "Chưa khởi động được xử lý nền; checkpoint được giữ.")); throw e
            }
        } finally { edits.unlock() }
    }
    @Synchronized fun pause() = requestStop("PAUSED")
    @Synchronized fun cancel() = requestStop("CANCELLED")
    private fun requestStop(state: String) {
        val id = mutableBusy.value ?: return
        if (!stopRequested || stopState != "CANCELLED") stopState = state
        stopRequested = true
        try {
            synchronized(writes) { persist(store.load(id).copy(state = stopState, autoRetryAtMs = 0)) }
        } finally {
            videos.pause(); worker?.cancel()
        }
    }

    /** Confirmation must use this fresh result, since the single-video editor can invalidate an export. */
    suspend fun validatedOutputs(id: String): List<BatchOutput> = withContext(Dispatchers.IO) {
        edits.withLock {
            synchronized(videos) { validateOutputs(store.load(id)) }
        }
    }

    private fun validateOutputs(batch: BatchProject): List<BatchOutput> {
        check(busy.value == null && videos.busy.value == null) { "Đợi xử lý xong trước khi xác nhận." }
        require(batch.state == "REVIEW") { "Hoàn tất tất cả video và nhóm ghép trước khi xác nhận." }
        val groups = BatchPlan.groups(batch).keys
        require(groups.isNotEmpty() && batch.outputs.size == groups.size &&
            batch.outputs.map { it.episode }.toSet() == groups && batch.outputs.all { validOutput(batch, it) }) {
            "Nguồn hoặc phụ đề đã thay đổi sau khi xuất. Tạo lại MP4 trước khi lưu."
        }
        return batch.outputs
    }

    /** The document picker may return after the selected batch or render has changed. */
    internal suspend fun copyMergedOutput(id: String, expectedPath: String, expectedFingerprint: String, destination: Uri) =
        withContext(Dispatchers.IO) {
            require(destination.scheme == "content") { "Chọn nơi lưu bằng trình chọn tệp Android." }
            val (input, length) = edits.withLock {
                synchronized(videos) {
                    val batch = store.load(id)
                    require(batch.mergeEpisodes) { "Đã đổi chế độ ghép. Mở lại kết quả để lưu." }
                    val output = validateOutputs(batch).single()
                    require(output.episode == BatchPlan.ALL_EPISODES && output.path == expectedPath &&
                        output.inputFingerprint == expectedFingerprint) { "Bản ghép đã thay đổi. Mở lại kết quả để lưu." }
                    val target = destination.normalizeScheme()
                    val sourceUris = batch.items.flatMap { item ->
                        val p = videoStore.load(item.projectId)
                        listOf(p.uri, p.subtitleUri, p.originalSourceUri, p.originalSubtitleUri)
                    }
                    require(sourceUris.filter { it.isNotBlank() }.none { sameExportDocument(Uri.parse(it), target) }) {
                        "Không được ghi đè video hoặc phụ đề nguồn. Chọn tên tệp mới."
                    }
                    val source = File(output.path)
                    val length = source.length()
                    source.inputStream() to length
                }
            }
            input.use { from ->
                currentCoroutineContext().ensureActive()
                checkNotNull(context.contentResolver.openOutputStream(destination, "wt")).use { to ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = from.read(buffer)
                        if (count < 0) break
                        to.write(buffer, 0, count)
                        copied += count
                    }
                    require(copied == length) { "Chưa lưu đủ dữ liệu MP4. Hãy chọn nơi lưu khác và thử lại." }
                }
            }
        }

    private fun sameExportDocument(source: Uri, destination: Uri): Boolean {
        if (source.normalizeScheme() == destination) return true
        if (source.scheme != "content" || source.authority != destination.authority) return false
        return runCatching { DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(destination) }
            .getOrDefault(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized internal fun execute(id: String, onDone: () -> Unit) {
        if (worker?.isActive == true) {
            check(mutableBusy.value == id) { "Another batch owns the worker" }
            return
        }
        val recovering = mutableBusy.value == null
        if (recovering) {
            // Redelivered foreground-service intents may resume only durable active work.
            // A stale intent cannot restart a batch explicitly paused/cancelled/completed.
            val saved = store.load(id)
            if (saved.state !in setOf("RUNNING", "RETRY_WAIT")) { onDone(); return }
            videos.reserveBatch(id)
            stopState = "PAUSED"; stopRequested = false; mutableBusy.value = id
        }
        check(mutableBusy.value == id) { "Another batch owns the reservation" }
        worker = scope.launch(start = CoroutineStart.ATOMIC) {
            var batch = BatchProject(id, "", BatchKind.MOVIE_PARTS)
            var loaded = false
            suspend fun processPass(automatic: Boolean) {
                val manualIds = if (automatic) batch.items.filter {
                    (it.stageStatus == "NEEDS_REVIEW" || it.state in setOf("FAILED", "NEEDS_REVIEW")) &&
                        !BatchRecovery.canRetry(it.errorCode)
                }.mapTo(mutableSetOf()) { it.projectId } else emptySet()
                // Reconcile lazily per episode; one damaged source cannot block every input.
                suspend fun reconcile(item: BatchItem) {
                    var project=videoStore.load(item.projectId)
                    val configured = BatchRoiValidation.withSharedRoi(project, batch).withOfflinePolishPolicy(batch.offlinePolishPolicy)
                    if (configured != project) project = videoStore.save(configured)
                    if(project.pipeline.contextRevision!=batch.contextRevision){
                        val hybrid=project.translationMode==VideoTranslationMode.HYBRID
                        videoStore.save(project.copy(pipeline=project.pipeline.invalidateFrom(if(hybrid)BatchPipelineStage.TRANSLATE else BatchPipelineStage.POLISH).copy(
                            contextProfile=batch.contextProfile,contextRevision=batch.contextRevision),
                            cues=project.cues.map{if(it.protectedText)it else it.copy(polishedVi="",
                                refinementState=if(hybrid)"pending"else it.refinementState,qualityFlags=(it.qualityFlags+"CONTEXT_CHANGED").distinct())}))
                    }
                }
                // Each episode owns its source-to-saved-subtitle pipeline. The media lane is
                // serial; independent text lanes use the existing provider/checkpoint machinery.
                val stateLock = Mutex()
                suspend fun processItem(item: BatchItem, stage: BatchPipelineStage, parallel: Boolean): Boolean {
                    currentCoroutineContext().ensureActive()
                    if (item.projectId in manualIds) return false
                    val previous = videoStore.load(item.projectId)
                    if (VideoStagedPipeline.completed(previous, stage)) {
                        stateLock.withLock { batch = stageState(batch, item.projectId, stage, "COMPLETE") }
                        return true
                    }
                    if (stage == BatchPipelineStage.TRANSLATE && previous.translationMode != VideoTranslationMode.OFFLINE &&
                        System.currentTimeMillis() < batch.retryAfterMs) {
                        stateLock.withLock { batch = persist(batch.copy(state = "PAUSED", currentStage = stage)) }
                        throw BatchStagePaused()
                    }
                    if (stage != BatchPipelineStage.EXTRACT && BatchPipelineStage.entries.take(stage.ordinal).any { !VideoStagedPipeline.completed(previous, it) }) return false
                    val offlinePolish = stage == BatchPipelineStage.POLISH && previous.translationMode == VideoTranslationMode.OFFLINE &&
                        previous.offlinePolishPolicy != VideoPolishPolicy.NONE
                    if (offlinePolish && !VideoStagedPipeline.completed(previous, stage) &&
                        System.currentTimeMillis() < batch.retryAfterMs) {
                        stateLock.withLock {
                            batch = persist(batch.copy(state = "PAUSED",
                                message = "Đã lưu bản dịch trên máy. Gemini đang chờ hạn mức; chờ rồi tiếp tục hoặc tắt hiệu đính để hoàn thiện bản offline."))
                        }
                        throw BatchStagePaused()
                    }
                    try {
                        stateLock.withLock { batch = stageState(batch, item.projectId, stage, "RUNNING") }
                        val result = if (parallel) videos.runParallelStageForBatch(id, item.projectId, stage, allowMediaOverlap = true)
                            else videos.runStageForBatch(id, item.projectId, stage)
                        currentCoroutineContext().ensureActive()
                        if (result.stage == VideoStage.PAUSED && result.pipeline.task(stage).status == "PAUSED")
                            throw CancellationException("Stage paused before its result was committed")
                        if (!VideoStagedPipeline.completed(result, stage)) {
                            requireFinished(result)
                            error("Giai đoạn ${stage.label} chưa hoàn tất; checkpoint được giữ.")
                        }
                        stateLock.withLock { batch = stageState(batch, item.projectId, stage, "COMPLETE") }
                        return true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = e.message?.take(600) ?: e.javaClass.simpleName
                        val task = videoStore.load(item.projectId).pipeline.task(stage)
                        val code = task.errorCode.ifBlank { VideoStagedPipeline.errorCode(e) }
                        val status = when (code) { "API_AUTH" -> 403; "API_QUOTA_EXHAUSTED", "API_RATE_LIMIT" -> 429; else -> sharedGeminiFailure(message) }
                        val transportOutage = code in setOf("API_NETWORK", "API_TIMEOUT") || code.startsWith("API_SERVICE_")
                        val stop = status != null || task.nextRetryAt > System.currentTimeMillis() || transportOutage
                        stateLock.withLock {
                            batch = stageState(batch, item.projectId, stage, "NEEDS_REVIEW", message, code)
                            if (stop) batch = persist(batch.copy(state = "PAUSED",
                                retryAfterMs = maxOf(batch.retryAfterMs, task.nextRetryAt,
                                    if (status == 429 && code != "API_QUOTA_EXHAUSTED") System.currentTimeMillis() + 61_000L else 0),
                                message = if (offlinePolish) "$message Bản nháp đã lưu. Thử lại hiệu đính hoặc tắt hiệu đính để hoàn thiện bản trên máy."
                                else "$message Đã lưu các giai đoạn hoàn thành. Tạm ngừng gửi yêu cầu Gemini."))
                        }
                        if (stop) throw BatchStagePaused()
                        return false
                    }
                }
                val workerLimit = if (VideoSmartBatchSettings(context).economyMode) 1
                    else BatchParallelism.workerLimit(context, batch.executionMode)
                try {
                    VideoApiRecovery.withParallelRequests(workerLimit) {
                        val resources = BatchResourceAdmission(context, batch.executionMode, workerLimit)
                        BatchEpisodePipeline(workerLimit, admission = resources::snapshot).run(batch.items.filterNot { it.projectId in manualIds }.map { it.projectId },
                            extract = { projectId ->
                                val item = batch.items.first { it.projectId == projectId }
                                processItem(item, BatchPipelineStage.EXTRACT, false) &&
                                    processItem(item, BatchPipelineStage.CLEAN, false)
                            },
                            translateValidateSave = { projectId ->
                                val item = batch.items.first { it.projectId == projectId }
                                for (stage in listOf(BatchPipelineStage.TRANSLATE, BatchPipelineStage.POLISH, BatchPipelineStage.FINISH)) {
                                    if (!processItem(item, stage, true)) break
                                }
                                if (batch.delivery == BatchDelivery.SOURCE_LIBRARY &&
                                    VideoStagedPipeline.completed(videoStore.load(projectId), BatchPipelineStage.FINISH)) {
                                    stateLock.withLock { batch = itemState(batch, projectId, "DONE") }
                                }
                            },
                            onFailure = { projectId, failure ->
                                if (failure is BatchStagePaused) throw failure
                                stateLock.withLock {
                                    val stage = batch.items.first { it.projectId == projectId }.stage
                                    batch = stageState(batch, projectId, stage, "NEEDS_REVIEW",
                                        failure.message?.take(600) ?: failure.javaClass.simpleName,
                                        VideoStagedPipeline.errorCode(failure))
                                }
                            }, requiresExtraction = { projectId ->
                                val item = batch.items.first { it.projectId == projectId }
                                reconcile(item)
                                !VideoStagedPipeline.completed(videoStore.load(projectId), BatchPipelineStage.EXTRACT)
                            })
                    }
                } catch (_: BatchStagePaused) {
                    // The structured pipeline has drained every lane and cancellation checkpoint.
                    val retryAt = batch.items.maxOfOrNull { item ->
                        runCatching { val project = videoStore.load(item.projectId); BatchPipelineStage.entries.maxOf { project.pipeline.task(it).nextRetryAt } }.getOrDefault(0L)
                    } ?: 0
                    batch = persist(batch.copy(retryAfterMs = maxOf(batch.retryAfterMs, retryAt), items = batch.items.map {
                        if (it.stageStatus == "RUNNING") it.copy(stageStatus = "PAUSED") else it
                    }))
                    return
                }
                for (item in batch.items.toList()) {
                    currentCoroutineContext().ensureActive()
                    if (item.projectId in manualIds) continue
                    try {
                        var project = videoStore.load(item.projectId)
                        val complete = project.cues.isNotEmpty() && !project.sourceExtractionPending &&
                            project.cues.all { VideoTranslationPlan.isComplete(it, project.translationMode == VideoTranslationMode.HYBRID) }
                        if (!complete || !VideoStagedPipeline.completed(project,BatchPipelineStage.FINISH)) {
                            // Failed inputs remain visible; successful episodes may continue/export independently.
                            batch=itemState(batch,item.projectId,"NEEDS_REVIEW",item.error.ifBlank{"Còn giai đoạn cần kiểm tra; kết quả các tập khác được giữ."})
                            continue
                        }
                        if (batch.delivery != BatchDelivery.SOURCE_LIBRARY && project.cues.any { !it.approved }) {
                            batch = itemState(batch, item.projectId, "DONE")
                            continue // Translation is complete; review must precede automatic hard-sub export.
                        }
                        if (batch.delivery != BatchDelivery.SOURCE_LIBRARY && (project.exportedMp4.isBlank() || !File(project.exportedMp4).isFile)) {
                            batch = itemState(batch, item.projectId, "EXPORTING")
                            project = videos.runForBatch(id, item.projectId, true)
                            requireFinished(project)
                        }
                        if (batch.delivery != BatchDelivery.SOURCE_LIBRARY)
                            require(project.exportedMp4.isNotBlank() && File(project.exportedMp4).length() > 0) { "Chưa có MP4 hoàn chỉnh." }
                        batch = itemState(batch, item.projectId, "DONE")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = e.message?.take(600) ?: e.javaClass.simpleName
                        batch = itemState(batch, item.projectId, "FAILED", message)
                        val status = sharedGeminiFailure(message)
                        if (status != null) {
                            batch = persist(batch.copy(state = "PAUSED",
                                retryAfterMs = if (status == 429) maxOf(batch.retryAfterMs, System.currentTimeMillis() + 61_000L) else batch.retryAfterMs,
                                message = if (status == 429) "$message Đã tạm dừng toàn bộ hàng đợi và lưu thời gian chờ hạn mức; các video sau chưa được gửi."
                                else "$message Đã tạm dừng toàn bộ hàng đợi. Kiểm tra khóa/quyền API rồi chủ động bấm Tiếp tục."))
                            return
                        }
                    }
                }
                if (batch.delivery != BatchDelivery.SOURCE_LIBRARY && batch.items.all { it.state == "DONE" } &&
                    batch.items.any { videoStore.load(it.projectId).cues.any { cue -> !cue.approved } }) {
                    batch = persist(batch.copy(state = "REVIEW", message = "Đã dịch xong. Kiểm tra và duyệt phụ đề trước khi tạo MP4 tự động; chưa render các câu chưa duyệt."))
                    return
                }
                if (batch.delivery == BatchDelivery.SOURCE_LIBRARY) {
                    val complete = batch.items.all { it.state == "DONE" }
                    batch = persist(batch.copy(state = if (complete) "REVIEW" else "FAILED",
                        message = if (complete) "Đã dịch xong, chưa lưu Kho phim. Kiểm tra và xác nhận lưu video gốc cùng phụ đề; không cần render MP4."
                        else "Có video chưa hoàn tất. Timeline đã xong được giữ; tiếp tục phần còn thiếu."))
                    return
                }
                val outputs = batch.outputs.filter { validOutput(batch, it) }.toMutableList()
                val mergeErrors = mutableListOf<String>()
                for ((episode, items) in BatchPlan.groups(batch)) {
                    currentCoroutineContext().ensureActive()
                    if (items.any { it.state != "DONE" }) continue
                    if (outputs.any { it.episode == episode && File(it.path).length() > 0 }) continue
                    val groupLabel = if (batch.mergeEpisodes) "cả bộ" else "nhóm $episode"
                    try {
                        val projects = items.map { videoStore.load(it.projectId) }
                        val files = projects.map { File(it.exportedMp4) }
                        val output = if (files.size == 1) files.single() else {
                            val target = File(store.folder(id), "merged-${UUID.randomUUID()}.mp4")
                            while (true) {
                                gate()
                                try {
                                    VideoExporter.concatenate(context, files, target, ::gate) { fraction ->
                                        batch = persist(batch.copy(message = "Đang ghép $groupLabel: ${(fraction * 100).toInt()}%"))
                                    }
                                    break
                                } catch (_: VideoExporter.LivePreemptedException) { gate() }
                            }
                            target
                        }
                        val metadata = VideoDecoder.metadata(context, Uri.fromFile(output))
                        require(metadata.durationMs > 0)
                        outputs += BatchOutput(episode, outputTitle(batch, episode), output.absolutePath, metadata.durationMs, inputFingerprint(items))
                        batch = persist(batch.copy(outputs = outputs.toList(), message = "Đã chuẩn bị $groupLabel để kiểm tra."))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = "Ghép $groupLabel chưa xong: ${e.message?.take(500) ?: e.javaClass.simpleName}"
                        mergeErrors += message
                        batch = persist(batch.copy(message = message))
                    }
                }
                val allReady = outputs.map { it.episode }.toSet() == BatchPlan.groups(batch).keys && batch.items.all { it.state == "DONE" }
                batch = persist(batch.copy(state = if (allReady) "REVIEW" else "FAILED", outputs = outputs,
                    message = if (allReady) "Đã hoàn tất. Xem từng kết quả rồi xác nhận đưa vào Kho phim; chưa tự tải cloud."
                    else "Có video hoặc nhóm ghép chưa hoàn tất. Kết quả đã xong được giữ; bấm thử lại. ${mergeErrors.joinToString("; ")}"))
            }
            try {
                batch = store.load(id); loaded = true
                if (stopRequested) throw CancellationException("Stopped before service started")
                var automatic = recovering
                if (recovering) {
                    val deadline = maxOf(batch.autoRetryAtMs, batch.retryAfterMs)
                    if (deadline > System.currentTimeMillis()) {
                        waitBeforeRetry(deadline - System.currentTimeMillis())
                        gate()
                    }
                }
                VideoApiRecovery.withAdaptiveBatch(BatchParallelism.workerLimit(context, batch.executionMode)) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        processPass(automatic)
                        val now = System.currentTimeMillis()
                        val deadline = BatchRecovery.nextAttemptAt(batch, now) ?: break
                        batch = persist(batch.copy(state = "RETRY_WAIT", autoRetryAtMs = deadline,
                            message = "Đã lưu tiến độ. Sẽ tự tiếp tục phần còn thiếu khi hết thời gian chờ."))
                        waitBeforeRetry(deadline - now)
                        gate()
                        batch = persist(batch.copy(state = "RUNNING", autoRetryAtMs = 0,
                            autoRetryAttempts = batch.autoRetryAttempts + 1, message = "Đang tự tiếp tục phần còn thiếu."))
                        automatic = true
                    }
                }
                if (batch.state == "REVIEW" && batch.autoRetryAttempts > 0) {
                    batch = persist(batch.copy(autoRetryAttempts = 0))
                } else if (batch.autoRetryAttempts >= BatchRecovery.MAX_ATTEMPTS &&
                    batch.items.any { it.stageStatus == "NEEDS_REVIEW" && BatchRecovery.canRetry(it.errorCode) }) {
                    batch = persist(batch.copy(message = "Đã tự thử lại ${BatchRecovery.MAX_ATTEMPTS} lượt. Kiểm tra chỉ dẫn ở tập chưa xong rồi bấm Tiếp tục."))
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (loaded) persist(BatchPlan.interrupted(batch).copy(state = stopState, autoRetryAtMs = 0,
                        message = if (e.message.orEmpty().startsWith("Gemini đang xử lý tác vụ nền")) e.message.orEmpty()
                        else if (stopState == "CANCELLED") "Đã hủy hàng đợi; nguồn, kết quả đã xong và checkpoint vẫn được giữ." else "Đã tạm dừng và lưu checkpoint. Bấm Tiếp tục."))
                }
            } catch (e: Exception) {
                if (loaded) persist(batch.copy(state = "FAILED", autoRetryAtMs = 0, message = e.message?.take(600) ?: e.javaClass.simpleName))
            } finally {
                synchronized(this@BatchJobs) { worker = null; mutableBusy.value = null; videos.releaseBatch(id); refresh(); onDone() }
            }
        }
    }
    private fun stageState(batch:BatchProject,projectId:String,stage:BatchPipelineStage,status:String,error:String="",errorCode:String=""):BatchProject = persist(batch.copy(
        currentStage=stage,
        items=batch.items.map{if(it.projectId==projectId)it.copy(stage=stage,stageStatus=status,error=error,errorCode=errorCode,
            state=if(status=="NEEDS_REVIEW")"NEEDS_REVIEW" else if(stage==BatchPipelineStage.FINISH && status=="COMPLETE")"DONE" else "PENDING") else it},
        message=if(error.isNotBlank())error else "${stage.label}: ${batch.items.indexOfFirst{it.projectId==projectId}+1}/${batch.items.size}"))
    private fun itemState(batch: BatchProject, projectId: String, state: String, error: String = ""): BatchProject = persist(batch.copy(
        items = batch.items.map { if (it.projectId == projectId) it.copy(state = state, error = error,
            errorCode = if (error.isBlank()) "" else it.errorCode) else it },
        message = if (state == "FAILED") error else "${batch.items.indexOfFirst { it.projectId == projectId } + 1}/${batch.items.size}: $state"))
    private fun requireFinished(project: VideoProject) {
        if (project.stage == VideoStage.PAUSED) throw CancellationException(project.message)
        check(project.stage != VideoStage.FAILED) { project.message }
    }
    private fun inputFingerprint(items: List<BatchItem>,legacyShape:Boolean=false): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (item in items) {
            val project=videoStore.load(item.projectId)
            require(!legacyShape || project.pipeline.version==0){"Dự án staged không dùng dấu nguồn của phiên bản cũ."}
            val payload = project.json().apply {
                remove("updatedAt")
                if(legacyShape){
                    // Exact 1.7.9 serialization: only additive staged metadata is absent.
                    // Source, timing, style, configuration and output file identity remain hashed.
                    remove("pipeline")
                    val rows=getJSONArray("cues")
                    for(index in 0 until rows.length())for(key in listOf("rawZh","correctedZh","draftVi","polishedVi","ocrConfidence","qualityFlags","version"))
                        rows.getJSONObject(index).remove(key)
                }
            }.toString().toByteArray(Charsets.UTF_8)
            digest.update(payload.size.toString().toByteArray(Charsets.UTF_8)); digest.update(':'.code.toByte()); digest.update(payload)
            val export = File(videoStore.load(item.projectId).exportedMp4)
            digest.update("|${export.length()}:${export.lastModified()}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun validOutput(batch: BatchProject, output: BatchOutput): Boolean = runCatching {
        val items = BatchPlan.groups(batch)[output.episode] ?: return@runCatching false
        val file = File(output.path).canonicalFile
        val owned = if (items.size == 1) file == File(videoStore.load(items.single().projectId).exportedMp4).canonicalFile
            else file.parentFile == store.folder(batch.id).canonicalFile && file.name.matches(Regex("merged-[a-f0-9-]{36}\\.mp4"))
        owned && output.inputFingerprint.isNotBlank() && file.length() > 0 &&
            (output.inputFingerprint == inputFingerprint(items) ||
                (items.all{videoStore.load(it.projectId).pipeline.version==0} &&
                    output.inputFingerprint == inputFingerprint(items,legacyShape=true)))
    }.getOrDefault(false)
    private fun outputTitle(batch: BatchProject, episode: String): String {
        val groups = BatchPlan.groups(batch)
        return when {
            groups.size == 1 -> batch.title
            episode.startsWith("video-") && groups[episode]?.size == 1 -> "${batch.title} · ${groups.getValue(episode).single().name}"
            else -> "${batch.title} · Tập $episode"
        }.take(200)
    }
    private suspend fun gate() { currentCoroutineContext().ensureActive(); if (stopRequested) throw CancellationException("Batch stopped"); while (SessionState.running.value || BatchPlaybackPriority.current.restricted()) delay(300); currentCoroutineContext().ensureActive() }
    companion object {
        /** Match only the existing provider's shared quota/auth errors, never an input/media failure. */
        internal fun sharedGeminiFailure(message: String): Int? {
            if(message.contains("[API_AUTH]"))return 403
            if(message.contains("[API_QUOTA_EXHAUSTED]") || message.contains("[API_RATE_LIMIT]"))return 429
            if (!message.contains("Gemini")) return null
            Regex("\\((?:HTTP\\s+)?(429|401|403)\\)").find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            // Visual OCR's existing errors omit the status code; preserve their exact known meaning.
            if (message.startsWith("Gemini đang hết hạn mức hoặc giới hạn lượt gọi.")) return 429
            if (message.startsWith("Gemini chưa cho phép nhận chữ.")) return 403
            return null
        }
        @Volatile private var instance: BatchJobs? = null
        fun get(context: Context): BatchJobs = instance ?: synchronized(this) { instance ?: BatchJobs(context.applicationContext).also { instance = it } }
    }
}
