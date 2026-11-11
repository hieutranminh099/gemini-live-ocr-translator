package com.gemini.ocr.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gemini.ocr.BuildConfig
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

    /** Requirement 1: Locked to Chinese ("zh" -> Vietnamese) only. */
    var language: String
        get() = "zh"
        set(@Suppress("UNUSED_PARAMETER") value) {
            prefs.edit().putString("language", "zh").apply()
        }

    var mode: String
        get() = prefs.getString("mode", "fast")!!
        set(value) { prefs.edit().putString("mode", value).apply() }
    var profile: String
        get() = prefs.getString("profile", "manga")!!
        set(value) { prefs.edit().putString("profile", value).apply() }

    /** Explicit film scope; blank means reviewed memory is not enabled for a film yet. */
    var memoryFilmId: String
        get() = prefs.getString("memory_film_id", "")!!
        set(value) {
            require(value.length <= 160 && value.none { it.isISOControl() })
            prefs.edit().putString("memory_film_id", value.trim()).apply()
        }
    var memoryDomain: String
        get() = prefs.getString("memory_domain", "general")!!
        set(value) {
            require(value.isNotBlank() && value.length <= 160 && value.none { it.isISOControl() })
            prefs.edit().putString("memory_domain", value.trim()).apply()
        }

    /** Requirement 4.1: Default region is "subtitle" (ROI cropping in bottom 1/3 of screen). */
    var region: String
        get() = prefs.getString("region", "subtitle")!!
        set(value) { prefs.edit().putString("region", value).apply() }
    val subtitleFocus: Boolean get() = region == "subtitle"
    var trackSourceRow: Boolean
        get() = prefs.getBoolean("trackSourceRow", false)
        set(value) { prefs.edit().putBoolean("trackSourceRow", value).apply() }

    val adjacentSubtitles: Boolean get() = subtitleFocus && subtitleCapture == "auto" &&
        (android.os.Build.VERSION.SDK_INT < 34 || fullScreen)
    var subtitleCapture: String
        get() = prefs.getString("subtitleCapture", "auto")!!
        set(value) { prefs.edit().putString("subtitleCapture", value).apply() }

    /** Requirement 4.1: Default subtitle ROI in bottom 1/3 of screen (top = 0.67f, bottom = 0.94f). */
    var subtitleRegion: android.graphics.RectF
        get() = android.graphics.RectF(
            prefs.getFloat("roiLeft", .06f),
            prefs.getFloat("roiTop", .67f),
            prefs.getFloat("roiRight", .94f),
            prefs.getFloat("roiBottom", .94f)
        )
        set(value) {
            prefs.edit()
                .putFloat("roiLeft", value.left)
                .putFloat("roiTop", value.top)
                .putFloat("roiRight", value.right)
                .putFloat("roiBottom", value.bottom)
                .apply()
        }

    /** Standalone on-device mode by default ("standalone"). */
    var connectionMode: String
        get() = prefs.getString("connectionMode", "standalone")!!
        set(value) { prefs.edit().putString("connectionMode", value).apply() }

    /**
     * Requirement 2: 2 Clear Standalone Modes:
     * - "hybrid_gemini": Chế độ 1 — Kho cục bộ + Gemini; độ trễ đo theo từng câu.
     * - "gemini_direct": Chế độ 2 — Chỉ dùng Gemini (Gemini Direct: Gửi kèm ngữ cảnh + LruCache 100)
     */
    var translationMode: String
        get() {
            val raw = prefs.getString("translationMode", null)
            return when (raw) {
                "gemini_direct" -> "gemini_direct"
                "hybrid_gemini" -> "hybrid_gemini"
                "bank_only" -> "hybrid_gemini"
                else -> "hybrid_gemini"
            }
        }
        set(value) {
            val normalized = when (value) {
                "gemini_direct", "direct" -> "gemini_direct"
                else -> "hybrid_gemini"
            }
            prefs.edit()
                .putString("translationMode", normalized)
                .putString("standaloneEngine", normalized)
                .apply()
        }

    var standaloneEngine: String
        get() = translationMode
        set(value) {
            translationMode = value
        }

    var hybridCharThreshold: Int
        get() = prefs.getInt("hybridCharThreshold", 15).coerceIn(5, 60)
        set(value) { prefs.edit().putInt("hybridCharThreshold", value.coerceIn(5, 60)).apply() }

    var ocrPreCorrection: Boolean
        get() = prefs.getBoolean("ocrPreCorrection", true)
        set(value) { prefs.edit().putBoolean("ocrPreCorrection", value).apply() }

    var turboLatency: Boolean
        get() = prefs.getBoolean("turboLatency", true)
        set(value) { prefs.edit().putBoolean("turboLatency", value).apply() }

    var preloadedDialogueEnabled: Boolean
        get() = translationMode == "hybrid_gemini" && prefs.getBoolean("preloadedDialogueEnabled", true)
        set(value) { prefs.edit().putBoolean("preloadedDialogueEnabled", value).apply() }

    var fastOcrDownscale: Boolean
        get() = prefs.getBoolean("fastOcrDownscale", true)
        set(value) { prefs.edit().putBoolean("fastOcrDownscale", value).apply() }

    var costSavingMode: Boolean
        get() = prefs.getBoolean("costSavingMode", true)
        set(value) { prefs.edit().putBoolean("costSavingMode", value).apply() }

    var stabilizeBeforeCloud: Boolean
        get() = prefs.getBoolean("stabilizeBeforeCloud", false)
        set(value) { prefs.edit().putBoolean("stabilizeBeforeCloud", value).apply() }

    var localGlossary: String
        get() = prefs.getString("localGlossary", "")!!
        set(value) { prefs.edit().putString("localGlossary", value).apply() }

    val standaloneBadge: String
        get() = standaloneBadgeFor(translationMode)

    fun standaloneBadgeFor(selectedMode: String): String = when (selectedMode) {
            "gemini_direct" -> "Chế độ 2: Chỉ dùng Gemini (Gemini Direct · Ngữ cảnh 1 câu + LruCache 100)"
            else -> "Chế độ 1: Kho cục bộ + Gemini • Độ trễ đo theo câu"
        }

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
        get() = prefs.getInt("font", 20)
        set(value) { prefs.edit().putInt("font", value).apply() }
    var opacity: Float
        get() = prefs.getFloat("opacity", .9f)
        set(value) { prefs.edit().putFloat("opacity", value).apply() }
    var textColor: Int
        get() = prefs.getInt("textColor", android.graphics.Color.WHITE)
        set(value) { prefs.edit().putInt("textColor", value).apply() }
    var subtitleBackground: Boolean
        get() = prefs.getBoolean("subtitleBackground", true)
        set(value) { prefs.edit().putBoolean("subtitleBackground", value).apply() }
    var subtitleOutline: Boolean
        get() = prefs.getBoolean("subtitleOutline", false)
        set(value) { prefs.edit().putBoolean("subtitleOutline", value).apply() }
    var interval: Long
        get() = prefs.getLong("interval", 150)
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
        get() = decryptPref("token")
        set(value) = encryptPref("token", value)

    var standaloneApiKey: String
        get() = decryptPref("standalone_api_key")
        set(value) = encryptPref("standalone_api_key", value.trim())

    fun effectiveStandaloneApiKey(): String =
        standaloneApiKey.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }

    var geminiKeyMode: GeminiKeyMode
        get() = GeminiKeyMode.fromPersisted(prefs.getString("gemini_video_key_mode", "") ?: "")
        set(value) { prefs.edit().putString("gemini_video_key_mode", value.name).apply() }

    fun secondaryGeminiApiKey(slot: Int): String {
        require(slot in 2..3)
        return decryptPref("gemini_video_api_key_$slot")
    }

    fun setSecondaryGeminiApiKey(slot: Int, value: String) {
        require(slot in 2..3)
        encryptPref("gemini_video_api_key_$slot", value.trim(), allowPlainFallback = false)
    }

    fun geminiVideoKeys(): List<GeminiKeySlot> = listOf(
        GeminiKeySlot(1, effectiveStandaloneApiKey().trim()),
        GeminiKeySlot(2, secondaryGeminiApiKey(2).trim()),
        GeminiKeySlot(3, secondaryGeminiApiKey(3).trim())
    ).filter { it.key.isNotBlank() }.distinctBy { it.key }

    fun hasGeminiVideoKey(): Boolean = if (geminiKeyMode == GeminiKeyMode.PRIMARY_ONLY)
        effectiveStandaloneApiKey().isNotBlank() else geminiVideoKeys().isNotEmpty()

    private fun decryptPref(prefKey: String): String {
        val stored = prefs.getString(prefKey, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.getString("${prefKey}_plain_fallback", "") ?: ""
        }
    }

    private fun encryptPref(prefKey: String, value: String, allowPlainFallback: Boolean = true) {
        if (value.isEmpty()) {
            prefs.edit().remove(prefKey).remove("${prefKey}_plain_fallback").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(prefKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove("${prefKey}_plain_fallback").apply()
        } catch (_: Exception) {
            check(allowPlainFallback) { "Chưa lưu được khóa vào bộ nhớ bảo mật. Hãy thử lại trên thiết bị." }
            prefs.edit().putString("${prefKey}_plain_fallback", value).apply()
        }
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
