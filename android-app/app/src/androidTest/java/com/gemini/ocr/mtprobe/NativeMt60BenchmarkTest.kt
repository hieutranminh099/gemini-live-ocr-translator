package com.gemini.ocr.mtprobe

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/** Runs the real model; these source-only diagnostics do not replace a live-video acceptance test. */
class NativeMt60BenchmarkTest {
    private fun memory(): JSONObject = JSONObject().put("pss_kib_snapshot_not_peak",
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss)

    @Test fun authorizedRealNativeModelMeasuresFreshDecodingAndFrozenContext() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMt60Benchmark") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        val threads = (args.getString("mtThreads")?.toIntOrNull() ?: 2).also { require(it in 1..4) }
        val allowSpinning = (args.getString("mtSpinning") ?: "true").toBooleanStrict()
        val name = (args.getString("mtReport") ?: "mt60-native-v1.json").also {
            require(it.matches(Regex("mt60-native-[a-zA-Z0-9_-]{1,40}\\.json")))
        }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "verification")
        val reportFile = File(directory, name); check(!reportFile.exists())
        val fixtureBytes = instrumentation.context.assets.open("mt60-tokenizer/benchmark-source-v1.json").use { it.readBytes() }
        require(fixtureBytes.size in 1..16000)
        val fixtureHash = MessageDigest.getInstance("SHA-256").digest(fixtureBytes).joinToString("") { "%02x".format(it) }
        require(fixtureHash == "5575b20f67a0b104fbb43f7bb6fdd3ff925be69229790a7bdf35936793704735")
        val fixture = JSONObject(fixtureBytes.toString(Charsets.UTF_8))
        require(fixture.getString("kind") == "PUBLIC_AND_AUTHORED_MT60_NATIVE_DIAGNOSTIC_INPUTS_NOT_TRANSLATION_LOOKUP")
        val cases = fixture.getJSONArray("cases"); require(cases.length() == 39)
        val samples = JSONArray()
        val report = JSONObject().put("kind", "REAL_ANDROID_NATIVE_MT60_MICROBENCHMARK_NOT_VIDEO_E2E")
            .put("model_export", "yennguyen45/moxhimt-60-web")
            .put("revision", "907cfd51a52aaaac6323fa8bd749aeb00020c2f3")
            .put("model_license_resolved_for_distribution", false).put("product_selected", false)
            .put("threads", threads).put("api_requests", 0).put("translation_cache", false)
            .put("ort_thread_spinning", allowSpinning)
            .put("source_token_limit", 192).put("generated_token_limit", 128)
            .put("context", "one explicit accepted previous pair; no current answer supplied")
            .put("fixture_sha256", fixtureHash).put("samples", samples).put("completed", false)
            .put("before_load", memory())
        fun persist() {
            val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
            val values = rows.filter { it.has("process_ms") }.map { it.getDouble("process_ms") }.sorted()
            report.put("model_calls", rows.size).put("successful_calls", values.size)
                .put("failed_calls", rows.size - values.size)
                .put("process_p50_ms", if (values.isEmpty()) JSONObject.NULL else values[ceil(values.size * .5).toInt() - 1])
                .put("process_p95_ms", if (values.isEmpty()) JSONObject.NULL else values[ceil(values.size * .95).toInt() - 1])
            directory.mkdirs(); reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        var model: NativeMt60Probe? = null
        try {
            val load = SystemClock.elapsedRealtimeNanos()
            val codec = OrtxMt60TokenCodec.create(File(directory, "mt60-tokenizer/graphs"))
            model = NativeMt60Probe.create(context, File(directory, "mt60-model"), codec, threads, allowSpinning)
            report.put("all_prepare_ms", (SystemClock.elapsedRealtimeNanos() - load) / 1_000_000.0)
                .put("artifact_verification_ms", model.prepareMetrics.artifactVerificationNs / 1_000_000.0)
                .put("model_sessions_load_ms", model.prepareMetrics.modelSessionsLoadNs / 1_000_000.0)
                .put("after_load", memory())
            val warm = model.translate("请把窗户打开。")
            report.put("separate_warmup", JSONObject().put("source_zh", "请把窗户打开。")
                .put("translation_vi", warm.text).put("process_ms", warm.processNs / 1_000_000.0))
            persist()
            withTimeout(120000) {
                for (index in 0 until cases.length()) {
                    val input = cases.getJSONObject(index)
                    val row = JSONObject().put("case_id", input.getString("case_id"))
                        .put("source_zh", input.getString("source_zh")).put("kind", input.getString("kind"))
                    val previous = if (input.has("previous_zh")) Mt60DecodePolicy.AcceptedDialoguePair(
                        input.getString("previous_zh"), input.getString("previous_vi")) else null
                    try {
                        val output = model.translate(input.getString("source_zh"), previous)
                        row.put("translation_vi", output.text).put("input_tokens", output.inputTokens)
                            .put("prefix_tokens", output.contextPrefixTokens).put("output_tokens_including_eos", output.outputTokensIncludingEos)
                            .put("source_unknown_tokens", output.sourceUnknownTokens).put("process_ms", output.processNs / 1_000_000.0)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { row.put("failure_type", failure.javaClass.simpleName) }
                    samples.put(row); persist()
                }
            }
            report.put("completed", true).put("after_cases", memory())
        } finally {
            withContext(NonCancellable) { model?.release() }
            report.put("after_release", memory()); persist()
        }
        Unit
    }
}
