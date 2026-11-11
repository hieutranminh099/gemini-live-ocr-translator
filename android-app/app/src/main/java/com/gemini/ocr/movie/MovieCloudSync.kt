@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.VideoJobs
import com.gemini.ocr.video.sync.DriveAuthorizationRequired
import com.gemini.ocr.video.sync.DrivePlaybackException
import com.gemini.ocr.video.sync.DrivePlaybackFailure
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Movie confirmation is explicit upload consent; project auto-backup remains a separate setting. */
class MovieCloudSync internal constructor(private val context:Context) {
    private val identity=VideoCloudSync.get(context)
    val state get()=identity.state
    private val library get()=MovieLibrary.get(context)
    private val operation=Mutex()
    private val progressQueuedAt=ConcurrentHashMap<String,Long>()

    fun enqueue(id:String)=scheduleUpload(id,ExistingWorkPolicy.KEEP)
    private fun scheduleUpload(id:String,policy:ExistingWorkPolicy) {
        val movie=library.find(id)?:return
        val selected=identity.state.value
        if(selected.accountEmail.isBlank() || selected.authorizationRequired) {
            library.update(id){it.copy(syncState="LOCAL",message="Đã lưu trong máy. Kết nối Google rồi bấm Thử đồng bộ để tải lên.")}
            return
        }
        if(movie.accountEmail.isNotBlank() && movie.accountEmail!=selected.accountEmail)return
        if(movie.syncState=="SYNCED" && movie.cloudId.isNotBlank())return
        if(movie.episodes.any{!library.isAvailable(it)})return
        library.update(id){it.copy(accountEmail=selected.accountEmail,syncState="QUEUED",message="Đang chờ đồng bộ Google Drive.")}
        try {
            val work=OneTimeWorkRequestBuilder<MovieCloudWorker>()
                .setInputData(workDataOf("movieId" to id,"account" to selected.accountEmail))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if(selected.wifiOnly)NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG-$id",policy,work)
        } catch(e:Exception) {
            library.update(id){it.copy(syncState="FAILED",message="Chưa xếp được lịch đồng bộ; bản trong máy được giữ. Bấm Thử đồng bộ.")}
        }
    }

