package com.gemini.ocr.video.sync

import android.accounts.Account
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.movie.MovieCloudSync
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class VideoCloudState(
    val enabled:Boolean=false,val accountEmail:String="",val message:String="Chưa kết nối Google Drive.",
    val busy:Boolean=false,val wifiOnly:Boolean=true,val authorizationRequired:Boolean=false
)

/** Only email/settings/status are persisted. Access tokens stay in memory/Google Play services. */
class VideoCloudSync internal constructor(
    private val context:Context,
    private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(),
    private val userInfoUrl:HttpUrl="https://www.googleapis.com/oauth2/v3/userinfo".toHttpUrl(),
    private val authorizationResultFromIntent:(Intent)->AuthorizationResult={
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(it)
    }
){
    private val prefs=context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(readState())
    val state:StateFlow<VideoCloudState> = mutable
    private val operation=Mutex()
    private val playbackGeneration=AtomicLong()
    private fun readState()=VideoCloudState(prefs.getBoolean("enabled",false),prefs.getString("account","").orEmpty(),
        prefs.getString("message","Chưa kết nối Google Drive.").orEmpty(),false,prefs.getBoolean("wifiOnly",true),prefs.getBoolean("needsAuth",false))
    internal fun status(message:String,busy:Boolean=false,needsAuth:Boolean=false){
        prefs.edit().putString("message",message).putBoolean("needsAuth",needsAuth).apply()
        mutable.value=readState().copy(busy=busy)
    }
    fun setEnabled(enabled:Boolean){
        prefs.edit().putBoolean("enabled",enabled).apply()
        if(!enabled){WorkManager.getInstance(context).cancelAllWorkByTag(TAG);status("Đã tắt tự sao lưu; bản trên máy và Drive được giữ.")}
        else{
            val account=prefs.getString("account","").orEmpty()
            if(account.isBlank())status("Kết nối tài khoản Google trước; dữ liệu vẫn được lưu trên máy.",needsAuth=true)
            else{
                status("Đã bật tự sao lưu ${if(prefs.getBoolean("wifiOnly",true))"qua Wi-Fi/mạng không tính dung lượng" else "khi có mạng"}.")
                VideoStore(context).list().filter{ready(it)}.forEach{enqueue(it.id)}
            }
        }
    }
    fun setWifiOnly(wifiOnly:Boolean){
        prefs.edit().putBoolean("wifiOnly",wifiOnly).apply();mutable.value=readState()
        if(state.value.enabled)setEnabled(true)
        MovieCloudSync.get(context).rescheduleConstraints()
    }
    /** First interactive use can return a Google account/consent PendingIntent. Never launch from a worker. */
    suspend fun authorize(selectAccount:Boolean=true):PendingIntent?{
        try{
            status("Đang yêu cầu Google chọn tài khoản và cấp quyền…")
            val request=request(if(selectAccount)null else state.value.accountEmail)
            val result=Identity.getAuthorizationClient(context).authorize(request).await()
            if(result.hasResolution())return result.pendingIntent.also{
                if(it==null)throw AuthorizationFailure("NO_RESOLUTION","Google không trả cửa sổ cấp quyền.")
                status("Đang chờ bạn chọn tài khoản và cấp quyền Google Drive.")
            }
            accept(result)
            return null
        }catch(e:CancellationException){throw e}
        catch(e:Exception){failAuthorization(e,"request")}
    }
    suspend fun completeAuthorization(data:Intent?,resultCode:Int=Activity.RESULT_OK){
        try{
            // Google returns error Status in the intent even when the activity result is cancelled.
            // Parse it as documented instead of replacing developer/network errors with a refusal.
            if(data==null)throw AuthorizationFailure("NO_RESULT_$resultCode",
                "Google đã đóng cửa sổ mà chưa trả kết quả cấp quyền. Hãy chọn tài khoản và thử lại.")
            val result=authorizationResultFromIntent(data)
            if(result.hasResolution())throw AuthorizationFailure("INCOMPLETE","Cấp quyền Google chưa hoàn tất.")
            accept(result)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){failAuthorization(e,"callback")}
    }
    private fun failAuthorization(error:Exception,stage:String):Nothing{
        val message=authMessage(error,stage)
        status(message,needsAuth=true)
        throw IOException(message,error)
    }
    private suspend fun accept(result:AuthorizationResult)=acceptAuthorization(result.accessToken,result.grantedScopes)
    /** Commit account state only after the required Drive grant and userinfo have been verified. */
    internal suspend fun acceptAuthorization(access:String?,grantedScopes:List<String>){
        if(APPDATA_SCOPE !in grantedScopes)throw AuthorizationFailure("DRIVE_SCOPE_MISSING",
            "Bạn chưa cấp quyền dữ liệu riêng của ứng dụng trong Google Drive. Chọn lại tài khoản và chấp thuận quyền này.")
        if(access.isNullOrBlank())throw AuthorizationFailure("NO_ACCESS_TOKEN","Google chưa cấp access token.")
        val email=withContext(Dispatchers.IO){
            http.newCall(Request.Builder().url(userInfoUrl)
                .header("Authorization","Bearer $access").build()).execute().use{response->
                if(!response.isSuccessful)throw AuthorizationFailure("USERINFO_HTTP_${response.code}",
                    "Chưa xác minh được tài khoản Google đã chọn; hãy kiểm tra mạng và kết nối lại.")
                val body=response.body?:throw AuthorizationFailure("USERINFO_EMPTY","Google không trả thông tin tài khoản.")
                fun tooLarge():Nothing=throw AuthorizationFailure("USERINFO_TOO_LARGE","Phản hồi tài khoản Google vượt giới hạn.")
                if(body.contentLength()>64*1024)tooLarge()
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                body.byteStream().use{input->while(true){val count=input.read(buffer);if(count<0)break
                    if(output.size()+count>64*1024)tooLarge();output.write(buffer,0,count)}}
                try{JSONObject(output.toString("UTF-8")).getString("email")}
                catch(_:org.json.JSONException){throw AuthorizationFailure("USERINFO_INVALID","Google không trả địa chỉ tài khoản hợp lệ.")}
            }
        }
        if(email.length !in 3..320 || !email.contains('@'))throw AuthorizationFailure("USERINFO_INVALID",
            "Google không trả địa chỉ tài khoản hợp lệ.")
        rememberVerifiedAccount(email)
        status("Đã kết nối Google Drive: $email. ${if(state.value.enabled)"Tự sao lưu đang bật." else "Bật Tự sao lưu để tải dữ liệu lên."}")
        if(state.value.enabled)setEnabled(true)
    }
    internal fun rememberVerifiedAccount(email:String){
        require(email.length in 3..320 && email.contains('@'))
        playbackGeneration.incrementAndGet()
        if(state.value.accountEmail!=email){
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(MovieCloudSync.TAG)
            // Choosing another account never authorizes uploading this device's projects to it.
            prefs.edit().putBoolean("enabled",false).putString("account",email).putBoolean("needsAuth",false).apply()
        }else prefs.edit().putString("account",email).putBoolean("needsAuth",false).apply()
        mutable.value=readState()
    }
    private fun request(email:String?):AuthorizationRequest{
        val builder=AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(APPDATA_SCOPE),Scope("openid"),Scope("email")))
        if(email.isNullOrBlank())builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else builder.setAccount(Account(email,"com.google"))
        return builder.build()
    }
    private suspend fun accessToken(email:String):String{
        if(email.isBlank() || state.value.accountEmail!=email)throw DriveAuthorizationRequired()
        val result=try{Identity.getAuthorizationClient(context).authorize(request(email)).await()}
        catch(e:CancellationException){throw e}
        catch(e:Exception){throw IOException(authMessage(e,"refresh"),e)}
        if(result.hasResolution())throw DriveAuthorizationRequired()
        if(state.value.accountEmail!=email)throw DriveAuthorizationRequired()
        if(APPDATA_SCOPE !in result.grantedScopes)throw DriveAuthorizationRequired()
        return result.accessToken?.takeIf{it.isNotBlank()}?:throw DriveAuthorizationRequired()
    }
    private fun repository(email:String)=DriveVideoRepository(context,{accessToken(email)},gate={
        while(SessionState.running.value || VideoJobs.get(context).busy.value!=null || VideoJobs.get(context).batchBusy.value!=null){
            status("Đồng bộ đang nhường tài nguyên cho Live/tác vụ video.",busy=true);delay(500)
        }
        if(state.value.accountEmail!=email)throw DriveAuthorizationRequired()
    })
    internal fun repositoryForMovies(email:String)=repository(email)

    internal fun requirePlaybackAccount(email:String,generation:Long?=null) {
        if(email.isBlank() || state.value.accountEmail!=email ||
            (generation!=null && generation!=playbackGeneration.get()))
            throw DrivePlaybackException(DrivePlaybackFailure.ACCOUNT)
        if(state.value.authorizationRequired)throw DrivePlaybackException(DrivePlaybackFailure.AUTH)
    }
    internal fun playbackAccountGuard(email:String):()->Unit {
        val generation=playbackGeneration.get()
        return {requirePlaybackAccount(email,generation)}
    }
    private suspend fun playbackToken(email:String,rejected:String?=null):String {
        requirePlaybackAccount(email)
        try {
            return withTimeoutOrNull(20_000) {
                if(rejected!=null)Identity.getAuthorizationClient(context)
                    .clearToken(ClearTokenRequest.builder().setToken(rejected).build()).await()
                requirePlaybackAccount(email)
                accessToken(email).also{requirePlaybackAccount(email)}
            } ?: throw DrivePlaybackException(DrivePlaybackFailure.AUTH)
        } catch(e:CancellationException){throw e}
        catch(e:DrivePlaybackException){throw e}
        catch(_:Exception){throw DrivePlaybackException(DrivePlaybackFailure.AUTH)}
    }
    /** Playback does not wait behind the background upload/video resource gate. */
    internal fun repositoryForPlayback(email:String,guard:()->Unit):DriveVideoRepository {
        val playbackHttp=http.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        return DriveVideoRepository(context,{guard();playbackToken(email).also{guard()}},
            client=playbackHttp,
            refreshPlaybackToken={rejected->guard();playbackToken(email,rejected).also{guard()}},
            playbackAccountGuard=guard)
    }

    fun enqueue(projectId:String){
        val current=state.value
        if(!current.enabled)return
        if(current.accountEmail.isBlank()){status("Dự án đã lưu trên máy; cần kết nối Google để tự sao lưu.",needsAuth=true);return}
        val project=runCatching{VideoStore(context).load(projectId)}.getOrNull()?:return
        if(!ready(project))return
        val fingerprint=fingerprint(project)
        if(prefs.getString(marker(current.accountEmail,projectId),"")==fingerprint)return
        val work=OneTimeWorkRequestBuilder<VideoCloudSyncWorker>()
            .setInputData(workDataOf("projectId" to projectId,"account" to current.accountEmail))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if(current.wifiOnly)NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG-$projectId",ExistingWorkPolicy.REPLACE,work)
        status("Đã xếp hàng sao lưu ${project.title.take(60)}${if(current.wifiOnly)"; chờ Wi-Fi/mạng không tính dung lượng" else ""}.")
    }
    suspend fun listCloud():List<CloudVideoSnapshot> = operation.withLock{
        val email=state.value.accountEmail
        try{status("Đang tải danh sách Google Drive…",true)
            repository(email).list().also{status("Google Drive có ${it.size} bản lưu; tải một bản để xem trên thiết bị này.")}
        }catch(e:CancellationException){status("Đã dừng tải danh sách.");throw e}
        catch(e:Exception){status(errorMessage(e),needsAuth=e is DriveAuthorizationRequired);throw e}
    }
    suspend fun restore(snapshot:CloudVideoSnapshot):VideoProject = operation.withLock{
        try{
            status("Đang tải dự án và video từ Google Drive…",true)
            repository(state.value.accountEmail).restore(snapshot){status(it,true)}.also{
                VideoJobs.get(context).refresh();status("Đã tải ${it.title}; có thể xem offline từ timeline đã lưu.")
            }
        }catch(e:CancellationException){status("Đã dừng tải bản sao; dự án cũ trên máy giữ nguyên.");throw e}
        catch(e:Exception){status(errorMessage(e),needsAuth=e is DriveAuthorizationRequired);throw e}
    }
    fun disconnect(){
        // Forget this app's account selection; never sign out of Android or delete Drive/user data.
        playbackGeneration.incrementAndGet()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        WorkManager.getInstance(context).cancelAllWorkByTag(MovieCloudSync.TAG)
        prefs.edit().putBoolean("enabled",false).remove("account").remove("needsAuth").apply()
        status("Đã ngắt đồng bộ trên thiết bị này. Dữ liệu trên máy và Drive vẫn còn.")
    }
    internal suspend fun runQueued(projectId:String,email:String):Boolean=operation.withLock{
        if(!state.value.enabled || state.value.accountEmail!=email)return@withLock true
        while(SessionState.running.value || VideoJobs.get(context).busy.value!=null || VideoJobs.get(context).batchBusy.value!=null){status("Đồng bộ đang nhường tài nguyên cho Live/tác vụ video.");delay(500)}
        if(!state.value.enabled || state.value.accountEmail!=email)return@withLock true
        val project=VideoStore(context).load(projectId)
        if(!ready(project))return@withLock true
        val fingerprint=fingerprint(project)
        val key=marker(email,projectId)
        if(prefs.getString(key,"")==fingerprint)return@withLock true
        try{
            status("Đang sao lưu ${project.title.take(60)}…",true)
            repository(email).publish(project){status(it,true)}
            // If the local project changed while streaming, root enqueues the next immutable revision.
            prefs.edit().putString(key,fingerprint).apply()
            status("Đã sao lưu ${project.title.take(60)} lên Google Drive ($email).")
            true
        }catch(e:CancellationException){status("Sao lưu đã tạm dừng; dữ liệu trên máy được giữ.");throw e}
        catch(e:DriveAuthorizationRequired){status(e.message.orEmpty(),needsAuth=true);false}
        catch(e:Exception){status(errorMessage(e));false}
    }
    private fun marker(email:String,id:String)="synced-${hash(email.toByteArray())}-$id"
    private fun fingerprint(project:VideoProject)=hash(project.json().apply{remove("updatedAt")}.toString().toByteArray(Charsets.UTF_8))
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    internal fun ready(p:VideoProject)=!java.io.File(VideoStore(context).folder(p.id),"batch-owner").exists() &&
        p.cues.isNotEmpty() && p.cues.all{it.translation.isNotBlank()}
    private fun errorMessage(e:Exception)=e.message?.takeIf{it.startsWith("Google") || it.startsWith("Cần") || it.startsWith("Không") || it.startsWith("Tệp") || it.startsWith("Mỗi") || it.startsWith("Dự") || it.startsWith("Kiểm")}
        ?:"Đồng bộ chưa xong (${e.javaClass.simpleName}); bản trên máy an toàn, tác vụ sẽ thử lại khi có mạng."
    private fun authMessage(e:Exception,stage:String):String{
        val code=when(e){is ApiException->e.statusCode.toString();is AuthorizationFailure->e.code;else->e.javaClass.simpleName}
        val prefix="Google Drive [$stage/$code]: "
        return prefix+when{
            e is AuthorizationFailure->e.message.orEmpty()
            e is ApiException && e.statusCode==10->
                "OAuth chưa được cấu hình cho ${context.packageName}. Kiểm tra Android OAuth client đúng package/SHA-1 APK, consent/test users và Drive API trong cùng Google Cloud project."
            e is ApiException && e.statusCode in listOf(16,12501)->
                "Cửa sổ Google đã bị hủy hoặc chưa được cấp quyền. Chọn lại tài khoản và chấp thuận quyền Drive."
            e is ApiException && e.statusCode==7->"Lỗi mạng khi cấp quyền; kiểm tra kết nối rồi thử lại."
            else->"Chưa kết nối được. Kiểm tra Google Play services, mạng và quyền tài khoản; dữ liệu vẫn lưu trên máy."
        }
    }
    private class AuthorizationFailure(val code:String,message:String):IOException(message)
    companion object{
        const val APPDATA_SCOPE="https://www.googleapis.com/auth/drive.appdata"
        internal const val TAG="video-google-sync"
        @Volatile private var singleton:VideoCloudSync?=null
        fun get(context:Context):VideoCloudSync=singleton?:synchronized(this){singleton?:VideoCloudSync(context.applicationContext).also{singleton=it}}
    }
}

/** Durable network-constrained queue; it never invokes authorization UI in the background. */
class VideoCloudSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters){
    override suspend fun doWork():Result{
        val id=inputData.getString("projectId")?:return Result.failure()
        val email=inputData.getString("account")?:return Result.failure()
        val sync=VideoCloudSync.get(applicationContext)
        return try{
            setForeground(getForegroundInfo())
            if(sync.runQueued(id,email))Result.success() else Result.retry()
        }catch(e:CancellationException){throw e}
        catch(e:Exception){sync.status("Sao lưu đang chờ thử lại; bản trên máy được giữ.");Result.retry()}
    }
    override suspend fun getForegroundInfo():ForegroundInfo{
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("video-cloud-sync","Đồng bộ video Google Drive",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"video-cloud-sync")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Gemini OCR · sao lưu Google Drive")
            .setContentText("Đang tải dự án và video; bản trên máy được giữ.").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(7302,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(7302,notification)
    }
}
