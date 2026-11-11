package com.gemini.ocr.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import com.gemini.ocr.video.sync.VideoCloudSync
import com.gemini.ocr.batch.BatchReviewedMemory
import com.gemini.ocr.asr.ChineseAsrModels
import com.gemini.ocr.asr.ChineseAsrSession
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One foreground reservation, with isolated text workers for opted-in batch parallelism. */
class VideoJobs internal constructor(
    private val context:Context,
    private val batchParent:VideoJobs? = null,
    requestContext:CoroutineContext = EmptyCoroutineContext
) {
    private val store=VideoStore(context)
    private val batchCache=VideoPrivateBatchCache(context)
    private val batchReviewedMemory=BatchReviewedMemory(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+requestContext.minusKey(Job))
    private val mutableProjects=MutableStateFlow<List<VideoProject>>(emptyList())
    private val mutableBusy=MutableStateFlow<String?>(null)
    private val mutableBatchBusy=MutableStateFlow<String?>(null)
    private val mutableOffline=MutableStateFlow("Đang kiểm tra model đã tải…")
    val projects:StateFlow<List<VideoProject>> = mutableProjects
    val busy:StateFlow<String?> = mutableBusy
    private val mutableParallelProjects=MutableStateFlow<Set<String>>(emptySet())
    val parallelProjects:StateFlow<Set<String>> = mutableParallelProjects
    /** Reservation spans extraction, export and concatenation; single-video writes cannot race it. */
    val batchBusy:StateFlow<String?> = mutableBatchBusy
    val offlineState:StateFlow<String> = mutableOffline
    private var worker:Job?=null
    private var pauseRequested=false
    private val edits=Mutex()
    private var speechSession: ChineseAsrSession? = null
    private suspend fun speechRecognizer(): ChineseAsrSession {
        speechSession?.let { return it }
        val models = ChineseAsrModels(context)
        check(models.supported) { "ASR_ABI_UNSUPPORTED: Dùng OCR hoặc phụ đề có sẵn trên Android x86 32 bit." }
        check(models.status().ready) { "ASR_MODEL_MISSING: Chưa có gói nhận diện tiếng Trung. Tải gói trong Nguồn phụ đề, hoặc chọn OCR/phụ đề có sẵn. Không tự tải hoặc gửi âm thanh lên mạng." }
        val memory = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem >= 450L * 1024 * 1024) { "ASR_LOW_MEMORY: Chưa đủ RAM cho gói nhận diện. Đóng bớt ứng dụng rồi tiếp tục; bản nháp được giữ." }
        return ChineseAsrSession.open(models, minOf(2, maxOf(1, Runtime.getRuntime().availableProcessors() / 2))).also { speechSession = it }
    }
    init { if(batchParent==null){refresh();scope.launch{checkOffline()}} }
    @Synchronized fun refresh(){
        batchParent?.let { it.refresh();return }
        mutableProjects.value=store.list().map { p ->
        if(p.id!=busy.value && p.id !in mutableParallelProjects.value && p.stage in ACTIVE_STAGES) p.copy(stage=VideoStage.PAUSED,message="Đã lưu checkpoint. Bấm Tiếp tục sau khi mở lại.") else p
    }}
    private suspend fun checkOffline(){mutableOffline.value=runCatching {
        if(VideoOfflineTranslator.ready())"Model Trung → Việt đã sẵn sàng; có thể tắt mạng." else "Chưa có đủ model. Bấm Chuẩn bị offline khi có mạng."
    }.getOrElse{"Chưa kiểm tra được model: ${it.javaClass.simpleName}"}}
    suspend fun prepareOffline()=withContext(Dispatchers.IO){
        requireNoBatch()
        check(busy.value==null){"Tạm dừng xử lý trước khi chuẩn bị model."}
        mutableOffline.value="Đang tải model Trung và Việt; cần mạng…"
        try {VideoOfflineTranslator().use{it.prepare()};checkOffline()} catch(e:Exception){mutableOffline.value="Tải model chưa xong: ${e.javaClass.simpleName}";throw e}
    }
    suspend fun create(uri:Uri,title:String):VideoProject=withContext(Dispatchers.IO){
        store.importVideo(uri,title).also{refresh()}
    }
    /** A visual rescan has a separate timeline; previously reviewed work remains available. */
    suspend fun createVisualRevision(id:String):VideoProject=withContext(Dispatchers.IO){edits.withLock {
        requireNoBatch()
        check(busy.value==null){"Tạm dừng tác vụ trước khi tạo bản quét mới."}
        val old=store.load(id)
        require(old.sourceMode==VideoSourceMode.OCR){"Bản quét ảnh chỉ dành cho phụ đề dính trong hình."}
        val created=store.create(Uri.parse(old.uri),old.title.take(170)+" · OCR Gemini",
            VideoMetadata(old.durationMs,old.width,old.height))
        // A rescan of an unconfirmed batch part keeps the same privacy boundary.
        val owner=File(store.folder(old.id),"batch-owner")
        if(owner.isFile){
            require(owner.length()==36L){"Dấu dự án hàng loạt không hợp lệ; dự án cũ được giữ."}
            owner.copyTo(File(store.folder(created.id),"batch-owner"))
        }
        store.save(created.copy(sourceMode=VideoSourceMode.OCR,
            translationMode=VideoTranslationMode.GEMINI,
            roi=old.roi,style=old.style,glossary=old.glossary,profile=old.profile,visualSampleMs=old.visualSampleMs,
            originalSourceUri=old.originalSourceUri,originalSubtitleUri=old.originalSubtitleUri,
            message="Bản quét mới giữ khung và từ điển. Bấm Bắt đầu để gửi ảnh vùng phụ đề cho Gemini."))
            .also{refresh()}
    }}
    suspend fun tracks(project:VideoProject)=withContext(Dispatchers.IO){
        VideoSubtitles.embedded(context,Uri.parse(project.uri),project.durationMs,probeOnly=true).first
    }
    suspend fun selectSubtitle(id:String,uri:Uri,name:String)=withContext(Dispatchers.IO){edits.withLock {
        requireNoBatch()
        check(busy.value!=id){"Tạm dừng trước khi chọn phụ đề khác."}
        val old=store.load(id)
        require(!old.hasSpeechSource() || old.cues.none { it.protectedText }) {
            "Tạo dự án mới trước khi thay nguồn giọng nói để giữ các câu đã duyệt và sửa tay."
        }
        val retained=store.retainSubtitle(id,uri)
        store.save(old.copy(sourceMode=VideoSourceMode.SIDECAR,subtitleUri=retained.toString(),originalSubtitleUri=uri.toString(),
            subtitleName=name,cues=emptyList(),scanMs=0,carry=org.json.JSONObject(),speech=org.json.JSONObject(),stage=VideoStage.READY,
            resumeStage=VideoStage.EXTRACTING,pipeline=VideoPipelineCheckpoint(contextProfile=old.pipeline.contextProfile,contextRevision=old.pipeline.contextRevision,sharedRoiRevision=old.pipeline.sharedRoiRevision),exportedMp4="",progress=0,message="Đã chọn phụ đề mới. Bấm Bắt đầu để xử lý."));refresh()
    }}
    suspend fun save(project:VideoProject):VideoProject=withContext(Dispatchers.IO){ edits.withLock {
        requireNoBatch()
        check(busy.value!=project.id){"Tạm dừng tác vụ trước khi chỉnh sửa."}
        val old=store.load(project.id)
        require(project.visualSampleMs in setOf(250L,500L))
        require(old.visualSampleMs==project.visualSampleMs || (old.cues.isEmpty() && old.scanMs==0L)){
            "Tạo bản quét mới trước khi đổi tốc độ lấy mẫu để giữ timeline và câu đã duyệt."}
        val extractionChanged=old.sourceMode!=project.sourceMode || old.subtitleUri!=project.subtitleUri || old.trackId!=project.trackId || old.roi!=project.roi
        require(!extractionChanged || (project.updatedAt==old.updatedAt && project.cues==old.cues && project.scanMs==old.scanMs)) {
            "Dự án đã thay đổi; mở lại cấu hình trước khi thay nguồn hoặc vùng OCR. Nội dung mới vẫn được giữ."
        }
        val changesSpeech = old.hasSpeechSource() || project.sourceMode == VideoSourceMode.ASR ||
            (old.sourceMode != project.sourceMode && project.sourceMode == VideoSourceMode.AUTO)
        require(!extractionChanged || !changesSpeech || old.cues.none { it.protectedText }) {
            "Tạo dự án mới trước khi thay nguồn hoặc vùng OCR để giữ các câu đã duyệt và sửa tay."
        }
        val translationChanged=old.translationMode!=project.translationMode || old.glossary!=project.glossary || old.profile!=project.profile
        val extractionPending=old.sourceExtractionPending
        val compatibleHybridCarry=project.translationMode==VideoTranslationMode.HYBRID &&
            (old.carry.optBoolean("visualScan") || old.carry.optInt("draftVersion")>=2)
        val ocrRouteChanged=old.sourceMode==VideoSourceMode.OCR && old.translationMode!=project.translationMode && !compatibleHybridCarry
        require(!extractionPending || old.scanMs==0L || !ocrRouteChanged || extractionChanged){
            "Đang có checkpoint OCR chưa xong. Giữ chế độ để tiếp tục, hoặc tạo bản quét mới bằng Gemini để giữ dự án cũ."}
        // Configuration edits must preserve the latest persisted timeline and checkpoint.
        val configured=old.copy(sourceMode=project.sourceMode,translationMode=project.translationMode,
            subtitleUri=project.subtitleUri,subtitleName=project.subtitleName,trackId=project.trackId,
            roi=project.roi,style=project.style,glossary=project.glossary,profile=project.profile,
            visualSampleMs=project.visualSampleMs).withOfflinePolishPolicy(project.offlinePolishPolicy)
        val retained=if(project.subtitleUri.isNotBlank() && project.subtitleUri!=old.subtitleUri)
            configured.copy(subtitleUri=store.retainSubtitle(project.id,Uri.parse(project.subtitleUri)).toString(),originalSubtitleUri=project.subtitleUri) else configured
        val next=when {
            extractionChanged->retained.copy(pipeline=VideoPipelineCheckpoint(contextProfile=old.pipeline.contextProfile,contextRevision=old.pipeline.contextRevision,sharedRoiRevision=old.pipeline.sharedRoiRevision),cues=emptyList(),scanMs=0,carry=org.json.JSONObject(),speech=org.json.JSONObject(),ocrMetrics=org.json.JSONObject(),stage=VideoStage.READY,resumeStage=VideoStage.EXTRACTING,exportedMp4="",progress=0)
            translationChanged->retained.copy(pipeline=retained.pipeline.invalidateFrom(BatchPipelineStage.TRANSLATE),cues=retained.cues.map{if(it.protectedText)it else it.copy(translation="",model="",error="",localDraft="",refinementState="",draftVi="",polishedVi="")},stage=VideoStage.PAUSED,
                resumeStage=if(extractionPending)VideoStage.EXTRACTING else VideoStage.TRANSLATING,exportedMp4="")
            else->retained.copy(exportedMp4=if(old.style!=project.style)"" else retained.exportedMp4)
        }
        store.save(next).also{refresh();queueSync(it)}
    } }
    suspend fun updateCue(id:String,cue:VideoCue)=withContext(Dispatchers.IO){ edits.withLock {
        requireNoBatch()
        check(busy.value!=id){"Tạm dừng trước khi sửa phụ đề."};val p=store.load(id)
        require(p.cues.any{it.id==cue.id})
        val previous=p.cues.first{it.id==cue.id}
        val sourceChanged=previous.source!=cue.source
        val translationEdited=previous.translation!=cue.translation
        val edited=when {
            sourceChanged && !translationEdited->cue.copy(translation="",approved=false,model="",error="",localDraft="",refinementState="",
                rawZh=previous.rawZh.ifBlank{previous.source},correctedZh=cue.source,draftVi="",polishedVi="",version=previous.version+1)
            translationEdited->cue.copy(model="user-edited",error="",rawZh=previous.rawZh.ifBlank{previous.source},
                correctedZh=if(sourceChanged)cue.source else previous.correctedZh,
                draftVi=previous.draftVi.ifBlank{previous.translation},polishedVi=cue.translation,version=previous.version+1,localDraft=if(sourceChanged)"" else previous.localDraft,
                refinementState=if(p.translationMode==VideoTranslationMode.HYBRID && cue.translation.isNotBlank())"done" else "")
            else->cue.copy(error=previous.error,model=previous.model,localDraft=previous.localDraft,refinementState=previous.refinementState)
        }
        val rows=VideoTimeline.validate(p.cues.map{if(it.id==cue.id)edited else it},p.durationMs)
        val saved=store.save(p.copy(cues=rows,pipeline=p.pipeline.invalidateFrom(if(sourceChanged)BatchPipelineStage.CLEAN else BatchPipelineStage.POLISH),exportedMp4="",stage=VideoStage.REVIEW,
            resumeStage=if(p.scanMs<p.durationMs && p.resumeStage==VideoStage.EXTRACTING)VideoStage.EXTRACTING else VideoStage.TRANSLATING,
            message="Đã lưu thay đổi. Approved chỉ áp dụng khi bạn xác nhận."));refresh();queueSync(saved)
    } }
    suspend fun retryCue(id:String,cueId:String)=withContext(Dispatchers.IO){
        edits.withLock {
        requireNoBatch()
        check(busy.value==null){"Tạm dừng tác vụ hiện tại."};val p=store.load(id)
        require(p.cues.any{it.id==cueId})
        store.save(p.copy(pipeline=p.pipeline.invalidateFrom(BatchPipelineStage.TRANSLATE),cues=p.cues.map{if(it.id==cueId)it.copy(translation="",approved=false,model="",error="",localDraft="",refinementState="",draftVi="",polishedVi="") else it},stage=VideoStage.PAUSED,
            resumeStage=if(p.sourceExtractionPending)VideoStage.EXTRACTING else VideoStage.TRANSLATING,exportedMp4=""));refresh()
        };start(id)
    }
    private fun VideoProject.hasSpeechSource() = sourceMode == VideoSourceMode.ASR ||
        speech.optBoolean("asrActive") || cues.any { it.origin == "ASR" }
    suspend fun cancelEconomy(id:String)=withContext(Dispatchers.IO){edits.withLock {
        requireNoBatch();check(busy.value==null){"Tạm dừng xử lý trước khi hủy tác vụ Gemini nền."}
        VideoEconomyBatch(context,id).cancel(AppSettings(context).effectiveStandaloneApiKey()) {
            currentCoroutineContext().ensureActive()
        }
    }}
    @Synchronized fun start(id:String)=dispatch(id,false)
    @Synchronized fun exportMp4(id:String)=dispatch(id,true)
    private fun dispatch(id:String,export:Boolean){
        requireNoBatch()
        check(edits.tryLock()){ "Đang lưu thay đổi. Thử lại sau khi lưu xong." }
        try {
        check(busy.value==null){"Chỉ xử lý một video mỗi lần."}
        store.load(id);pauseRequested=false;mutableBusy.value=id
        try {ContextCompat.startForegroundService(context,Intent(context,VideoProcessingService::class.java).putExtra("project",id).putExtra("export",export))}
        catch(e:Exception){mutableBusy.value=null;throw e}
        } finally {edits.unlock()}
    }
    @Synchronized fun pause(){pauseRequested=true;worker?.cancel()}
    private fun requireNoBatch(){check(mutableBatchBusy.value==null){"Tạm dừng dịch hàng loạt trước khi sửa hoặc xử lý video riêng."}}
    @Synchronized internal fun reserveBatch(batchId:String){
        check(mutableBatchBusy.value==null && busy.value==null && worker?.isActive!=true){"Tạm dừng tác vụ video hiện tại trước khi xử lý hàng loạt."}
        check(edits.tryLock()){"Đang lưu thay đổi. Hãy thử lại sau."}
        try { mutableBatchBusy.value=batchId } finally { edits.unlock() }
    }
    @Synchronized internal fun releaseBatch(batchId:String){if(mutableBatchBusy.value==batchId){
        mutableBatchBusy.value=null
        if(worker?.isActive!=true){runCatching { speechSession?.close() };speechSession=null}
    }}
    /** Runs the existing checkpointed worker under the batch's already-active foreground service. */
    internal suspend fun runForBatch(batchId:String,id:String,export:Boolean):VideoProject = runBatchWorker(batchId,id,export,null)
    /** Each lane owns its worker state; the parent reservation survives until every lane drains. */
    internal suspend fun runParallelStageForBatch(batchId:String,id:String,stage:BatchPipelineStage,
                                                  allowMediaOverlap:Boolean=false):VideoProject {
        require(stage==BatchPipelineStage.TRANSLATE || stage==BatchPipelineStage.POLISH || stage==BatchPipelineStage.FINISH)
        val lane=VideoJobs(context,this,currentCoroutineContext())
        synchronized(this){
            check(mutableBatchBusy.value==batchId && (allowMediaOverlap || worker?.isActive!=true))
            check(busy.value!=id && id !in mutableParallelProjects.value)
            mutableParallelProjects.value=mutableParallelProjects.value+id
        }
        try {
            lane.reserveBatch(batchId)
            return lane.runStageForBatch(batchId,id,stage)
        } finally {
            lane.releaseBatch(batchId)
            lane.scope.cancel()
            synchronized(this){
                mutableParallelProjects.value=mutableParallelProjects.value-id
                refresh()
            }
        }
    }
    internal suspend fun runStageForBatch(batchId:String,id:String,stage:BatchPipelineStage):VideoProject {
        synchronized(this){
            check(mutableBatchBusy.value==batchId && busy.value==null && worker?.isActive!=true && id !in mutableParallelProjects.value){"Tác vụ hàng loạt không còn giữ quyền xử lý."}
            if(stage!=BatchPipelineStage.EXTRACT){speechSession?.close();speechSession=null}
        }
        val previous=withContext(Dispatchers.IO){store.load(id)}
        if(VideoStagedPipeline.completed(previous,stage))return previous
        // A restart must respect the provider deadline saved before the process stopped.
        // Keep the durable RETRY_WAIT intact until the cancellable wait actually expires.
        val retryAt=previous.pipeline.task(stage).nextRetryAt
        while(true){
            currentCoroutineContext().ensureActive()
            val remaining=retryAt-System.currentTimeMillis()
            if(remaining<=0)break
            delay(minOf(remaining,1_000L))
        }
        return runBatchWorker(batchId,id,false,stage)
    }
    private suspend fun runBatchWorker(batchId:String,id:String,export:Boolean,stage:BatchPipelineStage?):VideoProject {
        val done=CompletableDeferred<Unit>()
        val recoveryContext = currentCoroutineContext().minusKey(Job)
        synchronized(this){
            check(mutableBatchBusy.value==batchId && busy.value==null && worker?.isActive!=true)
            store.load(id);pauseRequested=false
            executeWithContext(id,export,stage,recoveryContext){done.complete(Unit)}
        }
        try {
            done.await()
        } catch(e:CancellationException){
            pause()
            withContext(NonCancellable){done.await()}
            throw e
        }
        return withContext(Dispatchers.IO){store.load(id)}
    }
    @Synchronized internal fun execute(id:String,exportRequested:Boolean,pipelineStage:BatchPipelineStage?=null,onDone:()->Unit){
        executeWithContext(id, exportRequested, pipelineStage, EmptyCoroutineContext, onDone)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized private fun executeWithContext(id:String,exportRequested:Boolean,pipelineStage:BatchPipelineStage?,
                                                 recoveryContext:CoroutineContext,onDone:()->Unit){
        if(worker?.isActive==true){
            return
        }
        mutableBusy.value=id
        // The batch waiter owns a reservation until onDone, even if pause arrives before dispatch.
        worker=scope.launch(context=recoveryContext,start=CoroutineStart.ATOMIC){
            var p=try{store.load(id)}catch(e:Exception){
                try { synchronized(this@VideoJobs){worker=null;mutableBusy.value=null;refresh()} }
                finally { onDone() }
                return@launch
            };var lastTick=SystemClock.elapsedRealtime()
            val smartMetrics=VideoBatchTelemetry(context,id)
            val export=exportRequested || (pipelineStage==null && p.resumeStage==VideoStage.EXPORTING &&
                p.stage in setOf(VideoStage.PAUSED,VideoStage.FAILED,VideoStage.WAITING_LIVE))
            var intended=if(export)VideoStage.EXPORTING else if(pipelineStage==BatchPipelineStage.EXTRACT || p.sourceExtractionPending || VideoHybridWorkflow.requiresExtraction(p))VideoStage.EXTRACTING else if(p.cues.isNotEmpty() && p.resumeStage!=VideoStage.EXTRACTING)VideoStage.TRANSLATING else VideoStage.EXTRACTING
            val profiler = runCatching {
                VideoBatchProfiler(smartMetrics, VideoProfileStage.forWorker(pipelineStage, intended))
            }.getOrNull()
            var timingStage=intended
            val previousStageError = pipelineStage?.let { p.pipeline.task(it).errorCode }.orEmpty()
            suspend fun persist(next:VideoProject){
                val now=SystemClock.elapsedRealtime();val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                val delta=(now-lastTick).coerceAtLeast(0)
                val checkpointAt=System.nanoTime()
                var captured=when(pipelineStage){
                    BatchPipelineStage.EXTRACT -> next.copy(cues=next.cues.map{if(it.rawZh.isBlank())it.copy(rawZh=it.source)else it})
                    BatchPipelineStage.TRANSLATE -> next.copy(cues=next.cues.map{cue->if(!cue.protectedText && cue.translation.isNotBlank() && cue.draftVi.isBlank())cue.copy(draftVi=cue.translation)else cue})
                    else -> next
                }
                if(pipelineStage!=null){
                    val checkpoint=when(pipelineStage){BatchPipelineStage.EXTRACT->captured.scanMs
                        BatchPipelineStage.TRANSLATE->captured.cues.count{VideoTranslationPlan.isComplete(it,captured.translationMode==VideoTranslationMode.HYBRID)}.toLong()
                        BatchPipelineStage.POLISH->captured.cues.count{it.polishedVi.isNotBlank() || it.protectedText}.toLong()
                        else->captured.cues.size.toLong()}
                    captured=captured.copy(pipeline=captured.pipeline.copy(stages=captured.pipeline.stages+
                        (pipelineStage to captured.pipeline.task(pipelineStage).copy(lastCheckpoint=checkpoint))))
                }
                if (pipelineStage != null) captured = captured.copy(progress = VideoStageProgress.percent(captured, pipelineStage))
                p=store.save(captured.copy(elapsedMs=p.elapsedMs+delta,maxPssKiB=maxOf(p.maxPssKiB,info.totalPss.toLong()),resumeStage=intended,
                    extractionElapsedMs=p.extractionElapsedMs+if(timingStage==VideoStage.EXTRACTING)delta else 0,
                    translationElapsedMs=p.translationElapsedMs+if(timingStage==VideoStage.TRANSLATING)delta else 0,
                    exportElapsedMs=p.exportElapsedMs+if(timingStage==VideoStage.EXPORTING)delta else 0))
                runCatching {
                    smartMetrics.add("checkpoint_writes")
                    smartMetrics.add("checkpoint_wall_ms", (System.nanoTime() - checkpointAt) / 1_000_000L)
                    val nextProfileStage = if (p.stage == VideoStage.WAITING_LIVE) VideoProfileStage.LIVE_WAIT
                        else VideoProfileStage.forWorker(pipelineStage, intended)
                    profiler?.checkpoint(nextProfileStage, info.totalPss.toLong())
                }
                timingStage=intended;lastTick=now;refresh()
            }
            suspend fun gate(){
                currentCoroutineContext().ensureActive()
                if (intended == VideoStage.EXTRACTING || intended == VideoStage.EXPORTING) {
                    while (com.gemini.ocr.batch.BatchPlaybackPriority.current.restricted()) {
                        delay(100)
                        lastTick = SystemClock.elapsedRealtime()
                        currentCoroutineContext().ensureActive()
                    }
                }
                if(SessionState.running.value){
                    persist(p.copy(stage=VideoStage.WAITING_LIVE,message="Đang nhường tài nguyên cho Live. Tự tiếp tục khi Live dừng."))
                    while(SessionState.running.value){delay(300);lastTick=SystemClock.elapsedRealtime()}
                    persist(p.copy(stage=intended,message="Đang tiếp tục…"))
                }
                currentCoroutineContext().ensureActive()
            }
            suspend fun retryCheckpoint(attempt:Int,at:Long,code:String){
                runCatching {
                    VideoApiRecovery.currentCapacity()?.let { smartMetrics.capacity(it.limit, it.ceiling) }
                    smartMetrics.retry(code, attempt, at)
                }
                if(pipelineStage!=null){
                    val old=p.pipeline.task(pipelineStage)
                    persist(p.copy(pipeline=p.pipeline.copy(stages=p.pipeline.stages+(pipelineStage to old.copy(
                        status="RETRY_WAIT",attempts=maxOf(old.attempts,attempt+1),nextRetryAt=at,errorCode=code))),
                        message="Đang tự khôi phục, tiến độ đã được lưu."))
                }
            }
            suspend fun dispatchCheckpoint(){
                runCatching { VideoApiRecovery.currentCapacity()?.let { smartMetrics.capacity(it.limit, it.ceiling) } }
                if(pipelineStage!=null){
                    val old=p.pipeline.task(pipelineStage)
                    persist(p.copy(cloudCalls=p.cloudCalls+1,pipeline=p.pipeline.copy(stages=p.pipeline.stages+
                        (pipelineStage to old.copy(status="RUNNING",nextRetryAt=0,errorCode="")))))
                }else persist(p.copy(cloudCalls=p.cloudCalls+1))
            }
            try {
                if(pauseRequested)throw CancellationException("Pause before service started")
                if(pipelineStage!=null){
                    if(pipelineStage!=BatchPipelineStage.EXTRACT)require(VideoStagedPipeline.extracted(p)){"Chưa hoàn tất trích xuất nguồn; mốc trước được giữ."}
                    persist(p.copy(pipeline=p.pipeline.record(pipelineStage,"RUNNING",p.scanMs)))
                    if(pipelineStage==BatchPipelineStage.CLEAN){
                        gate()
                        val asrAvailable = p.speech.optBoolean("complete") || ChineseAsrModels(context).status().ready
                        val cleaned=VideoTimeline.validate(VideoStagedPipeline.clean(p.cues,asrAvailable),p.durationMs)
                        File(store.folder(id),"source.zh.srt").writeText(VideoStagedPipeline.sourceSrt(cleaned,durationMs=p.durationMs),Charsets.UTF_8)
                        persist(p.copy(cues=cleaned,stage=VideoStage.REVIEW,progress=100,
                            pipeline=p.pipeline.record(pipelineStage,"COMPLETE",cleaned.size.toLong()).copy(asrStatus=if(p.speech.optBoolean("complete"))"COMPLETE" else if(asrAvailable)"AVAILABLE" else "UNAVAILABLE",
                                contextProfile=p.pipeline.contextProfile.ifBlank{VideoStagedPipeline.contextProfile(p)}),
                            message="Đã làm sạch ${cleaned.size} câu nguồn; chữ gốc và nguồn OCR/âm thanh được giữ. Các câu cần kiểm tra được đánh dấu."))
                        return@launch
                    }
                    if(pipelineStage==BatchPipelineStage.POLISH){
                        require(p.cues.all{it.translation.isNotBlank()}){"Có câu chưa dịch; chưa hiệu đính."}
                        if(p.translationMode==VideoTranslationMode.HYBRID){
                            require(p.cues.all{VideoTranslationPlan.isComplete(it,true)}){"Còn câu chưa được Gemini dịch; bản nháp vẫn được giữ."}
                            persist(p.copy(stage=VideoStage.REVIEW,progress=100,
                                pipeline=p.pipeline.record(pipelineStage,"COMPLETE",p.cues.size.toLong()),
                                cues=p.cues.map{if(it.protectedText)it else it.copy(polishedVi=it.translation)},
                                message="Gemini đã dịch với toàn bộ hội thoại. Giữ bản nháp để kiểm tra; không cần gửi thêm lượt hiệu đính tự động."))
                            return@launch
                        }
                        val offlinePolicy = if (p.translationMode == VideoTranslationMode.OFFLINE) p.offlinePolishPolicy
                            else VideoPolishPolicy.NONE
                        val optedIn = offlinePolicy != VideoPolishPolicy.NONE
                        if(p.translationMode==VideoTranslationMode.OFFLINE && !optedIn){
                            persist(p.copy(stage=VideoStage.REVIEW,progress=100,
                                pipeline=p.pipeline.record(pipelineStage,"COMPLETE",p.cues.size.toLong()),
                                cues=p.cues.map{if(it.protectedText)it else it.copy(qualityFlags=(it.qualityFlags+"POLISH_OFFLINE_REVIEW").distinct())},
                                message="Đã giữ bản dịch offline. Không gọi AI khi bạn chọn offline; các câu cần kiểm tra được đánh dấu."))
                            return@launch
                        }
                        val client=VideoPolishClient(context)
                        val attemptedIds = mutableSetOf<String>()
                        val failedPolishIds = mutableSetOf<String>()
                        fun isTarget(cue: VideoCue) = if (offlinePolicy == VideoPolishPolicy.ALL)
                            !cue.protectedText && cue.translation.isNotBlank() && cue.polishedVi.isBlank()
                            else VideoStagedPipeline.needsPolish(cue)
                        fun markPending(cue: VideoCue, message: String): VideoCue = cue.copy(
                            qualityFlags = (cue.qualityFlags + if (optedIn) "POLISH_PENDING" else "POLISH_NEEDS_REVIEW").distinct(),
                            error = message)
                        while(true){
                            gate()
                            val candidates=p.cues.map{cue->if(isTarget(cue) && cue.id !in attemptedIds)
                                cue.copy(translation="",localDraft=cue.draftVi.ifBlank{cue.translation},refinementState="pending")
                                else cue.copy(approved=true)}
                            val selectedBatch=VideoTranslationPlan.nextCloudBatch(candidates,true,maxCues=VideoSmartBatchSettings(context).groupSize)?:break
                            val batch=VideoStagedPipeline.withContext(selectedBatch,p.pipeline.contextProfile)
                            attemptedIds += batch.cueIds
                            var dispatched = false
                            try {
                                val outcome = client.polish(AppSettings(context).effectiveStandaloneApiKey(), batch,
                                    p.profile, p.glossary, onRetry = ::retryCheckpoint, allowPartial = optedIn) {
                                    gate(); smartMetrics.requireQuotaReady(); dispatchCheckpoint()
                                    dispatched = true
                                    runCatching { smartMetrics.dispatch(batch.indices.size, false) }
                                }
                                runCatching { smartMetrics.response(outcome) }
                                val accepted = batch.cueIds.indices.filter { batch.cueIds[it] in outcome.accepted }
                                val selected = accepted.takeIf { it.isNotEmpty() }?.let { VideoTranslationPlan.selectBatch(batch, it) }
                                val updated = if (selected == null) p.cues else VideoTranslationPlan.applyCloudResult(
                                    p.cues, selected, selected.cueIds.map { outcome.accepted.getValue(it) }, outcome.model, false, p.glossary)
                                val acceptedIds = outcome.accepted.keys
                                val missingIds = outcome.missingIds.toSet()
                                val polished = updated.mapIndexed { index, cue -> when (cue.id) {
                                    in acceptedIds -> cue.copy(
                                        draftVi = p.cues[index].draftVi.ifBlank { p.cues[index].translation }, polishedVi = cue.translation,
                                        qualityFlags = (cue.qualityFlags.filterNot { it == "POLISH_PENDING" || it == "POLISH_NEEDS_REVIEW" } +
                                            if (cue.id in outcome.needsReviewIds) listOf("POLISH_NEEDS_REVIEW") else emptyList()).distinct(), version = cue.version + 1)
                                    in missingIds -> markPending(cue, "Gemini chưa trả kết quả hợp lệ cho câu này. Bản dịch nháp được giữ.")
                                    else -> cue
                                } }
                                failedPolishIds += missingIds
                                persist(p.copy(cues = polished, progress = polished.count { !isTarget(it) } * 100 / polished.size))
                            }catch(e:CancellationException){throw e}
                            catch(e:Exception){
                                val stopCloud = e is VideoQuotaWaitException || e is VideoApiException &&
                                    (e.code in setOf("API_AUTH", "API_QUOTA_EXHAUSTED", "API_RATE_LIMIT") || e.nextRetryAt > 0 ||
                                        optedIn && e.transient)
                                runCatching {
                                    if (dispatched) smartMetrics.failure(VideoStagedPipeline.errorCode(e))
                                    if (e is VideoApiException && e.code in setOf("API_RATE_LIMIT", "API_QUOTA_EXHAUSTED")) smartMetrics.quotaFailure()
                                }
                                failedPolishIds += batch.cueIds
                                val failedIds = batch.cueIds.toSet()
                                val failureMessage = if (e is VideoApiException || e is VideoQuotaWaitException) e.message.orEmpty().take(300)
                                    else "Chưa thể hiệu đính nhóm này. Bản dịch nháp được giữ."
                                persist(p.copy(cues=p.cues.map{if(it.id in failedIds && !it.protectedText)markPending(it, failureMessage)else it},
                                    message=if (stopCloud) "Đã giữ bản nháp và dừng hiệu đính."
                                    else "Đã giữ bản nháp nhóm chưa hiệu đính được; tiếp tục nhóm độc lập khác."))
                                if (stopCloud) throw e
                            }
                        }
                        require(failedPolishIds.isEmpty()){
                            if (optedIn) "Còn câu chưa hiệu đính. Tiếp tục để thử các câu còn thiếu, hoặc chọn giữ bản dịch offline."
                            else "Một số câu cần kiểm tra hiệu đính; bản nháp, bản đã hiệu đính và mốc thời gian được giữ."
                        }
                        persist(p.copy(stage=VideoStage.REVIEW,progress=100,pipeline=p.pipeline.record(pipelineStage,"COMPLETE",p.cues.size.toLong()),
                            message=if (offlinePolicy == VideoPolishPolicy.ALL) "Đã hiệu đính các bản nháp chưa duyệt; nguồn và bản nháp vẫn được giữ."
                            else "Đã kiểm tra bản dịch; chỉ hiệu đính các câu được đánh dấu hoặc quá dài. Bản nháp và nguồn vẫn được giữ."))
                        return@launch
                    }
                    if(pipelineStage==BatchPipelineStage.FINISH){
                        require(p.cues.all{VideoTranslationPlan.isComplete(it,p.translationMode==VideoTranslationMode.HYBRID)}){"Còn câu chưa dịch; chưa đánh dấu hoàn tất."}
                        VideoTimeline.validate(p.cues,p.durationMs)
                        store.exportFile(id,"srt").writeText(VideoTimeline.srt(p.cues,p.durationMs),Charsets.UTF_8)
                        store.exportFile(id,"ass").writeText(VideoTimeline.ass(p),Charsets.UTF_8)
                        persist(p.copy(stage=VideoStage.REVIEW,progress=100,pipeline=p.pipeline.record(pipelineStage,"COMPLETE",p.cues.size.toLong()),
                            message="Đã lưu phụ đề. Kiểm tra các câu được đánh dấu rồi xác nhận xuất hoặc lưu Kho phim."))
                        return@launch
                    }
                }
                if(export){
                    require(!p.sourceExtractionPending){"Chưa nhận hết phụ đề nguồn. Bấm Tiếp tục xử lý trước khi xuất."}
                    require(p.cues.isNotEmpty() && p.cues.all{it.translation.isNotBlank()}){"Dịch hoặc sửa xong các câu trước khi xuất MP4."}
                    VideoTimeline.validate(p.cues,p.durationMs);gate();persist(p.copy(stage=intended,progress=0,message="Đang xuất MP4 có phụ đề…"))
                    val output=store.exportFile(id,"partial.mp4")
                    if(output.exists())check(output.delete()) { "Không thể dọn bản xuất tạm của dự án." }
                    while(true){
                        gate()
                        try {VideoExporter.export(context,p,output,::gate){fraction->persist(p.copy(stage=VideoStage.EXPORTING,progress=(fraction*100).toInt(),message="Đang xuất MP4…"))};break}
                        catch(_:VideoExporter.LivePreemptedException){gate();persist(p.copy(progress=0,message="Tiếp tục xuất MP4 từ đầu giai đoạn sau Live."))}
                    }
                    val final=store.exportFile(id,"mp4")
                    // The only replacement is this project's generated output; source URI is untouched.
                    if(final.exists())check(final.delete()){"Không thể thay bản xuất cũ."}
                    check(output.renameTo(final)){"Không thể hoàn tất MP4."}
                    persist(p.copy(stage=VideoStage.COMPLETE,progress=100,exportedMp4=final.absolutePath,message="Đã tạo MP4. Bấm Lưu MP4 để chọn thư mục."))
                } else {
                    val hybrid=p.translationMode==VideoTranslationMode.HYBRID
                    val sourceFirst = intended==VideoStage.EXTRACTING && p.sourceMode in setOf(VideoSourceMode.AUTO,VideoSourceMode.ASR)
                    if(!sourceFirst && !p.speech.optBoolean("noSpeech") && pipelineStage!=BatchPipelineStage.EXTRACT && p.translationMode==VideoTranslationMode.OFFLINE &&
                        (p.cues.isEmpty() || p.cues.any{VideoTranslationPlan.needsLocalDraft(it,false)}))
                        check(VideoOfflineTranslator.ready()){"OFFLINE_MODEL_MISSING: Thiếu model offline. Bấm Chuẩn bị offline khi có mạng."}
                    if(!sourceFirst && !p.speech.optBoolean("noSpeech") && pipelineStage!=BatchPipelineStage.EXTRACT &&
                        p.translationMode==VideoTranslationMode.GEMINI && !AppSettings(context).hasGeminiVideoKey())
                        throw VideoApiException("API_AUTH", detail = "Chưa cấu hình khóa Gemini trong Cài đặt.")
                    if(intended==VideoStage.EXTRACTING){
                        if(hybrid)persist(VideoHybridWorkflow.prepareLocalExtraction(p))
                        gate();persist(p.copy(stage=intended,message="Đang lấy phụ đề nguồn…"))
                        suspend fun readOcr(forceLocal:Boolean=false): List<VideoCue> {
                                val visual=!forceLocal && pipelineStage==null && p.translationMode==VideoTranslationMode.GEMINI
                                val checkpoint:suspend(Long,List<VideoCue>,org.json.JSONObject)->Unit={ms,rows,carry->persist(p.copy(scanMs=ms,cues=rows,carry=carry,
                                    ocrMetrics=carry.optJSONObject("metrics")?:p.ocrMetrics,progress=(ms*100/p.durationMs).toInt(),
                                    message="${if(visual)"Gemini đang đọc ảnh vùng chữ" else "Đang OCR offline vùng chữ"}: ${ms/1000}/${p.durationMs/1000} giây"))}
                                return if(visual)VideoVisualDecoder.scan(context,p,::gate,{gate();persist(p.copy(cloudCalls=p.cloudCalls+1))},checkpoint)
                                else if(forceLocal || pipelineStage==BatchPipelineStage.EXTRACT || hybrid)VideoDecodeRecovery.run(onRetry={attempt,error->
                                    gate()
                                    val stage=BatchPipelineStage.EXTRACT;val old=p.pipeline.task(stage)
                                    persist(p.copy(pipeline=if(pipelineStage==stage)p.pipeline.copy(stages=p.pipeline.stages+(stage to old.copy(
                                        status="RETRY_WAIT",errorCode=error.code,nextRetryAt=0)))else p.pipeline,
                                        message="Bộ đọc video tạm ngừng. Đang mở lại ($attempt/${VideoDecodeRecovery.MAX_REOPENS}); các câu đã nhận được giữ."))
                                }){
                                    gate()
                                    val stage=BatchPipelineStage.EXTRACT;val old=p.pipeline.task(stage)
                                    if(pipelineStage==stage && old.status=="RETRY_WAIT")persist(p.copy(stage=intended,
                                        pipeline=p.pipeline.copy(stages=p.pipeline.stages+(stage to old.copy(
                                            status="RUNNING",attempts=old.attempts+1,errorCode="",nextRetryAt=0))),
                                        message="Đang tiếp tục đọc video từ mốc đã lưu ${p.scanMs/1000} giây…"))
                                    // p is refreshed by every checkpoint, including the one forced on
                                    // a native failure. Never reopen from the original project snapshot.
                                    VideoDecoder.scanDrafts(context,p,::gate,checkpoint)
                                }
                                else VideoDecoder.scan(context,p,::gate,checkpoint)
                        }
                        suspend fun readSpeech(): List<VideoCue> {
                            gate()
                            if(p.speech.optBoolean("complete") && p.speech.optString("sourceSha")==VideoSpeechExtractor.sourceHash(context,Uri.parse(p.uri),::gate))return p.cues
                            persist(p.copy(speech=org.json.JSONObject(p.speech.toString()).put("asrActive",true).put("complete",false),
                                message=if(p.cues.isEmpty())"Đang chuẩn bị nhận diện giọng nói offline…" else "OCR thưa; có thể do nội dung hoặc vùng chữ. Đang kiểm tra lời nói để bổ sung khoảng trống; chữ OCR được giữ."))
                            val recognizer=speechRecognizer()
                            return VideoSpeechExtractor.scan(context,p,recognizer,::gate) { ms,rows,state ->
                                persist(p.copy(scanMs=ms,cues=rows,speech=state,progress=(ms*100/p.durationMs).toInt(),
                                    message="Đang nhận diện giọng nói: ${ms/1000}/${p.durationMs/1000} giây · Đã tạo ${rows.count{it.origin=="ASR"}} câu. ${if(state.optBoolean("needsReview"))"Có đoạn giao với OCR cần kiểm tra." else ""}"))
                            }
                        }
                        suspend fun readAuto():List<VideoCue> {
                            if(p.speech.optBoolean("asrActive"))return readSpeech()
                            if(!p.speech.optBoolean("probeComplete") && p.scanMs==0L) {
                                persist(p.copy(message="Đang kiểm tra phụ đề có sẵn…"))
                                val embedded=if(p.subtitleUri.isNotBlank())VideoSubtitles.readSidecar(context,p) else {
                                    val uri = Uri.parse(p.uri)
                                    val tracks = VideoSubtitles.embedded(context,uri,p.durationMs,probeOnly=true,gate=::gate).first
                                    val track = if(p.trackId>=0)tracks.firstOrNull{it.id==p.trackId} else preferredVideoTextTrack(tracks)
                                    if(track==null)emptyList()
                                    else VideoSubtitles.embedded(context,uri,p.durationMs,track.id,gate=::gate,allowEmpty=true).second
                                        .takeIf { rows -> rows.any { row -> row.source.any { it in '\u3400'..'\u9fff' } } }.orEmpty()
                                }
                                val selected=if(embedded.isNotEmpty())if(p.subtitleUri.isNotBlank())"SIDECAR" else "EMBEDDED" else "OCR"
                                // Text rows and the source decision share one AtomicFile checkpoint.
                                persist(p.copy(cues=if(embedded.isNotEmpty())embedded else p.cues,
                                    scanMs=if(embedded.isNotEmpty())p.durationMs else p.scanMs,
                                    speech=org.json.JSONObject(p.speech.toString()).put("probeComplete",true).put("resolvedSource",selected)))
                                if(embedded.isNotEmpty())return embedded
                            }
                            if(p.speech.optString("resolvedSource") in setOf("EMBEDDED","SIDECAR") && p.scanMs>=p.durationMs)return p.cues
                            val rows=if(p.speech.optBoolean("ocrComplete"))p.cues else readOcr(forceLocal=true)
                            persist(p.copy(speech=org.json.JSONObject(p.speech.toString()).put("ocrComplete",true).put("resolvedSource","OCR")))
                            return if(VideoSourceSelection.assessSpeech(p,rows))readSpeech() else rows
                        }
                        val cues=when(p.sourceMode){
                            VideoSourceMode.SIDECAR->{require(p.subtitleUri.isNotBlank()){"Chọn tệp SRT/ASS/VTT trước."};VideoSubtitles.readSidecar(context,p)}
                            VideoSourceMode.EMBEDDED->VideoSubtitles.embedded(context,Uri.parse(p.uri),p.durationMs,p.trackId,gate=::gate).second
                            VideoSourceMode.OCR->readOcr()
                            VideoSourceMode.ASR->readSpeech()
                            VideoSourceMode.AUTO->readAuto()
                        }
                        require(cues.isNotEmpty() || p.speech.optBoolean("noSpeech")){
                            if(p.speech.optInt("unclearWindows")>0)"ASR_UNCLEAR_AUDIO: Có đoạn lời nói chưa nhận rõ. Kiểm tra âm thanh, chỉnh nguồn hoặc phụ đề; không tạo thoại giả."
                            else "Không tìm thấy chữ Trung trong vùng đã chọn. Kiểm tra vùng OCR, chọn phụ đề đi kèm hoặc Nhận diện giọng nói."
                        }
                        intended=VideoStage.TRANSLATING
                        val fresh=if(pipelineStage!=null)cues.map{it.copy(rawZh=it.rawZh.ifBlank{it.source})} else cues
                        // Sidecar/embedded imports are atomic. If an older checkpoint marked
                        // them partial, retain every saved row while adding the missing IDs.
                        val extracted=if(hybrid && p.sourceMode in setOf(VideoSourceMode.EMBEDDED,VideoSourceMode.SIDECAR) && p.cues.isNotEmpty()){
                            val saved=p.cues.associateBy{it.id};val freshIds=fresh.map{it.id}.toSet()
                            require(saved.keys.all{it in freshIds}){"Nguồn phụ đề đã thay đổi; giữ các câu đã lưu để kiểm tra trước khi nhập lại."}
                            fresh.map{saved[it.id]?:it}
                        }else fresh
                        if(hybrid)VideoTimeline.validate(extracted,p.durationMs)
                        val sourceState = if(p.sourceMode == VideoSourceMode.AUTO)
                            org.json.JSONObject(p.speech.toString()).put("resolutionComplete",true) else p.speech
                        persist(p.copy(cues=extracted,scanMs=p.durationMs,carry=org.json.JSONObject(),speech=sourceState,
                            stage=intended,progress=0,message="Đã lấy ${cues.size} câu nguồn."))
                    }
                    if (pipelineStage == null && mutableBatchBusy.value == null) {
                        speechSession?.close()
                        speechSession = null
                    }
                    if(hybrid){
                        require(!VideoHybridWorkflow.requiresExtraction(p)){"Chưa lấy hết phụ đề nguồn; tiếp tục quét trước khi gửi Gemini."}
                        val ordered=VideoTimeline.validate(p.cues,p.durationMs)
                        File(store.folder(id),"source.zh.srt").writeText(VideoStagedPipeline.sourceSrt(ordered,true,p.durationMs),Charsets.UTF_8)
                        if(ordered!=p.cues)persist(p.copy(cues=ordered))
                    }
                    val waitForTranslationTools = pipelineStage==null && p.sourceMode in setOf(VideoSourceMode.AUTO,VideoSourceMode.ASR) &&
                        ((p.translationMode==VideoTranslationMode.OFFLINE && !VideoOfflineTranslator.ready()) ||
                            (p.translationMode!=VideoTranslationMode.OFFLINE && !AppSettings(context).hasGeminiVideoKey()))
                    if(pipelineStage==BatchPipelineStage.EXTRACT || waitForTranslationTools || (pipelineStage==null && p.speech.optBoolean("noSpeech"))){
                        File(store.folder(id),"source.zh.srt").writeText(VideoStagedPipeline.sourceSrt(p.cues,true,p.durationMs),Charsets.UTF_8)
                        persist(p.copy(stage=VideoStage.REVIEW,progress=100,pipeline=if(pipelineStage==null)p.pipeline else p.pipeline.record(pipelineStage,"COMPLETE",p.scanMs),
                            message=if(p.speech.optBoolean("noSpeech"))"Không phát hiện lời nói hoặc phụ đề hợp lệ; không tạo câu giả. Kiểm tra âm thanh/vùng chữ nếu bạn vẫn nghe thấy thoại."
                                else "Đã lưu ${p.cues.size} câu nguồn và mốc thời gian. ${if(waitForTranslationTools)"Chuẩn bị công cụ dịch rồi bấm Tiếp tục; không phải nhận diện lại." else "Chưa gửi câu nào để dịch."}"))
                        return@launch
                    }
                    if(hybrid && p.cues.any{!VideoTranslationPlan.isComplete(it,true)})check(AppSettings(context).hasGeminiVideoKey()){
                        "Đã lấy đủ nguồn (100%) và lưu phụ đề Trung. Cấu hình khóa Gemini trong Cài đặt để dịch toàn bộ hội thoại."}
                    require(p.cues.none{it.approved && it.translation.isBlank()}){"Câu đã duyệt chưa có bản dịch; hãy sửa câu trước khi tiếp tục."}
                    val local=if(p.translationMode==VideoTranslationMode.OFFLINE && p.cues.any{VideoTranslationPlan.needsLocalDraft(it,false)})VideoOfflineTranslator() else null
                    val contextualDrafts = if (local != null && p.offlinePolishPolicy != VideoPolishPolicy.NONE &&
                        pipelineStage == BatchPipelineStage.TRANSLATE) VideoOfflineDraftCache().apply {
                        p.cues.forEachIndexed { index, cue ->
                            if (cue.model == VideoOfflineTranslator.MODEL) remember(p.cues, index, cue.localDraft)
                        }
                    } else null
                    var failedGroup: Exception? = null
                    try {
                        if(local!=null)while(true){
                            gate();val index=p.cues.indexOfFirst{VideoTranslationPlan.needsLocalDraft(it,hybrid)};if(index<0)break
                            val row=p.cues[index]
                            persist(p.copy(stage=VideoStage.TRANSLATING,message="Đang dịch offline câu ${index+1}/${p.cues.size}${if(hybrid)"; tiếp theo Gemini hiệu chỉnh ngữ cảnh" else ""}…"))
                            // Translate each complete subtitle, never infer cue mapping from returned line breaks.
                            // An exact duplicate can reuse this project's local result; no contextual cloud reuse.
                            val translated = if (contextualDrafts != null) {
                                contextualDrafts.lookup(p.cues, index) ?: run {
                                    val raw = local.translate(row.source.replace(Regex("\\s*\\n\\s*"), " "))
                                    StandaloneTranslator.applyGlossaryPostProcess(listOf(row.source), listOf(raw), p.glossary,
                                        enrichWithBank = false).single().also { contextualDrafts.remember(p.cues, index, it) }
                                }
                            } else {
                                val reused=p.cues.firstOrNull{it.id!=row.id && !it.protectedText && it.source==row.source && it.localDraft.isNotBlank()}?.localDraft
                                val raw=reused?:local.translate(row.source.replace(Regex("\\s*\\n\\s*")," "))
                                StandaloneTranslator.applyGlossaryPostProcess(listOf(row.source),listOf(raw),p.glossary,enrichWithBank=false).single()
                            }
                            val updated=p.cues.toMutableList();updated[index]=row.copy(translation=translated,localDraft=translated,
                                refinementState=if(hybrid)"pending" else "",approved=false,model=VideoOfflineTranslator.MODEL,error="")
                            persist(p.copy(cues=updated,model=VideoOfflineTranslator.MODEL,
                                progress=updated.count{VideoTranslationPlan.isComplete(it,hybrid)}*100/updated.size))
                        }
                        var pendingRepair:VideoTranslationPlan.Batch?=null
                        val textClient=VideoTranslationClient(context)
                        val economy=if(VideoSmartBatchSettings(context).economyMode) VideoEconomyBatch(context,id) else null
                        economy?.reconcile(p)
                        val recoverHybrid = hybrid && pipelineStage == BatchPipelineStage.TRANSLATE && economy == null
                        val hybridRecovery = if (recoverHybrid) VideoHybridRecovery(
                            p.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit, previousStageError) else null
                        var hybridTargetLimit = hybridRecovery?.targetLimit ?: Int.MAX_VALUE
                        if (recoverHybrid && p.pipeline.task(BatchPipelineStage.TRANSLATE).targetCueLimit != hybridTargetLimit) {
                            val task = p.pipeline.task(BatchPipelineStage.TRANSLATE)
                            persist(p.copy(pipeline = p.pipeline.copy(stages = p.pipeline.stages +
                                (BatchPipelineStage.TRANSLATE to task.copy(targetCueLimit = hybridTargetLimit)))))
                        }
                        var emptyHybridReplies = 0
                        val groupSize=VideoSmartBatchSettings(context).groupSize
                        var recoveryGroupLimit=if (pipelineStage == BatchPipelineStage.TRANSLATE &&
                            previousStageError in setOf("TEXT_INCOMPLETE_RESPONSE", "TEXT_TRUNCATED")) maxOf(1, groupSize / 2) else groupSize
                        var failedCloudIds=emptySet<String>()
                        var splitRetriedIds=emptySet<String>()
                        if(p.translationMode!=VideoTranslationMode.OFFLINE)while(true){
                            gate();val repairing=pendingRepair!=null
                            val pendingCues=if(pipelineStage==BatchPipelineStage.TRANSLATE)p.cues.map{if(it.id in failedCloudIds)it.copy(approved=true)else it} else p.cues
                            val selectedBatch=if(hybrid)VideoFullDialoguePlan.next(p.cues,p.durationMs,p.pipeline.contextProfile,hybridTargetLimit){candidate->
                                runCatching{VideoTranslationClient.buildRequest(candidate,p.profile,p.glossary)}.isSuccess
                            }?:break
                                else pendingRepair?:VideoTranslationPlan.nextCloudBatch(pendingCues,false,maxCues=recoveryGroupLimit)?:break
                            val batch=if(pipelineStage!=null && !hybrid)VideoStagedPipeline.withContext(selectedBatch,p.pipeline.contextProfile)else selectedBatch
                            pendingRepair=null
                            val reviewed=if(repairing)null else batchReviewedMemory.lookup(p,batch)
                            if(reviewed!=null){
                                val selected=VideoTranslationPlan.selectBatch(batch,reviewed.positions)
                                val updated=VideoTranslationPlan.applyCloudResult(p.cues,selected,reviewed.translations,reviewed.scope.modelVersion,hybrid,p.glossary)
                                persist(p.copy(cues=updated,model=reviewed.scope.modelVersion,progress=updated.count{VideoTranslationPlan.isComplete(it,hybrid)}*100/updated.size,
                                    message="Đã dùng câu đã duyệt khớp nguyên văn trong phạm vi phim đã chọn. Các câu mới vẫn được xử lý."))
                                continue
                            }
                            val cacheAt=System.nanoTime()
                            val cached=if(repairing)null else batchCache.lookup(p,batch)
                            if(!repairing)runCatching{smartMetrics.add("cache_lookups");smartMetrics.add("cache_lookup_wall_ms",(System.nanoTime()-cacheAt)/1_000_000L)}
                            if(cached!=null){
                                runCatching{smartMetrics.add("cache_${cached.level.name.lowercase()}_hits");smartMetrics.flush()}
                                val updated=VideoTranslationPlan.applyCloudResult(p.cues,batch,cached.translations,cached.model,hybrid,p.glossary)
                                persist(p.copy(cues=updated,model=cached.model,progress=updated.count{VideoTranslationPlan.isComplete(it,hybrid)}*100/updated.size,
                                    message="Đã dùng bản dịch khớp nguyên văn và ngữ cảnh trong dự án hàng loạt."))
                                continue
                            }
                            persist(p.copy(stage=VideoStage.TRANSLATING,
                                message=if(hybrid)"Đã quét nguồn 100%. Gemini đang dịch phần ${batch.cueIds.size} câu, trong ${p.cues.count{!VideoTranslationPlan.isComplete(it,true)}} câu còn thiếu. Tự chọn phần lớn nhất vừa giới hạn…"
                                    else "${if(repairing)"Gemini đang sửa ký tự lạ trong" else "Gemini dịch"} câu ${batch.indices.first()+1}–${batch.indices.last()+1}/${p.cues.size}…"))
                            try {
                                suspend fun request():VideoTranslationClient.Result {
                                    val dispatch:suspend()->Unit={
                                    gate();smartMetrics.requireQuotaReady()
                                    dispatchCheckpoint()
                                    runCatching{smartMetrics.dispatch(batch.indices.size,repairing)}
                                    }
                                    return if(economy!=null)economy.translate(AppSettings(context).effectiveStandaloneApiKey(),batch,p.profile,p.glossary,dispatch)
                                    else textClient.translate(AppSettings(context).effectiveStandaloneApiKey(),batch,p.profile,p.glossary,onRetry=::retryCheckpoint,beforeDispatch=dispatch)
                                }
                                val outcome=try { VideoRequestDeadline.run(hybrid, pipelineStage != null, economy != null) { request() } }
                                catch(e:TimeoutCancellationException){throw VideoApiException("API_TIMEOUT", transient = true,
                                    nextRetryAt = pipelineStage?.let { p.pipeline.task(it).nextRetryAt } ?: 0,
                                    detail = "Gemini chưa hoàn tất trong thời gian chờ. Các câu đã lưu được giữ.")}
                                val bad=batch.cueIds.indices.filter{position->
                                    outcome.accepted[batch.cueIds[position]]?.let{VideoTranslationPlan.hasUnexpectedCyrillic(it,p.glossary)}?:true
                                }.toMutableList()
                                if(hybrid && outcome.truncated && bad.isEmpty())throw VideoApiException("TEXT_TRUNCATED",
                                    detail="Phản hồi bị giới hạn đầu ra nhưng chưa xác định được ID cần kiểm tra; nguồn và bản nháp được giữ.")
                                val good=batch.cueIds.indices.filter{it !in bad}
                                val accepted=good.map{outcome.accepted.getValue(batch.cueIds[it])}
                                runCatching{smartMetrics.response(outcome.copy(
                                    accepted=good.associate{batch.cueIds[it] to outcome.accepted.getValue(batch.cueIds[it])},
                                    missingIds=bad.map{batch.cueIds[it]}))}
                                if(bad.isEmpty() && (!hybrid || !outcome.truncated)) {
                                    emptyHybridReplies = 0
                                    val updated=VideoTranslationPlan.applyCloudResult(p.cues,batch,accepted,outcome.model,hybrid,p.glossary)
                                    hybridTargetLimit = hybridRecovery?.completeReply() ?: hybridTargetLimit
                                    val recoveredPipeline = if (recoverHybrid && hybridTargetLimit != Int.MAX_VALUE) {
                                        val task = p.pipeline.task(BatchPipelineStage.TRANSLATE)
                                        p.pipeline.copy(stages = p.pipeline.stages +
                                            (BatchPipelineStage.TRANSLATE to task.copy(targetCueLimit = hybridTargetLimit)))
                                    } else p.pipeline
                                    persist(p.copy(cues=updated,model=outcome.model,pipeline=recoveredPipeline,
                                        progress=updated.count{VideoTranslationPlan.isComplete(it,hybrid)}*100/updated.size))
                                    if(!repairing)runCatching{batchCache.save(p,batch,accepted,outcome.model)}
                                    economy?.acknowledge()
                                } else {
                                    // Persist valid IDs before planning any smaller request.
                                    var updated=if(good.isEmpty())p.cues else VideoTranslationPlan.applyCloudResult(p.cues,
                                        VideoTranslationPlan.selectBatch(batch,good),accepted,outcome.model,hybrid,p.glossary)
                                    val foreign=bad.joinToString(" "){position->outcome.accepted[batch.cueIds[position]].orEmpty().filter{
                                        Character.UnicodeScript.of(it.code)==Character.UnicodeScript.CYRILLIC}.take(80)}.take(180)
                                    updated=VideoTranslationPlan.markCloudFailure(updated,VideoTranslationPlan.selectBatch(batch,bad),
                                        if(foreign.isNotBlank())"Bản dịch trước chèn chữ Kirill: $foreign. Viết lại tên bằng chữ Latin/tiếng Việt theo nguồn Trung; không dùng lại chữ Kirill."
                                        else "Gemini chưa trả bản dịch hợp lệ cho ID câu này. Chỉ dịch lại câu nguồn còn thiếu; không tự đoán nội dung.",hybrid)
                                    if (recoverHybrid) {
                                        hybridTargetLimit = requireNotNull(hybridRecovery).partialReply(batch.cueIds.size)
                                        val task = p.pipeline.task(BatchPipelineStage.TRANSLATE)
                                        persist(p.copy(cues = updated, progress = updated.count { VideoTranslationPlan.isComplete(it, true) } * 100 / updated.size,
                                            pipeline = p.pipeline.copy(stages = p.pipeline.stages +
                                                (BatchPipelineStage.TRANSLATE to task.copy(targetCueLimit = hybridTargetLimit))),
                                            message = "Đã lưu câu hợp lệ. Đang chia phần còn thiếu thành nhóm tối đa $hybridTargetLimit câu để tiếp tục."))
                                        emptyHybridReplies = if (good.isEmpty()) emptyHybridReplies + 1 else 0
                                        if (emptyHybridReplies >= 3) throw VideoApiException("TEXT_EMPTY_RESPONSE",
                                            detail = "Gemini chưa trả câu hợp lệ sau ba nhóm liên tiếp. Tiến độ đã lưu; kiểm tra phần còn thiếu rồi bấm Tiếp tục.")
                                        continue
                                    }
                                    persist(p.copy(cues=updated,progress=updated.count{VideoTranslationPlan.isComplete(it,hybrid)}*100/updated.size,
                                        message=if(hybrid)"Đã lưu các câu hợp lệ; còn câu cần tiếp tục hoặc kiểm tra."
                                            else "Đã lưu câu hợp lệ. Gemini sẽ thử riêng ID còn thiếu hoặc ký tự lạ tối đa một lượt."))
                                    economy?.acknowledge()
                                    if(hybrid)throw VideoApiException(if(outcome.truncated)"TEXT_TRUNCATED" else "TEXT_INCOMPLETE_RESPONSE",
                                        detail="Đã lưu các câu hợp lệ. Phần đang dịch chưa có đủ kết quả; nguồn và bản nháp được giữ. Bấm Tiếp tục để gửi các ID còn thiếu; các phần đã xong không bị dịch lại.")
                                    if(repairing)throw VideoApiException("TEXT_INCOMPLETE_RESPONSE",
                                        detail = "Gemini vẫn thiếu bản dịch hợp lệ sau một lượt sửa. Các câu hợp lệ và bản nháp được giữ.")
                                    // One bounded retry for only missing/invalid IDs. Never replay successful rows,
                                    // an HTTP failure, auth/quota failure, timeout or an ambiguous ID response.
                                    delay(500L+kotlin.random.Random.nextLong(251L));gate()
                                    pendingRepair=VideoTranslationPlan.repairBatch(p.cues,batch,bad,hybrid)
                                }
                            }catch(e:VideoEconomyPendingException){throw e}
                            catch(e:CancellationException){throw e}
                            catch(e:Exception){
                                if (e is VideoApiException) runCatching {
                                    e.responseReason?.let { smartMetrics.responseFailure(it.id) }
                                }
                                runCatching{smartMetrics.failure(VideoStagedPipeline.errorCode(e));if(e !is VideoQuotaWaitException &&
                                    (e.message.orEmpty().contains("(429)") || e is VideoApiException && e.code in setOf("API_RATE_LIMIT","API_QUOTA_EXHAUSTED")))smartMetrics.quotaFailure();smartMetrics.flush()}
                                persist(p.copy(cues=VideoTranslationPlan.markCloudFailure(p.cues,batch,e.message?.take(300)?:"Gemini chưa hoàn tất",hybrid)))
                                if (recoverHybrid && e is VideoApiException && e.code == "TEXT_INVALID_RESPONSE") {
                                    val retry = requireNotNull(hybridRecovery).invalidReply()
                                    hybridTargetLimit = hybridRecovery.targetLimit
                                    val task = p.pipeline.task(BatchPipelineStage.TRANSLATE)
                                    persist(p.copy(pipeline = p.pipeline.copy(stages = p.pipeline.stages +
                                        (BatchPipelineStage.TRANSLATE to task.copy(targetCueLimit = hybridTargetLimit))),
                                        message = if (retry) "Phản hồi chưa hợp lệ. Đã giữ nguồn; đang thử một nhóm nhỏ hơn."
                                            else "Phản hồi vẫn chưa hợp lệ sau lượt phục hồi. Đã lưu tiến độ và nguyên nhân; không tự gửi lặp."))
                                    if (retry) { delay(500); gate(); continue }
                                }
                                if(!hybrid && pipelineStage==BatchPipelineStage.TRANSLATE && e !is VideoQuotaWaitException &&
                                    VideoStagedPipeline.errorCode(e) !in setOf("API_AUTH","API_RATE_LIMIT") &&
                                    !(e is VideoApiException && (e.transient || e.code=="API_AUTH" || e.code=="API_QUOTA_EXHAUSTED" || e.code=="API_RATE_LIMIT" || e.nextRetryAt>0))){
                                    if(e is VideoApiException && e.splitAllowed && batch.indices.size>1 && batch.cueIds.none{it in splitRetriedIds}){
                                        splitRetriedIds=splitRetriedIds+batch.cueIds;recoveryGroupLimit=maxOf(1,batch.indices.size/2)
                                        persist(p.copy(message="Đang chia nhỏ nhóm chưa hợp lệ; các câu đã lưu được giữ."))
                                    }else {
                                        // Preserve a manual error if independent groups failed for different reasons.
                                        val first = failedGroup
                                        if (first == null || (VideoStagedPipeline.errorCode(first) in INCOMPLETE_RESPONSE_CODES &&
                                                VideoStagedPipeline.errorCode(e) !in INCOMPLETE_RESPONSE_CODES)) failedGroup = e
                                        failedCloudIds=failedCloudIds+batch.cueIds
                                        persist(p.copy(cues=p.cues.map{if(it.id in batch.cueIds && !it.protectedText)
                                            it.copy(qualityFlags=(it.qualityFlags+"TRANSLATION_NEEDS_REVIEW").distinct()) else it},
                                            message="Đã đánh dấu nhóm cần kiểm tra; đang tiếp tục nhóm độc lập khác."))
                                    }
                                }else {
                                    val manualCode = failedGroup?.let(VideoStagedPipeline::errorCode)
                                        ?.takeUnless { it in INCOMPLETE_RESPONSE_CODES }
                                    val retryAt = when {
                                        e is VideoQuotaWaitException -> e.nextRetryAt
                                        e is VideoApiException && e.transient -> maxOf(e.nextRetryAt, System.currentTimeMillis() + 60_000)
                                        VideoStagedPipeline.errorCode(e) == "API_RATE_LIMIT" -> System.currentTimeMillis() + 61_000
                                        else -> null
                                    }
                                    if (manualCode != null && retryAt != null) {
                                        throw VideoApiException(manualCode, nextRetryAt = retryAt,
                                            detail = "Có nhóm cần kiểm tra thủ công. Các câu đã lưu và mốc chờ dịch vụ được giữ.")
                                    }
                                    throw e
                                }
                            }
                        }
                    } finally {local?.close()}
                    if(pipelineStage==BatchPipelineStage.TRANSLATE){
                        if (p.cues.any { !VideoTranslationPlan.isComplete(it,hybrid) }) {
                            throw failedGroup ?: VideoApiException("RESPONSE_NEEDS_REVIEW",
                                detail = "Còn câu cần kiểm tra. Các câu đã lưu sẽ được giữ khi tiếp tục.")
                        }
                        persist(p.copy(cues=p.cues.map{if(!it.protectedText && it.draftVi.isBlank())it.copy(draftVi=it.translation)else it},
                            pipeline=p.pipeline.record(pipelineStage,"COMPLETE",p.cues.size.toLong()),stage=VideoStage.REVIEW,progress=100,
                            message="Đã lưu bản dịch nháp theo từng nhóm. Tiếp theo kiểm tra và hiệu đính."))
                        return@launch
                    }
                    val srt=store.exportFile(id,"srt");srt.writeText(VideoTimeline.srt(p.cues,p.durationMs),Charsets.UTF_8)
                    store.exportFile(id,"ass").writeText(VideoTimeline.ass(p),Charsets.UTF_8)
                    persist(p.copy(stage=VideoStage.REVIEW,progress=100,message=when(p.translationMode){
                        VideoTranslationMode.OFFLINE->"Đã dịch offline ${p.cues.size} câu dưới dạng Draft. Kiểm tra chữ Trung, tên, xưng hô và phủ định trước khi tải xuống."
                        VideoTranslationMode.GEMINI->"Gemini đã dịch ${p.cues.size} câu theo ngữ cảnh. Bản dịch vẫn là Draft; kiểm tra chữ Trung nguồn trước khi tải xuống."
                        VideoTranslationMode.HYBRID->"Đã lấy đủ nguồn và dịch các phần hội thoại bằng Gemini. Bản dịch vẫn là Draft; kiểm tra trước khi tải xuống."
                    }))
                }
                queueSync(p)
            } catch(e:VideoEconomyPendingException){persist(p.copy(stage=VideoStage.PAUSED,message=e.message.orEmpty()))}
            catch(e:CancellationException){withContext(NonCancellable){
                val checkpoint = pipelineStage?.let(p.pipeline::task)
                persist(p.copy(stage=VideoStage.PAUSED,
                    pipeline=if(pipelineStage==null || checkpoint==null)p.pipeline else p.pipeline.record(
                        pipelineStage,"PAUSED",checkpoint.lastCheckpoint,checkpoint.errorCode,checkpoint.nextRetryAt),
                    message="Đã tạm dừng và lưu checkpoint. Bấm Tiếp tục."))
            }}
            catch(e:Exception){
                if(com.gemini.ocr.BuildConfig.DEBUG)runCatching{workerFailureForTest?.invoke(e)}
                persist(p.copy(stage=VideoStage.FAILED,message=e.message?.take(400)?.takeIf{it.isNotBlank()}?:e.javaClass.simpleName,
                pipeline=if(pipelineStage==null)p.pipeline else p.pipeline.record(pipelineStage,"NEEDS_REVIEW",p.scanMs,
                    VideoStagedPipeline.errorCode(e),when(e) {
                        is VideoApiException -> e.nextRetryAt
                        is VideoQuotaWaitException -> e.nextRetryAt
                        else -> 0
                    })))}
            finally {
                runCatching { profiler?.finish(p) }
                runCatching { smartMetrics.flush() }
                try { synchronized(this@VideoJobs){
                    if(mutableBatchBusy.value==null){runCatching { speechSession?.close() };speechSession=null}
                    worker=null;mutableBusy.value=null;refresh()
                } } finally { onDone() }
            }
        }
    }
    suspend fun exportText(id:String,destination:Uri,ass:Boolean=false,validateSnapshot:(VideoProject)->Unit={})=withContext(Dispatchers.IO){
        val p=synchronized(this@VideoJobs) { store.load(id).also(validateSnapshot) }
        VideoTimeline.validate(p.cues,p.durationMs)
        require(!p.sourceExtractionPending){"Chưa nhận hết phụ đề nguồn. Bấm Tiếp tục xử lý trước khi xuất."}
        protectSource(p,destination)
        require(p.cues.isNotEmpty() && p.cues.all{it.translation.isNotBlank()}){"Có câu chưa dịch."}
        checkNotNull(context.contentResolver.openOutputStream(destination,"wt")).use{it.write((if(ass)VideoTimeline.ass(p) else VideoTimeline.srt(p.cues,p.durationMs)).toByteArray(Charsets.UTF_8))}
    }
    suspend fun exportSourceText(id:String,destination:Uri)=withContext(Dispatchers.IO){
        val p=store.load(id);require(VideoStagedPipeline.extracted(p)){"Hoàn tất trích xuất chữ Trung trước khi lưu SRT."}
        VideoTimeline.validate(p.cues,p.durationMs);protectSource(p,destination)
        checkNotNull(context.contentResolver.openOutputStream(destination,"wt")).use{it.write(VideoStagedPipeline.sourceSrt(p.cues,durationMs=p.durationMs).toByteArray(Charsets.UTF_8))}
    }
    suspend fun copyMp4(id:String,destination:Uri,validateSnapshot:(VideoProject)->Unit={})=withContext(Dispatchers.IO){
        val source = synchronized(this@VideoJobs) {
            val p=store.load(id);require(p.exportedMp4.isNotBlank()){"Tạo MP4 trước khi lưu."}
            validateSnapshot(p)
            protectSource(p,destination)
            File(p.exportedMp4).inputStream()
        }
        source.use{input->checkNotNull(context.contentResolver.openOutputStream(destination,"wt")).use{input.copyTo(it,128*1024)}}
    }
    private fun protectSource(project:VideoProject,destination:Uri){
        require(destination.scheme=="content"){"Chọn nơi lưu bằng trình chọn tệp Android."}
        require(listOf(project.uri,project.subtitleUri,project.originalSourceUri,project.originalSubtitleUri).filter{it.isNotBlank()}.none{Uri.parse(it).normalizeScheme()==destination.normalizeScheme()}){"Không được ghi đè video hoặc phụ đề nguồn. Chọn tên tệp mới."}
    }
    private fun queueSync(project:VideoProject) {
        // Cloud status is separate: a pending login/upload must never fail completed local work.
        if(project.stage in setOf(VideoStage.REVIEW,VideoStage.COMPLETE) && project.cues.isNotEmpty() && project.cues.all{it.translation.isNotBlank()})
            runCatching { VideoCloudSync.get(context).enqueue(project.id) }
    }
    companion object {
        private val INCOMPLETE_RESPONSE_CODES = setOf("TEXT_INCOMPLETE_RESPONSE", "TEXT_TRUNCATED")
        /** Debug-only observation of synthetic worker failures; never logged or persisted. */
        @Volatile internal var workerFailureForTest: ((Throwable) -> Unit)? = null
        private val ACTIVE_STAGES=setOf(VideoStage.EXTRACTING,VideoStage.TRANSLATING,VideoStage.EXPORTING,VideoStage.WAITING_LIVE)
        @Volatile private var instance:VideoJobs?=null
        fun get(context:Context):VideoJobs=instance?:synchronized(this){instance?:VideoJobs(context.applicationContext).also{instance=it}}
    }
}
