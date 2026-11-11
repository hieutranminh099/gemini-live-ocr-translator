package com.gemini.ocr

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.settings.AppSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI contracts against the real Activity; service intents are recorded without running capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OceanLiveTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val customRegion = RectF(.12f, .71f, .88f, .93f)
    private val reset = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            AppSettings(context).apply {
                profile = "document"
                mode = "quality"
                fontSize = 26
                opacity = .6f
                interval = 640L
                subtitleCapture = "auto"
                subtitleRegion = customRegion
                region = "subtitle"
            }
            SessionState.running.value = false
            SessionState.paused.value = false
            SessionState.connected.value = false
            SessionState.standaloneActive.value = true
            SessionState.overlayVisible.value = false
            SessionState.status.value = "Sẵn sàng kết nối"
            SessionState.backend.value = TranslationBackendState()
            SessionState.lastOcr.value = ""
            SessionState.lastTranslation.value = ""
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @After fun finishBeforeScenarioCleanup() {
        SessionState.running.value = false
        compose.activityRule.scenario.onActivity { it.finish() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertCustomSettingsPreserved() {
        val settings = AppSettings(compose.activity)
        assertEquals("zh", settings.language)
        assertEquals("document", settings.profile)
        assertEquals("quality", settings.mode)
        assertEquals(26, settings.fontSize)
        assertEquals(.6f, settings.opacity, .001f)
        assertEquals(640L, settings.interval)
        assertEquals(customRegion, settings.subtitleRegion)
    }

    @Test fun openingLiveDoesNotApplyDesignDemoDefaults() {
        compose.onNodeWithTag("primary_session_button").assertIsDisplayed()
        assertCustomSettingsPreserved()
        assertEquals("auto", AppSettings(compose.activity).subtitleCapture)
        assertFalse(SessionState.running.value)
    }

    @Test fun scanModeChangesOnlyExistingCaptureChoice() {
        compose.onNodeWithTag("ocean_scan_frame").performScrollTo().performClick()
        assertEquals("frame", AppSettings(compose.activity).subtitleCapture)
        assertCustomSettingsPreserved()
        compose.onNodeWithTag("ocean_scan_auto").performScrollTo().performClick()
        assertEquals("auto", AppSettings(compose.activity).subtitleCapture)
        assertCustomSettingsPreserved()
        assertFalse(SessionState.running.value)
    }

    @Test fun liveSessionLocksConfigurationButKeepsRegionControlAvailable() {
        compose.runOnIdle { SessionState.running.value = true }
        compose.onNodeWithTag("ocean_scan_auto").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ocean_scan_frame").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live_select_region").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Dùng kiểu phụ đề 1 khung").performScrollTo().assertIsNotEnabled()
        assertCustomSettingsPreserved()
    }

    @Test fun choosingRecognitionRegionDoesNotReplaceCustomRoiOrStyle() {
        compose.onNodeWithTag("live_select_region").performScrollTo().performClick()
        compose.onNodeWithTag("ocean_roi_dialog").assertExists()
        compose.onNodeWithTag("ocean_roi_full").performClick()
        assertEquals("full", AppSettings(compose.activity).region)
        assertCustomSettingsPreserved()
        assertFalse(SessionState.running.value)
    }

    @Test fun disconnectedStateDoesNotReuseAnOldReadyBackend() {
        compose.runOnIdle {
            SessionState.backend.value = TranslationBackendState("api", "api", true)
            SessionState.connected.value = false
        }
        compose.onNodeWithTag("ocean_backend_status").assertTextContains("Chưa kết nối dịch", substring = true)
        compose.onNodeWithText("AI sẵn sàng", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun backendUnavailabilityAndUnknownAvailabilityRemainVisible() {
        compose.runOnIdle {
            SessionState.connected.value = true
            SessionState.backend.value = TranslationBackendState("api", "api", false)
        }
        compose.onNodeWithTag("ocean_backend_status").assertTextContains("Bộ dịch chưa sẵn sàng", substring = true)
        compose.runOnIdle { SessionState.backend.value = TranslationBackendState("api", "api", null) }
        compose.onNodeWithTag("ocean_backend_status").assertTextContains("Chưa rõ trạng thái bộ dịch", substring = true)
        compose.onNodeWithText("AI sẵn sàng", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun activeStandaloneSessionShowsItsBackendWithoutClaimingCloudReadiness() {
        val actualBackend = TranslationBackendState("standalone", "gemini_direct", true)
        compose.runOnIdle {
            // The standalone service does not establish a Companion connection. Its availability
            // field describes the service path and cannot prove that cloud authentication works.
            SessionState.connected.value = false
            SessionState.standaloneActive.value = true
            SessionState.backend.value = actualBackend
            SessionState.running.value = true
        }
        compose.onNodeWithTag("ocean_backend_status").assertTextEquals(actualBackend.badge)
        compose.onNodeWithText("Chưa kết nối dịch").assertDoesNotExist()
        compose.onNodeWithText("AI sẵn sàng", substring = true, ignoreCase = true).assertDoesNotExist()
        assertCustomSettingsPreserved()
    }

    @Test fun advancedRecognitionChoicesImmediatelyUpdateQuickControls() {
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Nửa dưới").performScrollTo().performClick()
        compose.onNodeWithText("Chạm dịch 1 khung").performScrollTo().performClick()
        assertEquals("dialogue", AppSettings(compose.activity).region)
        assertEquals("frame", AppSettings(compose.activity).subtitleCapture)
        // Scope to the quick card: the advanced chip intentionally repeats this label.
        compose.onNode(hasText("Nửa dưới") and hasAnyAncestor(hasTestTag("quick_roi_choice")))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ocean_scan_frame").assertIsSelected()
        compose.onNodeWithTag("ocean_scan_auto").assertIsNotSelected()
        compose.onNodeWithTag("tab_live").assertIsSelected()
        assertCustomSettingsPreserved()
    }

    @Test fun regionAndStopUseTheExistingServiceActions() {
        compose.runOnIdle { SessionState.running.value = true }
        compose.onNodeWithTag("live_select_region").performScrollTo().performClick()
        compose.runOnIdle {
            val selected = shadowOf(compose.activity).nextStartedService
            assertEquals("SELECT_REGION", selected.action)
            assertEquals(ScreenCaptureService::class.java.name, selected.component?.className)
        }
        compose.onNodeWithTag("live_stop").performScrollTo().performClick()
        compose.runOnIdle {
            val stopped = shadowOf(compose.activity).nextStartedService
            assertEquals("STOP", stopped.action)
            assertEquals(ScreenCaptureService::class.java.name, stopped.component?.className)
        }
    }

    @Test fun sessionButtonKeepsToggleActionAndOneFrameUsesCaptureAction() {
        compose.onNodeWithTag("ocean_scan_frame").performScrollTo().performClick()
        compose.runOnIdle { SessionState.running.value = true }
        compose.onNodeWithTag("primary_session_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("TOGGLE", shadowOf(compose.activity).nextStartedService.action) }
        compose.onNodeWithTag("live_capture_frame").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("CAPTURE_FRAME", shadowOf(compose.activity).nextStartedService.action) }
    }

    @Test fun settingsAndConnectionShortcutsKeepExistingSettingsReachable() {
        compose.onNodeWithTag("live_settings").performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("live_subtitle_preview").assertExists()
        compose.onNodeWithTag("tab_live").performClick()
        compose.onNodeWithTag("live_connection").performScrollTo().performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("standalone_api_key_input").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp-mdpi")
    fun landscapeKeepsPrimaryControlsAndExistingDestinationsReachable() {
        compose.onNodeWithTag("primary_session_button").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("live_subtitle_preview").assertExists()
        compose.onNodeWithTag("tab_live").performClick()
        compose.onNodeWithTag("ocean_scan_frame").performScrollTo().performClick()
        assertEquals("frame", AppSettings(compose.activity).subtitleCapture)
        assertCustomSettingsPreserved()
    }
}
