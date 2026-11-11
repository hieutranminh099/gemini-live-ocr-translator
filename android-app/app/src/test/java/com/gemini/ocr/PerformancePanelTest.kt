package com.gemini.ocr

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerformancePanelTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun show(telemetry: PerformanceTelemetry) {
        // Replace only this test activity's content; the panel can use an injected pure sampler.
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PerformancePanel(telemetry = telemetry)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun optInSwitchDoesNotStartSamplingAndUnknownValuesAreNotZero() {
        var calls = 0
        val telemetry = PerformanceTelemetry({ 0L }) { calls++; PerformanceProcessCounters() }
        show(telemetry)
        compose.onNodeWithTag("performance_enabled").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("performance_capture_fps").assertTextEquals("—")
        compose.onNodeWithTag("performance_ocr_fps").assertTextEquals("—")
        compose.onNodeWithTag("performance_canvas_fps").assertTextEquals("—")
        compose.onNodeWithTag("performance_cpu").assertTextEquals("—")
        compose.onNodeWithTag("performance_pss").assertTextEquals("—")
        compose.onNodeWithTag("performance_cache_reviewed_memory").assertTextEquals("Chưa có lượt tra cứu")
        compose.onNodeWithTag("performance_visible_source").assertTextEquals("Chưa có bản dịch được vẽ")
        assertEquals(0, calls)
        compose.onNodeWithTag("performance_enabled").performClick().assertIsOff()
    }

    @Test fun measuredFieldsKeepUnitsStagesCacheDenominatorAndTranslationSource() {
        var now = 0L; var cpu = 10L
        val telemetry = PerformanceTelemetry({ now }) { PerformanceProcessCounters(cpu, now, 2048) }
        telemetry.setEnabled(true); now = 1000L; telemetry.sampleIfDue()
        repeat(30) { telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED) }
        repeat(4) { telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED) }
        repeat(2) { telemetry.recordFrame(PerformanceFrameStage.CANVAS_DRAWN) }
        telemetry.recordCacheLookups(PerformanceCacheTier.REVIEWED_MEMORY, 3, 1)
        telemetry.recordVisibleTranslation(PerformanceTranslationSource.MIXED)
        now = 2000L; cpu += 1500L; telemetry.sampleIfDue()
        show(telemetry)
        compose.onNodeWithTag("performance_capture_fps").assertTextEquals("30.0")
        compose.onNodeWithTag("performance_ocr_fps").assertTextEquals("4.0")
        compose.onNodeWithTag("performance_canvas_fps").assertTextEquals("2.0")
        compose.onNodeWithTag("performance_cpu").assertTextEquals("150.0%")
        compose.onNodeWithTag("performance_pss").assertTextEquals("2.0 MiB")
        compose.onNodeWithTag("performance_cache_reviewed_memory")
            .assertTextEquals("75.0% • 3 trúng / 1 trượt • đơn vị: dòng nguồn")
        compose.onNodeWithTag("performance_cache_legacy_bank").assertTextEquals("Chưa có lượt tra cứu")
        compose.onNodeWithTag("performance_visible_source").assertTextEquals("Nhiều nguồn")
        compose.onNodeWithText("CPU: 100% = 1 lõi, có thể vượt 100%. PSS là mẫu RAM hiện tại, không phải peak.").assertExists()
    }

    @Test fun unknownOriginIsExplicitAndDisablingClearsDisplayedMeasurements() {
        var now = 0L
        val telemetry = PerformanceTelemetry({ now }) { PerformanceProcessCounters() }
        telemetry.setEnabled(true)
        telemetry.recordVisibleTranslation(PerformanceTranslationSource.UNKNOWN)
        now = 1000L; telemetry.sampleIfDue()
        show(telemetry)
        compose.onNodeWithTag("performance_visible_source").assertTextEquals("Chưa xác định")
        compose.onNodeWithTag("performance_enabled").performClick()
        compose.onNodeWithTag("performance_visible_source").assertTextEquals("Chưa có bản dịch được vẽ")
        compose.onNodeWithTag("performance_cpu").assertTextEquals("—")
    }
}
