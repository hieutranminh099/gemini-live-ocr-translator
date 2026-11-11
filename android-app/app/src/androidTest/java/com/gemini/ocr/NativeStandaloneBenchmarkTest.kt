package com.gemini.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.gemini.ocr.capture.PipelineTrace
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.ChineseDialogueBank
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.text.Normalizer

/**
 * Native capture/OCR/Canvas measurement. The default is a controlled fake translator.
 * Real Gemini requires the explicit instrumentation argument realGemini=true and a key entered by
 * the owner into the disposable app. Real mode preserves preferences and permits one POST per cue.
 * Local research uses realLocal=true and an owner-started loopback model, never a fixture lookup.
 * All modes deliberately refuse the user's original installed package.
 */
@RunWith(AndroidJUnit4::class)
class NativeStandaloneBenchmarkTest {
    private data class Cue(val source: String, val fakeTranslation: String)
    private val cues = listOf(
        "关键时候掉链子", "还得是我兄弟", "今天我们先去车站", "明天早上不要迟到",
        "这件事情我还没答应", "请把窗户打开一点", "他已经等了很长时间", "你先回去我随后就到",
        "这个办法恐怕不行", "别把钥匙忘在屋里", "他们刚才往东边走了", "我以为你知道这件事",
        "等雨停了我们再出发", "请问附近有没有药店", "有些话现在还不能说", "这回总算赶上末班车",
    ).mapIndexed { index, source -> Cue(source, "Mẫu giả ${index + 1}: đã nhận đúng câu nguồn") }

    private fun rawNormal(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\p{Z}]+"), "")

    private fun scriptNormal(text: String): String = ChineseDialogueBank.normalizeTraditional(rawNormal(text))
        // These genuine script variants are missing from the production table. Test-oracle only;
        // neither changes an OCR result nor repairs 侯/候, 夭/天, 靖/请, 设/没, or 超/赶 substitutions.
        .replace('戶', '户').replace('鍵', '键')

    private fun characterErrors(reference: String, prediction: String): Int {
        val expected = reference.codePoints().toArray()
        val actual = prediction.codePoints().toArray()
        var previous = IntArray(actual.size + 1) { it }
        for (left in expected.indices) {
            val current = IntArray(actual.size + 1)
            current[0] = left + 1
            for (right in actual.indices) current[right + 1] = minOf(
                current[right] + 1, previous[right + 1] + 1,
                previous[right] + if (expected[left] == actual[right]) 0 else 1,
            )
            previous = current
        }
        return previous[actual.size]
    }

