package com.gemini.ocr.asr

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class ChineseAsrModelStatus(
    val ready: Boolean,
    val downloading: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String,
    val supported: Boolean = true,
)

/** Explicit model installation only; subtitle extraction never initiates a download. */
class ChineseAsrModels(context: Context) {
    val directory = File(context.applicationContext.noBackupFilesDir, "asr/sensevoice-small-int8-2024-07-17")
    val modelFile get() = File(directory, "model.int8.onnx")
    val tokensFile get() = File(directory, "tokens.txt")
    val vadFile get() = File(directory, "silero_vad.onnx")
    val fingerprint: String get() = FINGERPRINT
    val supported: Boolean get() = Build.SUPPORTED_ABIS.firstOrNull() != "x86"
    private val shared = states.getOrPut(directory.absolutePath) { InstallState() }

    /** Fast UI status. Each native session rechecks all hashes before opening the models. */
    fun status(): ChineseAsrModelStatus {
        if (!supported) return ChineseAsrModelStatus(false, false, 0, TOTAL_BYTES,
            "Gói giọng nói không có cho Android x86 32 bit. Dùng OCR hoặc phụ đề có sẵn trên thiết bị này.", supported = false)
        val ready = hasVerifiedPackage()
        return ChineseAsrModelStatus(ready, shared.downloading, shared.downloaded, TOTAL_BYTES,
            when {
                shared.downloading -> "Đang tải gói nhận diện tiếng Trung"
                ready -> "Gói nhận diện tiếng Trung đã sẵn sàng offline"
                shared.lastMessage.isNotBlank() -> shared.lastMessage
                else -> "Chưa có gói nhận diện tiếng Trung"
            })
    }

