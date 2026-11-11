package com.gemini.ocr.mtprobe

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

/** A/B actual model context protocols on observed public inputs; never supplies current answers. */
class NativeMt60ContextDiagnosticTest {
    private fun terminated(text: String, mark: String): String {
        val trimmed = text.trim()
        return if (trimmed.last() in ".!?。！？…") trimmed else trimmed + mark
    }

    @Test fun realModelComparesRawPrefixSentenceBoundaryAndSourceOnly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMt60ContextDiagnostic") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench" && BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        val name = (args.getString("mtContextReport") ?: "mt60-context-native-v1.json").also {
            require(it.matches(Regex("mt60-context-native-[a-zA-Z0-9_-]{1,35}\\.json")))
        }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "verification")
        val reportFile = File(directory, name); check(!reportFile.exists())
        val bytes = instrumentation.context.assets.open("mt60-tokenizer/live-context-diagnostic-v1.json").use { it.readBytes() }
        require(bytes.size in 1..16000)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(sha == "160a2a8d734de08aba43c4fe081062b79c34d254cf267cb07baceb1e532f9e04")
        val fixture = JSONObject(bytes.toString(Charsets.UTF_8))
        require(fixture.getString("kind") == "PUBLIC_VIDEO_OBSERVED_INPUT_PAIRS_NOT_CURRENT_TARGET_ANSWERS")
        val inputs = fixture.getJSONArray("cases"); require(inputs.length() == 16)
        val samples = JSONArray()
        val report = JSONObject().put("kind", "REAL_NATIVE_CONTEXT_DIAGNOSTIC_NOT_VIDEO_ACCEPTANCE")
            .put("revision", "907cfd51a52aaaac6323fa8bd749aeb00020c2f3")
            .put("fixture_sha256", sha).put("api_requests", 0).put("translation_cache", false)
            .put("current_target_answers_supplied", false).put("product_selected", false)
            .put("completed", false).put("samples", samples)
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        var model: NativeMt60Probe? = null
        try {
            val codec = OrtxMt60TokenCodec.create(File(directory, "mt60-tokenizer/graphs"))
            model = NativeMt60Probe.create(context, File(directory, "mt60-model"), codec, 2)
            model.translate("请把窗户打开。")
            withTimeout(120000) {
                for (index in 0 until inputs.length()) {
                    val input = inputs.getJSONObject(index)
                    val previous = if (input.isNull("previous_zh")) null else Mt60DecodePolicy.AcceptedDialoguePair(
                        input.getString("previous_zh"), input.getString("previous_vi"))
                    for (protocol in listOf("source_only", "raw_forced_prefix", "terminated_forced_prefix")) {
                        val accepted = when (protocol) {
                            "source_only" -> null
                            "terminated_forced_prefix" -> previous?.let { Mt60DecodePolicy.AcceptedDialoguePair(
                                terminated(it.source, "。"), terminated(it.translation, ".")) }
                            else -> previous
                        }
                        val row = JSONObject().put("case_id", input.getString("case_id"))
                            .put("source_zh", input.getString("source_zh")).put("protocol", protocol)
                            .put("previous_source_zh", accepted?.source ?: JSONObject.NULL)
                            .put("previous_translation_vi", accepted?.translation ?: JSONObject.NULL)
                        try {
                            val output = model.translate(input.getString("source_zh"), accepted)
                            row.put("translation_vi", output.text).put("process_ms", output.processNs / 1_000_000.0)
                                .put("prefix_tokens", output.contextPrefixTokens)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { row.put("failure_type", error.javaClass.simpleName) }
                        samples.put(row); persist()
                    }
                }
            }
            report.put("completed", true)
        } finally {
            withContext(NonCancellable) { model?.release() }
            persist()
        }
        Unit
    }
}
