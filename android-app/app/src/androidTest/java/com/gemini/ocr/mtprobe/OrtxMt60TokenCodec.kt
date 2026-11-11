package com.gemini.ocr.mtprobe

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.io.File
import java.io.FileInputStream
import java.nio.LongBuffer
import java.security.MessageDigest

/** Debug research codec. Its sole owner must close it after the last in-flight MT operation. */
internal class OrtxMt60TokenCodec private constructor(
    private val environment: OrtEnvironment,
    private val source: OrtSession,
    private val prefix: OrtSession,
    private val decoder: OrtSession,
) : Mt60TokenCodec {
    private val gate = Any()
    private var closed = false // Every access, including native session use, holds gate.

    override fun encodeSource(text: String): LongArray = synchronized(gate) {
        checkOpen(); checkText(text)
        OnnxTensor.createTensor(environment, arrayOf(text)).use { input ->
            source.run(mapOf("text" to input)).use { result ->
                val idsTensor = tensor(result, "input_ids")
                val maskTensor = tensor(result, "attention_mask")
                val idsShape = idsTensor.info.shape
                check(idsShape.size == 2 && idsShape[0] == 1L && idsShape[1] in 1L..MAX_TOKEN_IDS.toLong()) {
                    "Source tokenizer returned an invalid [1,N] shape"
                }
                check(maskTensor.info.shape.contentEquals(idsShape)) { "Source attention mask shape differs" }
                val ids = copyLongs(idsTensor, idsShape[1].toInt())
                val mask = copyLongs(maskTensor, ids.size)
                check(mask.all { it == 1L }) { "Source attention mask is not all ones" }
                check(ids.all { it in 0L until VOCABULARY }) { "Source token is outside vocabulary" }
                check(ids.last() == EOS && ids.dropLast(1).none { it == EOS || it == 0L || it == 1L }) {
                    "Source tokenizer must append exactly one final EOS without BOS/PAD"
                }
                ids // A detached copy survives Result.close().
            }
        }
    }

    override fun encodeTargetPrefix(text: String): LongArray = synchronized(gate) {
        checkOpen(); checkText(text)
        OnnxTensor.createTensor(environment, arrayOf(text)).use { input ->
            prefix.run(mapOf("text" to input)).use { result ->
                val output = tensor(result, "token_ids")
                val shape = output.info.shape
                check(shape.size == 1 && shape[0] in 0L..MAX_TOKEN_IDS.toLong()) { "Target prefix must be rank one" }
                copyLongs(output, shape[0].toInt()).also { ids ->
                    check(ids.all { it in 0L until VOCABULARY && it != 0L && it != 1L && it != EOS }) {
                        "Target tokenizer added an unsupported control token"
                    }
                    // Natural OOV input can yield UNK=3. Preserve the real tokenizer output;
                    // Mt60DecodePolicy.InputPlan rejects UNK for an accepted context prefix.
                }
            }
        }
    }

    override fun decodeTarget(ids: LongArray): String = synchronized(gate) {
        checkOpen()
        require(ids.size <= MAX_DECODE_IDS) { "Target token count exceeds the debug codec bound" }
        val ownedIds = ids.copyOf()
        require(ownedIds.all { it in 0L until VOCABULARY }) { "Target token is outside vocabulary" }
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ownedIds), longArrayOf(ownedIds.size.toLong())).use { input ->
            decoder.run(mapOf("token_ids" to input)).use { result ->
                val output = tensor(result, "text")
                check(output.info.type == OnnxJavaType.STRING && output.info.shape.contentEquals(longArrayOf(1))) {
                    "Target decoder returned an invalid string tensor"
                }
                val values = output.value as? Array<*> ?: error("Target decoder did not return a string array")
                check(values.size == 1)
                (values.single() as? String ?: error("Target decoder returned a non-string value")).also {
                    check(it.length <= MAX_DECODED_CHARS) { "Decoded text exceeds the debug codec bound" }
                }
            }
        }
    }

    override fun close() = synchronized(gate) {
        if (!closed) {
            closed = true // Failed close still leaves the owner unusable.
            closeSessions(listOf(decoder, prefix, source))
        }
    }

    private fun checkOpen() = check(!closed) { "MT60 token codec is closed" }

    private fun checkText(text: String) {
        require(text.length <= MAX_TEXT_CHARS) { "Tokenizer input exceeds the debug text bound" }
        // v0.13 native SentencePiece uses a C string. Do not permit embedded NUL truncation.
        require('\u0000' !in text) { "Tokenizer input contains an unsupported NUL character" }
    }

    companion object {
        private const val VOCABULARY = 24000L
        private const val EOS = 2L
        private const val MAX_TEXT_CHARS = 8192
        private const val MAX_TOKEN_IDS = MAX_TEXT_CHARS * 4 + 1
        private const val MAX_DECODE_IDS = 512
        private const val MAX_DECODED_CHARS = 262144

        internal data class GraphPin(val name: String, val bytes: Int, val sha256: String)
        internal val GRAPH_PINS = listOf(
            GraphPin("source-tokenizer.onnx", 799345, "a2b75d7cd40a17b8bdc18724e893ce8b36add7e94e341b563e1e2c3d77b4f91e"),
            GraphPin("target-prefix-tokenizer.onnx", 798659, "6bb70b9179d6050872f9d1a1f4791df62e95fa11fb063429edb2873969f2b9c7"),
            GraphPin("target-detokenizer.onnx", 799321, "66108577ca32eed252e3c0ccfea33bd792b1c80ed4b95f67e2341406f7042c94"),
        )

        /** Blocking native factory; the caller runs it on its worker and owns cancellation cleanup. */
        fun create(graphDirectory: File): OrtxMt60TokenCodec {
            val directory = graphDirectory.canonicalFile
            require(directory.isDirectory) { "Tokenizer graph directory is missing" }
            // Validate all three bounded files before opening any native session/library.
            val bytes = GRAPH_PINS.map { readVerifiedGraph(directory, it) }
            val sessions = mutableListOf<OrtSession>()
            try {
                val environment = OrtEnvironment.getEnvironment() // Shared global environment, never close here.
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                    bytes.forEach { sessions += environment.createSession(it, options) }
                }
                check(sessions[0].inputNames == setOf("text") &&
                    sessions[0].outputNames == setOf("input_ids", "attention_mask"))
                check(sessions[1].inputNames == setOf("text") && sessions[1].outputNames == setOf("token_ids"))
                check(sessions[2].inputNames == setOf("token_ids") && sessions[2].outputNames == setOf("text"))
                return OrtxMt60TokenCodec(environment, sessions[0], sessions[1], sessions[2])
            } catch (failure: Throwable) {
                try { closeSessions(sessions.asReversed()) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun readVerifiedGraph(directory: File, pin: GraphPin): ByteArray {
            val file = File(directory, pin.name)
            require(file.canonicalFile.parentFile == directory && file.isFile && file.length() == pin.bytes.toLong()) {
                "Tokenizer graph has an invalid path or exact size: ${pin.name}"
            }
            val bytes = ByteArray(pin.bytes)
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0) { "Tokenizer graph was truncated: ${pin.name}" }
                    offset += count
                }
                require(input.read() == -1) { "Tokenizer graph grew while being read: ${pin.name}" }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == pin.sha256) { "Tokenizer graph SHA-256 differs: ${pin.name}" }
            return bytes
        }

        private fun tensor(result: OrtSession.Result, name: String): OnnxTensor =
            result.get(name).orElseThrow { IllegalStateException("Tokenizer graph output is missing: $name") } as? OnnxTensor
                ?: error("Tokenizer graph output is not a tensor: $name")

        private fun copyLongs(tensor: OnnxTensor, expectedCount: Int): LongArray {
            check(tensor.info.type == OnnxJavaType.INT64) { "Tokenizer graph did not return INT64" }
            val buffer = tensor.longBuffer.duplicate().apply { rewind() }
            check(buffer.remaining() == expectedCount) { "Tokenizer tensor element count differs from shape" }
            return LongArray(expectedCount).also { buffer.get(it) }
        }

        private fun closeSessions(sessions: List<OrtSession>) {
            var failure: Throwable? = null
            sessions.forEach { session ->
                try { session.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}
