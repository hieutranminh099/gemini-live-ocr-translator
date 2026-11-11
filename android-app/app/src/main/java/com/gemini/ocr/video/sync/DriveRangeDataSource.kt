@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video.sync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

internal data class DriveMovieMedia(val id: String, val size: Long, val sha256: String) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        require(size in 1..20L * 1024 * 1024 * 1024 && sha256.matches(Regex("[a-f0-9]{64}")))
    }
    // The player sees an opaque local identifier, never an access token or a public Drive URL.
    val uri: Uri get() = Uri.Builder().scheme("gemini-drive").authority("media").appendPath(id).build()
}

internal enum class DrivePlaybackFailure(val message: String) {
    ACCOUNT("Tài khoản Google đã thay đổi. Kết nối đúng tài khoản của phim để xem tiếp."),
    AUTH("Phiên Google đã hết hạn. Kết nối lại Google Drive để xem tiếp."),
    FORBIDDEN("Google Drive từ chối truy cập. Kiểm tra quyền và hạn mức của tài khoản."),
    MISSING("Video không còn trên Google Drive. Làm mới Kho phim hoặc dùng bản ngoại tuyến."),
    RANGE("Google Drive trả đoạn video không hợp lệ. Hãy thử lại hoặc tải ngoại tuyến."),
    QUOTA("Google Drive đang giới hạn yêu cầu. Chờ một lúc rồi thử xem tiếp."),
    NETWORK("Mất kết nối khi xem Google Drive. Kiểm tra mạng rồi thử xem tiếp."),
    METADATA("Bản phim Google Drive không khớp danh mục đã xác nhận. Làm mới Kho phim để kiểm tra.")
}

/** No provider response, URL, token, or original exception cause can reach the player/UI. */
internal class DrivePlaybackException(val failure: DrivePlaybackFailure) : IOException(failure.message)

/** Shared only by the loaders of one playback handle; credentials remain in memory. */
private class DrivePlaybackAccess(
    private val guard: () -> Unit,
    private val acquire: (String?) -> String
) {
    private var access: String? = null
    @Synchronized fun checkAccount() {
        try { guard() }
        catch (e: DrivePlaybackException) { access = null; throw e }
        catch (_: Exception) { access = null; throw DrivePlaybackException(DrivePlaybackFailure.ACCOUNT) }
    }
    @Synchronized fun token(rejected: String? = null): String {
        checkAccount()
        if (access == null || (rejected != null && access == rejected)) {
            access = try { acquire(rejected).takeIf { it.isNotBlank() } }
            catch (_: Exception) { throw DrivePlaybackException(DrivePlaybackFailure.AUTH) }
            checkAccount()
            if (access == null) throw DrivePlaybackException(DrivePlaybackFailure.AUTH)
        }
        return requireNotNull(access)
    }
}

/**
 * Demand-only private Drive reader. Each request is at most 1 MiB and only one chunk is retained.
 * The complete chunk is validated before delivery, so retries cannot duplicate partial bytes.
 * No disk cache, download file, background prefetch, or source decoder is involved.
 */
