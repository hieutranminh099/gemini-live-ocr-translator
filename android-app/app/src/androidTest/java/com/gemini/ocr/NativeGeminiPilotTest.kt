package com.gemini.ocr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.network.HttpCallMetrics
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import kotlin.math.ceil

/** Fixed opt-in plans, one candidate model, no retries or automatic fallback. */
@RunWith(AndroidJUnit4::class)
class NativeGeminiPilotTest {
    private data class Probe(val item: JSONObject, val structured: Boolean, val compact: Boolean = false)
    private fun metric(value: HttpCallMetrics) = JSONObject()
        .put("phase", value.phase).put("total_ms", value.totalMs)
        .put("headers_ms", value.headersMs ?: JSONObject.NULL)
        .put("body_ms", value.bodyMs ?: JSONObject.NULL)
        .put("status_code", value.statusCode ?: JSONObject.NULL)
        .put("exception_type", value.exceptionType ?: JSONObject.NULL)

    @Test fun boundedCandidateCallsPreserveContextAndRecordTransportTiming() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeRealGemini") == "true")
        val profile = InstrumentationRegistry.getArguments().getString("pilotProfile") ?: "format_v1"
        require(profile in setOf("format_v1", "quantity_v2", "quality_v2", "remaining_v2", "legacy_v2", "compact_v3"))
        val quantityPilot = profile == "quantity_v2"
        val fullQualityReview = profile == "quality_v2"
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        check(StandaloneTranslator.overrideGeminiTranslator == null && StandaloneTranslator.overrideOnDeviceTranslator == null)
        val settings = AppSettings(context)
        check(StandaloneTranslator.hasApiKey(settings)) { "Missing key in the disposable app" }
        val corpus = instrumentation.context.assets.open("translation_quality_zh_vi.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("cases")
        }
        val cases = (0 until corpus.length()).associate { corpus.getJSONObject(it).getString("id") to corpus.getJSONObject(it) }
        require(cases.size == 20)
        val ids = when (profile) {
            "quantity_v2" -> listOf("repeated_number_ambiguity", "numeric_correction",
                "chinese_numeral_contrast", "target_number_order")
            "remaining_v2" -> listOf("ability_vs_permission", "name_replacement", "unfinished_clause")
            "legacy_v2" -> listOf("repeated_number_ambiguity", "negation_negative", "brother_praise", "ability_vs_permission")
            "compact_v3" -> listOf("repeated_number_ambiguity", "negation_negative", "brother_praise", "double_negation_explanation")
            else -> listOf("repeated_number_ambiguity", "negation_negative", "teacher_honorific", "friend_address")
        }
        val plan = if (profile == "compact_v3") (0..1).flatMap { round -> ids.mapIndexed { index, id ->
                Probe(checkNotNull(cases[id]), false, (index + round) % 2 == 0)
            } } else if (fullQualityReview) cases.values.map { Probe(it, false) } else
            if (profile != "format_v1") ids.map { Probe(checkNotNull(cases[it]), false) } else
            (0..1).flatMap { round -> ids.mapIndexed { index, id ->
                Probe(checkNotNull(cases[id]), (index + round) % 2 == 0)
            } }
        val model = if (profile == "legacy_v2") "gemini-2.5-flash-lite" else "gemini-3.5-flash-lite"
        val minimumStartSpacingMs = when (profile) { "format_v1" -> 0L; "compact_v3" -> 8000L; else -> 4000L }
        var nextDispatchAt = 0L
        val rows = JSONArray()
        var errors = 0
        var completed = false
        check(plan.size <= 20)
        val reportName = when (profile) {
            "quantity_v2" -> "gemini-quantity-pilot-v2.json"
            "quality_v2" -> "gemini-quality-candidate-v2.json"
            "remaining_v2" -> "gemini-quality-remaining-v2.json"
            "legacy_v2" -> "gemini-legacy-pilot-v2.json"
            "compact_v3" -> "gemini-compact-pilot-v3.json"
            else -> "gemini-pilot.json"
        }
        val output = File(context.getExternalFilesDir(null), "verification/$reportName")
        checkNotNull(output.parentFile).mkdirs()
        fun base(probe: Probe) = JSONObject().put("id", probe.item.getString("id"))
            .put("source_zh", probe.item.getString("source_zh"))
            .put("previous_zh", probe.item.getString("previous_zh"))
            .put("previous_vi", probe.item.getString("previous_vi"))
            .put("reference_vi", probe.item.getString("reference_vi"))
            .put("critical_meaning", probe.item.getString("critical_meaning"))
            .put("format", if (probe.structured) "json" else "plain")
            .put("prompt_style", if (probe.compact) "compact_en" else "baseline_vi")
            .put("model", model).put("quality_review", "unscored")
        fun persist() {
            val groups = JSONObject()
            for (format in listOf("json", "plain")) {
                val durations = (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { it.optString("status") == "success" && it.getString("format") == format }
                    .map { it.getLong("call_ms") }.sorted()
                fun percentile(p: Double): Any = if (durations.isEmpty()) JSONObject.NULL
                    else durations[(ceil(durations.size * p).toInt() - 1).coerceIn(0, durations.lastIndex)]
                groups.put(format, JSONObject().put("successful_n", durations.size)
                    .put("planned_n", plan.count { (if (it.structured) "json" else "plain") == format })
                    .put("p50_ms", percentile(.5)).put("p95_ms", percentile(.95)))
            }
            output.writeText(JSONObject().put("kind", "REAL_GEMINI_SMALL_CANDIDATE_PILOT")
                .put("latency_scope", "translation call only, excludes capture/OCR/Canvas")
                .put("model", model).put("planned_requests", plan.size).put("recorded", rows.length())
                .put("profile", profile)
                .put("minimum_experiment_request_start_spacing_ms", minimumStartSpacingMs)
                .put("request_errors", errors).put("completed", completed).put("automatic_retries", 0)
                .put("quality_review", "unscored").put("format_summaries", groups).put("cases", rows).toString(2))
        }
        try {
            for (probe in plan) {
                delay((nextDispatchAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                val row = base(probe)
                val metrics = Collections.synchronizedList(mutableListOf<HttpCallMetrics>())
                val started = SystemClock.elapsedRealtime()
                nextDispatchAt = started + minimumStartSpacingMs
                try {
                    val outcome = withTimeout(15000) {
                        StandaloneTranslator.translateWithGemini(apiKey = settings.effectiveStandaloneApiKey(),
                            texts = listOf(probe.item.getString("source_zh")), profile = "manga",
                            saveToSelfLearningBank = false, context = context, deferSideEffects = true,
                            contextItems = listOf(probe.item.getString("previous_zh") to probe.item.getString("previous_vi")),
                            requestModel = model, structuredOutput = probe.structured,
                            httpObserver = { metrics.add(it) }, singleHttpAttempt = true,
                            compactInstruction = probe.compact)
                    }
                    row.put("status", "success").put("translation_vi", outcome.translations.single())
                        .put("call_ms", SystemClock.elapsedRealtime() - started).put("outcome_ms", outcome.latencyMs)
                        .put("cached", false).put("corrected_sources", JSONArray(outcome.correctedSources))
                    outcome.generationMetrics?.let { usage ->
                        row.put("generation_metrics", JSONObject()
                            .put("input_tokens", usage.inputTokens ?: JSONObject.NULL)
                            .put("output_tokens", usage.outputTokens ?: JSONObject.NULL)
                            .put("thinking_tokens", usage.thinkingTokens ?: JSONObject.NULL)
                            .put("cached_input_tokens", usage.cachedInputTokens ?: JSONObject.NULL))
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    errors++; row.put("status", "request_error").put("exception_type", "TimeoutCancellationException")
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    errors++; row.put("status", "request_error").put("exception_type", error.javaClass.simpleName)
                }
                row.put("call_ms", SystemClock.elapsedRealtime() - started)
                    .put("http_metrics", JSONArray(synchronized(metrics) { metrics.map(::metric) }))
                rows.put(row); persist()
                if (errors > 0) break
            }
            completed = rows.length() == plan.size && errors == 0
        } finally {
            for (index in rows.length() until plan.size) rows.put(base(plan[index]).put("status", "not_attempted"))
            persist()
        }
        if (!completed) fail("Candidate pilot stopped on first error; see sanitized $reportName")
    }
}
