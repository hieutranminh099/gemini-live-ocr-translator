@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video.sync

import android.content.Context
import android.net.Uri
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTimeline
import com.gemini.ocr.movie.MovieRecord
import com.gemini.ocr.movie.MoviePlaybackHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudVideoSnapshot(
    val fileId:String, val projectId:String, val title:String, val updatedAt:Long,
    val revisionId:String, val sourceBytes:Long=0
)

/** Immutable revisions in the current app's private Drive space. No shared/public URLs or secrets. */
internal class DriveVideoRepository(
    private val context:Context,
    private val token:suspend()->String,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build(),
    private val api:HttpUrl="https://www.googleapis.com/drive/v3/".toHttpUrl(),
    private val uploadApi:HttpUrl="https://www.googleapis.com/upload/drive/v3/".toHttpUrl(),
    private val gate:suspend()->Unit={},
    private val progressWriterId:String=installationWriterId(context),
    private val refreshPlaybackToken:(suspend (String)->String)?=null,
    private val playbackAccountGuard:()->Unit={}
) {
    private val store=VideoStore(context)
    private data class LocalMedia(val role:String,val size:Long,val sha:String,val mime:String,val open:()->InputStream)

    suspend fun publish(project:VideoProject,onProgress:(String)->Unit={}):CloudVideoSnapshot=withContext(Dispatchers.IO){
        require(project.cues.isNotEmpty() && project.cues.all{it.translation.isNotBlank()}){"Dịch xong các câu trước khi sao lưu."}
        VideoTimeline.validate(project.cues,project.durationMs)
        val revision=UUID.randomUUID().toString()
        val media=mutableListOf(inspect("source",Uri.parse(project.uri)))
        if(project.subtitleUri.isNotBlank())media+=inspect("subtitle",Uri.parse(project.subtitleUri))
        if(project.exportedMp4.isNotBlank()){
            val uri=Uri.parse(project.exportedMp4).let{if(it.scheme==null)Uri.fromFile(File(project.exportedMp4)) else it}
            media+=inspect("export",uri)
        }
        run {
            val descriptors=JSONArray()
            for(local in media){
                currentCoroutineContext().ensureActive()
                onProgress("Đang sao lưu ${if(local.role=="source")"video nguồn" else if(local.role=="export")"MP4 đã dịch" else "phụ đề nguồn"}…")
                val existing=findMedia(local.sha,local.size)
                val id=existing?:upload(local,project.id,revision)
                descriptors.put(JSONObject().put("role",local.role).put("id",id).put("size",local.size)
                    .put("sha256",local.sha).put("mime",local.mime))
            }
            // Local document URIs and private filesystem paths never belong in a portable backup.
            val portable=project.json().put("uri","").put("subtitleUri","").put("exportedMp4","")
                .put("originalSourceUri","").put("originalSubtitleUri","")
            val envelope=JSONObject().put("schema",1).put("revision",revision)
                .put("project",portable).put("media",descriptors)
            val bytes=envelope.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size<=MAX_MANIFEST){"Dự án quá lớn để sao lưu (32 MiB)."}
            val local=LocalMedia("manifest",bytes.size.toLong(),hash(bytes),"application/json"){bytes.inputStream()}
            val id=upload(local,project.id,revision,project.title,project.updatedAt,media.first().size)
            CloudVideoSnapshot(id,project.id,project.title,project.updatedAt,revision,media.first().size)
        }
    }

    suspend fun list():List<CloudVideoSnapshot> = withContext(Dispatchers.IO){
        val files=listFiles("appProperties has { key='kind' and value='manifest' }")
        files.mapNotNull{item->runCatching{
            val p=item.getJSONObject("appProperties")
            CloudVideoSnapshot(item.getString("id"),p.getString("projectId"),p.optString("title",item.optString("name")),
                p.getString("updatedAt").toLong(),p.getString("revision"),p.optString("sourceBytes","0").toLong())
        }.getOrNull()}.sortedByDescending{it.updatedAt}
    }

    /** A separate manifest kind is the commit boundary for the confirmed movie catalog. */
    suspend fun publishMovie(movie:MovieRecord,onProgress:(String)->Unit={}):String=withContext(Dispatchers.IO) {
        MovieRecord.validate(movie)
        val media=JSONArray()
        for(episode in movie.episodes) {
            val local=if(episode.source!=null) {
                require(episode.source.uri.isNotBlank()) {"Tải video nguồn trước khi đồng bộ phụ đề thay đổi."}
                inspect("source",Uri.parse(episode.source.uri)).also{
                    require(it.sha==episode.source.sha256 && it.size==episode.source.bytes){
                        "Video nguồn đã thay đổi; không đồng bộ bản phim khác với snapshot đã xác nhận."}
                }
            } else {
                require(episode.path.isNotBlank()) {"Tải bản ngoại tuyến trước khi đồng bộ thay đổi."}
                inspectMovieMedia(File(episode.path),"video/mp4")
            }
            onProgress("Đang tải ${episode.title.take(60)} lên Google Drive…")
            val existing=findMedia(local.sha,local.size)
            val fileId=if(existing!=null){verifyMovieUpload(existing,local);existing}
                else upload(local,movie.id,"movie",verifyServer=true)
            media.put(JSONObject().put("role","episode").put("episodeId",episode.id).put("id",fileId)
                .put("size",local.size).put("sha256",local.sha).put("mime",local.mime))
        }
        if(movie.coverPath.isNotBlank() && File(movie.coverPath).isFile) {
            val local=inspectMovieMedia(File(movie.coverPath),"image/jpeg")
            val existing=findMedia(local.sha,local.size)
            val fileId=if(existing!=null){verifyMovieUpload(existing,local);existing}
                else upload(local,movie.id,"movie",verifyServer=true)
            media.put(JSONObject().put("role","cover").put("id",fileId).put("size",local.size).put("sha256",local.sha).put("mime",local.mime))
        }
        // Old clients must never treat original pixels as a translated hard-subtitle MP4.
        // A separate discoverable kind keeps their schema-1 catalog usable and hides source movies.
        val sourceMovie=movie.episodes.any{it.source!=null}
        val manifestKind=if(sourceMovie)"movie-source-manifest" else "movie-manifest"
        val envelope=JSONObject().put("schema",if(sourceMovie)2 else 1).put("kind","movie").put("movie",movie.json(portable=true)).put("media",media)
        val bytes=envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_MANIFEST){"Danh mục phim vượt 32 MiB."}
        val revision=hash(bytes)
        val local=LocalMedia("movie-manifest",bytes.size.toLong(),revision,"application/json"){bytes.inputStream()}
        // A retry after a successful publication reuses the same catalog revision.
        val previous=listFiles("appProperties has { key='kind' and value='$manifestKind' } and appProperties has { key='projectId' and value='${movie.id}' } and appProperties has { key='revision' and value='$revision' }").firstOrNull()
        if(previous!=null) {
            val fileId=previous.getString("id");verifyMovieUpload(fileId,local);return@withContext fileId
        }
        upload(local,movie.id,revision,movie.title,movie.updatedAt,media.sumSizes(),finalKind=manifestKind,verifyServer=true)
    }

    suspend fun listMovies():List<MovieRecord> = withContext(Dispatchers.IO) {
        // Each writer's newest complete snapshot carries per-episode clocks. This bounds reads
        // by devices, rather than downloading every immutable historical watch update.
        val positions=listFiles("appProperties has { key='kind' and value='movie-progress' }")
            .groupBy{it.getJSONObject("appProperties").getString("projectId")}
            .mapValues{(_,versions)->versions.groupBy{it.getJSONObject("appProperties").optString("writerId","legacy")}
                .values.map{writerVersions->writerVersions.maxWith(compareBy<JSONObject>(
                    {it.getJSONObject("appProperties").optString("updatedAt","0").toLongOrNull()?:0},
                    {it.getJSONObject("appProperties").optString("revision")}))}}
        (listFiles("appProperties has { key='kind' and value='movie-manifest' }")+
            listFiles("appProperties has { key='kind' and value='movie-source-manifest' }"))
            .groupBy{it.getJSONObject("appProperties").getString("projectId")}.values
            .map{versions->versions.maxBy{it.getJSONObject("appProperties").optString("updatedAt","0").toLongOrNull()?:0}}
            .map { item->
            val id=item.getString("id").also{validId(it)}
            val envelope=movieManifest(id)
            val movie=MovieRecord.from(envelope.getJSONObject("movie")).copy(cloudId=id,syncState="CLOUD")
            var episodes=movie.episodes
            var updatedAt=movie.updatedAt
            for(positionFile in positions[movie.id].orEmpty()) {
                val raw=JSONObject(downloadBytes(positionFile.getString("id").also{validId(it)},MAX_MANIFEST).toString(Charsets.UTF_8))
                require(raw.getInt("schema")==1 && raw.getString("kind")=="movie-progress" && raw.getString("movieId")==movie.id)
                val snapshotAt=raw.getLong("updatedAt");require(snapshotAt>=0)
                updatedAt=maxOf(updatedAt,snapshotAt)
                val rows=raw.getJSONArray("episodes")
                val map=(0 until rows.length()).map{rows.getJSONObject(it)}.associateBy{it.getString("id")}
                episodes=episodes.map{episode->map[episode.id]?.let{row->
                    val stamp=row.optLong("positionUpdatedAt",snapshotAt);require(stamp>=0)
                    episode.mergeProgress(episode.copy(positionMs=row.getLong("positionMs").coerceIn(0,episode.durationMs),
                        positionUpdatedAt=stamp,positionConflict=row.optBoolean("positionConflict")),keepLocalOnTie=false)
                }?:episode}
            }
            movie.copy(episodes=episodes,updatedAt=updatedAt,message=if(episodes.any{it.positionConflict})MovieRecord.PROGRESS_CONFLICT_MESSAGE else "")
        }.sortedByDescending{it.confirmedAt}
    }

    /** Watch updates never inspect or upload a video again. */
    suspend fun publishMovieProgress(movie:MovieRecord):String=withContext(Dispatchers.IO) {
        MovieRecord.validate(movie)
        require(movie.cloudId.isNotBlank()){"Đồng bộ phim trước khi gửi vị trí xem."}
        require(progressWriterId.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val envelope=JSONObject().put("schema",1).put("kind","movie-progress").put("movieId",movie.id).put("writerId",progressWriterId)
            .put("updatedAt",movie.updatedAt).put("episodes",JSONArray(movie.episodes.map{
                JSONObject().put("id",it.id).put("positionMs",it.positionMs).put("positionUpdatedAt",it.positionUpdatedAt)
                    .put("positionConflict",it.positionConflict)
            }))
        val bytes=envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_MANIFEST)
        val revision=hash(bytes)
        val local=LocalMedia("movie-progress",bytes.size.toLong(),revision,"application/json"){bytes.inputStream()}
        val existing=listFiles("appProperties has { key='kind' and value='movie-progress' } and appProperties has { key='projectId' and value='${movie.id}' } and appProperties has { key='revision' and value='$revision' }").firstOrNull()
        if(existing!=null)return@withContext existing.getString("id")
        upload(local,movie.id,revision,movie.title,movie.updatedAt,finalKind="movie-progress",verifyServer=true,writerId=progressWriterId)
    }

    /** Resolve metadata only. Media bytes are fetched later, on demand by the player. */
    internal suspend fun prepareMoviePlayback(movie:MovieRecord,episodeId:String):MoviePlaybackHandle=withContext(Dispatchers.IO) {
        try {
            playbackAccountGuard()
            MovieRecord.validate(movie)
            val local=requireNotNull(movie.episodes.firstOrNull{it.id==episodeId})
            val envelope=movieManifest(movie.cloudId)
            val remote=MovieRecord.from(envelope.getJSONObject("movie"))
            require(remote.id==movie.id)
            val episode=requireNotNull(remote.episodes.firstOrNull{it.id==episodeId})
            require(episode.durationMs==local.durationMs && episode.source?.revision==local.source?.revision)
            val rows=envelope.getJSONArray("media")
            val descriptors=(0 until rows.length()).map{rows.getJSONObject(it)}
                .filter{it.getString("role")=="episode" && it.getString("episodeId")==episodeId}
            require(descriptors.size==1)
            val row=descriptors.single()
            val media=DriveMovieMedia(row.getString("id"),row.getLong("size"),row.getString("sha256"))
            episode.source?.let{require(it.bytes==media.size && it.sha256==media.sha256)}
            // Drive verifies the full immutable blob; partial playback cannot hash an unseen file.
            val verified=jsonRequest(Request.Builder().url(api.newBuilder().addPathSegment("files").addPathSegment(media.id)
                .addQueryParameter("fields","size,sha256Checksum,trashed,capabilities(canDownload)").build()).get())
            require(!verified.optBoolean("trashed") && verified.optString("size").toLongOrNull()==media.size &&
                verified.optString("sha256Checksum")==media.sha256)
            if(verified.optJSONObject("capabilities")?.optBoolean("canDownload",true)==false)
                throw DrivePlaybackException(DrivePlaybackFailure.FORBIDDEN)
            playbackAccountGuard()
            val selected=episode.mergeProgress(local)
            val project=episode.source?.project(selected)?.copy(uri=media.uri.toString())
                ?:VideoProject(episode.id,episode.title,media.uri.toString(),episode.durationMs,1,1)
            val factory=DriveRangeDataSource.Factory(client,api,media,token={rejected->
                runBlocking {
                    if(rejected!=null && refreshPlaybackToken!=null)refreshPlaybackToken.invoke(rejected) else token()
                }
            },accountGuard=playbackAccountGuard)
            MoviePlaybackHandle(project,selected,movie.accountEmail,factory)
        } catch(e:CancellationException) { throw e }
        catch(e:DrivePlaybackException) { throw e }
        catch(_:DriveAuthorizationRequired) { throw DrivePlaybackException(DrivePlaybackFailure.AUTH) }
        catch(_:IOException) { throw DrivePlaybackException(DrivePlaybackFailure.NETWORK) }
        catch(_:Exception) { throw DrivePlaybackException(DrivePlaybackFailure.METADATA) }
    }

    /** Downloads are bounded streams verified before any local movie record is published. */
    suspend fun downloadMovie(movie:MovieRecord,folder:File,onProgress:(String)->Unit={}):MovieRecord=withContext(Dispatchers.IO) {
        val envelope=movieManifest(movie.cloudId)
        val remote=MovieRecord.from(envelope.getJSONObject("movie"))
        require(remote.id==movie.id){"Bản phim Google Drive không khớp danh mục."}
        val rows=envelope.getJSONArray("media")
        val revision=UUID.randomUUID().toString();val created=mutableListOf<File>()
        try {
            val episodes=remote.episodes.mapIndexed{index,episode->
                onProgress("Đang tải ${episode.title.take(60)} về xem ngoại tuyến…")
                val descriptors=(0 until rows.length()).map{rows.getJSONObject(it)}
                    .filter{it.getString("role")=="episode" && it.getString("episodeId")==episode.id}
                require(descriptors.size==1){"Danh mục media của tập không hợp lệ."}
                val descriptor=descriptors.single()
                episode.source?.let{source->
                    require(source.sha256==descriptor.getString("sha256") && source.bytes==descriptor.getLong("size")){
                        "Media nguồn không khớp snapshot phụ đề đã xác nhận."}
                }
                val target=File(folder,"$revision-$index.${if(episode.source!=null)"input" else "mp4"}");created+=target
                downloadFile(descriptor,target)
                episode.copy(path=target.absolutePath,source=episode.source?.copy(uri=Uri.fromFile(target).toString(),projectId=""))
            }
            val covers=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getString("role")=="cover"}
            require(covers.size<=1 && rows.length()==episodes.size+covers.size){"Danh mục media có phần không hợp lệ."}
            val cover=if(covers.isNotEmpty())File(folder,"$revision-cover.jpg").also{created+=it;downloadFile(covers.single(),it)} else null
            remote.copy(episodes=episodes,cloudId=movie.cloudId,accountEmail=movie.accountEmail,syncState="SYNCED",coverPath=cover?.absolutePath.orEmpty(),message="Đã tải và xác minh; có thể xem ngoại tuyến.")
        } catch(e:Throwable) {created.forEach{it.delete()};throw e}
    }

    private suspend fun movieManifest(id:String):JSONObject {
        validId(id)
        val envelope=JSONObject(downloadBytes(id,MAX_MANIFEST).toString(Charsets.UTF_8))
        val schema=envelope.getInt("schema")
        require(schema in 1..2 && envelope.getString("kind")=="movie"){"Bản cloud chưa phải phim đã xác nhận hoặc cần ứng dụng mới hơn."}
        val movie=MovieRecord.from(envelope.getJSONObject("movie"))
        require(schema==envelope.getJSONObject("movie").getInt("schema")) {"Phiên bản snapshot phim không khớp."}
        require(schema!=1 || movie.episodes.none{it.source!=null}) {"Bản nguồn có phụ đề rời phải dùng định dạng Kho phim mới."}
        require(movie.episodes.all{it.path.isBlank() && (it.source==null || it.source.uri.isBlank() && it.source.projectId.isBlank())} && movie.accountEmail.isBlank() && movie.coverPath.isBlank() && movie.cloudId.isBlank()){
            "Danh mục cloud chứa đường dẫn không hợp lệ."
        }
        return envelope
    }
    private suspend fun inspectMovieMedia(file:File,mime:String):LocalMedia {
        require(file.isFile && file.canRead()){"Không đọc được bản phim đã xác nhận."}
        var count=0L;val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(128*1024)
        file.inputStream().use{input->while(true){currentCoroutineContext().ensureActive();gate();val n=input.read(buffer);if(n<0)break
            count+=n;require(count<=MAX_MEDIA){"Mỗi tệp sao lưu tối đa 20 GiB."};digest.update(buffer,0,n)}}
        require(count>0){"Tệp phim trống."}
        return LocalMedia("movie-media",count,digest.digest().joinToString(""){"%02x".format(it)},mime){file.inputStream()}
    }
    private fun JSONArray.sumSizes():Long {
        var result=0L
        for(i in 0 until length()){val size=getJSONObject(i).getLong("size");require(result<=Long.MAX_VALUE-size);result+=size}
        return result
    }

    suspend fun restore(snapshot:CloudVideoSnapshot,onProgress:(String)->Unit={}):VideoProject=withContext(Dispatchers.IO){
        validId(snapshot.fileId)
        val raw=downloadBytes(snapshot.fileId,MAX_MANIFEST)
        val envelope=JSONObject(raw.toString(Charsets.UTF_8))
        require(envelope.getInt("schema")==1){"Phiên bản bản sao chưa được hỗ trợ."}
        require(envelope.getString("revision")==snapshot.revisionId){"Bản sao đã thay đổi; hãy tải lại danh sách."}
        val old=VideoProject.from(envelope.getJSONObject("project"))
        require(old.id==snapshot.projectId)
        require(old.durationMs>0 && old.durationMs<=24L*60*60*1000 && old.width in 1..16384 && old.height in 1..16384)
        require(old.cues.sumOf{it.source.length.toLong()+it.translation.length}<=2_000_000L)
        VideoTimeline.validate(old.cues,old.durationMs)
        val rows=envelope.getJSONArray("media")
        require(rows.length() in 1..3)
        val roles=(0 until rows.length()).map{rows.getJSONObject(it).getString("role")}
        require(roles.toSet().size==roles.size && roles.all{it in setOf("source","subtitle","export")} && "source" in roles)
        // Always clone: a restore can never overwrite a local timeline or User Approved edit.
        val freshId=UUID.randomUUID().toString()
        val folder=store.folder(freshId)
        var saved=false
        try{
            val restored=mutableMapOf<String,Uri>()
            for(i in 0 until rows.length()){
                val descriptor=rows.getJSONObject(i);val role=descriptor.getString("role")
                onProgress("Đang tải ${if(role=="source")"video nguồn" else if(role=="export")"MP4 đã dịch" else "phụ đề nguồn"}…")
                val path=File(folder,if(role=="export")"cloud-translated.mp4" else "cloud-$role.input")
                downloadFile(descriptor,path)
                restored[role]=Uri.fromFile(path)
            }
            val source=restored.getValue("source").toString()
            val subtitle=restored["subtitle"]?.toString().orEmpty()
            val export=restored["export"]?.path.orEmpty()
            val p=old.copy(id=freshId,title=old.title.take(180)+" · Google Drive",uri=source,subtitleUri=subtitle,
                originalSourceUri=source,originalSubtitleUri=subtitle,exportedMp4=export,
                stage=if(old.cues.all{it.translation.isNotBlank()})VideoStage.REVIEW else VideoStage.PAUSED,
                message="Đã tải bản sao Google Drive. Timeline và trạng thái duyệt được giữ; bản cũ trên máy không đổi.")
            store.save(p).also{saved=true}
        }finally{
            if(!saved){
                // Only our new UUID folder, containing known own downloads, can be removed.
                folder.listFiles()?.filter{it.name.startsWith("cloud-") || it.name=="project.json"}?.forEach{it.delete()}
                folder.delete()
            }
        }
    }

    private suspend fun inspect(role:String,uri:Uri):LocalMedia{
        require(uri.scheme in setOf("content","file")){"Không đọc được nguồn để sao lưu; hãy chọn lại video."}
        val open:()->InputStream={if(uri.scheme=="file")File(requireNotNull(uri.path)).inputStream()
            else checkNotNull(context.contentResolver.openInputStream(uri)){"Không còn quyền đọc video. Chọn lại nguồn."}}
        var bytes=0L;val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(128*1024)
        open().use{stream->while(true){currentCoroutineContext().ensureActive();gate();val n=stream.read(buffer);if(n<0)break
            bytes+=n;require(bytes<=MAX_MEDIA){"Mỗi tệp sao lưu tối đa 20 GiB."};digest.update(buffer,0,n)}}
        require(bytes>0){"Tệp nguồn trống."}
        val mime=if(role=="export")"video/mp4" else if(role=="subtitle")"application/octet-stream"
            else context.contentResolver.getType(uri)?:"application/octet-stream"
        return LocalMedia(role,bytes,digest.digest().joinToString(""){"%02x".format(it)},mime,open)
    }

    private suspend fun findMedia(sha:String,size:Long):String? = listFiles(
        "appProperties has { key='kind' and value='media' } and appProperties has { key='sha256' and value='$sha' }")
        .firstOrNull{it.optString("size").toLongOrNull()==size}?.getString("id")

    private suspend fun listFiles(query:String):List<JSONObject>{
        val rows=mutableListOf<JSONObject>();var page:String?=null
        do{
            val url=api.newBuilder().addPathSegment("files").addQueryParameter("spaces","appDataFolder")
                .addQueryParameter("q","trashed = false and ($query)").addQueryParameter("pageSize","1000")
                .addQueryParameter("fields","nextPageToken,files(id,name,size,appProperties)")
            page?.let{url.addQueryParameter("pageToken",it)}
            val json=jsonRequest(Request.Builder().url(url.build()).get())
            val files=json.optJSONArray("files")?:JSONArray()
            for(i in 0 until files.length())rows+=files.getJSONObject(i)
            require(rows.size<=100000){"Kho có quá nhiều phiên bản; cần dọn dữ liệu trong cài đặt Google Drive."}
            page=json.optString("nextPageToken").ifBlank{null}
        }while(page!=null)
        return rows
    }

    private suspend fun upload(local:LocalMedia,projectId:String,revision:String,title:String="",updatedAt:Long=0,sourceBytes:Long=0,
        finalKind:String=if(local.role=="manifest")"manifest" else "media",verifyServer:Boolean=false,writerId:String?=null):String{
        val props=JSONObject().put("kind",finalKind)
            .put("projectId",projectId).put("revision",revision).put("sha256",local.sha)
        writerId?.let{props.put("writerId",it)}
        // A Drive app-property key+value is limited to 124 UTF-8 bytes; 25 UTF-16 units fit even for CJK/emoji.
        if(local.role.endsWith("manifest") || local.role=="movie-progress")props.put("title",title.take(25)).put("updatedAt",updatedAt.toString()).put("sourceBytes",sourceBytes.toString())
        val metadata=JSONObject().put("name","gemini-ocr-$projectId-$revision-${local.role}")
            // Discoverability is committed only after the uploaded stream matches the preflight hash.
            .put("parents",JSONArray(listOf("appDataFolder"))).put("appProperties",JSONObject(props.toString()).put("kind","staging"))
        val start=Request.Builder().url(uploadApi.newBuilder().addPathSegment("files")
            .addQueryParameter("uploadType","resumable").addQueryParameter("fields","id").build())
            .header("X-Upload-Content-Type",local.mime).header("X-Upload-Content-Length",local.size.toString())
            .post(metadata.toString().toRequestBody(JSON))
        val location=read(start){response->checkResponse(response);response.header("Location")?:throw IOException("Google Drive không trả phiên upload.")}
        val session=location.toHttpUrl()
        require(session.scheme=="https" && (session.host=="www.googleapis.com" || session.host.endsWith(".googleapis.com")) ||
            api.host in setOf("localhost","127.0.0.1") && session.host==api.host){"Phiên upload Google Drive không hợp lệ."}
        var offset=0L;val uploadedDigest=MessageDigest.getInstance("SHA-256")
        local.open().use{input->
            val buffer=ByteArray(CHUNK)
            while(offset<local.size){
                currentCoroutineContext().ensureActive();gate()
                val wanted=minOf(CHUNK.toLong(),local.size-offset).toInt();var count=0
                while(count<wanted){val n=input.read(buffer,count,wanted-count);if(n<0)throw IOException("Nguồn đã thay đổi trong lúc sao lưu.");count+=n}
                uploadedDigest.update(buffer,0,count)
                val final=offset+count==local.size
                val request=Request.Builder().url(session).header("Content-Range","bytes $offset-${offset+count-1}/${local.size}")
                    .put(buffer.toRequestBody(local.mime.toMediaType(),0,count))
                val id=read(request){response->
                    if(final){checkResponse(response);JSONObject(readLimited(response,MAX_MANIFEST)).getString("id")}
                    else{if(response.code!=308)checkResponse(response)
                        val expected="bytes=0-${offset+count-1}"
                        require(response.header("Range")==expected){"Google Drive chưa nhận đủ đoạn upload; tác vụ sẽ thử lại."};null}
                }
                offset+=count
                if(final){
                    val fileId=id!!.also{validId(it)}
                    val actual=uploadedDigest.digest().joinToString(""){"%02x".format(it)}
                    if(actual!=local.sha){
                        // Still staging, hence no other device can discover/reuse this incomplete blob.
                        withContext(NonCancellable){runCatching{delete(fileId)}}
                        throw IOException("Tệp nguồn đã thay đổi trong lúc sao lưu. Hãy thử lại; chưa tạo bản cloud.")
                    }
                    if(verifyServer)verifyMovieUpload(fileId,local)
                    props.put("kind",finalKind)
                    jsonRequest(Request.Builder().url(api.newBuilder().addPathSegment("files").addPathSegment(fileId)
                        .addQueryParameter("fields","id").build()).patch(JSONObject().put("appProperties",props).toString().toRequestBody(JSON)))
                    // Once media is advertised it may already be referenced by another device.
                    // Never roll it back when this device's later manifest upload fails.
                    return fileId
                }
            }
        }
        throw IOException("Upload chưa hoàn tất.")
    }

    private suspend fun verifyMovieUpload(id:String,local:LocalMedia) {
        validId(id)
        val metadata=jsonRequest(Request.Builder().url(api.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields","size,sha256Checksum").build()).get())
        require(metadata.optString("size").toLongOrNull()==local.size && metadata.optString("sha256Checksum")==local.sha){
            "Google Drive chưa xác minh đúng dung lượng/SHA-256; phim chưa được đánh dấu đã đồng bộ."
        }
    }

    private suspend fun downloadFile(row:JSONObject,output:File){
        val id=row.getString("id").also{validId(it)};val expectedSize=row.getLong("size")
        val expectedHash=row.getString("sha256")
        require(expectedSize in 1..MAX_MEDIA && expectedHash.matches(Regex("[a-f0-9]{64}")))
        require(output.parentFile!!.usableSpace>expectedSize+128L*1024*1024){"Không đủ dung lượng để tải video từ Drive."}
        val part=File(output.parentFile,output.name+".pending")
        try{
            val actual=read(Request.Builder().url(api.newBuilder().addPathSegment("files").addPathSegment(id).addQueryParameter("alt","media").build()).get()){response->
                checkResponse(response);val digest=MessageDigest.getInstance("SHA-256");var count=0L
                checkNotNull(response.body).byteStream().use{input->part.outputStream().use{out->
                    val bytes=ByteArray(128*1024)
                    while(true){currentCoroutineContext().ensureActive();gate();val n=input.read(bytes);if(n<0)break;count+=n;require(count<=expectedSize){"Tệp cloud vượt kích thước đã khai báo."};out.write(bytes,0,n);digest.update(bytes,0,n)}
                }}
                count to digest.digest().joinToString(""){"%02x".format(it)}
            }
            require(actual.first==expectedSize && actual.second==expectedHash){"Kiểm tra SHA-256 thất bại; bản tải chưa được lưu."}
            check(!output.exists() && part.renameTo(output)){"Không lưu được video tải về."}
        }finally{part.delete()}
    }

    private suspend fun downloadBytes(id:String,max:Int):ByteArray=read(Request.Builder().url(api.newBuilder()
        .addPathSegment("files").addPathSegment(id).addQueryParameter("alt","media").build()).get()){
        checkResponse(it);readLimited(it,max).toByteArray(Charsets.UTF_8)
    }
    private suspend fun delete(id:String){validId(id);read(Request.Builder().url(api.newBuilder().addPathSegment("files").addPathSegment(id).build()).delete()){checkResponse(it)}}
    private suspend fun jsonRequest(builder:Request.Builder):JSONObject=read(builder){checkResponse(it);JSONObject(readLimited(it,MAX_MANIFEST))}
    private suspend fun <T> read(builder:Request.Builder,block:suspend (Response)->T):T=withContext(Dispatchers.IO){
        var access=token();require(access.isNotBlank())
        var refreshed=false
        while(true){
            playbackAccountGuard()
            var retry=false
            val result=coroutineScope{
                val call=client.newCall(builder.header("Authorization","Bearer $access").build())
                val cancellation=launch(start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{call.cancel()}}
                try{call.execute().use{response->
                    playbackAccountGuard()
                    if(response.code==401 && !refreshed && refreshPlaybackToken!=null){retry=true;null}
                    else {
                        if(refreshPlaybackToken!=null && !response.isSuccessful)throw DrivePlaybackException(when(response.code){
                            401->DrivePlaybackFailure.AUTH;403->DrivePlaybackFailure.FORBIDDEN;404->DrivePlaybackFailure.MISSING
                            416->DrivePlaybackFailure.RANGE;429->DrivePlaybackFailure.QUOTA;else->DrivePlaybackFailure.NETWORK
                        })
                        block(response)
                    }
                }}finally{cancellation.cancelAndJoin()}
            }
            if(!retry){
                @Suppress("UNCHECKED_CAST")
                return@withContext result as T
            }
            refreshed=true
            access=requireNotNull(refreshPlaybackToken).invoke(access)
            require(access.isNotBlank())
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    private fun checkResponse(response:Response){
        if(response.isSuccessful)return
        if(response.code==401)throw DriveAuthorizationRequired()
        // Provider bodies/tokens are never copied into logs or UI.
        throw IOException(when(response.code){403->"Google Drive từ chối quyền hoặc vượt hạn mức. Kiểm tra Drive API/OAuth và dung lượng."
            404->"Bản sao không còn trên Google Drive. Tải lại danh sách."
            429->"Google Drive đang giới hạn yêu cầu; sẽ thử lại sau."
            else->"Google Drive chưa hoàn tất yêu cầu (HTTP ${response.code})."})
    }
    private fun readLimited(response:Response,max:Int):String{
        val body=checkNotNull(response.body);require(body.contentLength()<=max || body.contentLength()==-1L)
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        body.byteStream().use{input->while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=max);out.write(buffer,0,n)}}
        return out.toString("UTF-8")
    }
    private fun validId(id:String){require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}"))){"ID cloud không hợp lệ."}}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    companion object{
        private fun installationWriterId(context:Context):String=synchronized(DriveVideoRepository::class.java){
            val prefs=context.getSharedPreferences("movie-cloud-installation",Context.MODE_PRIVATE)
            prefs.getString("writerId",null)?:UUID.randomUUID().toString().also{
                check(prefs.edit().putString("writerId",it).commit()){"Không lưu được mã thiết bị đồng bộ."}
            }
        }
        private const val MAX_MANIFEST=32*1024*1024;private const val CHUNK=8*1024*1024
        private const val MAX_MEDIA=20L*1024*1024*1024;private val JSON="application/json; charset=utf-8".toMediaType()}
}

internal class DriveAuthorizationRequired:IOException("Cần kết nối lại tài khoản Google để đồng bộ.")