    internal fun enqueueProgress(id:String,replace:Boolean=false) {
        val movie=library.find(id)?:return
        val selected=identity.state.value
        if(movie.cloudId.isBlank() || movie.accountEmail.isBlank() || movie.accountEmail!=selected.accountEmail || selected.authorizationRequired)return
        val now=android.os.SystemClock.elapsedRealtime()
        synchronized(progressQueuedAt) {
            if(!replace && progressQueuedAt[id]?.let{now-it<30_000}==true)return
            progressQueuedAt[id]=now
        }
        try {
            val work=OneTimeWorkRequestBuilder<MovieCloudWorker>()
                .setInputData(workDataOf("movieId" to id,"account" to selected.accountEmail,"progressOnly" to true))
                .setInitialDelay(30,TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if(selected.wifiOnly)NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG-progress-$id",if(replace)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,work)
        } catch(_:Exception){progressQueuedAt.remove(id)}
    }
    internal fun rescheduleConstraints() {
        val email=identity.state.value.accountEmail
        if(email.isBlank())return
        library.all().filter{it.accountEmail==email && !library.isHidden(email,it.id)}.forEach{movie->
            if(movie.syncState in setOf("QUEUED","UPLOADING","FAILED"))scheduleUpload(movie.id,ExistingWorkPolicy.REPLACE)
            if(movie.cloudId.isNotBlank())enqueueProgress(movie.id,replace=true)
        }
    }
    internal fun cancel(id:String) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork("$TAG-$id")
            WorkManager.getInstance(context).cancelUniqueWork("$TAG-progress-$id")
        }
        progressQueuedAt.remove(id)
    }

    suspend fun refreshCloud()=withContext(Dispatchers.IO) {
        operation.withLock {
            val email=identity.state.value.accountEmail
            require(email.isNotBlank()){"Kết nối tài khoản Google trước khi tải Kho phim."}
            identity.status("Đang tải Kho phim trên Google Drive…",true)
            try {
                val records=identity.repositoryForMovies(email).listMovies()
                require(identity.state.value.accountEmail==email){"Tài khoản Google đã thay đổi."}
                library.mergeCloud(email,records)
                identity.status("Đã tải danh mục ${records.size} phim. Chọn Xem ngay hoặc Tải ngoại tuyến.")
            } catch(e:CancellationException){identity.status("Đã dừng tải Kho phim.");throw e}
            catch(e:Exception){identity.status(safeMessage(e),needsAuth=e is DriveAuthorizationRequired);throw e}
        }
    }

    /** Resolves only a private manifest and checksum metadata; never creates an offline copy. */
    internal suspend fun preparePlayback(movieId:String,episodeId:String):MoviePlaybackHandle=withContext(Dispatchers.IO) {
        val email=identity.state.value.accountEmail
        val guard=identity.playbackAccountGuard(email)
        guard()
        val original=library.find(movieId)
        if(original==null || original.accountEmail!=email || original.cloudId.isBlank())
            throw DrivePlaybackException(DrivePlaybackFailure.ACCOUNT)
        val handle=identity.repositoryForPlayback(email,guard).prepareMoviePlayback(original,episodeId)
        guard()
        val current=library.find(movieId)
        val currentEpisode=current?.episodes?.firstOrNull{it.id==episodeId}
        if(current==null || currentEpisode==null || current.cloudId!=original.cloudId ||
            currentEpisode.source?.revision!=handle.episode.source?.revision)
            throw DrivePlaybackException(DrivePlaybackFailure.METADATA)
        val progress=currentEpisode.mergeProgress(handle.episode)
        handle.copy(episode=handle.episode.copy(positionMs=progress.positionMs,
            positionUpdatedAt=progress.positionUpdatedAt,positionConflict=progress.positionConflict))
    }

    suspend fun download(id:String):MovieRecord=withContext(Dispatchers.IO) {
        operation.withLock {
            val original=requireNotNull(library.find(id)){"Không tìm thấy phim trên tài khoản này."}
            val email=identity.state.value.accountEmail
            require(email.isNotBlank() && original.accountEmail==email && original.cloudId.isNotBlank()){"Kết nối đúng tài khoản Google đã lưu phim."}
            if(original.episodes.all{library.isAvailable(it)})return@withLock original
            library.update(id){it.copy(message="Đang tải bản ngoại tuyến…")}
            val downloaded=try {
                identity.repositoryForMovies(email).downloadMovie(original,library.folder(id)){message->identity.status(message,true)}
            } catch(e:CancellationException){throw e}
            catch(e:Exception){library.update(id){it.copy(message=safeMessage(e))};identity.status(safeMessage(e),needsAuth=e is DriveAuthorizationRequired);throw e}
            var published=false
            try {
                require(identity.state.value.accountEmail==email && library.find(id)!=null){"Tài khoản hoặc Kho phim đã thay đổi."}
                val saved=requireNotNull(library.update(id){current->
                    val episodes=downloaded.episodes.map{episode->
                        val old=current.episodes.firstOrNull{it.id==episode.id}
                        require(old?.source==null || episode.source?.revision==old.source.revision){
                            "Phụ đề trong máy đã thay đổi; chưa thay bằng bản Drive. Bản chỉnh sửa hiện tại được giữ."}
                        if(old!=null)episode.mergeProgress(old).copy(path=episode.path) else episode
                    }
                    downloaded.copy(episodes=episodes,updatedAt=maxOf(current.updatedAt,downloaded.updatedAt),
                        message=if(episodes.any{it.positionConflict})MovieRecord.PROGRESS_CONFLICT_MESSAGE else downloaded.message)
                }){"Phim đã bị xóa trong lúc tải."}
                // After atomic publication these files belong to the saved record, even if an
                // account switch immediately hides it from the visible catalog.
                published=true
                identity.status("Đã tải và xác minh phim; có thể xem ngoại tuyến.")
                saved
            } catch(e:Throwable) {
                // Only files from this new completed download, never pre-existing media.
                if(!published)(downloaded.episodes.map{it.path}+downloaded.coverPath).filter{it.isNotBlank()}.forEach{path->
                    val file=File(path).canonicalFile
                    if(file.parentFile==library.folder(id).canonicalFile)file.delete()
                }
                throw e
            }
        }
    }

    internal suspend fun runQueued(id:String,email:String):Boolean=operation.withLock {
        if(identity.state.value.accountEmail!=email)return@withLock true
        val movie=library.find(id)?:return@withLock true
        if(movie.accountEmail!=email || library.isHidden(email,id))return@withLock true
        if(movie.syncState=="SYNCED" && movie.cloudId.isNotBlank())return@withLock true
        try {
            while(SessionState.running.value || VideoJobs.get(context).busy.value!=null || VideoJobs.get(context).batchBusy.value!=null) {
                if(identity.state.value.accountEmail!=email)return@withLock true
                delay(500)
            }
            if(identity.state.value.accountEmail!=email)return@withLock true
            library.update(id){it.copy(syncState="UPLOADING",message="Đang tải phim lên Google Drive…")}
            val uploading=library.find(id)?:return@withLock true
            val cloudId=identity.repositoryForMovies(email).publishMovie(uploading){message->
                if(identity.state.value.accountEmail==email)library.update(id){it.copy(message=message)}
            }
            if(identity.state.value.accountEmail!=email)return@withLock true
            val current=library.find(id)?:return@withLock true
            if(current.episodes.map{it.id to it.source?.revision}!=uploading.episodes.map{it.id to it.source?.revision}) {
                library.update(id){it.copy(syncState="QUEUED",message="Có phụ đề mới đã lưu; đang chờ đồng bộ phiên bản mới.")}
                return@withLock false
            }
            library.update(id){it.copy(syncState="SYNCED",cloudId=cloudId,message="Đã đồng bộ và xác minh thành công.")}
            true
        } catch(e:CancellationException) {
            library.update(id){it.copy(syncState="QUEUED",message="Đồng bộ đã tạm dừng; bản trong máy được giữ.")};throw e
        } catch(e:Exception) {
            library.update(id){it.copy(syncState="FAILED",message=safeMessage(e))}
            identity.status(safeMessage(e),needsAuth=e is DriveAuthorizationRequired)
            false
        }
    }
    internal suspend fun runProgress(id:String,email:String):Boolean=operation.withLock {
        if(identity.state.value.accountEmail!=email)return@withLock true
        val movie=library.find(id)?:return@withLock true
        if(movie.accountEmail!=email || movie.cloudId.isBlank() || library.isHidden(email,id))return@withLock true
        try {
            identity.repositoryForMovies(email).publishMovieProgress(movie)
            true
        } catch(e:CancellationException){throw e}
        catch(_:Exception){false}
    }
    private fun safeMessage(error:Exception)=when(error) {
        is DriveAuthorizationRequired->"Cần kết nối lại Google; bản phim trong máy được giữ."
        else->"Đồng bộ chưa hoàn tất (${error.javaClass.simpleName}); bản trong máy được giữ và sẽ thử lại."
    }
    companion object {
        internal const val TAG="movie-cloud-sync"
        @Volatile private var instance:MovieCloudSync?=null
        fun get(context:Context):MovieCloudSync=instance?:synchronized(this){instance?:MovieCloudSync(context.applicationContext).also{instance=it}}
    }
}

class MovieCloudWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val id=inputData.getString("movieId")?:return Result.failure()
        val email=inputData.getString("account")?:return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            val sync=MovieCloudSync.get(applicationContext)
            val success=if(inputData.getBoolean("progressOnly",false))sync.runProgress(id,email) else sync.runQueued(id,email)
            if(success)Result.success() else Result.retry()
        } catch(e:CancellationException){throw e}
        catch(e:Exception){Result.retry()}
    }
    override suspend fun getForegroundInfo():ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MovieCloudSync.TAG,"Đồng bộ Kho phim",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,MovieCloudSync.TAG)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Gemini OCR · Kho phim")
            .setContentText("Đang đồng bộ phim đã xác nhận; bản trong máy được giữ.").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(7304,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(7304,notification)
    }
}
