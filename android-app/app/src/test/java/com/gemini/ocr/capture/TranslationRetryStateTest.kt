package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class TranslationRetryStateTest {
    private val cliReason = "Google không hỗ trợ dùng Antigravity CLI gói cá nhân làm backend ứng dụng bên thứ ba. Chọn Gemini API bằng khóa riêng."

    @Test fun configurationFailureDoesNotExpireAfterOldSixtySecondRetryInterval() {
        val state = TranslationRetryState()
        val failure = state.failed("zh|game|fast|A", "cli_configuration", cliReason, 100)
        assertFalse(failure.automaticRetry)
        assertNull(failure.retryAt)
        assertSame(failure, state.waiting("zh|game|fast|A", 60100))
        assertSame(failure, state.waiting("zh|game|fast|A", 86400100))
        assertTrue(failure.status(86400100).startsWith(cliReason))
        assertFalse(failure.status(86400100).contains("Thử lại sau"))
    }

    @Test fun eachConfigurationOrAuthenticationErrorRequiresRecovery() {
        listOf("no_api_key", "provider_configuration", "cli_auth", "cli_login_active",
            "cli_missing", "cli_configuration", "cli_platform").forEach { code ->
            val state = TranslationRetryState()
            state.failed("A", code, "Actual reason: $code", 0)
            assertNotNull(code, state.waiting("A", Long.MAX_VALUE))
            assertNull(code, state.waiting("B", 0))
        }
    }

    @Test fun repeatedConfigurationFailuresAreBoundedAndRefreshExistingEntry() {
        val state = TranslationRetryState(capacity = 2)
        state.failed("A", "no_api_key", "A", 0)
        state.failed("B", "no_api_key", "B", 0)
        state.failed("A", "no_api_key", "A again", 1)
        state.failed("C", "no_api_key", "C", 2)
        assertNull(state.waiting("B", 3))
        assertEquals("A again", state.waiting("A", 3)?.message)
        assertEquals("C", state.waiting("C", 3)?.message)
    }

    @Test fun differentSentenceAndTranslationSettingsMayStillQueryServerCache() {
        val state = TranslationRetryState()
        state.failed("zh|game|fast|A", "cli_configuration", cliReason, 0)
        assertNull(state.waiting("zh|game|fast|B", 1))
        assertNull(state.waiting("ja|game|fast|A", 1))
        assertNull(state.waiting("zh|web|fast|A", 1))
        assertNull(state.waiting("zh|game|smart|A", 1))
    }

    @Test fun successfulLookupReleasesOnlyThatConfigurationFailure() {
        val state = TranslationRetryState()
        state.failed("A", "no_api_key", "A missing", 0)
        state.failed("B", "no_api_key", "B missing", 0)
        state.succeeded("A")
        assertNull(state.waiting("A", 1))
        assertNotNull(state.waiting("B", 1))
    }

    @Test fun explicitResetClearsConfigurationAndTransientFailures() {
        val state = TranslationRetryState()
        state.failed("A", "no_api_key", "Missing key", 0)
        state.failed("B", "quota_exceeded", "Quota", 0)
        state.clear()
        assertNull(state.waiting("A", 1))
        assertNull(state.waiting("B", 1))
        assertNull(state.waiting("C", 1))
    }

    @Test fun transientBackoffIsGlobalPreservesReasonAndExpiresAtExactDeadline() {
        val state = TranslationRetryState()
        val failure = state.failed("A", "rate_limited", "Đạt giới hạn lượt gọi/phút.", 100)
        assertTrue(failure.automaticRetry)
        assertEquals(15100L, failure.retryAt)
        assertSame(failure, state.waiting("B", 101))
        assertEquals("Đạt giới hạn lượt gọi/phút.\nThử lại sau 15 giây.", failure.status(101))
        assertTrue(failure.status(15099).endsWith("1 giây."))
        assertNotNull(state.waiting("B", 15099))
        assertNull(state.waiting("A", 15100))
        assertNull(state.waiting("B", 15100))
    }

    @Test fun dailyAndUnknownErrorsRetainTheirActualRetryIntervals() {
        val daily = TranslationRetryState()
        daily.failed("A", "daily_limit", "Đã đạt giới hạn ngày.", 1000)
        assertNotNull(daily.waiting("B", 60999))
        assertNull(daily.waiting("B", 61000))
        val temporary = TranslationRetryState()
        temporary.failed("A", "provider_unavailable", "Máy chủ không sẵn sàng.", 1000)
        assertNotNull(temporary.waiting("A", 5999))
        assertNull(temporary.waiting("A", 6000))
    }

    @Test fun compactCliFeedbackStatesBlockedCapabilityWithoutPretendingToRetry() {
        val state = TranslationRetryState()
        val failure = state.failed("A", "cli_configuration", cliReason, 0)
        assertEquals("CLI AI Pro không hỗ trợ dịch câu mới.\nChỉ dùng cache • Mở app xem chi tiết.", failure.feedback(1))
        assertTrue(failure.status(1).contains(cliReason))
        val other = state.failed("B", "cli_configuration", "Mô hình không được phép truy cập.", 0)
        assertTrue(other.feedback(1).startsWith("Mô hình không được phép truy cập."))
    }

    @Test fun validServerRetryInfoOverridesFallbackExactlyForAllSources() {
        listOf(1L, 6000L, 25000L, 900000L).forEach { wait ->
            val state = TranslationRetryState()
            val failure = state.failed("A", "rate_limited", "Quota", 100, wait)
            assertEquals(100 + wait, failure.retryAt)
            assertSame(failure, state.waiting("B", 100 + wait - 1))
            assertNull(state.waiting("B", 100 + wait))
        }
    }

    @Test fun malformedServerWaitUsesFallbackAndExcessiveWaitIsBounded() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { wait ->
            val state = TranslationRetryState()
            assertEquals(15100L, state.failed("A", "quota_exceeded", "Quota", 100, wait).retryAt)
        }
        val state = TranslationRetryState()
        assertEquals(900100L, state.failed("A", "rate_limited", "Quota", 100, Long.MAX_VALUE).retryAt)
    }

    @Test fun serverWaitCannotMakeConfigurationFailuresAutomaticallyRetry() {
        val state = TranslationRetryState()
        val failure = state.failed("A", "no_api_key", "Missing API key", 100, 1000)
        assertNull(failure.retryAt)
        assertSame(failure, state.waiting("A", Long.MAX_VALUE))
    }

    @Test fun laterShorterErrorCannotShortenExistingProviderCooldown() {
        val state = TranslationRetryState()
        val first = state.failed("A", "rate_limited", "Wait for quota reset", 100, 20000)
        val later = state.failed("B", "rate_limited", "An older request failed", 200, 1000)
        assertSame(first, later)
        assertSame(first, state.waiting("C", 20099))
        assertNull(state.waiting("C", 20100))
    }

    @Test fun laterLongerErrorExtendsProviderCooldown() {
        val state = TranslationRetryState()
        state.failed("A", "rate_limited", "First wait", 100, 1000)
        val extended = state.failed("B", "rate_limited", "Later reset", 200, 20000)
        assertSame(extended, state.waiting("C", 20199))
        assertNull(state.waiting("C", 20200))
    }

    @Test fun explicitConfigurationRecoveryCannotBypassProviderCooldown() {
        val state = TranslationRetryState()
        state.failed("A", "no_api_key", "Missing key", 100)
        val quota = state.failed("B", "quota_exceeded", "Quota", 100, 20000)
        state.clearConfigurationFailures()
        assertSame(quota, state.waiting("A", 101))
        assertNull(state.waiting("A", 20100))
    }
}
