package com.gemini.ocr.movie

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import com.gemini.ocr.video.VideoDecoder
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MovieEpisode(val id:String,val title:String,val path:String,val durationMs:Long,val positionMs:Long=0,
    val positionUpdatedAt:Long=0,val positionConflict:Boolean=false,val source:MovieSourceSnapshot?=null) {
    val isSourceQuality get()=source!=null
    val mediaUri:String get()=source?.uri ?: path.takeIf{it.isNotBlank()}?.let{Uri.fromFile(File(it)).toString()}.orEmpty()
    internal fun json(portable:Boolean=false)=JSONObject().put("id",id).put("title",title)
        .put("path",if(portable)"" else path).put("durationMs",durationMs).put("positionMs",positionMs)
        .put("positionUpdatedAt",positionUpdatedAt).put("positionConflict",positionConflict)
        .put("source",source?.json(portable)?:JSONObject.NULL)
    internal fun mergeProgress(remote:MovieEpisode,keepLocalOnTie:Boolean=true):MovieEpisode {
        val incoming=remote.positionMs.coerceIn(0,durationMs)
        return when {
            remote.positionUpdatedAt>positionUpdatedAt->copy(positionMs=incoming,
                positionUpdatedAt=remote.positionUpdatedAt,positionConflict=remote.positionConflict)
            remote.positionUpdatedAt<positionUpdatedAt->this
            incoming!=positionMs->copy(positionMs=if(keepLocalOnTie)positionMs else minOf(positionMs,incoming),positionConflict=true)
            else->copy(positionConflict=positionConflict || remote.positionConflict)
        }
    }
    companion object {
        internal fun from(j:JSONObject,legacyUpdatedAt:Long=0)=MovieEpisode(j.getString("id"),j.getString("title"),j.optString("path"),
            j.getLong("durationMs"),j.optLong("positionMs"),j.optLong("positionUpdatedAt",legacyUpdatedAt),j.optBoolean("positionConflict"),
            j.optJSONObject("source")?.let{MovieSourceSnapshot.from(it)})
    }
}

data class MovieRecord(
    val id:String,val title:String,val episodes:List<MovieEpisode>,val accountEmail:String="",
    val syncState:String="LOCAL",val cloudId:String="",val coverPath:String="",
    val confirmedAt:Long=System.currentTimeMillis(),val message:String="",val updatedAt:Long=confirmedAt
) {
    internal fun json(portable:Boolean=false)=JSONObject().put("schema",if(episodes.any{it.source!=null})2 else 1).put("id",id).put("title",title)
        .put("episodes",JSONArray(episodes.map{it.json(portable)})).put("accountEmail",if(portable)"" else accountEmail)
        .put("syncState",if(portable)"CLOUD" else syncState).put("cloudId",if(portable)"" else cloudId)
        .put("coverPath",if(portable)"" else coverPath).put("confirmedAt",confirmedAt).put("message",if(portable)"" else message)
        .put("updatedAt",updatedAt)
    companion object {
        internal const val PROGRESS_CONFLICT_MESSAGE="Xung đột vị trí xem giữa các thiết bị. Đã giữ vị trí trên máy; hãy kiểm tra trước khi xem tiếp."
        internal fun from(j:JSONObject):MovieRecord {
            val schema=j.getInt("schema")
            require(schema in 1..2){"Phiên bản Kho phim chưa được hỗ trợ."}
            val rows=j.getJSONArray("episodes")
            val result=MovieRecord(j.getString("id"),j.getString("title"),(0 until rows.length()).map{MovieEpisode.from(rows.getJSONObject(it),j.optLong("updatedAt",j.getLong("confirmedAt")))},
                j.optString("accountEmail"),j.optString("syncState","LOCAL"),j.optString("cloudId"),j.optString("coverPath"),
                j.getLong("confirmedAt"),j.optString("message"),j.optLong("updatedAt",j.getLong("confirmedAt")))
            validate(result)
            require(schema!=2 || result.episodes.any{it.source!=null}){"Snapshot Kho phim nguồn thiếu timeline phụ đề."}
            return result
        }
        internal fun validate(record:MovieRecord) {
            require(record.id.matches(Regex("[a-f0-9-]{36}")) && record.title.isNotBlank() && record.title.length<=200)
            require(record.confirmedAt>0 && record.episodes.isNotEmpty())
            require(record.episodes.map{it.id}.toSet().size==record.episodes.size)
            require(record.episodes.all{it.id.isNotBlank() && it.id.length<=200 && it.title.length<=200 && it.durationMs>0 && it.positionMs in 0..it.durationMs && it.positionUpdatedAt>=0})
            record.episodes.forEach{it.source?.validate(it.durationMs)}
        }
    }
}

