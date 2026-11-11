package com.gemini.ocr.mtprobe

/**
 * Real tokenizer graph adapter supplied by the parent. It owns native tokenizer resources.
 * No implementation, fake translator or hardcoded tokenization is supplied by this decoder.
 */
internal interface Mt60TokenCodec : AutoCloseable {
    /** HF vocabulary IDs, with exactly one final EOS=2, as encoder input_ids[1,N]. */
    fun encodeSource(text: String): LongArray

    /** HF vocabulary IDs of accepted Vietnamese context, without any added special tokens. */
    fun encodeTargetPrefix(text: String): LongArray

    /** Generated suffix only, including terminal EOS. Skip HF control IDs 0/1/2/3. */
    fun decodeTarget(ids: LongArray): String
}
