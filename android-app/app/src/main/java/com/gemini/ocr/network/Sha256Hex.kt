package com.gemini.ocr.network

import java.security.MessageDigest

/** Stable UTF-8 digest keys; avoid constructing a Formatter for every digest byte. */
internal object Sha256Hex {
    private const val DIGITS = "0123456789abcdef"

    fun utf8(text: String): String = encode(MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)))

    internal fun encode(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            result[index * 2] = DIGITS[value ushr 4]
            result[index * 2 + 1] = DIGITS[value and 0x0f]
        }
        return result.concatToString()
    }
}
