package com.gemini.ocr.mtprobe

import java.nio.FloatBuffer

/** The immutable public export's contract, not a generic Marian model configuration. */
internal object Mt60DecodePolicy {
    const val START = 0L
    const val EOS = 2L
    const val UNKNOWN = 3L
    const val VOCABULARY = 24000
    const val LAYERS = 2
    const val HEADS = 8
    const val HEAD_WIDTH = 72
    const val HIDDEN = 576
    const val SOURCE_LIMIT = 192
    const val PREFIX_LIMIT = 192
    const val GENERATED_LIMIT = 128
    const val POSITION_LIMIT = 512

    class InvalidModelOutput(message: String) : IllegalStateException(message)

    data class AcceptedDialoguePair(val source: String, val translation: String) {
        init {
            require(source.isNotBlank() && translation.isNotBlank()) { "Context must be one accepted source/target pair" }
            require(source.length <= 4000 && translation.length <= 8000) { "Context text exceeds the probe bound" }
        }
    }

    data class InputPlan(val sourceIds: LongArray, val prefixIds: LongArray) {
        init {
            require(sourceIds.size in 1..SOURCE_LIMIT) { "Source accepts 1..192 tokens including EOS; no truncation" }
            require(sourceIds.all { it in 0 until VOCABULARY.toLong() }) { "Source token is outside the model vocabulary" }
            require(sourceIds.last() == EOS && sourceIds.dropLast(1).none { it == EOS }) {
                "Source codec must append exactly one final EOS"
            }
            require(prefixIds.size <= PREFIX_LIMIT) { "Accepted context prefix exceeds the token bound" }
            require(prefixIds.all { it in 4 until VOCABULARY.toLong() }) {
                "Target prefix must contain ordinary HF tokens without PAD/BOS/EOS/UNK"
            }
            require(1 + prefixIds.size + GENERATED_LIMIT <= POSITION_LIMIT) { "Decoder positions exceed the pinned export" }
        }

        fun initialDecoderIds(): LongArray = longArrayOf(START) + prefixIds
    }

    fun encoderText(source: String, accepted: AcceptedDialoguePair?): String {
        require(source.isNotBlank() && source.length <= 4000) { "Source text is empty or exceeds the probe bound" }
        return if (accepted == null) source else accepted.source + "\n" + source
    }

    fun generatedToken(token: Long) {
        if (token !in 0 until VOCABULARY.toLong()) throw InvalidModelOutput("Generated token is outside vocabulary")
        if (token == START || token == 1L || token == UNKNOWN) {
            throw InvalidModelOutput("Model emitted an unsupported PAD/BOS/UNK token")
        }
    }

    /** Decoder was fed the forced prefix. Its returned logits describe only the next suffix token. */
    fun suffixForDecode(generated: List<Long>): LongArray {
        if (generated.size !in 2..GENERATED_LIMIT || generated.lastOrNull() != EOS ||
            generated.dropLast(1).any { it == EOS }) {
            throw InvalidModelOutput("Model returned an empty or truncated translation")
        }
        generated.forEach(::generatedToken)
        return generated.toLongArray()
    }

    fun elementCount(shape: LongArray): Int {
        require(shape.isNotEmpty() && shape.all { it >= 0 }) { "Negative or missing tensor dimensions" }
        if (shape.any { it == 0L }) return 0
        var size = 1L
        for (dimension in shape) {
            require(dimension <= Int.MAX_VALUE && size <= Int.MAX_VALUE / dimension) { "Tensor exceeds Int indexing" }
            size *= dimension
        }
        return size.toInt()
    }

    fun decoderCacheShape(shape: LongArray, expectedLength: Int) {
        val expected = longArrayOf(1, HEADS.toLong(), expectedLength.toLong(), HEAD_WIDTH.toLong())
        require(shape.contentEquals(expected)) { "Decoder KV shape does not match the pinned head/sequence contract" }
    }

    /** All four cross-attention outputs must be nonempty together, or empty together. */
    fun replacesCrossCache(crossShapes: List<LongArray>, sourceLength: Int, initial: Boolean): Boolean {
        require(crossShapes.size == LAYERS * 2)
        val nonempty = crossShapes.map { shape ->
            require(shape.size == 4) { "Cross-attention KV must be rank four" }
            elementCount(shape) != 0
        }
        if (nonempty.none { it }) {
            require(!initial) { "Initial decoder must produce encoder key/value cache" }
            // Optimum's cached branch can return [0,H,1,W], not only [1,H,0,W].
            return false
        }
        require(nonempty.all { it }) { "Partially empty cross-attention output cannot replace a cache owner" }
        val expected = longArrayOf(1, HEADS.toLong(), sourceLength.toLong(), HEAD_WIDTH.toLong())
        require(crossShapes.all { it.contentEquals(expected) }) { "Cross-attention KV differs from source/head shape" }
        return true
    }

    /** Read the last time step directly; copying all prefix logits would add unnecessary work. */
    fun greedyToken(logits: FloatBuffer, shape: LongArray, decoderInputLength: Int): Long {
        require(decoderInputLength in 1..PREFIX_LIMIT + 1)
        require(shape.contentEquals(longArrayOf(1, decoderInputLength.toLong(), VOCABULARY.toLong()))) {
            "Logits shape must be [1,current decoder input length,24000]"
        }
        val data = logits.duplicate().apply { rewind() }
        require(data.remaining() == elementCount(shape)) { "Logits data does not match tensor shape" }
        val offset = (decoderInputLength - 1) * VOCABULARY
        var bestIndex = -1
        var best = Float.NEGATIVE_INFINITY
        for (index in 0 until VOCABULARY) {
            val value = data.get(offset + index)
            if (!value.isFinite()) throw InvalidModelOutput("Model returned a non-finite logit")
            if (bestIndex < 0 || value > best) { best = value; bestIndex = index }
        }
        return bestIndex.toLong().also(::generatedToken)
    }
}
