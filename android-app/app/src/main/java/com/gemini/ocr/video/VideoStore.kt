package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.RandomAccess
import java.util.UUID

/** All source handles belong to an explicit document selection; source media is never overwritten. */
class VideoStore(context: Context) {
    private val context=context.applicationContext
    val directory=File(context.filesDir,"video-projects").apply{mkdirs()}
    fun folder(id:String):File {require(id.matches(Regex("[a-f0-9-]{36}")));return File(directory,id).apply{mkdirs()}}
    fun save(project: VideoProject): VideoProject = save(project, null)

    @Synchronized internal fun save(project: VideoProject, checkpoint: VideoCheckpointEncoding?,
                                    dispatchOnly: Boolean = false): VideoProject = synchronized(persistenceLock) {
        val p=project.copy(updatedAt=System.currentTimeMillis())
        val base = File(folder(p.id), "project.json")
        val metadata = checkpoint?.let { p.json(includeCues = false) }
        val protectedHeader = if (checkpoint?.dispatchOverlays == true && metadata != null && p.cues.size >= 512)
            withoutDispatchFields(metadata) else null
        if (dispatchOnly && protectedHeader != null && checkpoint != null) {
            val marker = checkpoint.dispatchMarker(p.cues, protectedHeader, base, writeVersion)
            if (marker != null) {
                DISPATCH_FIELDS.forEach { field -> marker.put(field, requireNotNull(metadata).get(field)) }
                val bytes = marker.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size <= MAX_DISPATCH_BYTES) {
                    writeAtomic(AtomicFile(File(base.parentFile, DISPATCH_FILE))) { it.write(bytes) }
                    return@synchronized p
                }
            }
        }

        require(p.cues.sumOf{it.source.length.toLong()+it.translation.length+it.localDraft.length}<=2_000_000L){"Phụ đề quá lớn cho một dự án; hãy chia video. Checkpoint trước vẫn được giữ."}
        val generation = protectedHeader?.let { UUID.randomUUID().toString() }
        if (generation != null) requireNotNull(metadata).put("checkpointGeneration", generation)
        checkpoint?.invalidateDispatchBaseline()
        val rows = checkpoint?.encode(p, requireNotNull(metadata))
        val payload = if (rows == null) p.json().toString().toByteArray(Charsets.UTF_8) else null
        require((rows?.size ?: requireNotNull(payload).size.toLong()) <= 32 * 1024 * 1024) {
            "Dự án vượt 32 MiB; checkpoint trước vẫn được giữ."
        }
        writeAtomic(AtomicFile(base)) { out ->
            if (rows != null) rows.writeTo(out) else out.write(requireNotNull(payload))
        }
        writeVersion++
        if (protectedHeader != null && generation != null) {
            checkpoint?.recordDispatchBaseline(base, writeVersion, generation, protectedHeader)
        }
        p
    }
    @Synchronized fun load(id:String):VideoProject = synchronized(persistenceLock) {
        val file=File(folder(id),"project.json")
        require(file.length()<=32L*1024*1024){"Dự án vượt giới hạn 32 MiB."}
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
        readDispatchFields(File(file.parentFile, DISPATCH_FILE), json.optString("checkpointGeneration")).forEach { (key, value) ->
            json.put(key, value)
        }
        VideoProject.from(json)
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
    internal fun writeSubtitle(id: String, name: String, text: String) {
        require(name in setOf("source.zh.srt", "translated.srt", "translated.ass"))
        val destination = File(folder(id), name)
        val temporary = File.createTempFile("subtitle-", ".partial", destination.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            publishVideoArtifact(temporary, destination)
        } finally {
            temporary.delete()
        }
    }
    companion object {
        // AtomicFile is not thread safe: UI, video service and cloud workers use separate stores.
        // A process-wide lock prevents openRead() recovering a backup while another instance writes.
        private val persistenceLock=Any()
        private var writeVersion = 0L
    }
}

private const val DISPATCH_FILE = "project.dispatch.json"
private const val MAX_DISPATCH_BYTES = 64 * 1024
private val DISPATCH_FIELDS = setOf("message", "cloudCalls", "updatedAt", "elapsedMs", "maxPssKiB",
    "extractionElapsedMs", "translationElapsedMs", "exportElapsedMs")

private fun withoutDispatchFields(metadata: JSONObject): String = JSONObject().apply {
    for (key in metadata.keys()) if (key !in DISPATCH_FIELDS) put(key, metadata.get(key))
}.toString()

private inline fun writeAtomic(atomic: AtomicFile, write: (FileOutputStream) -> Unit) {
    val output = atomic.startWrite()
    try {
        write(output)
        atomic.finishWrite(output)
    } catch (error: Throwable) {
        atomic.failWrite(output)
        throw error
    }
}

private fun readDispatchFields(file: File, generation: String): List<Pair<String, Any>> {
    if (generation.isBlank() || (!file.isFile && !File(file.path + ".bak").isFile)) return emptyList()
    return runCatching {
        val bytes = AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_DISPATCH_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val marker = JSONObject(bytes.toString(Charsets.UTF_8))
        if (marker.optString("generation") != generation) return emptyList()
        DISPATCH_FIELDS.map { key ->
            val value = marker.get(key)
            require(if (key == "message") value is String else (value is Int || value is Long))
            key to value
        }
    }.getOrDefault(emptyList())
}

