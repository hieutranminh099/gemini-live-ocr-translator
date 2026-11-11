package com.gemini.ocr.mtprobe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.gemini.ocr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * TEST APK ONLY. Real resident ONNX model, with a separate real tokenizer supplied by the parent.
 * No HTTP, learned memory, cache, implicit model discovery, source correction or app state writes.
 * Root still owns requestId/regionId/screenGeneration checks before using any returned string.
 */
internal class NativeMt60Probe private constructor(
    private val backend: Backend,
    val prepareMetrics: PrepareMetrics,
) {
    data class PrepareMetrics(val artifactVerificationNs: Long, val modelSessionsLoadNs: Long, val totalNs: Long)
    data class Translation(
        val text: String,
        val inputTokens: Int,
        val contextPrefixTokens: Int,
        val outputTokensIncludingEos: Int,
        val sourceUnknownTokens: Int,
        /** Tokenization + encoder + greedy decoder + detokenization, excludes waiting for owner. */
        val processNs: Long,
    )
    private data class Request(val source: String, val accepted: Mt60DecodePolicy.AcceptedDialoguePair?)
    private val owner = Mt60NativeOwner<Request, Translation>(
        executeNative = { request, active -> backend.translate(request, active) },
        closeNative = { withContext(Dispatchers.IO) { backend.close() } },
    )

    suspend fun translate(source: String, accepted: Mt60DecodePolicy.AcceptedDialoguePair? = null): Translation =
        withContext(Dispatchers.Default) { owner.execute(Request(source, accepted)) }

    /** Call in finally. Native work finishes or stops at the next token boundary before release. */
    suspend fun release() = owner.release()

    private class Backend(
        private val env: OrtEnvironment,
        private val encoder: OrtSession,
        private val decoder: OrtSession,
        private val codec: Mt60TokenCodec,
    ) : AutoCloseable {
        init {
            require(encoder.inputNames == setOf("input_ids", "attention_mask")) { "Unexpected encoder inputs" }
            require("last_hidden_state" in encoder.outputNames) { "Missing encoder hidden state" }
            val expected = mutableSetOf("encoder_attention_mask", "encoder_hidden_states", "input_ids", "use_cache_branch")
            for (layer in 0 until Mt60DecodePolicy.LAYERS) for (side in listOf("decoder", "encoder")) {
                for (kind in listOf("key", "value")) expected += "past_key_values.$layer.$side.$kind"
            }
            require(decoder.inputNames == expected) { "Unexpected merged-decoder input contract" }
            require(decoder.outputNames == expected.filter { it.startsWith("past_key_values.") }
                .map { it.replaceFirst("past_key_values.", "present.") }.toSet() + "logits") {
                "Unexpected merged-decoder output contract"
            }
        }

        fun translate(request: Request, active: () -> Unit): Translation {
            val started = System.nanoTime()
            active()
            val sourceText = Mt60DecodePolicy.encoderText(request.source, request.accepted)
            val plan = Mt60DecodePolicy.InputPlan(
                codec.encodeSource(sourceText).copyOf(),
                request.accepted?.let { codec.encodeTargetPrefix(it.translation).copyOf() } ?: longArrayOf(),
            )
            active()
            val output = withEncoder(plan, active)
            active()
            val translated = codec.decodeTarget(output).trim()
            active()
            check(translated.isNotBlank() && translated.length <= 8000) { "Model returned an empty or oversized translation" }
            return Translation(translated, plan.sourceIds.size, plan.prefixIds.size, output.size,
                plan.sourceIds.count { it == Mt60DecodePolicy.UNKNOWN }, System.nanoTime() - started)
        }

        private fun withEncoder(plan: Mt60DecodePolicy.InputPlan, active: () -> Unit): LongArray {
            val shape = longArrayOf(1, plan.sourceIds.size.toLong())
            longTensor(plan.sourceIds, shape).use { ids ->
                longTensor(LongArray(plan.sourceIds.size) { 1 }, shape).use { mask ->
                    active()
                    encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { encoded ->
                        active()
                        val hidden = encoded.tensor("last_hidden_state")
                        require(hidden.info.shape.contentEquals(longArrayOf(1, plan.sourceIds.size.toLong(), Mt60DecodePolicy.HIDDEN.toLong()))) {
                            "Encoder hidden shape differs from the pinned model"
                        }
                        // Keep encoded Result and mask alive throughout all borrowed hidden-state use.
                        return decode(plan, mask, hidden, active)
                    }
                }
            }
        }

        private fun decode(plan: Mt60DecodePolicy.InputPlan, mask: OnnxTensor,
                           hidden: OnnxTensor, active: () -> Unit): LongArray {
            val empty = linkedMapOf<String, OnnxTensor>()
            try {
                for (layer in 0 until Mt60DecodePolicy.LAYERS) for (side in listOf("decoder", "encoder")) {
                    for (kind in listOf("key", "value")) empty["past_key_values.$layer.$side.$kind"] =
                        OnnxTensor.createTensor(env,
                            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer(),
                            longArrayOf(1, Mt60DecodePolicy.HEADS.toLong(), 0, Mt60DecodePolicy.HEAD_WIDTH.toLong()))
                }
                Mt60ResultOwners<OrtSession.Result>().use { owners ->
                    val generated = ArrayList<Long>(Mt60DecodePolicy.GENERATED_LIMIT)
                    var last = Mt60DecodePolicy.START
                    for (step in 0 until Mt60DecodePolicy.GENERATED_LIMIT) {
                        active() // A cancelled source never starts the next native step.
                        val tokenIds = if (step == 0) plan.initialDecoderIds() else longArrayOf(last)
                        longTensor(tokenIds, longArrayOf(1, tokenIds.size.toLong())).use { input ->
                            OnnxTensor.createTensor(env, booleanArrayOf(step > 0)).use { cachedBranch ->
                                val inputs = linkedMapOf("encoder_attention_mask" to mask, "encoder_hidden_states" to hidden,
                                    "input_ids" to input, "use_cache_branch" to cachedBranch)
                                if (step == 0) inputs.putAll(empty)
                                else {
                                    for (layer in 0 until Mt60DecodePolicy.LAYERS) for (kind in listOf("key", "value")) {
                                        // Borrowed outputs remain owned by retained Results until this run returns.
                                        inputs["past_key_values.$layer.decoder.$kind"] =
                                            checkNotNull(owners.decoder).tensor("present.$layer.decoder.$kind")
                                        inputs["past_key_values.$layer.encoder.$kind"] =
                                            checkNotNull(owners.cross).tensor("present.$layer.encoder.$kind")
                                    }
                                }
                                active()
                                val next = decoder.run(inputs)
                                try {
                                    active() // Safe only after native returned; finally below cleans all ownership.
                                    val logits = next.tensor("logits")
                                    last = Mt60DecodePolicy.greedyToken(logits.floatBuffer, logits.info.shape, tokenIds.size)
                                    generated += last
                                    if (last == Mt60DecodePolicy.EOS) return Mt60DecodePolicy.suffixForDecode(generated)
                                    val expectedDecoderLength = plan.prefixIds.size + 1 + step
                                    for (layer in 0 until Mt60DecodePolicy.LAYERS) for (kind in listOf("key", "value")) {
                                        Mt60DecodePolicy.decoderCacheShape(next.tensor("present.$layer.decoder.$kind").info.shape,
                                            expectedDecoderLength)
                                    }
                                    val crossShapes = (0 until Mt60DecodePolicy.LAYERS).flatMap { layer ->
                                        listOf("key", "value").map { kind -> next.tensor("present.$layer.encoder.$kind").info.shape }
                                    }
                                    val replaceCross = Mt60DecodePolicy.replacesCrossCache(crossShapes,
                                        plan.sourceIds.size, initial = step == 0)
                                    // At most cross owner + latest decoder owner survive. The previous owners
                                    // are closed only now, after run() finished reading their tensors.
                                    owners.adopt(next, replaceCross)
                                } finally {
                                    if (!owners.owns(next)) next.close()
                                }
                            }
                        }
                    }
                    throw Mt60DecodePolicy.InvalidModelOutput("Model did not produce EOS in 128 generated tokens")
                }
            } finally { closeAll(empty.values.toList()) }
        }

        private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

        private fun OrtSession.Result.tensor(name: String): OnnxTensor =
            get(name).orElseThrow { IllegalStateException("Missing native output: $name") } as? OnnxTensor
                ?: throw IllegalStateException("Native output is not a tensor: $name")

        override fun close() = closeAll(listOf(decoder, encoder, codec))
    }

    companion object {
        private data class ModelPin(val relative: String, val bytes: Long, val sha256: String)
        private val pins = listOf(
            ModelPin("onnx/encoder_model_quantized.onnx", 46486972,
                "5df641619bf518890fe90e0ad9e8f85664398d8ecdcfdd9603a1f45899d9a171"),
            ModelPin("onnx/decoder_model_merged_quantized.onnx", 38988324,
                "e75e66654a8c0844be0de4e27447fffe638aa57564867d159ba7368b76c5a037"),
        )

        /**
         * Explicit public-artifact directory only. After preconditions pass, consumes codec ownership
         * even if loading is cancelled/fails. Do not share that tokenizer with another owner.
         */
        suspend fun create(context: Context, directory: File, codec: Mt60TokenCodec, threads: Int = 2,
                           allowSpinning: Boolean = true): NativeMt60Probe {
            require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.codexbench")
            require(threads in 1..4)
            val caller = currentCoroutineContext()
            var encoder: OrtSession? = null; var decoder: OrtSession? = null
            val started = System.nanoTime()
            try {
                return withContext(NonCancellable + Dispatchers.IO) {
                    caller.ensureActive()
                    val verifyStarted = System.nanoTime()
                    val root = directory.canonicalFile
                    require(root.isDirectory) { "Explicit model artifact directory does not exist" }
                    val files = pins.map { pin -> verifyPinned(root, pin) }
                    val verifyNs = System.nanoTime() - verifyStarted
                    caller.ensureActive()
                    val env = OrtEnvironment.getEnvironment() // Shared process environment is not owned/closed here.
                    val loadStarted = System.nanoTime()
                    OrtSession.SessionOptions().use { options ->
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        options.setIntraOpNumThreads(threads)
                        options.setInterOpNumThreads(1)
                        com.paddle.ocr.OrtThreadPoolConfig.entries(allowSpinning).forEach { (key, value) ->
                            options.addConfigEntry(key, value)
                        }
                        encoder = env.createSession(files[0].absolutePath, options)
                        caller.ensureActive()
                        decoder = env.createSession(files[1].absolutePath, options)
                    }
                    val loadNs = System.nanoTime() - loadStarted
                    caller.ensureActive()
                    val backend = Backend(env, checkNotNull(encoder), checkNotNull(decoder), codec)
                    NativeMt60Probe(backend, PrepareMetrics(verifyNs, loadNs, System.nanoTime() - started))
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { closeAll(listOfNotNull(decoder, encoder, codec)) }
                    catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }

        private fun verifyPinned(root: File, pin: ModelPin): File {
            val model = File(root, pin.relative)
            require(model.canonicalFile.toPath().startsWith(root.toPath())) { "Model artifact escaped its explicit directory" }
            require(model.isFile && model.length() == pin.bytes) { "Model artifact has an unexpected size" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            model.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= pin.bytes)
                    digest.update(buffer, 0, count)
                }
            }
            require(total == pin.bytes && digest.digest().joinToString("") { "%02x".format(it) } == pin.sha256) {
                "Model artifact SHA-256 mismatch"
            }
            return model
        }

        private fun closeAll(values: List<AutoCloseable>) {
            var failure: Throwable? = null
            for (value in values) try { value.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }
}