/** Only explicitly confirmed, independently owned playable outputs belong in this store. */
class MovieLibrary internal constructor(
    private val context:Context,
    private val validateVideo:(File)->Long=::playableDuration,
    private val makeCover:(File,File)->Unit=::extractCover
) {
    private val root=File(context.filesDir,"movie-library").apply{mkdirs()}
    private val hidden=context.getSharedPreferences("movie-library-hidden",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow<List<MovieRecord>>(emptyList())
    val movies:StateFlow<List<MovieRecord>> = mutable
    internal val operation=Mutex()
    init {
        refresh()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            VideoCloudSync.get(context).state.collect{refresh()}
        }
    }
    internal fun folder(id:String):File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root,id).apply{mkdirs()}
    }
    internal fun currentAccount()=VideoCloudSync.get(context).state.value.accountEmail
    private fun visible(p:MovieRecord)=p.accountEmail.isBlank() || p.accountEmail==currentAccount()
    private fun hiddenKey(account:String,id:String)="$account/$id"
    internal fun isHidden(account:String,id:String)=hidden.getBoolean(hiddenKey(account,id),false)
    internal fun all():List<MovieRecord> = synchronized(storageLock) {
        root.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}"))}
            .mapNotNull{runCatching{read(File(it,"movie.json"))}.getOrNull()}
    }
    internal fun find(id:String):MovieRecord?=all().firstOrNull{it.id==id && visible(it)}
    internal fun refresh() { mutable.value=all().filter{visible(it) && !isHidden(it.accountEmail,it.id)}.sortedByDescending{it.confirmedAt} }
    private fun read(file:File):MovieRecord {
        require(file.length()<=32L*1024*1024)
        val bytes=AtomicFile(file).openRead().use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=32*1024*1024);out.write(buffer,0,count)}
            out.toByteArray()
        }
        return MovieRecord.from(JSONObject(bytes.toString(Charsets.UTF_8)))
    }
    internal fun save(record:MovieRecord):MovieRecord = synchronized(storageLock) {
        MovieRecord.validate(record)
        val bytes=record.json().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=32*1024*1024){"Danh mục phim quá lớn; bản cũ vẫn được giữ."}
        val file=AtomicFile(File(folder(record.id),"movie.json"));val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}catch(e:Throwable){file.failWrite(out);throw e}
        refresh();record
    }
    internal fun update(id:String,change:(MovieRecord)->MovieRecord):MovieRecord?=synchronized(storageLock) {
        find(id)?.let{save(change(it))}
    }

    suspend fun confirm(id:String,title:String,episodes:List<MovieEpisode>):MovieRecord=withContext(Dispatchers.IO) {
        operation.withLock {
            find(id)?.let{return@withLock it} // Repeated confirmation never creates a second copy or queue item.
            require(all().none{it.id==id}){"Phim thuộc tài khoản khác trên thiết bị này."}
            val confirmingAccount=currentAccount()
            val initial=MovieRecord(id,title.trim().take(200),episodes)
            MovieRecord.validate(initial)
            val durations=episodes.map{episode->
                val file=File(episode.path)
                require(file.isFile && file.canRead() && file.length()>0){"Video hoàn chỉnh không còn đọc được."}
                validateVideo(file)
            }
            val folder=folder(id);val revision=UUID.randomUUID().toString();val created=mutableListOf<File>();var published=false
            try {
                val copies=episodes.mapIndexed{index,episode->
                    currentCoroutineContext().ensureActive()
                    val source=File(episode.path)
                    require(folder.usableSpace>source.length()+128L*1024*1024){"Không đủ dung lượng để giữ bản phim đã xác nhận."}
                    val target=File(folder,"$revision-$index.mp4");created+=target
                    source.inputStream().use{input->target.outputStream().use{out->
                        val buffer=ByteArray(128*1024)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}
                    }}
                    require(target.length()==source.length()){"Chưa sao chép đủ video; chưa đưa vào Kho phim."}
                    val duration=validateVideo(target)
                    require(kotlin.math.abs(duration-durations[index])<=1000){"Video đã thay đổi trong lúc xác nhận."}
                    episode.copy(path=target.absolutePath,durationMs=duration,positionMs=0)
                }
                val cover=File(folder,"$revision-cover.jpg");created+=cover
                runCatching{makeCover(File(copies.first().path),cover)}
                val record=save(initial.copy(episodes=copies,accountEmail=confirmingAccount,coverPath=cover.takeIf{it.isFile && it.length()>0}?.absolutePath.orEmpty(),
                    message="Đã xác nhận và lưu trong máy."))
                published=true
                hidden.edit().remove(hiddenKey(record.accountEmail,id)).apply()
                if(currentAccount()==confirmingAccount)MovieCloudSync.get(context).enqueue(id)
                find(id)?:record
            } catch(e:Throwable) { if(!published)created.forEach{it.delete()};throw e }
        }
    }
    /** Atomic snapshot of original media + translated timeline; no render and no Downloads write. */
    suspend fun confirmSources(id:String,title:String,episodes:List<MovieSourceInput>,copySources:Boolean=false,
        appendToExisting:Boolean=false):MovieRecord=withContext(Dispatchers.IO) {
        operation.withLock {
            val previous=find(id)
            if(!appendToExisting) {
                previous?.let{return@withLock it}
                require(all().none{it.id==id}){"Phim thuộc tài khoản khác trên thiết bị này."}
            } else require(previous!=null && !isHidden(previous.accountEmail,id)){"Bộ phim không còn trong tài khoản hiện tại."}
            require(episodes.isNotEmpty() && episodes.map{it.episodeId}.distinct().size==episodes.size){"Danh sách tập rỗng hoặc trùng mã tập."}
            val additions=episodes.filter { input -> previous?.episodes?.none { it.id==input.episodeId } ?: true }
            if(additions.isEmpty())return@withLock requireNotNull(previous)
            if(appendToExisting)require(requireNotNull(previous).episodes.all(::isAvailable)) {
                "Tải các tập cũ ngoại tuyến trước khi thêm tập mới để đồng bộ đầy đủ."
            }
            check(VideoJobs.get(context).busy.value==null && VideoJobs.get(context).batchBusy.value==null){"Đợi xử lý dừng trước khi xác nhận Kho phim."}
            val account=currentAccount();val folder=folder(id);val created=mutableListOf<File>();var published=false
            try {
                val revision=UUID.randomUUID().toString()
                val savedEpisodes=additions.mapIndexed{index,input->
                    currentCoroutineContext().ensureActive()
                    val p=input.project
                    val original=Uri.parse(p.uri)
                    val fingerprint=MovieSourceAccess.inspect(context,original)
                    val duration=if(original.scheme=="file")validateVideo(File(requireNotNull(original.path)))
                        else VideoDecoder.metadata(context,original).durationMs
                    require(kotlin.math.abs(duration-p.durationMs)<=1000){"Video nguồn đã thay đổi; kiểm tra lại phụ đề trước khi xác nhận."}
                    var retained=original
                    if(copySources) {
                        require(folder.usableSpace>fingerprint.second+128L*1024*1024){"Không đủ dung lượng sao chép video nguồn."}
                        val target=File(folder,"$revision-source-$index.input");created+=target
                        MovieSourceAccess.open(context,original).use{from->target.outputStream().use{to->
                            val buffer=ByteArray(128*1024);var count=0L
                            while(true){currentCoroutineContext().ensureActive();val n=from.read(buffer);if(n<0)break
                                count+=n;require(count<=fingerprint.second){"Video thay đổi trong lúc sao chép."};to.write(buffer,0,n)}
                        }}
                        retained=Uri.fromFile(target)
                        require(MovieSourceAccess.inspect(context,retained)==fingerprint){"Bản sao nguồn chưa khớp SHA-256; chưa lưu phim."}
                    } else if(original.scheme=="content") {
                        require(context.contentResolver.persistedUriPermissions.any{it.uri==original && it.isReadPermission}){
                            "Nguồn chưa có quyền đọc lâu dài. Chọn sao chép vào thư viện nội bộ hoặc chọn lại tệp."}
                    }
                    val snapshot=MovieSourceSnapshot.freeze(p,fingerprint.first,fingerprint.second,retained.toString())
                    MovieEpisode(input.episodeId,input.title.take(200),if(retained.scheme=="file")retained.path.orEmpty() else "",
                        p.durationMs,source=snapshot)
                }
                val cover=File(folder,"$revision-cover.jpg");created+=cover
                val first=Uri.parse(savedEpisodes.first().mediaUri)
                if(previous==null)runCatching {
                    if(first.scheme=="file")makeCover(File(requireNotNull(first.path)),cover)
                    else extractSourceCover(context,first,cover)
                }
                val record=synchronized(storageLock) {
                    require(currentAccount()==account){"Tài khoản đã thay đổi; chưa thêm tập vào Kho phim."}
                    if(appendToExisting) {
                        val current=requireNotNull(find(id)){"Bộ phim đã bị xóa; chưa thêm tập mới."}
                        require(!isHidden(current.accountEmail,id)){"Bộ phim đã bị xóa khỏi thiết bị."}
                        require(current.episodes.all(::isAvailable)){"Nguồn của tập cũ không còn đọc được. Tải lại trước khi thêm tập."}
                        val fresh=savedEpisodes.filter { added -> current.episodes.none { it.id==added.id } }
                        save(current.copy(episodes=current.episodes+fresh,syncState="LOCAL",
                            updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1),
                            message=if(current.episodes.any{!isAvailable(it)}) "Đã thêm tập mới. Tải các tập cũ ngoại tuyến trước khi đồng bộ lại toàn bộ."
                                else "Đã thêm ${fresh.size} tập mới; giữ nguyên các tập và vị trí xem cũ."))
                    } else save(MovieRecord(id,title.trim().take(200),savedEpisodes,accountEmail=account,
                        coverPath=cover.takeIf{it.isFile && it.length()>0}?.absolutePath.orEmpty(),
                        message="Đã lưu video gốc và phụ đề riêng; không mã hóa lại hình ảnh."))
                }
                published=true;hidden.edit().remove(hiddenKey(account,id)).apply()
                if(currentAccount()==account)MovieCloudSync.get(context).enqueue(id)
                find(id)?:record
            } catch(e:Throwable){if(!published)created.forEach{it.delete()};throw e}
        }
    }

    /** Explicit review confirmation only; movie progress is retained and stale edits are rejected. */
    suspend fun refreshSourceSnapshot(movieId:String,episodeId:String,project:VideoProject,expectedRevision:String):MovieRecord=withContext(Dispatchers.IO) {
        operation.withLock {
            check(VideoJobs.get(context).busy.value==null && VideoJobs.get(context).batchBusy.value==null){"Tạm dừng xử lý trước khi cập nhật phụ đề Kho phim."}
            val old=requireNotNull(find(movieId)){"Không tìm thấy phim trong tài khoản hiện tại."}
            val episode=requireNotNull(old.episodes.firstOrNull{it.id==episodeId})
            val snapshot=requireNotNull(episode.source){"Phim MP4 cũ không có timeline rời."}
            require(snapshot.revision==expectedRevision){"Phụ đề trong Kho phim đã thay đổi. Mở lại để tránh ghi đè bản mới."}
            require(project.durationMs==episode.durationMs && project.width==snapshot.width && project.height==snapshot.height)
            val hash=MovieSourceAccess.inspect(context,Uri.parse(project.uri))
            require(hash.first==snapshot.sha256 && hash.second==snapshot.bytes){"Dự án đang dùng video nguồn khác; không thay timeline của phim này."}
            val changedAt=maxOf(System.currentTimeMillis(),old.updatedAt+1)
            val fresh=MovieSourceSnapshot.freeze(project,hash.first,hash.second,snapshot.uri,changedAt)
            val current=requireNotNull(find(movieId))
            require(current.episodes.first{it.id==episodeId}.source?.revision==expectedRevision){"Phụ đề đã thay đổi; bản hiện tại được giữ."}
            val record=save(current.copy(episodes=current.episodes.map{if(it.id==episodeId)it.copy(source=fresh) else it},
                syncState="LOCAL",updatedAt=changedAt,message="Đã cập nhật phụ đề đã kiểm tra; video gốc và vị trí xem được giữ."))
            MovieCloudSync.get(context).enqueue(movieId);record
        }
    }

    fun isAvailable(episode:MovieEpisode):Boolean=runCatching {
        if(episode.source==null)episode.path.isNotBlank() && File(episode.path).isFile
        else MovieSourceAccess.open(context,Uri.parse(episode.mediaUri)).use{true}
    }.getOrDefault(false)

    /** An editor clone owns its timeline; editing never silently mutates the confirmed snapshot. */
    suspend fun createSourceEditor(movieId:String,episodeId:String):VideoProject=withContext(Dispatchers.IO) {
        val episode=requireNotNull(find(movieId)?.episodes?.firstOrNull{it.id==episodeId})
        val source=requireNotNull(episode.source){"Phim MP4 cũ không có phụ đề riêng để sửa."}
        require(isAvailable(episode)){"Không còn đọc được video nguồn; phụ đề trong Kho phim vẫn được giữ."}
        val project=source.project(episode).copy(id=UUID.randomUUID().toString(),title=episode.title+" · Sửa phụ đề Kho phim")
        val store=VideoStore(context)
        // Private editor revisions are not standalone auto-backups or independently confirmed films.
        File(store.folder(project.id),"batch-owner").writeText(movieId)
        store.save(project)
    }
    fun progress(id:String,episodeId:String,position:Long,observedAt:Long?=null,
        expectedAccount:String?=null,expectedSourceRevision:String?=null) {
        val saved = synchronized(storageLock) {
            val p = find(id) ?: return@synchronized false
            val current = p.episodes.firstOrNull { it.id == episodeId } ?: return@synchronized false
            // A queued player observation must not become newer merely because disk I/O ran late.
            if(observedAt!=null && current.positionUpdatedAt>observedAt)return@synchronized false
            if(expectedAccount!=null && (p.accountEmail!=expectedAccount ||
                current.source?.revision!=expectedSourceRevision))return@synchronized false
            val nextPosition = position.coerceIn(0, current.durationMs)
            if (observedAt != null && current.positionMs == nextPosition) return@synchronized false
            val changedAt = observedAt ?: maxOf(System.currentTimeMillis(), p.updatedAt + 1)
            val episodes=p.episodes.map{if(it.id==episodeId)it.copy(positionMs=nextPosition,
                positionUpdatedAt=changedAt,positionConflict=false) else it}
            // The snapshot clock orders uploads from this device. It is independent of the episode clock.
            save(p.copy(episodes=episodes,updatedAt=maxOf(p.updatedAt+1,changedAt),message=if(episodes.any{it.positionConflict})MovieRecord.PROGRESS_CONFLICT_MESSAGE
                else p.message.takeUnless{it==MovieRecord.PROGRESS_CONFLICT_MESSAGE}.orEmpty()))
            true
        }
        if (saved) MovieCloudSync.get(context).enqueueProgress(id)
    }
    /** Local removal never follows source paths or deletes a cloud copy. */
    fun remove(id:String) = synchronized(storageLock) {
        val record=find(id)?:return@synchronized
        MovieCloudSync.get(context).cancel(id)
        hidden.edit().putBoolean(hiddenKey(record.accountEmail,id),true).commit()
        deleteOwnedMedia(record)
        AtomicFile(File(folder(id),"movie.json")).delete()
        refresh()
    }
    fun removeOffline(id:String) = synchronized(storageLock) {
        val p=find(id)?:return@synchronized
        require(p.syncState=="SYNCED" && p.cloudId.isNotBlank()){"Chỉ xóa bản ngoại tuyến khi đã xác minh bản Google Drive."}
        deleteOwnedMedia(p)
        save(p.copy(episodes=p.episodes.map{it.copy(path="",source=it.source?.copy(uri="",projectId=""))},coverPath="",syncState="CLOUD",message="Đã giải phóng bản ngoại tuyến; bản Google Drive được giữ."))
    }
    private fun deleteOwnedMedia(p:MovieRecord) {
        val own=folder(p.id).canonicalFile
        (p.episodes.map{it.path}+p.episodes.mapNotNull{it.source?.uri?.let{uri->Uri.parse(uri).takeIf{it.scheme=="file"}?.path}}+p.coverPath).filter{it.isNotBlank()}.forEach{path->
            val file=File(path).canonicalFile
            if(file.parentFile==own && file.isFile)file.delete()
        }
    }
    internal fun mergeCloud(account:String,remote:List<MovieRecord>)=synchronized(storageLock) {
        require(account==currentAccount()){"Tài khoản Google đã thay đổi."}
        for(p in remote) {
            if(isHidden(account,p.id))continue
            val old=all().firstOrNull{it.id==p.id}
            if(old!=null && old.accountEmail!=account)continue
            if(old==null)save(p.copy(accountEmail=account,syncState="CLOUD"))
            else if(old.syncState in setOf("CLOUD","SYNCED")) {
                val sourceChanged=old.episodes.map{it.id to it.source?.revision}!=p.episodes.map{it.id to it.source?.revision}
                if(sourceChanged && old.episodes.any{isAvailable(it)}) {
                    save(old.copy(syncState="CONFLICT",message="Phụ đề trên Drive khác bản trong máy. Đã giữ nguyên video và phụ đề đang có; kiểm tra hai phiên bản trước khi thay thế."))
                    continue
                }
                if(sourceChanged) {
                    val episodes=p.episodes.map{remote->old.episodes.firstOrNull{it.id==remote.id}?.let{remote.mergeProgress(it)}?:remote}
                    save(p.copy(accountEmail=account,episodes=episodes,syncState="CLOUD"))
                    continue
                }
                val episodes=old.episodes.map{episode->p.episodes.firstOrNull{it.id==episode.id}?.let{cloud->episode.mergeProgress(cloud)}?:episode}
                save(old.copy(cloudId=p.cloudId,updatedAt=maxOf(old.updatedAt,p.updatedAt),episodes=episodes,
                    message=if(episodes.any{it.positionConflict})MovieRecord.PROGRESS_CONFLICT_MESSAGE
                        else old.message.takeUnless{it==MovieRecord.PROGRESS_CONFLICT_MESSAGE}.orEmpty()))
            }
        }
        refresh()
    }
    companion object {
        private val storageLock=Any()
        @Volatile private var instance:MovieLibrary?=null
        fun get(context:Context):MovieLibrary=instance?:synchronized(this){instance?:MovieLibrary(context.applicationContext).also{instance=it}}
        private fun playableDuration(file:File):Long {
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                val duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0
                require(duration>0 && (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0)>0){"Tệp không phải video có thể phát."}
                val frame=reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                requireNotNull(frame){"Không giải mã được hình phim; chưa đưa vào Kho phim."};frame.recycle()
                return duration
            } finally {reader.release()}
        }
        private fun extractCover(source:File,target:File) {
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(source.absolutePath)
                val frame=if(android.os.Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)
                    else reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let{try{target.outputStream().use{out->it.compress(Bitmap.CompressFormat.JPEG,85,out)}}finally{it.recycle()}}
            } finally {reader.release()}
        }
        private fun extractSourceCover(context:Context,source:Uri,target:File) {
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(context,source)
                val frame=if(android.os.Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)
                    else reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let{try{target.outputStream().use{out->it.compress(Bitmap.CompressFormat.JPEG,85,out)}}finally{it.recycle()}}
            } finally {reader.release()}
        }
    }
}
