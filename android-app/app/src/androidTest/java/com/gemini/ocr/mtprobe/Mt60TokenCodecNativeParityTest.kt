package com.gemini.ocr.mtprobe

import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Only tokenizer graphs run here: no encoder, MT decoder, network, key access or source replay. */
class Mt60TokenCodecNativeParityTest {
    private fun ids(values: JSONArray) = LongArray(values.length()) { values.getLong(it) }

    @Test fun authorizedPinnedNativeSentencepieceMatchesThe95DesktopTokenizerVectors() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeMt60TokenizerParity") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value) { "Stop live capture before native tokenizer parity" }
        val bytes = ByteArray(28677)
        instrumentation.context.assets.open("mt60-tokenizer/tokenizer-parity-v1.json").use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                require(count > 0) { "Tokenizer fixture was truncated" }
                offset += count
            }
            require(input.read() == -1) { "Tokenizer fixture exceeds its pinned size" }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == "c7ca41abc47b0be11ecb66a96e7f477bdc48f458579da987ea2cf73129fbeed0")
        val fixture = JSONObject(bytes.toString(Charsets.UTF_8))
        require(fixture.getString("kind") == "MT60_NATIVE_TOKENIZER_PARITY_NOT_TRANSLATION_MODEL_TEST")
        require(fixture.getString("revision") == "907cfd51a52aaaac6323fa8bd749aeb00020c2f3")
        val sourceCases = fixture.getJSONArray("source"); require(sourceCases.length() == 38)
        val prefixCases = fixture.getJSONArray("target_prefix"); require(prefixCases.length() == 13)
        val decodeCases = fixture.getJSONArray("decode"); require(decodeCases.length() == 44)
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "verification/mt60-tokenizer/graphs")
        val codec = OrtxMt60TokenCodec.create(directory)
        try {
            for (index in 0 until sourceCases.length()) {
                val row = sourceCases.getJSONObject(index)
                assertArrayEquals("source vector $index", ids(row.getJSONArray("input_ids")),
                    codec.encodeSource(row.getString("text")))
                // encodeSource validates the returned attention mask against these all-one vectors.
                assertEquals(row.getJSONArray("input_ids").length(), row.getJSONArray("attention_mask").length())
            }
            for (index in 0 until prefixCases.length()) {
                val row = prefixCases.getJSONObject(index)
                assertArrayEquals("prefix vector $index", ids(row.getJSONArray("token_ids")),
                    codec.encodeTargetPrefix(row.getString("text")))
            }
            for (index in 0 until decodeCases.length()) {
                val row = decodeCases.getJSONObject(index)
                assertEquals("decode vector $index", row.getString("expected_text"),
                    codec.decodeTarget(ids(row.getJSONArray("token_ids"))))
            }
            for (invalid in listOf(longArrayOf(-1), longArrayOf(24000), longArrayOf(1L shl 40))) {
                assertThrows(IllegalArgumentException::class.java) { codec.decodeTarget(invalid) }
            }
            assertThrows(IllegalArgumentException::class.java) { codec.encodeSource("before\u0000after") }
            assertThrows(IllegalArgumentException::class.java) { codec.encodeTargetPrefix("before\u0000after") }
            assertThrows(IllegalArgumentException::class.java) { codec.encodeSource("中".repeat(8193)) }
            assertThrows(IllegalArgumentException::class.java) { codec.decodeTarget(LongArray(513) { 4 }) }
        } finally {
            codec.close()
        }
        codec.close() // Idempotent close; methods cannot use released native sessions.
        assertThrows(IllegalStateException::class.java) { codec.encodeSource("帮帮我") }
        assertThrows(IllegalStateException::class.java) { codec.encodeTargetPrefix("Giúp tôi") }
        assertThrows(IllegalStateException::class.java) { codec.decodeTarget(longArrayOf(4, 2)) }
    }
}
