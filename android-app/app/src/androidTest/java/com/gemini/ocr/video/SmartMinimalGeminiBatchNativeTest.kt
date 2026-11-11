package com.gemini.ocr.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.batch.BatchJobs
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in: at most two real text requests on generated SRT; no OCR/export/account changes. */
@RunWith(AndroidJUnit4::class)
class SmartMinimalGeminiBatchNativeTest {
    @Test(timeout = 180_000L)
    fun twentyIdsKeepChineseTimingAndDraftStatusWithActualGemini(): Unit = runBlocking {
        assumeTrue("Explicit bounded Gemini opt-in required",
            InstrumentationRegistry.getArguments().getString("executeSmartGemini") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr" && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val jobs = VideoJobs.get(context)
        require(!SessionState.running.value && jobs.busy.value == null && jobs.batchBusy.value == null && BatchJobs.get(context).busy.value == null)
        require(StandaloneTranslator.overrideGeminiTranslator == null && StandaloneTranslator.overrideOnDeviceTranslator == null &&
            StandaloneTranslator.overrideMt60TranslatorForVerification == null && VideoTranslationClient.overrideResponseForTest == null)
        require(StandaloneTranslator.beforeNetworkCallForTest == null)
        val directory = File(context.getExternalFilesDir(null), "smart-minimal-gemini").apply { mkdirs() }
        val reportFile = File(directory, "smart-gemini-${UUID.randomUUID()}.json")
        val report = JSONObject().put("schema", 1).put("completed", false).put("maximumRealDispatches", 2)
            .put("scope", "Real official Gemini ID batch for 20 synthetic SRT cues over a known generated four-second video; no OCR, encode, export or Google sync")
            .put("semanticReview", "Actual Vietnamese must be reviewed against the synthetic expected meaning; exact wording is not asserted")
        if (!AppSettings(context).effectiveStandaloneApiKey().isNotBlank()) {
            report.put("status", "BLOCKED_NO_CONFIGURED_KEY").put("allowedNetworkDispatches", 0)
            reportFile.writeText(report.toString(2))
            assumeTrue("No configured key; zero requests made", false)
        }
        val settings = VideoSmartBatchSettings(context)
        val originalGroupSize = settings.groupSize
        val originalEconomy = settings.economyMode
        val allowed = AtomicInteger(); val blocked = AtomicInteger()
        val started = SystemClock.elapsedRealtime()
        var peakPss = 0; var projectId: String? = null
        fun sample() { val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); peakPss = maxOf(peakPss, memory.totalPss) }
        fun code(message: String) = Regex("\\((?:429|401|403|400)\\)|HTTP \\d{3}").findAll(message).map { it.value }.joinToString(" ").take(80)
        try {
            settings.groupSize = 20
            settings.economyMode = false
            val source = File(context.getExternalFilesDir(null), "stability-fixtures/chinese-4s.mp4")
            require(source.isFile && source.length() in 1..5_000_000L) { "Missing generated four-second fixture" }
            val sourceHash = sha256(source)
            val created = jobs.create(Uri.fromFile(source), "Synthetic Smart Gemini · 20 IDs")
            projectId = created.id
            require(created.durationMs in 3600L..4500L)
            val pairs = listOf(
                "你好，我叫小明。" to "Xin chào, tôi tên là Tiểu Minh.",
                "我没有两本书。" to "Tôi không có hai quyển sách.",
                "请不要关门。" to "Xin đừng đóng cửa.",
                "他已经回家了。" to "Anh ấy đã về nhà rồi.",
                "我们明天再见。" to "Ngày mai chúng ta gặp lại.",
                "这不是我的手机。" to "Đây không phải điện thoại của tôi.",
                "她买了三杯茶。" to "Cô ấy đã mua ba tách trà.",
                "现在是下午两点。" to "Bây giờ là hai giờ chiều.",
                "别把钥匙弄丢了。" to "Đừng làm mất chìa khóa.",
                "哥哥在门外等你。" to "Anh trai đang đợi bạn ngoài cửa.",
                "我只要一个苹果。" to "Tôi chỉ cần một quả táo.",
                "他从来没有来过这里。" to "Anh ấy chưa từng đến đây.",
                "这条路比那条近。" to "Con đường này gần hơn con đường kia.",
                "如果下雨就不要出去。" to "Nếu trời mưa thì đừng ra ngoài.",
                "请先洗手再吃饭。" to "Hãy rửa tay trước rồi mới ăn cơm.",
                "我已经等了十分钟。" to "Tôi đã đợi mười phút rồi.",
                "你不必向我道歉。" to "Bạn không cần phải xin lỗi tôi.",
                "他不是医生，是老师。" to "Anh ấy là giáo viên, không phải bác sĩ.",
                "我们还有五公里要走。" to "Chúng ta còn phải đi năm ki lô mét nữa.",
                "谢谢你没有放弃我。" to "Cảm ơn vì bạn đã không từ bỏ tôi."
            )
            fun time(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
            val subtitle = File(directory, "twenty-${created.id}.srt")
            subtitle.writeText(pairs.mapIndexed { i, pair -> "${i + 1}\n${time(i * 180L)} --> ${time(i * 180L + 150)}\n${pair.first}\n" }.joinToString("\n"))
            val expected = VideoSubtitles.parse(subtitle.name, subtitle.readBytes(), created.durationMs)
            assertEquals(20, expected.size)
            File(VideoStore(context).folder(created.id), "batch-owner").writeText(UUID.randomUUID().toString())
            jobs.save(created.copy(sourceMode = VideoSourceMode.SIDECAR, translationMode = VideoTranslationMode.GEMINI,
                subtitleUri = Uri.fromFile(subtitle).toString(), subtitleName = subtitle.name, glossary = "", profile = "manga"))
            report.put("projectId", created.id).put("durationMs", created.durationMs).put("sourceSha256", sourceHash)
                .put("groupSize", 20).put("privateAutoBackupBlocked", true)
            StandaloneTranslator.beforeNetworkCallForTest = {
                synchronized(allowed) {
                    if (allowed.get() >= 2) { blocked.incrementAndGet(); error("Fixed Smart Gemini request budget reached; no further dispatch") }
                    allowed.incrementAndGet()
                }
            }
            sample()
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", created.id)).use { scenario ->
                scenario.onActivity { jobs.start(created.id) }
                withTimeout(140_000L) { while (jobs.busy.value == created.id) { sample(); delay(100) } }
            }
            val saved = VideoStore(context).load(created.id)
            val numericMetrics = File(VideoStore(context).folder(created.id), "smart-batch-metrics.json")
            if (numericMetrics.isFile && numericMetrics.length() <= 16 * 1024) report.put("metrics", JSONObject(numericMetrics.readText()))
            report.put("stage", saved.stage.name).put("diagnostic", code(saved.message)).put("cloudAttemptCounter", saved.cloudCalls)
                .put("workerPeakPssKiB", saved.maxPssKiB).put("translationElapsedMs", saved.translationElapsedMs)
                .put("cues", JSONArray(saved.cues.mapIndexed { index, cue -> JSONObject().put("id", cue.id)
                    .put("source", cue.source).put("translation", cue.translation).put("startMs", cue.startMs).put("endMs", cue.endMs)
                    .put("approved", cue.approved).put("expectedMeaning", pairs.getOrNull(index)?.second.orEmpty()) }))
            if (saved.stage != VideoStage.REVIEW && code(saved.message).contains("429")) {
                report.put("status", "BLOCKED_QUOTA")
                assumeTrue("Provider quota blocked bounded real verification; checkpoint retained", false)
            }
            if (saved.stage != VideoStage.REVIEW && Regex("401|403|400").containsMatchIn(code(saved.message))) {
                report.put("status", "BLOCKED_AUTH_OR_MODEL_CONFIGURATION")
                assumeTrue("Existing provider configuration blocked verification; no auth changes made", false)
            }
            assertEquals("Pipeline failed; safe code=${code(saved.message)}", VideoStage.REVIEW, saved.stage)
            assertEquals(expected.map { it.id }, saved.cues.map { it.id })
            assertEquals(expected.map { it.source }, saved.cues.map { it.source })
            assertEquals(expected.map { it.startMs to it.endMs }, saved.cues.map { it.startMs to it.endMs })
            assertTrue(saved.cues.all { it.translation.isNotBlank() && !it.approved })
            assertTrue(allowed.get() in 1..2); assertEquals(0, blocked.get())
            assertEquals(sourceHash, sha256(source))
            report.put("sourcePreserved", true).put("completed", true).put("status", "PASS_REQUIRES_SEMANTIC_REVIEW")
        } catch (error: Throwable) {
            if (!report.optString("status").startsWith("BLOCKED")) report.put("status", "FAIL").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            projectId?.let { id -> if (jobs.busy.value == id) {
                jobs.pause(); withTimeoutOrNull(10_000L) { while (jobs.busy.value == id) delay(100) }
            } }
            StandaloneTranslator.beforeNetworkCallForTest = null
            settings.groupSize = originalGroupSize
            settings.economyMode = originalEconomy
            sample()
            report.put("allowedNetworkDispatches", allowed.get()).put("blockedExtraDispatches", blocked.get())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("sampledPeakPssKiB", peakPss)
                .put("groupSizeRestored", settings.groupSize == originalGroupSize)
            reportFile.writeText(report.toString(2))
        }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
