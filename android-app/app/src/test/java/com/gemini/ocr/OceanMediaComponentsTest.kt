package com.gemini.ocr

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.gemini.ocr.ui.ocean.OceanTheme
import com.gemini.ocr.ui.ocean.OceanWorkflowSteps
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OceanMediaComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableReviewAndExportKeepTheirExistingNavigationGuard() {
        var chosen = -1
        compose.setContent { OceanTheme {
            OceanWorkflowSteps(current = 1, onSelect = { chosen = it }, enabled = { it < 3 }, completed = setOf(0))
        } }
        compose.onNodeWithTag("workflow_step_1").assertIsSelected()
        compose.onNodeWithTag("workflow_step_3").assertIsNotEnabled()
        compose.onNodeWithTag("workflow_step_4").assertIsNotEnabled()
        compose.onNodeWithTag("workflow_step_2").performScrollTo().performClick()
        assertEquals(2, chosen)
    }

    @Test fun narrowLargeTextKeepsCurrentAndPreviousStepAccessible() {
        var chosen = -1
        compose.setContent { OceanTheme {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                OceanWorkflowSteps(current = 4, onSelect = { chosen = it }, enabled = { true }, completed = setOf(0, 1, 2, 3))
            }
        } }
        compose.onNodeWithTag("workflow_step_4").assertIsSelected()
        compose.onNodeWithTag("workflow_step_0").performScrollTo().performClick()
        assertEquals(0, chosen)
    }
}