    suspend fun install(progress: (Long, Long) -> Unit = { _, _ -> }) = installPackage(progress) { spec, target, advanced ->
        val call = downloadClient.newCall(Request.Builder().url(spec.url).build())
        shared.call.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("Không tải được gói nhận diện")
                val body = response.body ?: throw IOException("Gói nhận diện trả về rỗng")
                if (body.contentLength() >= 0 && body.contentLength() != spec.bytes) throw IOException("Dung lượng gói nhận diện không khớp")
                body.byteStream().use { input -> copyVerified(spec, target, input::read, advanced) }
            }
        } finally { shared.call.compareAndSet(call, null) }
    }

    /** Allows an already downloaded, pinned package to be installed without network access. */
    suspend fun importVerifiedFiles(sourceDirectory: File, progress: (Long, Long) -> Unit = { _, _ -> }) =
        installPackage(progress) { spec, target, advanced ->
            val source = File(sourceDirectory, spec.name)
            if (!source.isFile || source.length() != spec.bytes) throw IOException("Tệp gói nhận diện thiếu hoặc không đúng dung lượng")
            source.inputStream().use { input -> copyVerified(spec, target, input::read, advanced) }
        }

    fun cancelInstall() {
        if (shared.downloading) {
            shared.cancel.set(true)
            shared.call.get()?.cancel()
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        shared.mutex.withLock {
            check(shared.sessions.get() == 0) { "Gói nhận diện đang được sử dụng. Hãy dừng xử lý trước khi xóa." }
            removeOwnedDirectory(directory)
            shared.downloaded = 0
            shared.lastMessage = "Đã xóa gói nhận diện tiếng Trung"
        }
    }

    internal suspend fun acquireSession() {
        check(supported) { "ASR_ABI_UNSUPPORTED: Thiết bị x86 32 bit chưa có bộ nhận diện giọng nói." }
        shared.mutex.withLock {
            if (!hasVerifiedPackage()) throw IOException("Cần tải gói nhận diện tiếng Trung trước khi xử lý âm thanh")
            for (spec in FILES) {
                coroutineContext.ensureActive()
                if (!verifyFile(File(directory, spec.name), spec)) throw IOException("Gói nhận diện bị thay đổi hoặc hỏng. Hãy tải lại gói.")
            }
            shared.sessions.incrementAndGet()
        }
    }

    internal fun releaseSession() { check(shared.sessions.decrementAndGet() >= 0) }

    private fun hasVerifiedPackage(): Boolean = runCatching {
        File(directory, VERIFIED).readText() == FINGERPRINT && FILES.all { File(directory, it.name).let { f -> f.isFile && f.length() == it.bytes } }
    }.getOrDefault(false)

    private suspend fun installPackage(
        progress: (Long, Long) -> Unit,
        obtain: suspend (AsrModelFile, File, (Int) -> Unit) -> Unit,
    ) = withContext(Dispatchers.IO) {
        check(supported) { "Gói nhận diện giọng nói chưa hỗ trợ Android x86 32 bit." }
        shared.mutex.withLock {
            if (hasVerifiedPackage() && FILES.all { verifyFile(File(directory, it.name), it) }) return@withLock
            check(shared.sessions.get() == 0) { "Gói nhận diện đang được sử dụng" }
            val parent = directory.parentFile ?: throw IOException("Không mở được thư mục gói nhận diện")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Không tạo được thư mục gói nhận diện")
            if (parent.usableSpace < TOTAL_BYTES + 16L * 1024 * 1024) throw IOException("Cần ít nhất 257 MB dung lượng trống để tải gói")
            parent.listFiles().orEmpty().filter { it.name.matches(Regex("sensevoice-small-int8-2024-07-17-incoming-[0-9a-f-]+")) }
                .forEach { removeOwnedDirectory(it) }
            val incoming = File(parent, directory.name + "-incoming-" + UUID.randomUUID())
            if (!incoming.mkdir()) throw IOException("Không tạo được thư mục tải tạm")
            shared.downloading = true
            shared.cancel.set(false)
            shared.downloaded = 0
            shared.lastMessage = ""
            try {
                for (spec in FILES) {
                    checkCancelled()
                    obtain(spec, File(incoming, spec.name)) { bytes ->
                        shared.downloaded += bytes
                        progress(shared.downloaded, TOTAL_BYTES)
                    }
                }
                checkCancelled()
                File(incoming, "LICENSE.txt").writeText(ChineseAsrLicense.TEXT)
                File(incoming, VERIFIED).writeText(FINGERPRINT)
                // A verified older installation remains usable until the complete replacement exists.
                if (directory.exists()) removeOwnedDirectory(directory)
                if (!incoming.renameTo(directory)) throw IOException("Không lưu được gói nhận diện đã tải")
                shared.lastMessage = "Gói nhận diện tiếng Trung đã sẵn sàng offline"
            } catch (error: Exception) {
                shared.lastMessage = if (shared.cancel.get() || error is CancellationException) "Đã hủy tải gói nhận diện" else "Tải gói chưa hoàn tất. Có thể thử lại."
                throw error
            } finally {
                shared.call.set(null)
                shared.downloading = false
                if (incoming.exists()) removeOwnedDirectory(incoming)
            }
        }
    }

    private suspend fun copyVerified(spec: AsrModelFile, target: File, read: (ByteArray) -> Int, advanced: (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        target.outputStream().use { output ->
            while (true) {
                checkCancelled()
                val readBytes = read(buffer)
                if (readBytes < 0) break
                if (readBytes == 0) continue
                count += readBytes
                if (count > spec.bytes) throw IOException("Dung lượng gói nhận diện vượt giới hạn")
                digest.update(buffer, 0, readBytes)
                output.write(buffer, 0, readBytes)
                advanced(readBytes)
            }
            output.fd.sync()
        }
        if (count != spec.bytes || hex(digest.digest()) != spec.sha256) throw IOException("Mã kiểm tra gói nhận diện không khớp")
    }

    private suspend fun checkCancelled() {
        coroutineContext.ensureActive()
        if (shared.cancel.get()) throw CancellationException("Đã hủy tải gói nhận diện")
    }

    private fun removeOwnedDirectory(folder: File) {
        val parent = directory.parentFile!!.canonicalFile
        require(folder.canonicalFile.parentFile == parent && folder.name.startsWith(directory.name))
        val allowed = FILES.map { it.name } + listOf(VERIFIED, "LICENSE.txt")
        folder.listFiles().orEmpty().forEach {
            check(it.name in allowed && it.isFile && it.canonicalFile.parentFile == folder.canonicalFile) { "Thư mục gói nhận diện có tệp không thuộc bộ cài" }
        }
        folder.listFiles().orEmpty().forEach { if (!it.delete()) throw IOException("Không xóa được tệp gói nhận diện") }
        if (folder.exists() && !folder.delete()) throw IOException("Không xóa được thư mục gói nhận diện")
    }

    companion object {
        const val MODEL_LABEL = "SenseVoiceSmall tiếng Trung (offline)"
        const val ATTRIBUTION = "SenseVoiceSmall · FunAudioLLM/FunASR; sherpa-onnx 2.0.1 · k2-fsa; Silero VAD · Silero Team."
        const val TOTAL_BYTES = 240193589L
        private const val VERIFIED = ".verified"
        private const val REPOSITORY = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
        const val FINGERPRINT = "sensevoice-small-2024-07-17-c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51:tokens-f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc:silero-9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6:sherpa2.0.1:zh-itn:vad-v1"
        internal val FILES = listOf(
            AsrModelFile("model.int8.onnx", 239233841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", "$REPOSITORY/model.int8.onnx"),
            AsrModelFile("tokens.txt", 315894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", "$REPOSITORY/tokens.txt"),
            AsrModelFile("silero_vad.onnx", 643854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
        )
        private val states = ConcurrentHashMap<String, InstallState>()
        private val downloadClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.MINUTES).followSslRedirects(false).build()
        }
        internal fun verifyFile(file: File, spec: AsrModelFile): Boolean {
            if (!file.isFile || file.length() != spec.bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return hex(digest.digest()) == spec.sha256
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

internal data class AsrModelFile(val name: String, val bytes: Long, val sha256: String, val url: String)
private class InstallState {
    val mutex = Mutex()
    val cancel = AtomicBoolean()
    val call = AtomicReference<Call?>()
    val sessions = AtomicInteger()
    @Volatile var downloading = false
    @Volatile var downloaded = 0L
    @Volatile var lastMessage = ""
}