/** Encoded cue blocks and last full checkpoint, owned by one worker. */
internal class VideoCheckpointEncoding(val dispatchOverlays: Boolean = false) {
    private var previous: List<Block?> = emptyList()
    private data class Baseline(val file: File, val size: Long, val mtime: Long, val version: Long,
                                val generation: String, val protectedHeader: String)
    private var dispatchBaseline: Baseline? = null

    fun invalidateDispatchBaseline() {
        dispatchBaseline = null
    }

    fun recordDispatchBaseline(file: File, version: Long, generation: String, protectedHeader: String) {
        dispatchBaseline = Baseline(file, file.length(), file.lastModified(), version, generation, protectedHeader)
    }

    fun dispatchMarker(cues: List<VideoCue>, protectedHeader: String, file: File, version: Long): JSONObject? {
        val saved = dispatchBaseline ?: return null
        if (saved.file != file || saved.version != version || saved.size <= 0L ||
            saved.size != file.length() || saved.mtime != file.lastModified() ||
            saved.protectedHeader != protectedHeader || cues !is RandomAccess) return null
        if (previous.size != (cues.size + ROWS_PER_BLOCK - 1) / ROWS_PER_BLOCK) return null
        for (start in cues.indices step ROWS_PER_BLOCK) {
            val savedBlock = previous[start / ROWS_PER_BLOCK] ?: return null
            if (!savedBlock.matches(cues.subList(start, minOf(start + ROWS_PER_BLOCK, cues.size)))) return null
        }
        return JSONObject().put("generation", saved.generation)
    }

    fun encode(project: VideoProject, metadata: JSONObject): Encoded {
        val prefix = (metadata.toString().dropLast(1) + ",\"cues\":[").toByteArray(Charsets.UTF_8)
        val cues = if (project.cues is RandomAccess) project.cues else project.cues.toList()
        val blockCount = (cues.size + ROWS_PER_BLOCK - 1) / ROWS_PER_BLOCK
        val cache = ArrayList<Block?>(blockCount)
        val payload = ArrayList<ByteArray>(blockCount)
        var retainedBytes = 0L
        var payloadBytes = 0L
        for (start in cues.indices step ROWS_PER_BLOCK) {
            val rows = cues.subList(start, minOf(start + ROWS_PER_BLOCK, cues.size))
            val old = previous.getOrNull(start / ROWS_PER_BLOCK)
            val block = old?.takeIf { it.matches(rows) } ?: encodeBlock(rows, old, leadingComma = start > 0)
            payload += block.bytes
            payloadBytes += block.bytes.size
            if (retainedBytes + block.bytes.size <= MAX_CACHED_BYTES) {
                cache += block
                retainedBytes += block.bytes.size
            } else {
                cache += null
            }
        }
        previous = cache
        return Encoded(prefix, payload, payloadBytes)
    }

    private fun encodeBlock(cues: List<VideoCue>, old: Block?, leadingComma: Boolean): Block {
        val buffer = ByteArrayOutputStream(old?.bytes?.size ?: (cues.size * 256))
        val rows = ArrayList<Row>(cues.size)
        val starts = IntArray(cues.size)
        cues.forEachIndexed { index, cue ->
            if (index > 0 || leadingComma) buffer.write(','.code)
            starts[index] = buffer.size()
            val cached = old?.rows?.getOrNull(index)
            if (old != null && cached != null && cached.matches(cue)) {
                rows += cached
                old.writeRow(index, buffer)
            } else {
                rows += Row(cue)
                buffer.write(cue.json(includeDefaults = false).toString().toByteArray(Charsets.UTF_8))
            }
        }
        return Block(rows, starts, buffer.toByteArray())
    }

    private class Row(private val cue: VideoCue) {
        // The cue holds read-only interfaces, which callers may back with mutable lists.
        private val flags = cue.qualityFlags.toList()
        private val alternatives = cue.ocrAlternatives.toList()

        fun matches(other: VideoCue): Boolean =
            (cue === other || cue == other) && flags == other.qualityFlags && alternatives == other.ocrAlternatives
    }

    private class Block(val rows: List<Row>, private val starts: IntArray, val bytes: ByteArray) {
        fun matches(cues: List<VideoCue>): Boolean {
            if (rows.size != cues.size) return false
            for (index in cues.indices) if (!rows[index].matches(cues[index])) return false
            return true
        }

        fun writeRow(index: Int, output: OutputStream) {
            // The next row starts one byte after the separator. The last row reaches the end.
            val end = starts.getOrNull(index + 1)?.minus(1) ?: bytes.size
            output.write(bytes, starts[index], end - starts[index])
        }
    }

    internal class Encoded(private val prefix: ByteArray, private val blocks: List<ByteArray>, cueBytes: Long) {
        val size = prefix.size + cueBytes + SUFFIX.size

        fun writeTo(output: OutputStream) {
            output.write(prefix)
            blocks.forEach(output::write)
            output.write(SUFFIX)
        }
    }

    private companion object {
        const val ROWS_PER_BLOCK = 256
        const val MAX_CACHED_BYTES = 4L * 1024 * 1024
        val SUFFIX = "]}".toByteArray(Charsets.UTF_8)
    }
}

/** Only completed files are published. A failed move leaves the previous complete version readable. */
internal fun publishVideoArtifact(temporary: File, destination: File) {
    require(temporary.parentFile == destination.parentFile)
    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

data class VideoMetadata(val durationMs:Long,val width:Int,val height:Int,val rotation:Int=0,val hasAudio:Boolean=false)
data class VideoTextTrack(val id:Int,val language:String,val mime:String)