internal class DriveRangeDataSource private constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val media: DriveMovieMedia,
    private val access: DrivePlaybackAccess,
    private val chunkBytes: Int,
    private val pause: (Long) -> Unit
) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var opened = false
    @Volatile private var closed = true
    @Volatile private var call: Call? = null
    private var cursor = 0L
    private var end = 0L
    private var chunk = ByteArray(0)
    private var chunkOffset = 0

    internal class Factory(
        client: OkHttpClient,
        api: HttpUrl,
        private val media: DriveMovieMedia,
        token: (String?) -> String,
        accountGuard: () -> Unit,
        private val chunkBytes: Int = MAX_CHUNK,
        private val pause: (Long) -> Unit = { Thread.sleep(it) }
    ) : DataSource.Factory {
        init { require(chunkBytes in 1..MAX_CHUNK) }
        // Disable implicit HTTP retries/redirects: the explicit budget below owns every request.
        private val http = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        private val endpoint = api.newBuilder().addPathSegment("files").addPathSegment(media.id)
            .addQueryParameter("alt", "media").build()
        private val access = DrivePlaybackAccess(accountGuard, token)
        override fun createDataSource(): DataSource = DriveRangeDataSource(http, endpoint, media, access, chunkBytes, pause)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(spec == null)
        if (dataSpec.uri != media.uri || dataSpec.position !in 0..media.size || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET)
            throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
        access.checkAccount()
        spec = dataSpec; cursor = dataSpec.position
        val remaining = media.size - cursor
        end = cursor + if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else minOf(remaining, dataSpec.length)
        closed = false
        transferInitializing(dataSpec)
        try {
            if (cursor < end) loadChunk()
            opened = true; transferStarted(dataSpec)
            return end - cursor
        } catch (e: IOException) { close(); throw e }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        check(opened && !closed)
        try {
            access.checkAccount()
            if (cursor >= end) return C.RESULT_END_OF_INPUT
            if (chunkOffset == chunk.size) loadChunk()
            access.checkAccount()
            val count = minOf(length, chunk.size - chunkOffset)
            chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + count)
            cursor += count; chunkOffset += count
            bytesTransferred(count)
            return count
        } catch (e: IOException) { close(); throw e }
    }

    private fun loadChunk() {
        chunk = ByteArray(0); chunkOffset = 0
        val last = minOf(end - 1, cursor + chunkBytes - 1)
        var retries = 0
        var refreshed = false
        var token = access.token()
        while (true) {
            checkActive()
            var waitMs: Long? = null
            try {
                val request = Request.Builder().url(endpoint).get()
                    .header("Authorization", "Bearer $token").header("Range", "bytes=$cursor-$last")
                    .header("Accept-Encoding", "identity").build()
                val active = client.newCall(request); call = active
                active.execute().use { response ->
                    checkActive()
                    when (response.code) {
                        401 -> {
                            if (refreshed) throw DrivePlaybackException(DrivePlaybackFailure.AUTH)
                            refreshed = true
                            response.close()
                            token = access.token(token)
                        }
                        403 -> throw DrivePlaybackException(DrivePlaybackFailure.FORBIDDEN)
                        404 -> throw DrivePlaybackException(DrivePlaybackFailure.MISSING)
                        416 -> throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
                        429, 500, 502, 503, 504 -> {
                            val failure = if (response.code == 429) DrivePlaybackFailure.QUOTA else DrivePlaybackFailure.NETWORK
                            val retryAfter = retryAfterMs(response.header("Retry-After"))
                            if (retries >= MAX_RETRIES || (retryAfter != null && retryAfter > MAX_WAIT_MS))
                                throw DrivePlaybackException(failure)
                            waitMs = retryAfter ?: (250L shl retries)
                        }
                        206 -> {
                            val expected = (last - cursor + 1).toInt()
                            val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                            val values = range?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                            val body = response.body ?: throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
                            if (values != listOf(cursor, last, media.size) ||
                                body.contentLength().let { it != -1L && it != expected.toLong() } ||
                                response.header("Content-Encoding")?.let { it != "identity" } == true)
                                throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
                            val bytes = ByteArray(expected)
                            body.byteStream().use { input ->
                                var offset = 0
                                while (offset < bytes.size) {
                                    checkActive()
                                    val count = input.read(bytes, offset, bytes.size - offset)
                                    if (count < 0) throw IOException()
                                    offset += count
                                }
                                if (input.read() != -1) throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
                            }
                            checkActive()
                            chunk = bytes
                            return
                        }
                        else -> throw DrivePlaybackException(DrivePlaybackFailure.RANGE)
                    }
                }
            } catch (e: DrivePlaybackException) { throw e }
            catch (_: IOException) {
                checkActive()
                if (retries >= MAX_RETRIES) throw DrivePlaybackException(DrivePlaybackFailure.NETWORK)
                waitMs = 250L shl retries
            } finally { call = null }
            if (waitMs != null) {
                retries++
                try { pause(requireNotNull(waitMs)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("Đã dừng xem video.") }
            }
        }
    }

    private fun checkActive() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Đã dừng xem video.")
        access.checkAccount()
    }

    private fun retryAfterMs(value: String?): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (header.all { it in '0'..'9' }) {
            val seconds = header.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > MAX_WAIT_MS / 1000) Long.MAX_VALUE else seconds * 1000
        }
        return try {
            val deadline = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: DateTimeParseException) { null }
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        closed = true; call?.cancel(); call = null
        chunk = ByteArray(0); chunkOffset = 0; spec = null
        if (opened) { opened = false; transferEnded() }
    }

    companion object {
        internal const val MAX_CHUNK = 1024 * 1024
        private const val MAX_RETRIES = 2
        private const val MAX_WAIT_MS = 5000L
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
