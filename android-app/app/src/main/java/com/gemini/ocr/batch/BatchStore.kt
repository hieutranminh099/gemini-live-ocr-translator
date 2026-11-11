package com.gemini.ocr.batch

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BatchStore(context: Context) {
    val directory = File(context.applicationContext.filesDir, "movie-batches").apply { mkdirs() }
    fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Mã dự án hàng loạt không hợp lệ." }
        return File(directory, id).apply { mkdirs() }
    }
    fun create(title: String, kind: BatchKind) = save(BatchProject(UUID.randomUUID().toString(), title.trim().take(200).ifBlank { "Phim mới" }, kind))
    fun save(batch: BatchProject): BatchProject = synchronized(lock) {
        val next = batch.copy(updatedAt = System.currentTimeMillis())
        val bytes = next.json().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024 * 1024) { "Danh mục dự án quá lớn; tách thành dự án khác. Checkpoint trước được giữ." }
        val atomic = AtomicFile(File(folder(next.id), "batch.json")); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Throwable) { atomic.failWrite(out); throw e }
        next
    }
    fun load(id: String): BatchProject = synchronized(lock) {
        val atomic = AtomicFile(File(folder(id), "batch.json"))
        require(atomic.baseFile.length() <= 32L * 1024 * 1024)
        BatchProject.from(JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })).also { require(it.id == id) }
    }
    fun list(): List<BatchProject> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }.sortedByDescending { it.updatedAt }
    }
    companion object { private val lock = Any() }
}
