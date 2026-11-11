package com.gemini.ocr.uxlibrary

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.MemoryReviewScreen
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.ContextualTranslationMemory
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewedEditorStateTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val memory = ContextualTranslationMemory(context, databaseName = null)
    @After fun close() = runBlocking { memory.close(); SessionState.running.value = false }

    @Test fun editorAndSelectedScopeRestoreWithoutAutoApprovingOrWritingDraft() {
        val settings = AppSettings(context).apply { memoryFilmId = "ux-fixture-film"; memoryDomain = "modern" }
        SessionState.running.value = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                MemoryReviewScreen(false, settings, injectedMemory = memory, initiallyReviewed = true)
            } }
        }
        compose.onNodeWithTag("memory_no_history_scope").performScrollTo().performClick()
        compose.onNodeWithTag("memory_open_editor").performScrollTo().performClick()
        compose.onNodeWithTag("memory_source").performTextInput("你好")
        compose.onNodeWithTag("memory_translation").performTextInput("Xin chào")
        compose.onNodeWithTag("memory_approve").performScrollTo().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("memory_source").assertTextContains("你好")
        compose.onNodeWithTag("memory_translation").assertTextContains("Xin chào")
        compose.onNodeWithTag("memory_approve").performScrollTo().assertIsNotEnabled()
    }
}
