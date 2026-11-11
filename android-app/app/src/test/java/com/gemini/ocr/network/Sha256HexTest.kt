package com.gemini.ocr.network

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Key compatibility only. Native import measurements establish any performance effect. */
class Sha256HexTest {
    private fun reference(text: String): String = BigInteger(1,
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        .toString(16).padStart(64, '0')

    @Test fun emptyPayloadKeepsTheStandardSha256Vector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Hex.utf8(""))
    }

    @Test fun asciiPayloadKeepsTheStandardSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256Hex.utf8("abc"))
    }

    @Test fun everySignedByteUsesTwoLowercaseDigitsAndRetainsLeadingZeroes() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = Sha256Hex.encode(bytes)
        assertEquals(512, encoded.length)
        assertTrue(encoded.startsWith("000102030405060708090a0b0c0d0e0f"))
        for (index in 0..255) assertEquals(index,
            encoded.substring(index * 2, index * 2 + 2).toInt(16))
        assertTrue(encoded.endsWith("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"))
        assertTrue(encoded.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("", Sha256Hex.encode(byteArrayOf()))
    }

    @Test fun hanAndVietnamesePayloadsHashTheirCompleteUtf8Encoding() {
        for (text in listOf("快快快进来进来", "你到底想干什么？", "Rốt cuộc anh muốn làm gì?")) {
            assertEquals(reference(text), Sha256Hex.utf8(text))
            assertEquals(64, Sha256Hex.utf8(text).length)
        }
    }

    @Test fun supplementaryHanEmojiAndEmbeddedControlBytesRemainByteExact() {
        for (text in listOf("𠀀✨😀", "汉字\u0000tiếng Việt\n", "\uFEFF字幕\r\n"))
            assertEquals(reference(text), Sha256Hex.utf8(text))
    }

    @Test fun hashDoesNotIntroduceUnicodeOrWhitespaceNormalization() {
        val decomposed = "e\u0301"; val composed = "é"
        assertNotEquals(Sha256Hex.utf8(decomposed), Sha256Hex.utf8(composed))
        assertEquals(reference(decomposed), Sha256Hex.utf8(decomposed))
        assertEquals(reference(composed), Sha256Hex.utf8(composed))
        assertNotEquals(Sha256Hex.utf8("字幕"), Sha256Hex.utf8(" 字幕 "))
        assertNotEquals(Sha256Hex.utf8("字幕\n"), Sha256Hex.utf8("字幕\r\n"))
    }

    @Test fun existingFormatterKeysRemainUnchangedForSourcesAndSerializedScopes() {
        val payloads = listOf("", "abc", "[]", "{\"film_id\":\"电影一\",\"context\":\"上一句：请等一下。\"}") +
            (0 until 128).map { "第${it}句。Tôi đã hiểu.𠀀\n${"x".repeat(it)}" }
        for (text in payloads) {
            val legacy = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(legacy, Sha256Hex.utf8(text))
        }
    }

    @Test fun parallelCallersCannotMixTheirDigestOrOutputBuffers() {
        val payloads = (0 until 256).map { "线程${it}：字幕与 ngữ cảnh ${"𠀀".repeat(it % 7)}" }
        val references = payloads.associateWith(::reference)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = payloads.map { text -> Callable {
                repeat(8) { assertEquals(references.getValue(text), Sha256Hex.utf8(text)) }
                true
            } }
            val futures = executor.invokeAll(work, 10, TimeUnit.SECONDS)
            for (future in futures) assertTrue(future.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
