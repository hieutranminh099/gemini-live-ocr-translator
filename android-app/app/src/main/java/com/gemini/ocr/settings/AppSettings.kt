package com.gemini.ocr.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var host: String
        get() = prefs.getString("host", "")!!
        set(value) { prefs.edit().putString("host", value).apply() }
    var pin: String
        get() = prefs.getString("pin", "")!!
        set(value) { prefs.edit().putString("pin", value).apply() }
    var language: String
        get() = prefs.getString("language", "ja")!!
        set(value) { prefs.edit().putString("language", value).apply() }
    var mode: String
        get() = prefs.getString("mode", "smart")!!
        set(value) { prefs.edit().putString("mode", value).apply() }
    var profile: String
        get() = prefs.getString("profile", "game")!!
        set(value) { prefs.edit().putString("profile", value).apply() }
    var region: String
        get() = prefs.getString("region", "full")!!
        set(value) { prefs.edit().putString("region", value).apply() }
    val subtitleFocus: Boolean get() = region == "subtitle"
    var trackSourceRow: Boolean
        get() = prefs.getBoolean("trackSourceRow", false)
        set(value) { prefs.edit().putBoolean("trackSourceRow", value).apply() }
    // A full-display projection also captures our windows. Keep automatic subtitles
    // outside the OCR strip so they can remain visible while new source text is read.
    val adjacentSubtitles: Boolean get() = subtitleFocus && subtitleCapture == "auto" &&
        (android.os.Build.VERSION.SDK_INT < 34 || fullScreen)
    var subtitleCapture: String
        get() = prefs.getString("subtitleCapture", "frame")!!
        set(value) { prefs.edit().putString("subtitleCapture", value).apply() }
    var subtitleRegion: android.graphics.RectF
        get() = android.graphics.RectF(prefs.getFloat("roiLeft", .08f), prefs.getFloat("roiTop", .78f),
            prefs.getFloat("roiRight", .92f), prefs.getFloat("roiBottom", .96f))
        set(value) { prefs.edit().putFloat("roiLeft", value.left).putFloat("roiTop", value.top)
            .putFloat("roiRight", value.right).putFloat("roiBottom", value.bottom).apply() }
    fun useSubtitlePreset() {
        region = "subtitle"; overlay = "cover"; subtitleCapture = "frame"
        mode = "fast"; fontSize = 22; opacity = 1f; textColor = android.graphics.Color.WHITE
        interval = 500
    }
    fun useAutoSubtitlePreset() {
        useSubtitlePreset()
        subtitleCapture = "auto"; interval = 150
    }
    var overlay: String
        get() = prefs.getString("overlay", "subtitle")!!
        set(value) { prefs.edit().putString("overlay", value).apply() }
    var fontSize: Int
        get() = prefs.getInt("font", 18)
        set(value) { prefs.edit().putInt("font", value).apply() }
    var opacity: Float
        get() = prefs.getFloat("opacity", .9f)
        set(value) { prefs.edit().putFloat("opacity", value).apply() }
    var textColor: Int
        get() = prefs.getInt("textColor", android.graphics.Color.WHITE)
        set(value) { prefs.edit().putInt("textColor", value).apply() }
    var interval: Long
        get() = prefs.getLong("interval", 900)
        set(value) { prefs.edit().putLong("interval", value).apply() }
    var subtitleY: Float
        get() = prefs.getFloat("subtitleY", .78f)
        set(value) { prefs.edit().putFloat("subtitleY", value).apply() }
    var fullScreen: Boolean
        get() = prefs.getBoolean("fullScreen", android.os.Build.VERSION.SDK_INT < 34)
        set(value) { prefs.edit().putBoolean("fullScreen", value).apply() }
    var excludedApps: String
        get() = prefs.getString("excluded", "")!!
        set(value) { prefs.edit().putString("excluded", value).apply() }
    var token: String
        get() {
            val stored = prefs.getString("token", null) ?: return ""
            return try {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }
        set(value) {
            if (value.isEmpty()) { prefs.edit().remove("token").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("GeminiOCRDevice", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("GeminiOCRDevice", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun savePairing(hostValue: String, pinValue: String, tokenValue: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(tokenValue.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("host", hostValue).putString("pin", pinValue)
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Không lưu được kết nối." }
    }
    fun unpair() { prefs.edit().remove("token").remove("host").remove("pin").apply() }
}

