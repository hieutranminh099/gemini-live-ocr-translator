package com.gemini.ocr.video

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

data class VideoDownload(val uri: Uri, val name: String, val relativePath: String)

/** Publish a new result in Download/Gemini OCR without storage permission or overwriting input.
 * Android 8/9 callers use the document picker instead. Incomplete MediaStore rows remain hidden and
 * are deleted if writing, cancellation or publication fails. No translation/edit is performed here. */
object VideoDownloads {
    val supportsDirectSave get() = Build.VERSION.SDK_INT >= 29
    const val DIRECTORY = "Download/Gemini OCR"

    suspend fun saveSubtitle(context: Context, project: VideoProject, ass: Boolean = false): VideoDownload =
        withContext(Dispatchers.IO) {
            if(Build.VERSION.SDK_INT<29)throw IllegalArgumentException("Phiên bản Android này cần chọn thư mục lưu bằng trình chọn tệp.")
            VideoTimeline.validate(project.cues, project.durationMs)
            require(!project.sourceExtractionPending){"Chưa nhận hết phụ đề nguồn. Bấm Tiếp tục xử lý trước khi tải."}
            require(project.cues.isNotEmpty() && project.cues.all { it.translation.isNotBlank() }) {
                "Còn câu chưa dịch. Dịch hoặc sửa những câu trống trước khi tải phụ đề."
            }
            val data = (if (ass) VideoTimeline.ass(project) else VideoTimeline.srt(project.cues,project.durationMs)).toByteArray(Charsets.UTF_8)
            publish(context.contentResolver, resultName(project.title, if (ass) "ass" else "srt"),
                if (ass) "text/x-ssa" else "application/x-subrip") { stream ->
                currentCoroutineContext().ensureActive()
                stream.write(data)
            }
        }

    suspend fun saveRenderedMp4(context: Context, project: VideoProject): VideoDownload =
        withContext(Dispatchers.IO) {
            if(Build.VERSION.SDK_INT<29)throw IllegalArgumentException("Phiên bản Android này cần chọn thư mục lưu bằng trình chọn tệp.")
            require(project.exportedMp4.isNotBlank()) { "Tạo MP4 trước khi lưu vào Download." }
            val source = File(project.exportedMp4)
            require(source.isFile && source.length() > 0) { "MP4 đã tạo không còn trong máy. Hãy tạo lại MP4." }
            // The rendered file belongs to this private project, not a caller-supplied source URI.
            val ownFolder = VideoStore(context).folder(project.id).canonicalFile
            require(source.canonicalFile.toPath().startsWith(ownFolder.toPath())) { "MP4 không thuộc dự án này." }
            publish(context.contentResolver, resultName(project.title, "mp4"), "video/mp4") { stream ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        stream.write(buffer, 0, count)
                    }
                }
            }
        }

    internal fun resultName(title: String, extension: String): String {
        require(extension in setOf("srt", "ass", "mp4"))
        val clean = title.substringBeforeLast('.', title).replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            .trim().trim('.').take(72).ifBlank { "video" }
        return "$clean-vi-${UUID.randomUUID().toString().take(8)}.$extension"
    }

    @RequiresApi(29)
    internal suspend fun publish(resolver: ContentResolver, name: String, mime: String,
        write: suspend (OutputStream) -> Unit): VideoDownload {
        currentCoroutineContext().ensureActive()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Gemini OCR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Không thể tạo tệp trong Download. Hãy chọn thư mục khác."
        }
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(destination, "w")) { "Không mở được tệp Download để ghi." }.use { stream ->
                write(stream)
                currentCoroutineContext().ensureActive()
                stream.flush()
            }
            currentCoroutineContext().ensureActive()
            val updated = resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            check(updated == 1) { "Không thể hoàn tất tệp Download. Hãy chọn thư mục khác." }
            published = true
            return VideoDownload(destination, name, DIRECTORY)
        } finally {
            if (!published) runCatching { resolver.delete(destination, null, null) }
        }
    }
}
