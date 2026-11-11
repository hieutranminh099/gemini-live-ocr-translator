package com.gemini.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val reset = object : ExternalResource() {
        override fun before() {
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            SessionState.running.value = false; SessionState.connected.value = false; SessionState.paused.value = false
            SessionState.status.value = "Sẵn sàng kết nối"
            SessionState.backend.value = TranslationBackendState()
            SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @After fun finishActivityBeforeScenarioCleanup() {
        SessionState.running.value = false
        // Deliver destruction while Robolectric's paused main looper is still test-owned.
        compose.activityRule.scenario.onActivity { it.finish() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun standaloneHomeDisplaysTwoSimplifiedModesAndStartsWithoutPc() {
        compose.onNodeWithTag("live_session_state").assertTextEquals("Sẵn sàng")
        screenshot("android-home.png")
        compose.onNodeWithText("Bắt đầu dịch").assertIsDisplayed()
        compose.onNodeWithText("Chế độ và ngữ cảnh").performScrollTo().performClick()
        compose.onNodeWithTag("mode_bank_only_card").performScrollTo().assertExists()
        compose.onNodeWithTag("mode_hybrid_gemini_card").performScrollTo().assertExists()
    }
    @Test fun translationBankTabDisplaysSelfLearningRecordsAndOcrPreCorrection() {
        compose.onNodeWithTag("tab_memory").performClick()
        compose.onNodeWithTag("bank_search_input").assertIsDisplayed()
    }
    @Test fun switchingBetweenMode1AndMode2UpdatesAppSettings() {
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        compose.runOnIdle {
            SessionState.connected.value = true
            SessionState.backend.value = TranslationBackendState("standalone", "gemini_direct", true)
        }
        compose.onNodeWithText("Chế độ và ngữ cảnh").performScrollTo().performClick()
        compose.onNodeWithTag("mode_bank_only_card").performScrollTo().performClick()
        org.junit.Assert.assertEquals("gemini_direct", settings.translationMode)
        compose.onNodeWithTag("selected_translation_mode_badge").assertTextContains("Chế độ 2", substring = true)
        compose.onNodeWithTag("mode_hybrid_gemini_card").performScrollTo().performClick()
        org.junit.Assert.assertEquals("hybrid_gemini", settings.translationMode)
        compose.onNodeWithTag("selected_translation_mode_badge").assertTextContains("Chế độ 1", substring = true)
    }
    @Test fun customizationScreenIsReachableWithoutConnection() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("live_subtitle_preview").assertExists()
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Vùng nhận diện").performScrollTo().assertIsDisplayed()
        screenshot("android-settings.png")
    }
    @Test fun oneFramePresetCanBeSelectedBeforeStartingCapture() {
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Dùng kiểu phụ đề 1 khung").performScrollTo().performClick()
        compose.onNodeWithText("Đã chọn · Phụ đề 1 khung").assertExists()
        compose.onNodeWithText("Chạm dịch 1 khung").performScrollTo().assertExists()
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        org.junit.Assert.assertTrue(settings.subtitleFocus)
        org.junit.Assert.assertEquals("cover", settings.overlay)
        org.junit.Assert.assertFalse(SessionState.running.value)
    }
    @Test fun recognizedChineseAndItsTranslationRemainReadableInSessionDetails() {
        compose.runOnIdle {
            SessionState.running.value = true
            SessionState.lastOcr.value = "亏你还是韩国人"
            SessionState.lastTranslation.value = "Thế mà cũng là người Hàn Quốc"
        }
        compose.onNodeWithText("Khung vừa đọc").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OCR: 亏你还是韩国人").assertExists()
        compose.onNodeWithText("Thế mà cũng là người Hàn Quốc").assertExists()
    }
    @Test fun automaticPresetKeepsLanguagePairingAndCustomRegion() {
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        val region = android.graphics.RectF(.2f, .8f, .9f, .95f)
        settings.language = "zh"; settings.host = "192.168.1.9:8765"; settings.subtitleRegion = region
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Tự động tăng tốc · Gemini").performScrollTo().performClick()
        compose.onNodeWithText("Đã chọn · Tự động tăng tốc").assertExists()
        org.junit.Assert.assertEquals("auto", settings.subtitleCapture)
        org.junit.Assert.assertEquals(150L, settings.interval)
        org.junit.Assert.assertEquals("fast", settings.mode)
        org.junit.Assert.assertEquals("zh", settings.language)
        org.junit.Assert.assertEquals("192.168.1.9:8765", settings.host)
        org.junit.Assert.assertEquals(region, settings.subtitleRegion)
        org.junit.Assert.assertFalse(SessionState.running.value)
    }
    @Test fun connectedBadgeChangesFromCacheOnlyToExperimentalWithoutRestart() {
        com.gemini.ocr.settings.AppSettings(compose.activity).connectionMode = "companion"
        compose.runOnIdle {
            SessionState.connected.value = true
            SessionState.backend.value = TranslationBackendState("cli", "cache_only", false)
        }
        compose.onNodeWithText("Cài đặt nâng cao").performScrollTo().performClick()
        compose.onNodeWithText("Chỉ dùng cache / từ điển • Chưa bật dịch câu mới").assertExists()
        compose.runOnIdle {
            SessionState.backend.value = TranslationBackendState("cli", "cli_experimental", true, true)
        }
        compose.onNodeWithText("CLI cá nhân thử nghiệm • Đã bật dịch câu mới").assertExists()
        compose.onNodeWithText("Chỉ dùng cache / từ điển • Chưa bật dịch câu mới").assertDoesNotExist()
    }
    @Test fun reviewedMemoryWritesAreDisabledWhenCaptureStarts() {
        compose.onNodeWithTag("tab_memory").performClick()
        compose.onNodeWithTag("reviewed_memory_tab").performClick()
        compose.onNodeWithTag("memory_film").performScrollTo().performTextInput("fictional-ui-film")
        compose.onNodeWithTag("memory_apply_film").performScrollTo().performClick()
        compose.onNodeWithTag("memory_no_history_scope").performScrollTo().performClick()
        compose.onNodeWithTag("memory_open_editor").performScrollTo().performClick()
        compose.onNodeWithTag("memory_source").performScrollTo().performTextInput("你好")
        compose.onNodeWithTag("memory_translation").performScrollTo().performTextInput("Chào bạn.")
        compose.onNodeWithText("Thông tin nguồn và điều kiện dùng lại").performScrollTo().performClick()
        compose.onNodeWithTag("memory_provenance").performScrollTo().performTextInput("user://test-fixture")
        compose.onNodeWithTag("memory_license").performScrollTo().performTextInput("owner-provided-test")
        compose.onNodeWithTag("memory_confidence").performScrollTo().performTextInput("1")
        compose.onNodeWithTag("memory_review_check").performScrollTo().performClick()
        compose.onNodeWithTag("memory_approve").performScrollTo().assertIsEnabled()
        compose.runOnIdle { SessionState.running.value = true }
        compose.onNodeWithTag("memory_approve").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("memory_save_draft").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Xong").performClick()
        compose.onNodeWithText("Nhập / xuất kho thoại").performScrollTo().performClick()
        compose.onNodeWithTag("memory_import_jsonl").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("memory_apply_film").performScrollTo().assertIsNotEnabled()
    }
    @Test fun subtitleDraftSurvivesTabSwitchAndSavesOnlyAfterConfirmation() {
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        compose.runOnIdle { settings.fontSize = 24; settings.opacity = .6f; settings.subtitleY = .5f }
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("live_font_slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(28f) }
        org.junit.Assert.assertEquals(24, settings.fontSize)
        compose.onNodeWithTag("tab_live").performClick()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Cỡ chữ · 28 sp").performScrollTo().assertExists()
        compose.onNodeWithTag("live_save_style").performScrollTo().performClick()
        org.junit.Assert.assertEquals(28, settings.fontSize)
        org.junit.Assert.assertEquals(.6f, settings.opacity, .001f)
        org.junit.Assert.assertEquals(.5f, settings.subtitleY, .001f)
        compose.onNodeWithText("Đã lưu cấu hình phụ đề.").assertExists()
    }
    @Test fun settingsRouteAndTabSelectionKeepTheMainNavigationConsistent() {
        compose.runOnIdle {
            // Test routing without invoking a platform lifecycle callback outside ActivityScenario.
            ReflectionHelpers.callInstanceMethod<Unit>(compose.activity, "routeIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent().putExtra("ux_tab", 3)))
        }
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("live_subtitle_preview").assertExists()
        // Platform Back is exercised by UiAutomator on Android, including returning to a video.
        compose.onNodeWithTag("tab_live").performClick()
        compose.onNodeWithTag("tab_live").assertIsSelected()
        compose.onNodeWithTag("primary_session_button").assertIsDisplayed()
    }
    @Test fun subtitleWritesStayDisabledWhileLiveCaptureIsRunning() {
        compose.runOnIdle { SessionState.running.value = true }
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("live_font_slider").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live_background").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live_outline").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live_save_style").performScrollTo().assertIsNotEnabled()
    }
    private fun screenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("gemini.test.screenshots")))
        directory.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
