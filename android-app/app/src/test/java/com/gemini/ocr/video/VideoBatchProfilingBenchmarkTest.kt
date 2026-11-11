package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real staged worker/storage with synthetic sidecars and fake translations. No video or cloud. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchProfilingBenchmarkTest {
    @Test fun oneEpisode() = measure(1)
    @Test fun threeEpisodes() = measure(3)
    @Test fun fiveEpisodes() = measure(5)
    @Test fun tenEpisodes() = measure(10)

    private fun measure(episodeCount: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousLiveState = SessionState.running.value
        SessionState.running.value = false
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previousEncryptedKey = prefs.getString("standalone_api_key", null)
        val previousFallbackKey = prefs.getString("standalone_api_key_plain_fallback", null)
        prefs.edit()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        val store = VideoStore(context)
        val jobs = VideoJobs(context)
        val owner = UUID.randomUUID().toString()
        val count = 80
        val duration = count * 1200L
        val expected = List(count) { i -> VideoCue("sub-${i + 1}", i * 1200L, i * 1200L + 1100,
            "这是第${i + 1}句完整测试字幕") }
        val approved = expected.first().copy(translation = "Giữ bản đã duyệt.", approved = true,
            model = "user-edited", rawZh = expected.first().source, correctedZh = expected.first().source,
            draftVi = "Giữ bản đã duyệt.", polishedVi = "Giữ bản đã duyệt.")
        val subtitle = File(context.cacheDir, "synthetic-profiling-$owner.srt").apply {
            writeText(VideoTimeline.srt(expected.map { it.copy(translation = it.source) }, duration), Charsets.UTF_8)
        }
        // Use the product's document-provider path so host drive letters are never file URI paths.
        val subtitleUri = Uri.parse("content://batch-profile-fixture/${subtitle.name}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(subtitleUri) { subtitle.inputStream() }
        val projects = List(episodeCount) {
            store.save(VideoProject(UUID.randomUUID().toString(), "Synthetic profiling fixture", "file:///unused.mp4",
                duration, 640, 360, sourceMode = VideoSourceMode.SIDECAR, translationMode = VideoTranslationMode.HYBRID,
                subtitleUri = subtitleUri.toString(), subtitleName = subtitle.name,
                cues = listOf(approved), scanMs = 0, resumeStage = VideoStage.EXTRACTING))
        }
        val rows = JSONArray()
        val workerFailure = AtomicReference<Throwable?>(null)
        val previousFailureObserver = VideoJobs.workerFailureForTest
        val previousNetworkObserver = StandaloneTranslator.beforeNetworkCallForTest
        val previousResponse = VideoTranslationClient.overrideResponseForTest
        var requests = 0
        var network = 0
        VideoJobs.workerFailureForTest = { workerFailure.set(it) }
        StandaloneTranslator.beforeNetworkCallForTest = { network++; error("Network forbidden in profiling fixture") }
        VideoTranslationClient.overrideResponseForTest = { batch ->
            requests++
            assertFalse(batch.cueIds.contains(approved.id))
            assertTrue(batch.cueIds.size <= VideoHybridRecovery.MAX_TARGETS)
            JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    JSONObject().put("translations", JSONArray(batch.cueIds.map { id ->
                        JSONObject().put("id", id).put("translation", "Bản mẫu $id.")
                    })).toString())))))).toString()
        }
        // Android's compile classpath excludes JVM management APIs. The host JDK may expose them.
        val processCpuTime = hostCpuReader()
        val runtime = Runtime.getRuntime()
        val started = System.nanoTime()
        val cpuStarted = processCpuTime()
        jobs.reserveBatch(owner)
        try {
            // Stage-major ordering uses the same complete-source barrier as BatchJobs.
            for (stage in BatchPipelineStage.entries) for ((episode, project) in projects.withIndex()) {
                val at = System.nanoTime()
                val cpuAt = processCpuTime()
                val saved = withTimeout(30_000) { jobs.runStageForBatch(owner, project.id, stage) }
                assertTrue("${stage.name}: ${saved.message}\n${workerFailure.get()?.stackTraceToString().orEmpty()}",
                    VideoStagedPipeline.completed(saved, stage))
                assertEquals(approved, saved.cues.first())
                assertEquals(expected.map { listOf(it.id, it.startMs, it.endMs, it.source) },
                    saved.cues.map { listOf(it.id, it.startMs, it.endMs, it.source) })
                val row = JSONObject().put("episode", episode + 1).put("stage", stage.name)
                    .put("wall_ns", System.nanoTime() - at)
                    .put("jvm_process_cpu_ns", cpuDelta(cpuAt, processCpuTime()))
                    .put("jvm_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
                rows.put(row)
            }
            val finalProjects = projects.map { store.load(it.id) }
            // Each 79-row pending timeline is split into bounded requests.
            assertTrue(requests in episodeCount * 2..episodeCount * 10)
            assertEquals(0, network)
            assertTrue(finalProjects.all { it.cues.all { cue -> VideoTranslationPlan.isComplete(cue, true) } })
            assertTrue(finalProjects.all { it.exportedMp4.isBlank() })
            val timeline = finalProjects.joinToString("\n") { p -> p.cues.joinToString("\n") { cue ->
                listOf(cue.id, cue.startMs, cue.endMs, cue.source, cue.translation, cue.approved).joinToString("|")
            } }
            val digest = MessageDigest.getInstance("SHA-256").digest(timeline.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val metrics = finalProjects.map { p -> JSONObject(File(store.folder(p.id), "smart-batch-metrics.json").readText()) }
            val profiled = metrics.all { it.has("worker_wall_ms") }
            val report = JSONObject().put("scope", "JVM staged worker, real sidecar parsing/storage, fake translation; no decoder, OCR, network, Gemini, MP4 or device performance")
                .put("episodes", projects.size).put("cues_per_episode", count).put("concurrency", 1)
                .put("source_mode", "SIDECAR").put("translation_mode", "HYBRID")
                .put("wall_ns", System.nanoTime() - started)
                .put("jvm_process_cpu_ns", cpuDelta(cpuStarted, processCpuTime()))
                .put("fake_requests", requests).put("real_network_calls", network).put("real_token_usage", JSONObject.NULL)
                .put("missing_cues", 0).put("approved_rows_preserved", episodeCount).put("timeline_sha256", digest)
                .put("profiled", profiled).put("stages", rows).put("metrics", JSONArray(metrics))
            val output = File(requireNotNull(System.getProperty("gemini.test.screenshots")),
                "batch-worker-$episodeCount-episodes.json")
            output.parentFile!!.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
        } finally {
            jobs.releaseBatch(owner)
            VideoTranslationClient.overrideResponseForTest = previousResponse
            StandaloneTranslator.beforeNetworkCallForTest = previousNetworkObserver
            VideoJobs.workerFailureForTest = previousFailureObserver
            SessionState.running.value = previousLiveState
            prefs.edit().apply {
                if (previousEncryptedKey == null) remove("standalone_api_key")
                else putString("standalone_api_key", previousEncryptedKey)
                if (previousFallbackKey == null) remove("standalone_api_key_plain_fallback")
                else putString("standalone_api_key_plain_fallback", previousFallbackKey)
            }.commit()
            for (project in projects) {
                val folder = store.folder(project.id)
                folder.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
                folder.delete()
            }
            subtitle.delete()
        }
    }

    private fun hostCpuReader(): () -> Long? = runCatching {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = factory.getMethod("getOperatingSystemMXBean").invoke(null)
        val method = Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime")
        val read: () -> Long? = {
            runCatching { (method.invoke(bean) as Long).takeIf { it >= 0L } }.getOrNull()
        }
        read
    }.getOrDefault({ null })

    private fun cpuDelta(start: Long?, end: Long?): Any =
        if (start != null && end != null && end >= start) end - start else JSONObject.NULL
}
