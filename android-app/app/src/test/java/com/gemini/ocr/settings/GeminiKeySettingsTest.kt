package com.gemini.ocr.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiKeySettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun setup() { preferences.edit().clear().commit() }

    private fun seed(name: String, value: String) {
        // Synthetic legacy preference fixture. Production saves secondary slots with encryption only.
        preferences.edit().putString(name, "synthetic-invalid-ciphertext")
            .putString("${name}_plain_fallback", value).commit()
    }

    @Test fun existingPrimaryKeyAndUnknownModeKeepTheOriginalDefault() {
        seed("standalone_api_key", "fixture-primary")
        preferences.edit().putString("gemini_video_key_mode", "FUTURE_MODE").commit()
        val settings = AppSettings(context)
        assertEquals(GeminiKeyMode.PRIMARY_ONLY, settings.geminiKeyMode)
        assertEquals("fixture-primary", settings.effectiveStandaloneApiKey())
        assertEquals(listOf(1), settings.geminiVideoKeys().map { it.slot })
        assertTrue(settings.hasGeminiVideoKey())
    }

    @Test fun configuredKeysAreTrimmedAndExactDuplicatesKeepTheFirstSlot() {
        seed("standalone_api_key", "fixture-same")
        seed("gemini_video_api_key_2", " fixture-same ")
        seed("gemini_video_api_key_3", " fixture-third ")
        val settings = AppSettings(context)
        val slots = settings.geminiVideoKeys()
        assertEquals(listOf(1, 3), slots.map { it.slot })
        assertEquals(listOf("fixture-same", "fixture-third"), slots.map { it.key })
        assertFalse(slots.toString().contains("fixture-"))
    }

    @Test fun secondaryKeysBecomeAvailableOnlyInAnExplicitVideoMode() {
        seed("gemini_video_api_key_2", "fixture-second")
        val settings = AppSettings(context)
        assertFalse(settings.hasGeminiVideoKey())
        for (mode in listOf(GeminiKeyMode.FAILOVER, GeminiKeyMode.BALANCED)) {
            settings.geminiKeyMode = mode
            assertEquals(mode, AppSettings(context).geminiKeyMode)
            assertTrue(AppSettings(context).hasGeminiVideoKey())
        }
        settings.geminiKeyMode = GeminiKeyMode.PRIMARY_ONLY
        assertFalse(settings.hasGeminiVideoKey())
        assertEquals("fixture-second", settings.secondaryGeminiApiKey(2))
    }

    @Test fun secondarySaveNeverFallsBackToPlaintextWhenSecureStorageIsUnavailable() {
        val settings = AppSettings(context)
        val fixture = "fixture-secondary-secret"
        try {
            settings.setSecondaryGeminiApiKey(2, fixture)
            assertEquals(fixture, settings.secondaryGeminiApiKey(2))
            assertNotEquals(fixture, preferences.getString("gemini_video_api_key_2", null))
        } catch (error: IllegalStateException) {
            assertFalse(error.message.orEmpty().contains(fixture))
            assertEquals("", settings.secondaryGeminiApiKey(2))
        }
        assertNull(preferences.getString("gemini_video_api_key_2_plain_fallback", null))
    }

    @Test fun removingASecondarySlotKeepsOtherSlotsAndRejectsOutOfRangeSlots() {
        seed("standalone_api_key", "fixture-primary")
        seed("gemini_video_api_key_2", "fixture-second")
        seed("gemini_video_api_key_3", "fixture-third")
        val settings = AppSettings(context)
        settings.setSecondaryGeminiApiKey(2, "  ")
        assertEquals("", settings.secondaryGeminiApiKey(2))
        assertEquals(listOf(1, 3), settings.geminiVideoKeys().map { it.slot })
        assertNull(preferences.getString("gemini_video_api_key_2", null))
        assertNull(preferences.getString("gemini_video_api_key_2_plain_fallback", null))
        assertThrows(IllegalArgumentException::class.java) { settings.secondaryGeminiApiKey(1) }
        assertThrows(IllegalArgumentException::class.java) { settings.setSecondaryGeminiApiKey(4, "fixture") }
    }
}