    @Test fun freshChineseCuesReachNativeOverlayWithControlledFakeLatency() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench") {
            "Use -PverificationAppId=com.gemini.ocr.codexbench; never run this against the user's app"
        }
        require(BuildConfig.GEMINI_API_KEY.isEmpty()) { "Verification build must not embed an API key" }
        val device = UiDevice.getInstance(instrumentation)
        val runStartedAt = SystemClock.elapsedRealtime()
        val runId = "codex-native-$runStartedAt"
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val args = InstrumentationRegistry.getArguments()
        val realGemini = args.getString("realGemini") == "true"
        val realLocal = args.getString("realLocal") == "true"
        require(!(realGemini && realLocal)) { "Choose exactly one real provider for research" }
        val realProvider = realGemini || realLocal
        val manualCapture = if (realProvider) args.getString("realCaptureMode") != "auto"
            else args.getString("fakeCaptureMode") == "frame"
        val cues = this@NativeStandaloneBenchmarkTest.cues.take(
            (args.getString("cueLimit")?.toIntOrNull() ?: 16).also { require(it in 1..16) })
        val fastDownscale = args.getString("fastDownscale") != "false"
        val sourceFontSp = args.getString("sourceFontSp")?.toFloatOrNull() ?: 30f
        val sourceBaseline = args.getString("sourceBaseline")?.toFloatOrNull() ?: .80f
        val roiTop = args.getString("roiTop")?.toFloatOrNull() ?: .67f
        val roiBottom = args.getString("roiBottom")?.toFloatOrNull() ?: .94f
        val trackSourceRow = args.getString("trackSourceRow") == "true"
        val movingBackground = args.getString("movingBackground") == "true"
        val includeHardcodedVietnamese = args.getString("includeHardcodedVietnamese") != "false"
        require(sourceFontSp.isFinite() && sourceFontSp in 12f..60f) { "sourceFontSp must be 12..60" }
        require(sourceBaseline.isFinite() && sourceBaseline in .5f.. .9f) { "sourceBaseline must be .5.. .9" }
        require(roiTop.isFinite() && roiBottom.isFinite() && roiTop >= 0f && roiTop < roiBottom && roiBottom <= 1f) {
            "ROI must satisfy 0 <= roiTop < roiBottom <= 1"
        }
        val reportStem = when { realLocal -> "standalone-native-local"; realGemini -> "standalone-native-real"; else -> "standalone-native-fake" }
        val reportFile = File(directory, "$reportStem.json")
        val traceFile = File(directory, "$reportStem-trace.jsonl")
        val fakeDelayMs = (args.getString("fakeDelayMs")?.toLongOrNull() ?: 250L).coerceIn(0, 1000)
        val cueTimeoutMs = (args.getString("cueTimeoutMs")?.toLongOrNull() ?: if (realProvider) 12000L else 4000L)
            .coerceIn(1500, if (realProvider) 14000 else 6000)
        val fakeCalls = AtomicInteger()
        val activeCue = AtomicInteger(-1)
        val dispatchedCue = AtomicInteger(-1)
        val realDispatches = AtomicInteger()
        val localDispatches = AtomicInteger()
        val deniedDispatches = AtomicInteger()
        val inactiveDispatches = AtomicInteger()
        val unrecognizedInputs = AtomicInteger()
        val scriptEquivalentInputs = AtomicInteger()
        val providerRows = Collections.synchronizedList(mutableListOf<JSONObject>())
        val rows = JSONArray()
        var completed = false
        var setupFailure: String? = null
        var setupPhase = "initializing"
        var appRequestCount = 0
        var nextCueAt = 0L
        var measurementStartedAt = runStartedAt
        val settings = AppSettings(context)
        // Save only the non-sensitive settings this test changes. Never enumerate preferences.
        val originalRegion = settings.region
        val originalCapture = settings.subtitleCapture
        val originalMode = settings.translationMode
        val originalConnection = settings.connectionMode
        val originalFullScreen = settings.fullScreen
        val originalInterval = settings.interval
        val originalRoi = settings.subtitleRegion
        val originalTracker = settings.trackSourceRow
        val originalTurbo = settings.turboLatency
        val originalDownscale = settings.fastOcrDownscale
        val originalCorrection = settings.ocrPreCorrection
        val originalStabilize = settings.stabilizeBeforeCloud
        val originalCostSaving = settings.costSavingMode
        val originalOverlay = settings.overlay
        val originalExcluded = settings.excludedApps
        val originalGlossary = settings.localGlossary
        val collectedEvents = linkedMapOf<String, JSONObject>()
        val directEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var sourceEventNeeded: String? = null
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        fun events(): List<JSONObject> {
            while (true) {
                val line = directEvents.poll() ?: break
                val event = JSONObject(line)
                if (event.optLong("at_ms") >= runStartedAt) collectedEvents.putIfAbsent(line, event)
            }
            if (sourceEventNeeded != null) shell("logcat -d -v raw -s GeminiScene:I '*:S'")
                .lineSequence().mapNotNull { line ->
                val start = line.indexOf('{')
                if (start < 0) null else runCatching { JSONObject(line.substring(start)) }.getOrNull()
            }.filter { event -> event.optString("id").startsWith(runId) ||
                (event.optString("event") != "source_drawn" && event.optLong("at_ms") >= runStartedAt)
            }.forEach { event ->
                collectedEvents.putIfAbsent(event.toString(), event)
                if (event.optString("id") == sourceEventNeeded) sourceEventNeeded = null
            }
            return collectedEvents.values.toList()
        }
        fun persistTrace() {
            val ownTrace = events().filter { event ->
                event.optString("id").startsWith(runId) || (event.optString("event") != "source_drawn" &&
                    event.optLong("at_ms") >= runStartedAt)
            }
            traceFile.writeText(ownTrace.sortedBy { it.optLong("at_ms", it.optLong("elapsed_ms")) }
                .joinToString("\n", postfix = "\n") { it.toString() })
        }
        fun persist() {
            val sampleRows = (0 until rows.length()).map { rows.getJSONObject(it) }
            val latencies = sampleRows
                .filter { !it.isNull("source_to_canvas_ms") }.map { it.getLong("source_to_canvas_ms") }.sorted()
            val referenceCharacters = sampleRows.sumOf { it.getInt("reference_characters") }
            val rawErrors = sampleRows.sumOf { it.getInt("raw_character_errors") }
            val scriptErrors = sampleRows.sumOf { it.getInt("script_normalized_character_errors") }
            fun percentile(percent: Int): Long? = if (latencies.isEmpty()) null else
                latencies[((latencies.size * percent + 99) / 100 - 1).coerceIn(0, latencies.lastIndex)]
            val providerSnapshot = synchronized(providerRows) { providerRows.toList() }
            reportFile.writeText(JSONObject()
                .put("kind", if (realLocal) {
                    if (manualCapture) "NATIVE_CAPTURE_OCR_REAL_LOCAL_MODEL_MANUAL_RESEARCH"
                    else "NATIVE_CAPTURE_OCR_REAL_LOCAL_MODEL_AUTO_RESEARCH"
                } else if (realGemini) {
                    if (manualCapture) "NATIVE_CAPTURE_OCR_REAL_GEMINI_MANUAL_BOUNDED"
                    else "NATIVE_CAPTURE_OCR_REAL_GEMINI_AUTO_BOUNDED"
                } else "NATIVE_OCR_CAPTURE_WITH_FAKE_TRANSLATOR_NOT_GEMINI")
                .put("run_id", runId).put("package", context.packageName).put("android_api", Build.VERSION.SDK_INT)
                .put("fake_service_ms", if (realProvider) JSONObject.NULL else fakeDelayMs)
                .put("real_google_requests", if (realGemini) JSONObject.NULL else 0)
                .put("standalone_app_requests_observed", appRequestCount)
                .put("maximum_explicit_api_dispatches", if (realGemini) cues.size else 0)
                .put("real_google_dispatches_allowed", realDispatches.get())
                .put("real_local_dispatches", localDispatches.get())
                .put("maximum_explicit_local_dispatches", if (realLocal) cues.size else 0)
                .put("local_prompt_version", if (realLocal) LocalModelProbeClient.PROMPT_VERSION else JSONObject.NULL)
                .put("local_prompt_cache", if (realLocal) false else JSONObject.NULL)
                .put("local_translation_cache", if (realLocal) false else JSONObject.NULL)
                .put("local_model_integration", if (realLocal) "research override only; production provider unchanged" else JSONObject.NULL)
                .put("verification_dispatches_denied", deniedDispatches.get())
                .put("inactive_phase_dispatches_denied", inactiveDispatches.get())
                .put("auto_blank_priming_ms", if (realProvider && !manualCapture) 1000 else 0)
                .put("capture_mode", if (manualCapture) "one-frame: one explicit tap per cue, no retries" else "auto")
                .put("minimum_experiment_cue_start_spacing_ms", if (realGemini) 8000 else if (realLocal) 1000 else 0)
                .put("translation_quality", if (realProvider) "unscored; every returned translation requires review" else "fake oracle")
                .put("trace_collection", "direct in-memory observer; scene log queried only until its Canvas event is seen")
                .put("benchmark_settings", JSONObject().put("fast_downscale", fastDownscale)
                    .put("source_font_sp", sourceFontSp).put("source_baseline", sourceBaseline)
                    .put("roi_top", roiTop).put("roi_bottom", roiBottom).put("track_source_row", trackSourceRow)
                    .put("moving_background_inside_roi", movingBackground)
                    .put("hardcoded_vietnamese", includeHardcodedVietnamese))
                .put("fresh_cues", cues.size).put("completed", completed)
                .put("setup_phase", setupPhase).put("capture_status", SessionState.status.value)
                .put("capture_running", SessionState.running.value)
                .put("sample_count", latencies.size).put("missed_cues", cues.size - latencies.size)
                .put("fake_provider_calls", fakeCalls.get()).put("unrecognized_fake_inputs", unrecognizedInputs.get())
                .put("script_equivalent_fake_inputs", scriptEquivalentInputs.get())
                .put("script_equivalent_cues", sampleRows.count { it.getBoolean("script_equivalent_at_end") })
                .put("raw_reference_characters", referenceCharacters).put("raw_character_errors", rawErrors)
                .put("raw_cer", if (referenceCharacters > 0) rawErrors.toDouble() / referenceCharacters else JSONObject.NULL)
                .put("script_normalized_character_errors", scriptErrors)
                .put("script_normalized_cer", if (referenceCharacters > 0) scriptErrors.toDouble() / referenceCharacters else JSONObject.NULL)
                .put("comparison", "NFKC and whitespace removal; Traditional/Simplified mapping only, no typo correction")
                .put("test_only_script_pairs", JSONObject().put("戶", "户").put("鍵", "键"))
                .put("source_to_canvas_p50_ms", percentile(50) ?: JSONObject.NULL)
                .put("source_to_canvas_p95_ms", percentile(95) ?: JSONObject.NULL)
                .put("percentile_method", "nearest-rank")
                .put("under_1000ms", latencies.count { it < 1000 })
                .put("setup_failure", setupFailure ?: JSONObject.NULL)
                .put("limitations", JSONArray(listOf(
                    if (realLocal) "Real local model translates the actual OCR input over fixed loopback HTTP; no lookup of fixture translations. Not integrated into production. Quality requires separate review."
                    else if (realGemini) "Real Gemini translations of synthetic source text; quality needs separate review of each output. No private movie is used."
                    else "Synthetic text and controlled fake translation; no translation quality or Google latency measured.",
                    "source_drawn is the source Canvas callback; overlay_drawn is the overlay Canvas callback, not display scanout.",
                    "Sequential fresh cues have up to the configured timeout to draw; misses remain in the denominator.",
                    if (realLocal) "No key is read by this harness or sent to the model. The normal app pipeline may access its own settings before the override. No Google POST is dispatched. Model provenance is verified separately on the host."
                    else if (realGemini) "Uses the key already entered into the disposable app without logging or clearing it. The single-attempt transport forbids POST replay; explicit app dispatches are counted."
                    else "No API key, original app data, private movie, or network provider is used.",
                    if (realProvider && manualCapture) "source-to-Canvas includes waiting for the single manual test tap; click-to-Canvas and frame-to-Canvas are recorded separately."
                    else if (realProvider) "Automatic source-to-Canvas includes capture cadence and post-response source confirmation; no test tap is used."
                    else "Fake mode uses the selected automatic or one-frame capture mode.",
                )))
                .put("samples", rows)
                .put("fake_provider", if (!realProvider) JSONArray(providerSnapshot) else JSONArray())
                .put("local_provider", if (realLocal) JSONArray(providerSnapshot) else JSONArray()).toString(2))
        }
        fun scene(text: String, id: String) {
            sourceEventNeeded = id
            context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                "com.gemini.ocr.TestSceneActivity"))
                .putExtra("subtitle", true).putExtra("animate", true).putExtra("movingBackground", movingBackground)
                .putExtra("subtitleBaseline", sourceBaseline).putExtra("subtitleSp", sourceFontSp)
                .putExtra("sourceText", text).putExtra("hardcodedVietnamese",
                    if (includeHardcodedVietnamese) "Dòng tiếng Việt có sẵn bên dưới" else "")
                .putExtra("eventId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        persist()
        try {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            withTimeout(5000) { while (SessionState.running.value) delay(50) }
            if (realGemini) require(StandaloneTranslator.hasApiKey(settings)) { "Owner must enter a key in the disposable app before realGemini=true" }
            StandaloneTranslator.clearDirectCache()
            settings.apply {
                region = "subtitle"; subtitleCapture = if (manualCapture) "frame" else "auto"; translationMode = "gemini_direct"
                connectionMode = "standalone"; fullScreen = true; interval = 150
                subtitleRegion = RectF(.06f, roiTop, .94f, roiBottom)
                this.trackSourceRow = trackSourceRow; turboLatency = true; fastOcrDownscale = fastDownscale
                ocrPreCorrection = false; stabilizeBeforeCloud = false; costSavingMode = false
                if (!realGemini) localGlossary = ""
                excludedApps = ""; overlay = "subtitle"
            }
            StandaloneTranslator.overrideOnDeviceTranslator = null
            PipelineTrace.observerForTest = { directEvents.add(it); Unit }
            StandaloneTranslator.beforeNetworkCallForTest = if (!realGemini) null else {
                {
                    val cue = activeCue.get()
                    if (cue !in cues.indices) {
                        inactiveDispatches.incrementAndGet()
                        throw java.io.IOException("Verification phase is not armed")
                    }
                    if (realDispatches.get() >= cues.size || dispatchedCue.getAndSet(cue) == cue) {
                        deniedDispatches.incrementAndGet()
                        throw java.io.IOException("Verification request budget exceeded")
                    }
                    realDispatches.incrementAndGet()
                }
            }
            StandaloneTranslator.overrideGeminiTranslator = if (realGemini) null else if (realLocal) { texts, _, _, _, _ ->
                require(texts.size == 1) { "Research local model uses batch=1" }
                val cue = activeCue.get()
                if (cue !in cues.indices) {
                    inactiveDispatches.incrementAndGet()
                    throw java.io.IOException("Local verification phase is not armed")
                }
                if (localDispatches.get() >= cues.size || dispatchedCue.getAndSet(cue) == cue) {
                    deniedDispatches.incrementAndGet()
                    throw java.io.IOException("Local verification request budget exceeded")
                }
                val call = localDispatches.incrementAndGet()
                val started = SystemClock.elapsedRealtime()
                val row = JSONObject().put("call", call).put("cue", cue).put("raw_recognized", texts.single())
                    .put("context_source", "snapshot of latest accepted context pair at override invocation")
                try {
                    val payload = LocalModelProbeClient.payload(texts.single(), StandaloneTranslator.getRecentContextSnapshot(1))
                    val response = LocalModelProbeClient.generate(payload)
                    val translated = LocalModelProbeClient.translation(response)
                    row.put("translation", translated).put("status", "success")
                        .put("usage", response.optJSONObject("usage") ?: JSONObject.NULL)
                        .put("timings", response.optJSONObject("timings") ?: JSONObject.NULL)
                    listOf(translated)
                } catch (error: Exception) {
                    row.put("status", "error").put("exception_type", error.javaClass.simpleName)
                    throw error
                } finally {
                    row.put("service_ms", SystemClock.elapsedRealtime() - started)
                    providerRows.add(row)
                }
            } else { texts, _, _, _, _ ->
                val call = fakeCalls.incrementAndGet()
                val started = SystemClock.elapsedRealtime()
                delay(fakeDelayMs)
                texts.map { recognized ->
                    val index = cues.indexOfFirst { scriptNormal(it.source) == scriptNormal(recognized) }
                    if (index < 0) unrecognizedInputs.incrementAndGet()
                    val scriptEquivalent = index >= 0 && rawNormal(cues[index].source) != rawNormal(recognized)
                    if (scriptEquivalent) scriptEquivalentInputs.incrementAndGet()
                    providerRows.add(JSONObject().put("call", call).put("cue", index)
                        .put("raw_recognized", recognized).put("script_equivalent", scriptEquivalent)
                        .put("service_ms", SystemClock.elapsedRealtime() - started))
                    if (index >= 0) cues[index].fakeTranslation else "OCR giả không khớp mẫu"
                }
            }
            // Only the disposable package is modified. Real mode leaves its owner-entered key intact.
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("Only the disposable app may receive capture actions",
                device.wait(Until.hasObject(By.pkg(context.packageName)),10000))
            val start = device.wait(Until.findObject(By.pkg(context.packageName)
                .text(Pattern.compile("Bắt đầu dịch|Bật dịch màn hình"))), 10000)
            assertNotNull("Capture start control was not found", start)
            // Resolve the action at click time: the saved-project count can recompose the home
            // screen after UiObject2 was obtained, invalidating that accessibility node.
            setupPhase = "requesting-projection"
            device.findObject(UiSelector().packageName(context.packageName).textMatches("Bắt đầu dịch|Bật dịch màn hình")).click()
            val consent = device.wait(Until.findObject(By.pkg("com.android.systemui").res("android:id/button1")
                .text(Pattern.compile("Start now|Start recording|Start sharing|Share screen|Start|Bắt đầu ngay|Bắt đầu", Pattern.CASE_INSENSITIVE))), 10000)
            if (consent == null) {
                device.dumpWindowHierarchy(File(directory, "standalone-native-permission.xml"))
                device.takeScreenshot(File(directory, "standalone-native-permission.png"))
                fail("MediaProjection consent was not found; verification screenshot contains only the test app")
            }
            consent!!.click()
            setupPhase = "waiting-for-capture-service"
            withTimeout(30000) { while (!SessionState.running.value) delay(50) }
            setupPhase = "capturing-generated-scenes"
            assertEquals("gemini_direct", settings.translationMode)

            if (realProvider && !manualCapture) {
                // Drain the app-to-scene transition on a known blank raster before the first
                // paid cue. The gate remains disarmed; no startup OCR may spend API quota.
                scene("", "$runId-prime")
                delay(1000)
                measurementStartedAt = SystemClock.elapsedRealtime()
            }

            for ((index, cue) in cues.withIndex()) {
                if (realProvider) delay((nextCueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                nextCueAt = SystemClock.elapsedRealtime() + if (realGemini) 8000 else 1000
                val id = "$runId-$index"
                val translationHash = PipelineTrace.hash(cue.fakeTranslation)
                activeCue.set(index)
                scene(cue.source, id)
                assertTrue("The generated scene must be the visible test package",
                    device.wait(Until.hasObject(By.pkg(instrumentation.context.packageName)),5000))
                var tapAt: Long? = null
                if (manualCapture) {
                    withTimeout(5000) {
                        while (events().none { it.optString("event") == "source_drawn" && it.optString("id") == id }) delay(50)
                    }
                    delay(30)
                    val capture = device.wait(Until.findObject(By.desc("Dịch 1 khung phụ đề")), 3000)
                    assertNotNull("One-frame control must be present; never retry a missing capture", capture)
                    tapAt = SystemClock.elapsedRealtime()
                    capture!!.click() // Exactly once. No automatic capture or harness retry in real mode.
                }
                val deadline = SystemClock.elapsedRealtime() + cueTimeoutMs
                var source: JSONObject? = null
                var drawn: JSONObject? = null
                var available = emptyList<JSONObject>()
                var currentRequestId: String? = null
                var realFailure: String? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(80)
                    available = events()
                    source = available.lastOrNull { it.optString("event") == "source_drawn" && it.optString("id") == id }
                    val sourceAt = source?.optLong("elapsed_ms")
                    if (sourceAt != null) {
                        if (realProvider) {
                            currentRequestId = available.firstOrNull { it.optString("event") == "request_sent" &&
                                it.optString("transport") == "standalone" && it.optLong("at_ms") >= (tapAt ?: sourceAt)
                            }?.optString("request_id")
                        }
                        drawn = available.firstOrNull { it.optString("event") == "overlay_drawn" &&
                            (if (realProvider) currentRequestId != null && it.optString("request_id") == currentRequestId
                            else it.optString("translation_hash") == translationHash) && it.optLong("at_ms") >= sourceAt }
                    }
                    if (realProvider) {
                        val requests = available.filter { it.optString("event") == "request_sent" &&
                            it.optString("transport") == "standalone" && it.optLong("at_ms") >= measurementStartedAt }
                        appRequestCount = requests.size
                        require(appRequestCount <= index + 1 && appRequestCount <= cues.size) { "Explicit API request budget exceeded" }
                        require(requests.count { it.optLong("at_ms") >= (tapAt ?: source?.optLong("elapsed_ms") ?: deadline) } <= 1) {
                            "More than one API dispatch for a single manual tap"
                        }
                    }
                    if (drawn != null) break
                }
                val sourceAt = source?.optLong("elapsed_ms")
                val drawnAt = drawn?.optLong("at_ms")
                if (realLocal && drawn != null) require(!drawn.optBoolean("phone_cache", false)) {
                    "A cached translation cannot count as fresh local inference"
                }
                val wrongDraws = if (sourceAt == null) 0 else available.count {
                    it.optString("event") == "overlay_drawn" && it.optLong("at_ms") >= sourceAt &&
                        (if (realProvider) it.optLong("at_ms") >= (tapAt ?: sourceAt) &&
                            (currentRequestId == null || it.optString("request_id") != currentRequestId)
                        else it.optString("translation_hash") != translationHash)
                }
                appRequestCount = available.count { it.optString("event") == "request_sent" &&
                    it.optString("transport") == "standalone" && it.optLong("at_ms") >= measurementStartedAt }
                val rawReference = rawNormal(cue.source)
                val rawOcr = rawNormal(SessionState.lastOcr.value)
                val referenceCount = rawReference.codePointCount(0, rawReference.length)
                val rawErrorCount = characterErrors(rawReference, rawOcr)
                val scriptErrorCount = characterErrors(scriptNormal(cue.source), scriptNormal(SessionState.lastOcr.value))
                rows.put(JSONObject().put("cue", index).put("id", id).put("source", cue.source)
                    .put("source_drawn_ms", sourceAt ?: JSONObject.NULL).put("overlay_drawn_ms", drawnAt ?: JSONObject.NULL)
                    .put("source_to_canvas_ms", if (sourceAt != null && drawnAt != null) drawnAt - sourceAt else JSONObject.NULL)
                    .put("frame_to_canvas_ms", drawn?.optLong("observed_to_draw_ms") ?: JSONObject.NULL)
                    .put("manual_tap_ms", tapAt ?: JSONObject.NULL)
                    .put("request_id", currentRequestId ?: JSONObject.NULL)
                    .put("quality_review", if (realProvider) "unscored" else "fake oracle")
                    .put("tap_to_canvas_ms", if (tapAt != null && drawnAt != null) drawnAt - tapAt else JSONObject.NULL)
                    .put("wrong_draws_while_current", wrongDraws)
                    .put("phone_cache", drawn?.optBoolean("phone_cache") ?: JSONObject.NULL)
                    .put("ocr_text_at_end", SessionState.lastOcr.value)
                    .put("reference_characters", referenceCount).put("raw_character_errors", rawErrorCount)
                    .put("raw_cer", rawErrorCount.toDouble() / referenceCount)
                    .put("script_normalized_character_errors", scriptErrorCount)
                    .put("script_normalized_cer", scriptErrorCount.toDouble() / referenceCount)
                    .put("script_equivalent_at_end", rawReference != rawOcr && scriptErrorCount == 0)
                    .put("translation_at_end", SessionState.lastTranslation.value)
                    .put("status_at_end", SessionState.status.value))
                persist()
                if (realProvider && (realFailure != null || drawn == null || wrongDraws > 0)) {
                    persistTrace()
                    fail(realFailure ?: "Real-provider research stopped on first missing/wrong cue; no retry or next call")
                }
                // Real blank transition exercises source disappearance and clears the overlay.
                scene("", "$id-blank")
                activeCue.set(-1)
                delay(350)
            }
            if (realLocal) assertEquals("Every local cue must dispatch exactly one actual local POST", cues.size, localDispatches.get())
            if (movingBackground) {
                val motionEvents = events()
                assertTrue("Regression must exercise changing ROI pixels while native OCR owns an earlier frame",
                    motionEvents.any { it.optString("event") == "ocr_frame_advanced" })
                assertFalse("Moving scenery is not a reason to discard a completed native subtitle observation",
                    motionEvents.any { it.optString("event") == "ocr_discarded" && it.optString("reason") == "newer_roi_frame" })
            }
            completed = true
            persistTrace()
            persist()
            assertEquals("Every synthetic source must produce a matching native overlay; see JSON for misses", cues.size,
                (0 until rows.length()).count { !rows.getJSONObject(it).isNull("source_to_canvas_ms") })
            assertEquals("OCR must not alter the synthetic source", 0, unrecognizedInputs.get())
            assertEquals("No stale or wrong translation may be drawn", 0,
                (0 until rows.length()).sumOf { rows.getJSONObject(it).getInt("wrong_draws_while_current") })
        } catch (error: Throwable) {
            setupFailure = error.javaClass.simpleName + ": " + error.message.orEmpty()
            runCatching { persistTrace() }
            persist()
            throw error
        } finally {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            withContext(NonCancellable) {
                withTimeout(10000) { while (SessionState.running.value) delay(50) }
            }
            StandaloneTranslator.overrideGeminiTranslator = null
            StandaloneTranslator.overrideOnDeviceTranslator = null
            StandaloneTranslator.beforeNetworkCallForTest = null
            PipelineTrace.observerForTest = null
            StandaloneTranslator.clearDirectCache()
            run {
                settings.apply {
                    region = originalRegion; subtitleCapture = originalCapture; translationMode = originalMode
                    connectionMode = originalConnection; fullScreen = originalFullScreen; interval = originalInterval
                    subtitleRegion = originalRoi; this.trackSourceRow = originalTracker; turboLatency = originalTurbo
                    fastOcrDownscale = originalDownscale; ocrPreCorrection = originalCorrection
                    stabilizeBeforeCloud = originalStabilize; costSavingMode = originalCostSaving
                    overlay = originalOverlay; excludedApps = originalExcluded
                    localGlossary = originalGlossary
                }
            }
            // Keep the device awake and the report available for adb pull. Never touch original app.
        }
    }
}
