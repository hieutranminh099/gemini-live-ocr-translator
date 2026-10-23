package com.gemini.ocr

import org.junit.Assert.*
import org.junit.Test

class TranslationBackendStateTest {
    @Test fun experimentalCliRequiresExplicitOptInAndAvailability() {
        val enabled = TranslationBackendState("cli", "cli_experimental", true, true)
        assertEquals("CLI cá nhân thử nghiệm • Đã bật dịch câu mới", enabled.badge)
        assertFalse(enabled.copy(experimentalCliEnabled = false).badge.contains("Đã bật"))
        assertEquals("CLI cá nhân thử nghiệm • Bộ dịch chưa sẵn sàng", enabled.copy(translationAvailable = false).badge)
        assertTrue(enabled.copy(translationAvailable = null).badge.contains("Chưa rõ"))
    }

    @Test fun cacheOnlyDoesNotClaimFreshTranslationEvenWithConflictingAvailability() {
        assertEquals("Chỉ dùng cache / từ điển • Chưa bật dịch câu mới",
            TranslationBackendState("cli", "cache_only", true, true).badge)
    }

    @Test fun apiBadgeReflectsUnavailableAndUnknownStates() {
        val api = TranslationBackendState("api", "api", true)
        assertEquals("Gemini API • Khóa trên Windows", api.badge)
        assertTrue(api.copy(translationAvailable = false).badge.contains("chưa sẵn sàng"))
        assertTrue(api.copy(translationAvailable = null).badge.contains("Chưa rõ"))
    }

    @Test fun legacyCompanionDoesNotImplicitlyClaimCacheOnlyOrAvailable() {
        listOf("cli", "api", "").forEach { provider ->
            val state = TranslationBackendState(provider = provider)
            assertNull(state.translationAvailable)
            assertTrue(state.badge.contains("Chưa rõ"))
            assertFalse(state.badge.contains("Chỉ dùng cache"))
            assertFalse(state.badge.contains("Đã bật"))
        }
    }
}
