package com.gemini.ocr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/**
 * Opt-in, at most twenty real Gemini requests using only the authored synthetic corpus.
 * This measures the translation call, not capture/OCR/Canvas. Quality remains unscored until review.
 * No key is accepted as a test argument or written to output; only the disposable app may use its key.
 */
@RunWith(AndroidJUnit4::class)
class NativeGeminiQualityTest {
    @Test fun twentyFreshSyntheticContextCasesWithRealGemini() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real requests require -e executeRealGemini true", args.getString("executeRealGemini") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench") {
            "Run only the disposable com.gemini.ocr.codexbench verification package"
        }
        require(BuildConfig.GEMINI_API_KEY.isEmpty()) { "Verification builds must not embed an API key" }

        val settings = AppSettings(context)
        val corpus = instrumentation.context.assets.open("translation_quality_zh_vi.json")
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val cases = corpus.getJSONArray("cases")
        require(cases.length() == 20) { "This test permits exactly the twenty authored cases" }
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val reportFile = File(directory, "gemini-quality.json")
        val runId = "quality-${SystemClock.elapsedRealtime()}"
        val rows = JSONArray()
        val durations = mutableListOf<Long>()
        var attempted = 0
        var succeeded = 0
        var requestErrors = 0
        var setupError: String? = null
        var completed = false
        var stoppedOnError = false
        val runStartedAt = SystemClock.elapsedRealtime()

        fun fixtureRow(item: JSONObject) = JSONObject()
            .put("id", item.getString("id"))
            .put("previous_zh", item.getString("previous_zh"))
            .put("previous_vi", item.getString("previous_vi"))
            .put("source_zh", item.getString("source_zh"))
            .put("reference_vi", item.getString("reference_vi"))
            .put("critical_meaning", item.getString("critical_meaning"))
            .put("reject_if", item.getString("reject_if"))
            .put("quality_review", "unscored")

        fun percentile(sorted: List<Long>, fraction: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
            else sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]

        fun writeReport() {
            val sorted = durations.sorted()
            val report = JSONObject()
                .put("kind", "REAL_GEMINI_SYNTHETIC_CONTEXT_REVIEW")
                .put("run_id", runId)
                .put("package", context.packageName)
                .put("latency_scope", "client translation call; excludes capture, native OCR, overlay and display")
                .put("quality_review", "unscored")
                .put("reference_provenance", corpus.getString("provenance"))
                .put("completed", completed)
                .put("setup_error", setupError ?: JSONObject.NULL)
                .put("stopped_on_first_request_error", stoppedOnError)
                .put("automatic_retries", 0)
                .put("planned_requests", cases.length())
                .put("attempted_requests", attempted)
                .put("successful_requests", succeeded)
                .put("request_errors", requestErrors)
                .put("not_attempted", cases.length() - attempted)
                .put("not_successful_of_planned", cases.length() - succeeded)
                .put("duration_ms", SystemClock.elapsedRealtime() - runStartedAt)
                .put("latency_summary", JSONObject()
                    .put("population", "successful uncached translation calls only; failures and unattempted retain the planned denominator above")
                    .put("n", sorted.size)
                    .put("p50_ms", percentile(sorted, .50))
                    .put("p95_ms", percentile(sorted, .95))
                    .put("max_ms", sorted.lastOrNull() ?: JSONObject.NULL)
                    .put("over_1000_ms", sorted.count { it > 1000L })
                    .put("planned_denominator", cases.length()))
                .put("cases", rows)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }

        try {
            setupError = when {
                SessionState.running.value -> "capture_session_still_running"
                StandaloneTranslator.overrideGeminiTranslator != null ||
                    StandaloneTranslator.overrideOnDeviceTranslator != null -> "translator_override_present"
                !StandaloneTranslator.hasApiKey(settings) -> "missing_api_key_in_disposable_app"
                else -> null
            }
            check(setupError == null) { "Verification setup incomplete; see sanitized gemini-quality.json" }

            for (index in 0 until cases.length()) {
                val item = cases.getJSONObject(index)
                val row = fixtureRow(item)
                val history = listOf(item.getString("previous_zh") to item.getString("previous_vi"))
                val namespace = "$runId:${item.getString("id")}:" +
                    StandaloneTranslator.cacheNamespace("manga", "", history)
                StandaloneTranslator.clearDirectCache() // Session RAM only; never clears preferences or bank.
                val startedAt = SystemClock.elapsedRealtime()
                attempted++
                try {
                    val outcome = withTimeout(15000L) {
                        StandaloneTranslator.translateStandaloneMode(
                            context = context,
                            texts = listOf(item.getString("source_zh")),
                            translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT,
                            apiKey = settings.effectiveStandaloneApiKey(),
                            glossaryRaw = "",
                            profile = "manga",
                            deferSideEffects = true,
                            cacheNamespace = namespace,
                            contextItems = history,
                            allowContextFreeBank = false,
                        )
                    }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    check(!outcome.fromBank && !outcome.provider.contains("LruCache", ignoreCase = true)) {
                        "Unexpected cached result in a fresh request"
                    }
                    check(outcome.translations.size == 1 && outcome.translations.single().isNotBlank()) {
                        "Unexpected translation shape"
                    }
                    row.put("status", "success")
                        .put("translation_vi", outcome.translations.single())
                        .put("call_ms", elapsed)
                        .put("outcome_ms", outcome.latencyMs)
                        .put("provider", outcome.provider)
                        .put("model", outcome.modelUsed)
                        .put("from_bank", outcome.fromBank)
                        .put("cached", false)
                    durations.add(elapsed)
                    succeeded++
                } catch (error: TimeoutCancellationException) {
                    row.put("status", "request_error").put("error", "request_timeout")
                        .put("call_ms", SystemClock.elapsedRealtime() - startedAt)
                    requestErrors++; stoppedOnError = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Provider exception text can contain request details. Keep only a type label.
                    row.put("status", "request_error").put("error", "request_failed")
                        .put("exception_type", error.javaClass.simpleName)
                        .put("call_ms", SystemClock.elapsedRealtime() - startedAt)
                    requestErrors++; stoppedOnError = true
                }
                rows.put(row)
                writeReport() // Preserve earlier results even if instrumentation is interrupted later.
                if (stoppedOnError) break
            }
            completed = succeeded == cases.length()
        } finally {
            for (index in rows.length() until cases.length()) {
                rows.put(fixtureRow(cases.getJSONObject(index))
                    .put("status", "not_attempted")
                    .put("reason", setupError ?: if (stoppedOnError) "stopped_after_request_error" else "run_interrupted"))
            }
            StandaloneTranslator.clearDirectCache()
            writeReport()
        }
        if (setupError != null) fail("Verification setup incomplete; see sanitized gemini-quality.json")
        if (!completed) fail("Real Gemini verification stopped on first error; see sanitized gemini-quality.json")
    }
}
