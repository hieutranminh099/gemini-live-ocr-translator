package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** All source handles belong to an explicit document selection; source media is never overwritten. */
class VideoStore(context: Context) {
    private val context=context.applicationContext
    val directory=File(context.filesDir,"video-projects").apply{mkdirs()}
    fun folder(id:String):File {require(id.matches(Regex("[a-f0-9-]{36}")));return File(directory,id).apply{mkdirs()}}
    @Synchronized fun save(project:VideoProject):VideoProject = synchronized(persistenceLock) {
        val p=project.copy(updatedAt=System.currentTimeMillis())
        require(p.cues.sumOf{it.source.length.toLong()+it.translation.length+it.localDraft.length}<=2_000_000L){"Phụ đề quá lớn cho một dự án; hãy chia video. Checkpoint trước vẫn được giữ."}
        val payload=p.json().toString().toByteArray(Charsets.UTF_8)
        require(payload.size<=32*1024*1024){"Dự án vượt 32 MiB; checkpoint trước vẫn được giữ."}
        val atomic=AtomicFile(File(folder(p.id),"project.json"))
        val out=atomic.startWrite()
        try { out.write(payload);atomic.finishWrite(out) }
        catch(e:Throwable){atomic.failWrite(out);throw e}
        p
    }
    @Synchronized fun load(id:String):VideoProject = synchronized(persistenceLock) {
        val file=File(folder(id),"project.json")
        require(file.length()<=32L*1024*1024){"Dự án vượt giới hạn 32 MiB."}
        VideoProject.from(JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()}))
    }
    @Synchronized fun list():List<VideoProject> = directory.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}"))}
        .mapNotNull{runCatching{load(it.name)}.getOrNull()}.sortedByDescending{it.updatedAt}
    /** Retain transient provider streams before probing: media readers need a seekable source. */
    fun importVideo(uri:Uri,title:String):VideoProject {
        val id=UUID.randomUUID().toString();val target=folder(id)
        try {
            var usable=retain(uri,target,"source")
            val metadata=try { VideoDecoder.metadata(context,usable) }
            catch(e:Exception) {
                if(usable.scheme!="content")throw e
                // Even a durable document grant may expose a pipe rather than a seekable fd.
                usable=retain(uri,target,"source",forceCopy=true)
                VideoDecoder.metadata(context,usable)
            }
            return createRetained(id,uri,usable,title,metadata)
        } catch(e:Throwable) {
            // Only this new UUID's own partial copies are eligible for cleanup. Never delete
            // the selected source or an existing project/checkpoint after an import failure.
            target.listFiles().orEmpty().filter{it.isFile && it.name.startsWith("source-") && it.extension in setOf("input","pending")}.forEach{it.delete()}
            target.delete() // succeeds only when empty
            when(e) {
                is SecurityException->throw IllegalArgumentException("Không có quyền đọc video. Chọn lại tệp bằng trình chọn của Android.",e)
                is java.io.FileNotFoundException->throw IllegalArgumentException("Không tìm thấy video hoặc nhà cung cấp chưa tải xong tệp. Tải bản cục bộ rồi chọn lại.",e)
                else->throw e
            }
        }
    }
    fun create(uri:Uri,title:String,metadata:VideoMetadata):VideoProject {
        require(metadata.durationMs>0 && metadata.durationMs<=24*60*60*1000L){"Video cần có thời lượng hợp lệ, tối đa 24 giờ."}
        val id=UUID.randomUUID().toString()
        val usable=retain(uri,folder(id),"source")
        return createRetained(id,uri,usable,title,metadata)
    }
    private fun createRetained(id:String,uri:Uri,usable:Uri,title:String,metadata:VideoMetadata):VideoProject {
        require(metadata.durationMs>0 && metadata.durationMs<=24*60*60*1000L){"Video cần có thời lượng hợp lệ, tối đa 24 giờ."}
        return save(VideoProject(id,title.take(200),usable.toString(),metadata.durationMs,metadata.width,metadata.height,originalSourceUri=uri.toString()))
    }
    fun retainSubtitle(id:String,uri:Uri)=retain(uri,folder(id),"subtitle")
    private fun retain(uri:Uri,folder:File,name:String,forceCopy:Boolean=false):Uri {
        if(uri.scheme=="file")return uri // only internal verification fixtures use file URIs
        require(uri.scheme=="content"){"Chọn video bằng trình chọn tệp của Android."}
        if(!forceCopy && context.contentResolver.persistedUriPermissions.any{it.uri==uri && it.isReadPermission})return uri
        // A provider may not grant durable access. Make a bounded streamed private copy instead.
        val revision=UUID.randomUUID().toString()
        val output=File(folder,"$name-$revision.input")
        val pending=File(folder,"$name-$revision.pending")
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                pending.outputStream().use { out ->
                    val buffer=ByteArray(128*1024);var total=0L
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n
                        require(total<=20L*1024*1024*1024 && folder.usableSpace>256L*1024*1024){"Không đủ chỗ để giữ quyền truy cập video. Chọn tệp cục bộ hoặc giải phóng dung lượng."}
                        out.write(buffer,0,n)
                    }
                }
            }
            check(pending.renameTo(output)){"Không thể lưu bản sao nguồn."};return Uri.fromFile(output)
        } catch(e:Throwable){pending.delete();throw e}
    }
    fun exportFile(id:String,extension:String):File {
        require(extension in setOf("srt","ass","mp4","partial.mp4"))
        return File(folder(id),"translated.$extension")
    }
    companion object {
        // AtomicFile is not thread safe: UI, video service and cloud workers use separate stores.
        // A process-wide lock prevents openRead() recovering a backup while another instance writes.
        private val persistenceLock=Any()
    }
}

data class VideoMetadata(val durationMs:Long,val width:Int,val height:Int,val rotation:Int=0,val hasAudio:Boolean=false)
data class VideoTextTrack(val id:Int,val language:String,val mime:String)
