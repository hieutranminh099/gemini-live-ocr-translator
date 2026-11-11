package com.gemini.ocr.video

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoPrivateBatchCacheTest {
    @get:Rule val fixture = TemporaryFolder()
    private lateinit var context: Context
    @Before fun useShortOwnedFilesystemRoot() {
        // Robolectric's per-method filesDir can exceed Windows native SQLite's path limit.
        // A short test-owned directory keeps real disk reopen coverage; Android paths stay unchanged.
        val files = fixture.newFolder("files")
        val base = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        VideoPrivateBatchCache.clearRamForTest()
    }
    @After fun clearRamFixture() { VideoPrivateBatchCache.clearRamForTest() }
    private fun project(owner: String?): VideoProject {
        val p = VideoProject(UUID.randomUUID().toString(), "Fixture", "file:///fixture.mp4", 5000, 640, 360,
            cues = listOf(VideoCue("one", 0, 1000, "你好")))
        VideoStore(context).save(p)
        if (owner != null) File(VideoStore(context).folder(p.id), "batch-owner").writeText(owner)
        return p
    }
    @Test fun ramThenSqliteReuseStaysInsideOnePrivateOwnerAndContext() {
        val owner = BatchStore(context).create("Fixture", BatchKind.SERIES).id
        val p = project(owner); val request = VideoTranslationPlan.nextCloudBatch(p.cues, false)!!
        val cache = VideoPrivateBatchCache(context)
        cache.save(p, request, listOf("Xin chào"), "gemini-test")
        assertEquals(VideoPrivateBatchCache.Level.RAM, cache.lookup(p, request)!!.level)
        VideoPrivateBatchCache.clearRamForTest()
        assertEquals(VideoPrivateBatchCache.Level.SQLITE, cache.lookup(p, request)!!.level)
        assertEquals(listOf("Xin chào"), cache.lookup(project(owner), request)!!.translations)
        assertNull(cache.lookup(project(null), request))
        assertNull(cache.lookup(project(BatchStore(context).create("Other", BatchKind.SERIES).id), request))
        assertNull(cache.lookup(p.copy(profile = "document"), request))
        assertNull(cache.lookup(p.copy(glossary = "你=Ngài"), request))
        assertNull(cache.lookup(p, request.copy(videoContext = request.videoContext.replace("你好", "再见"))))
    }
}
