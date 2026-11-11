package com.gemini.ocr.video

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchStore
import com.gemini.ocr.batch.BatchTranslationCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual Android SQLite persistence; generated strings only, no network or media processing. */
@RunWith(AndroidJUnit4::class)
class VideoSmartCacheNativeTest {
    @Test(timeout = 30_000L)
    fun privateRamSqliteLegacyAndCooldownSurviveReconstruction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeSmartCache") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        require(!SessionState.running.value && VideoJobs.get(context).busy.value == null && BatchJobs.get(context).busy.value == null)
        val owner = BatchStore(context).create("Synthetic cache fixture", BatchKind.SERIES).id
        fun project(batchId: String?): VideoProject {
            val p = VideoStore(context).save(VideoProject(UUID.randomUUID().toString(), "Synthetic", "file:///not-read.mp4", 5000, 640, 360,
                cues = listOf(VideoCue("cue", 0, 1000, "你好"))))
            if (batchId != null) File(VideoStore(context).folder(p.id), "batch-owner").writeText(batchId)
            return p
        }
        val p = project(owner); val request = VideoTranslationPlan.nextCloudBatch(p.cues, false)!!
        val cache = VideoPrivateBatchCache(context)
        cache.save(p, request, listOf("Xin chào"), "fixture-model")
        assertEquals(VideoPrivateBatchCache.Level.RAM, cache.lookup(p, request)!!.level)
        VideoPrivateBatchCache.clearRamForTest()
        assertEquals(VideoPrivateBatchCache.Level.SQLITE, VideoPrivateBatchCache(context).lookup(p, request)!!.level)
        assertNull(cache.lookup(project(null), request))
        assertNull(cache.lookup(project(BatchStore(context).create("Other", BatchKind.SERIES).id), request))
        assertNull(cache.lookup(p.copy(profile = "document"), request))
        val legacyProject = project(owner).copy(cues = listOf(VideoCue("legacy", 0, 1000, "再见")))
        val legacyRequest = VideoTranslationPlan.nextCloudBatch(legacyProject.cues, false)!!
        BatchTranslationCache(context).save(legacyProject, legacyRequest, listOf("Hẹn gặp lại"), "fixture-model")
        assertEquals(VideoPrivateBatchCache.Level.LEGACY_JSON, cache.lookup(legacyProject, legacyRequest)!!.level)
        VideoPrivateBatchCache.clearRamForTest()
        assertEquals(VideoPrivateBatchCache.Level.SQLITE, VideoPrivateBatchCache(context).lookup(legacyProject, legacyRequest)!!.level)
        val legacyDirectory = File(BatchStore(context).folder(owner), "translation-cache")
        assertTrue(legacyDirectory.listFiles().orEmpty().any { it.extension == "json" })
        val telemetry = VideoBatchTelemetry(context, p.id)
        telemetry.quotaFailure()
        try { VideoBatchTelemetry(context, p.id).requireQuotaReady(); fail("Reconstructed quota wait must block") }
        catch (_: VideoQuotaWaitException) { }
        val output = File(context.getExternalFilesDir(null), "smart-benchmark/smart-cache-native.json")
        output.parentFile!!.mkdirs()
        output.writeText(JSONObject().put("completed", true).put("cloudCalls", 0)
            .put("actualAndroidSqliteReopen", true).put("privateOwnerAndProfileIsolation", true)
            .put("legacyJsonPreservedAndImported", true).put("quotaDeadlineReconstructed", true).toString(2))
    }
}
