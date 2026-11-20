package com.gemini.ocr.settings

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable ciphertext. The password and derived key never enter Drive or preferences. */
internal object GeminiKeyBackup {
    private const val ITERATIONS = 210000
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 12..256) { "Mật khẩu sao lưu cần từ 12 đến 256 ký tự." }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun seal(keys: List<String>, password: CharArray, account: String): ByteArray {
        require(keys.size == 3 && keys.any { it.isNotBlank() } && keys.all { it.length <= 1024 }) {
            "Lưu ít nhất một khóa Gemini trên thiết bị trước."
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt))
        cipher.updateAAD(("gemini-keys-v1|" + account).toByteArray(Charsets.UTF_8))
        val plain = JSONArray(keys).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val encoder = Base64.getEncoder()
        return JSONObject().put("schema", 1).put("salt", encoder.encodeToString(salt))
            .put("iv", encoder.encodeToString(cipher.iv)).put("ciphertext", encoder.encodeToString(encrypted))
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun open(bytes: ByteArray, password: CharArray, account: String): List<String> {
        require(bytes.size in 1..32768)
        try {
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            require(envelope.getInt("schema") == 1)
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(envelope.getString("salt"))
            val iv = decoder.decode(envelope.getString("iv"))
            require(salt.size == 16 && iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(("gemini-keys-v1|" + account).toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(decoder.decode(envelope.getString("ciphertext")))
            return try {
                val values = JSONArray(plain.toString(Charsets.UTF_8))
                require(values.length() == 3)
                (0..2).map { values.getString(it).also { value -> require(value.length <= 1024) } }
            } finally { plain.fill(0) }
        } catch (_: Exception) {
            throw IllegalArgumentException("Không mở được bản sao. Kiểm tra tài khoản và mật khẩu sao lưu.")
        }
    }
}
