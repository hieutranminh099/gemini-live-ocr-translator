package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** A synthetic provider verifies local cache admission, never real cloud translation or quality. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchVideoCacheIntegrationTest {
    @Test fun contextualDuplicateInAnotherBatchVideoUsesSavedResultWhileNewNegativeSentenceStillDispatches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previousEncrypted = preferences.getString("standalone_api_key", null)
        val previousFallback = preferences.getString("standalone_api_key_plain_fallback", null)
        val previousOverride = StandaloneTranslator.overrideGeminiTranslator
        val owner = UUID.randomUUID().toString(); val store = VideoStore(context); val jobs = VideoJobs(context)
        SessionState.running.value = false
        var calls = 0
        fun project(source: String): VideoProject {
            val id = UUID.randomUUID().toString(); File(store.folder(id), "batch-owner").writeText(owner)
            return store.save(VideoProject(id, "Cache test", "file:///unused-source.mp4", 2000, 720, 1280,
                translationMode = VideoTranslationMode.GEMINI, stage = VideoStage.PAUSED,
                resumeStage = VideoStage.TRANSLATING, scanMs = 2000, cues = listOf(VideoCue("cue", 0, 1000, source))))
        }
        try {
            // Robolectric has no Android Keystore. Seed only this synthetic test app's existing
            // unreadable-ciphertext fallback seam so VideoJobs passes its configured-key gate.
            preferences.edit().putString("standalone_api_key", "synthetic-invalid-ciphertext")
                .putString("standalone_api_key_plain_fallback", "synthetic-test-only").commit()
            assertEquals("synthetic-test-only", settings.effectiveStandaloneApiKey())
            StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
                calls++
                texts.map { if (it == "我有两本书") "Tôi có hai quyển sách." else "Tôi không có hai quyển sách." }
            }
            val first = project("我有两本书"); val duplicate = project("我有两本书"); val negative = project("我没有两本书")
            jobs.reserveBatch(owner)
            val a = withTimeout(15_000) { jobs.runForBatch(owner, first.id, false) }
            val b = withTimeout(15_000) { jobs.runForBatch(owner, duplicate.id, false) }
            val c = withTimeout(15_000) { jobs.runForBatch(owner, negative.id, false) }
            assertEquals(a.message, VideoStage.REVIEW, a.stage); assertEquals(b.message, VideoStage.REVIEW, b.stage); assertEquals(c.message, VideoStage.REVIEW, c.stage)
            assertEquals(2, calls)
            assertEquals(1, a.cloudCalls); assertEquals(0, b.cloudCalls); assertEquals(1, c.cloudCalls)
            assertEquals(a.cues.single().translation, b.cues.single().translation)
            assertNotEquals(b.cues.single().translation, c.cues.single().translation)
            assertTrue(listOf(a, b, c).all { !it.cues.single().approved })
            assertEquals(listOf(first.cues.single().source, duplicate.cues.single().source, negative.cues.single().source),
                listOf(a, b, c).map { it.cues.single().source })
        } finally {
            jobs.releaseBatch(owner)
            StandaloneTranslator.overrideGeminiTranslator = previousOverride
            preferences.edit().apply {
                if (previousEncrypted == null) remove("standalone_api_key") else putString("standalone_api_key", previousEncrypted)
                if (previousFallback == null) remove("standalone_api_key_plain_fallback") else putString("standalone_api_key_plain_fallback", previousFallback)
            }.commit()
        }
    }
}
