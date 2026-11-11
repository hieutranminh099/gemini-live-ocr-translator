package com.gemini.ocr.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.network.ContextualTranslationMemory
import com.gemini.ocr.network.ContextualMemoryRepository
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.video.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchReviewedMemoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun project(batchId: String): VideoProject {
        val id = UUID.randomUUID().toString(); File(VideoStore(context).folder(id), "batch-owner").writeText(batchId)
        return VideoProject(id, "Video", "file:///unused.mp4", 4000, 720, 1280,
            translationMode = VideoTranslationMode.GEMINI, cues = listOf(VideoCue("1", 0, 1000, "我有两本书"), VideoCue("2", 1000, 2000, "我没有两本书")))
    }
    @Test fun readsOnlyExactApprovedRowsWithinFrozenVideoScopeAndNeverAcceptsDraftOrChangedNegativeSource() = runBlocking {
        val store = BatchStore(context); val initial = store.create("Film", BatchKind.SERIES)
        val batch = store.save(initial.copy(useReviewedMemory = true, reviewedFilmId = "film-user-selected", reviewedDomain = "drama",
            reviewedProfile = "manga", reviewedGlossary = "", reviewedModel = StandaloneTranslator.cacheModelIdentity()))
        val p = project(batch.id); val request = checkNotNull(VideoTranslationPlan.nextCloudBatch(p.cues, false))
        val scope = checkNotNull(BatchReviewedMemory.scope(batch, p, request))
        val memory = ContextualTranslationMemory(context, databaseName = null)
        try {
            fun entry(source: String, translation: String) = ContextualTranslationMemory.Entry(scope, source, translation, "dialogue",
                ContextualTranslationMemory.Provenance("user://reviewed-video", "user-confirmed"), 1.0)
            memory.saveReviewed(entry("我有两本书", "Tôi có hai quyển sách."))
            memory.recordDraft(entry("我没有两本书", "Tôi không có hai quyển sách."))
            val matched = checkNotNull(BatchReviewedMemory(context, memory).lookup(p, request))
            assertEquals(listOf(0), matched.positions)
            assertEquals(listOf("Tôi có hai quyển sách."), matched.translations)
            val changed = request.copy(texts = listOf("我有三本书", "我没有两本书"))
            assertNull(BatchReviewedMemory(context, memory).lookup(p, changed))
            assertNull(BatchReviewedMemory.scope(batch.copy(useReviewedMemory = false), p, request))
            assertNull(BatchReviewedMemory.scope(batch, p.copy(glossary = "书=sổ"), request))
            assertNull(BatchReviewedMemory.scope(batch, p.copy(profile = "historical"), request))
            assertNotEquals(scope, BatchReviewedMemory.scope(batch.copy(reviewedFilmId = "other-film"), p, request))
            assertNotEquals(scope, BatchReviewedMemory.scope(batch, p, request.copy(videoContext = request.videoContext.replace("我有两本书", "我有三本书"))))
        } finally { memory.close() }
    }
    @Test fun optInFreezesSelectedFilmAndDoesNotAutomaticallyEnableUnselectedOrChangedLiveScope() = runBlocking {
        val settings = AppSettings(context); val jobs = BatchJobs(context, VideoJobs(context))
        val batch = jobs.create("Film", BatchKind.SERIES)
        settings.memoryFilmId = ""
        assertThrows(IllegalArgumentException::class.java) { runBlocking { jobs.useReviewedMemory(batch.id, true) } }
        settings.memoryFilmId = "selected-film"; settings.memoryDomain = "drama"; settings.profile = "manga"; settings.localGlossary = "军哥=anh Quân"
        val enabled = jobs.useReviewedMemory(batch.id, true)
        settings.memoryFilmId = "another-film"; settings.localGlossary = "军哥=người khác"
        val restored = BatchStore(context).load(batch.id)
        assertTrue(restored.useReviewedMemory)
        assertEquals("selected-film", restored.reviewedFilmId)
        assertEquals("军哥=anh Quân", restored.reviewedGlossary)
        assertEquals(enabled.reviewedModel, restored.reviewedModel)
        assertFalse(jobs.useReviewedMemory(batch.id, false).useReviewedMemory)
    }
    @Test fun explicitOptInReadsActualUiNoHistoryUserApprovedScopeButRejectsVerifiedDraftForeignFilmAndNegation() = runBlocking {
        val settings = AppSettings(context)
        settings.memoryFilmId = "selected-film"; settings.memoryDomain = "drama"; settings.profile = "manga"; settings.localGlossary = ""
        val store = BatchStore(context); val initial = store.create("Film", BatchKind.SERIES)
        val batch = store.save(initial.copy(useReviewedMemory = true, reviewedFilmId = settings.memoryFilmId,
            reviewedDomain = settings.memoryDomain, reviewedProfile = settings.profile, reviewedGlossary = settings.localGlossary,
            reviewedModel = StandaloneTranslator.cacheModelIdentity()))
        val p = project(batch.id); val request = checkNotNull(VideoTranslationPlan.nextCloudBatch(p.cues, false))
        // Exactly the code used by MemoryReviewScreen's memory_no_history_scope button.
        val namespace = StandaloneTranslator.cacheNamespace(settings.profile, settings.localGlossary, emptyList(), settings.memoryFilmId, settings.memoryDomain)
        val uiScope = checkNotNull(ContextualMemoryRepository.snapshotScope(settings, namespace))
        assertEquals(uiScope, BatchReviewedMemory.commonScope(batch, p, request))
        val memory = ContextualTranslationMemory(context, databaseName = null)
        try {
            fun entry(source: String, translation: String, scope: ContextualTranslationMemory.Scope = uiScope) =
                ContextualTranslationMemory.Entry(scope, source, translation, "dialogue",
                    ContextualTranslationMemory.Provenance("user://common-film", "user-confirmed"), 1.0)
            memory.saveReviewed(entry("我有两本书", "Tôi có hai quyển sách."), ContextualTranslationMemory.Quality.USER_APPROVED)
            memory.saveReviewed(entry("我没有两本书", "Tôi không có hai quyển sách."), ContextualTranslationMemory.Quality.VERIFIED)
            val helper = BatchReviewedMemory(context, memory)
            assertEquals(listOf(0), checkNotNull(helper.lookup(p, request)).positions)
            val changed = request.copy(texts = listOf("我有三本书", "我没有两本书"))
            assertNull(helper.lookup(p, changed))
            store.save(batch.copy(reviewedFilmId = "different-film"))
            assertNull(helper.lookup(p, request))
            store.save(batch)
            val draft = project(batch.id).copy(cues = listOf(VideoCue("draft", 0, 1000, "这是一幅画")))
            memory.recordDraft(entry("这是一幅画", "Đây là một bức tranh."))
            assertNull(helper.lookup(draft, checkNotNull(VideoTranslationPlan.nextCloudBatch(draft.cues, false))))
            val foreign = project(batch.id).copy(cues = listOf(VideoCue("foreign", 0, 1000, "今天很好")))
            memory.saveReviewed(entry("今天很好", "Hôm nay rất đẹp.", uiScope.copy(contextFingerprint = "f".repeat(64))))
            assertNull(helper.lookup(foreign, checkNotNull(VideoTranslationPlan.nextCloudBatch(foreign.cues, false))))
        } finally { memory.close() }
    }
}
