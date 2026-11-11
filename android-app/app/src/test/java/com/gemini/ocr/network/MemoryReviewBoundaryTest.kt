package com.gemini.ocr.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryReviewBoundaryTest {
    @After fun restore() { SessionState.running.value = false; ContextualMemoryRepository.clearCandidate() }
    @Test fun delayedDocumentCallbackCannotWriteAfterCaptureHasStarted() = runBlocking {
        var wrote = false
        SessionState.running.value = true
        try {
            MemoryReviewWriteGuard.run({ false }) { wrote = true }
            fail("A picker callback must recheck the actual capture state")
        } catch (_: IllegalStateException) { }
        assertFalse(wrote)
        SessionState.running.value = false
        MemoryReviewWriteGuard.run({ false }) { wrote = true }
        assertTrue(wrote)
    }
    @Test fun noHistoryScopeAndFrozenCandidateKeepTheServiceFilmNamespace() {
        val settings = AppSettings(ApplicationProvider.getApplicationContext<Context>()).apply {
            memoryFilmId = "fictional-film-a"; memoryDomain = "historical"; localGlossary = "师父=Sư phụ"
        }
        val namespace = StandaloneTranslator.cacheNamespace(settings.profile, settings.localGlossary,
            emptyList(), settings.memoryFilmId, settings.memoryDomain)
        val scope = ContextualMemoryRepository.snapshotScope(settings, namespace)!!
        val candidate = ContextualMemoryRepository.ReviewCandidate(scope, "师父", "Sư phụ", "request-a", "region-a", 1, "test-provider")
        ContextualMemoryRepository.publishCandidate(candidate)
        settings.memoryFilmId = "fictional-film-b"
        assertEquals(namespace, scope.contextFingerprint)
        assertEquals("fictional-film-a", ContextualMemoryRepository.candidate.value!!.scope.filmId)
        assertNotEquals(scope, ContextualMemoryRepository.snapshotScope(settings, namespace))
        settings.memoryFilmId = ""
        assertNull(ContextualMemoryRepository.snapshotScope(settings, namespace))
    }
}
