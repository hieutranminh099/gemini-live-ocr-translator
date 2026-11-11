package com.gemini.ocr.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoExportCardTest {
    @get:Rule val compose=createComposeRule()
    private fun project()=VideoProject("11111111-2222-3333-4444-555555555555","video","file:///video.mp4",5000,1280,720,
        stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING,scanMs=5000,cues=listOf(VideoCue("one",0,1000,"你好","Xin chào.")))

    @Test fun partialSourceCannotBeMistakenForACompleteVideoEvenWhenAllKnownCuesAreTranslated() {
        val p=project().copy(stage=VideoStage.PAUSED,resumeStage=VideoStage.EXTRACTING,scanMs=1000)
        compose.setContent {MaterialTheme {VideoExportCard(p,true,false,false,false,
            onSrt={},onAss={},onRender={},onSaveMp4={},onChooseFolder={},onContinue={})}}
        compose.onNodeWithTag("video_download_srt").assertIsNotEnabled()
        compose.onNodeWithTag("video_render_mp4").assertIsNotEnabled()
        compose.onNodeWithTag("video_continue_missing").assertIsEnabled()
        assertTrue(VideoExportReadiness.from(p).sourcePending)
        assertFalse(VideoExportReadiness.from(p).complete)
    }

    @Test fun reviewWithMissingTranslationsKeepsDownloadsDisabledAndOffersContinue() {
        val p=project().copy(cues=listOf(project().cues.single(),VideoCue("two",2000,3000,"再见")))
        var continues=0;var downloads=0
        compose.setContent {MaterialTheme {VideoExportCard(p,true,false,false,true,
            onSrt={downloads++},onAss={downloads++},onRender={downloads++},onSaveMp4={downloads++},
            onChooseFolder={downloads++},onContinue={continues++})}}
        compose.onNodeWithTag("video_download_srt").assertIsNotEnabled()
        compose.onNodeWithTag("video_download_ass").assertIsNotEnabled()
        compose.onNodeWithTag("video_render_mp4").assertIsNotEnabled()
        compose.onNodeWithTag("video_continue_missing").performClick()
        assertEquals(1,continues);assertEquals(0,downloads)
        assertEquals(1,VideoExportReadiness.from(p).translated)
        assertFalse(VideoExportReadiness.from(p).complete)
        assertFalse(p.cues.first().approved)
    }

    @Test fun hybridDraftCanExportImmediatelyWhilePendingRefinementRemainsExplicit() {
        val p=project().copy(translationMode=VideoTranslationMode.HYBRID,
            cues=project().cues.map{it.copy(localDraft="Chào.",refinementState="pending")})
        var srt=0;var render=0;var refine=0
        compose.setContent {MaterialTheme {VideoExportCard(p,true,false,false,false,
            onSrt={srt++},onAss={},onRender={render++},onSaveMp4={},onChooseFolder={},onContinue={refine++})}}
        compose.onNodeWithTag("video_download_srt").assertIsEnabled().performClick()
        compose.onNodeWithTag("video_continue_refinement").assertIsEnabled().performClick()
        compose.onNodeWithTag("video_render_mp4").assertIsEnabled().performClick()
        assertEquals(1,srt);assertEquals(1,render);assertEquals(1,refine)
        assertEquals(1,VideoExportReadiness.from(p).pendingRefinements)
        assertEquals("pending",p.cues.single().refinementState)
        assertFalse(p.cues.single().approved)
    }

    @Test fun readyMp4HasProminentDownloadAndAlternativeFolderWithoutRenderingAgain() {
        val p=project();var save=0;var choose=0;var render=0
        compose.setContent {MaterialTheme {VideoExportCard(p,true,true,false,true,
            onSrt={},onAss={},onRender={render++},onSaveMp4={save++},onChooseFolder={choose++},onContinue={})}}
        compose.onNodeWithTag("video_save_mp4").assertIsDisplayed().performClick()
        compose.onNodeWithTag("video_save_mp4_choose_folder").performClick()
        assertEquals(1,save);assertEquals(1,choose);assertEquals(0,render)
        compose.onNodeWithText("Tệp được lưu vào Download/Gemini OCR. Sau khi lưu có nút mở tệp ngay.").assertIsDisplayed()
    }
}
