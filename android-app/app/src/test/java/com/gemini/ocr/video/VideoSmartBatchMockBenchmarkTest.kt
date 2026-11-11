package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Planning/payload experiment only. No HTTP, decoder, model, quota or real latency claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoSmartBatchMockBenchmarkTest {
    @Test fun compareLegacyTwelveAndSelectableGroupSizesOnTheSameSyntheticTimeline() {
        val report = JSONObject().put("kind", "synthetic planning/payload benchmark; no network or translation quality measurement")
            .put("real_api_calls", 0).put("real_token_usage", JSONObject.NULL)
            .put("default_reason", "20 limits partial-response retry scope while amortizing request setup; not claimed fastest for a real provider")
            .put("concurrency", 1).put("concurrency_reason", "next request depends on accepted preceding-context translations")
        val rows = List(300) { VideoCue("cue-$it", it * 1200L, it * 1200L + 1100, "这是第${it}句用于测试的字幕内容不能漏字") }
        val experiments = JSONArray()
        for (videos in listOf(1, 3, 10)) for (size in listOf(12, 10, 20, 30, 50)) {
            var requests = 0; var payloadChars = 0L; val times = mutableListOf<Long>()
            repeat(videos) {
                var cues = rows
                while (true) {
                    val started = System.nanoTime()
                    val next = VideoTranslationPlan.nextCloudBatch(cues, false, maxCues = size) ?: break
                    val payload = VideoTranslationClient.buildRequest(next, "manga", "")
                    times += System.nanoTime() - started; requests++; payloadChars += payload.length
                    // A deterministic fake is used solely to advance the planner and preserve context.
                    cues = VideoTranslationPlan.applyCloudResult(cues, next, next.cueIds.map { "Bản mẫu $it" }, "mock", false)
                }
                assertTrue(cues.all { it.translation.isNotBlank() })
            }
            val sorted = times.sorted()
            experiments.put(JSONObject().put("videos", videos).put("group_size", size).put("modeled_requests", requests)
                .put("payload_characters", payloadChars).put("planner_payload_p50_us", sorted[sorted.size / 2] / 1000)
                .put("planner_payload_p95_us", sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)] / 1000)
                .put("missing_cues", 0).put("subtitle_rows", rows.size * videos))
        }
        report.put("experiments", experiments)
        val output = File(requireNotNull(System.getProperty("gemini.test.screenshots")), "smart-batch-mock-benchmark.json")
        output.parentFile!!.mkdirs(); output.writeText(report.toString(2))
        val baseline = (0 until experiments.length()).map { experiments.getJSONObject(it) }
        for (videos in listOf(1, 3, 10)) assertTrue(baseline.single { it.getInt("videos") == videos && it.getInt("group_size") == 20 }.getInt("modeled_requests") <
            baseline.single { it.getInt("videos") == videos && it.getInt("group_size") == 12 }.getInt("modeled_requests"))
    }
}
