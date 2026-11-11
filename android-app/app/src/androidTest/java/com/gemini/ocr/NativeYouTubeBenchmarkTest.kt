package com.gemini.ocr

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gemini.ocr.capture.PipelineTrace
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/** Run screenshot capture in a disposable child, never UiAutomation's Binder process. */
internal fun publicYouTubeScreenshotCommand(file: File): String {
    val path = file.absolutePath
    require(path.matches(Regex("/(?:storage/emulated/[0-9]+|sdcard)/Android/data/com[.]gemini[.]ocr[.]codexbench/files/verification/[A-Za-z0-9._-]+[.]png")))
    return "timeout -k 1 5 screencap -p $path"
}

/**
 * Explicit owner-authorized, bounded public YouTube observation. Never runs on the release package.
 * Snapshot→Canvas is measurable here; subtitle-onset→Canvas needs independent video annotation.
 */
@RunWith(AndroidJUnit4::class)
class NativeYouTubeBenchmarkTest {
    private data class OriginalSettings(
        val region: String, val capture: String, val mode: String, val connection: String,
        val fullScreen: Boolean, val interval: Long, val roi: RectF, val tracker: Boolean,
        val turbo: Boolean, val downscale: Boolean, val correction: Boolean,
        val stabilize: Boolean, val saving: Boolean, val overlay: String,
    ) {
        fun restore(settings: AppSettings) = with(settings) {
            region = this@OriginalSettings.region; subtitleCapture = capture; translationMode = this@OriginalSettings.mode
            connectionMode = connection; fullScreen = this@OriginalSettings.fullScreen
            interval = this@OriginalSettings.interval; subtitleRegion = roi; trackSourceRow = tracker
            turboLatency = turbo; fastOcrDownscale = downscale; ocrPreCorrection = correction
            stabilizeBeforeCloud = stabilize; costSavingMode = saving; overlay = this@OriginalSettings.overlay
        }
        companion object {
            fun read(settings: AppSettings) = with(settings) {
                OriginalSettings(region, subtitleCapture, translationMode, connectionMode, fullScreen,
                    interval, subtitleRegion, trackSourceRow, turboLatency, fastOcrDownscale,
                    ocrPreCorrection, stabilizeBeforeCloud, costSavingMode, overlay)
            }
        }
    }

    @Test fun boundedPublicYouTubeCaptureUsesRealTranslationAndPreservesItsLimits() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeYouTube") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty()) { "Verification APK must not embed a key" }
        val startSeconds = args.getString("startSeconds")?.toIntOrNull() ?: 936
        val urlArgument = requireNotNull(args.getString("youtubeUrl"))
        val copiedLink = urlArgument == "clipboard"
        val target = if (copiedLink) {
            // Parent must first use Copy link on the specific public video it observed.
            // Read exactly once, text only: do not enumerate clipboard history, coerce
            // content URIs, report the raw item, or retain it in a benchmark artifact.
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            require(clip?.itemCount == 1) { "Copy the current public video link before starting the probe" }
            val text = clip.getItemAt(0).text
                ?: throw IllegalArgumentException("The copied item is not a supported public YouTube video link")
            YouTubeProbeTarget.fromCopiedLink(text.toString(), startSeconds)
        } else YouTubeProbeTarget.parse(urlArgument, startSeconds)
        val requestedProvider = (args.getString("translationProvider") ?: "gemini").also {
            require(it in setOf("gemini", "mlkit", "moxhi60"))
        }
        val mtContextProtocol = (args.getString("mtContextProtocol") ?: "raw_forced_prefix").also {
            require(it in setOf("raw_forced_prefix", "source_only"))
        }
        val ocrEngine = (args.getString("ocrEngine") ?: "mlkit").also {
            require(it in setOf("mlkit", "paddle_tiny", "paddle_v4"))
        }
        val ocrThreads = (args.getString("ocrThreads")?.toIntOrNull() ?: 1).also { require(it in 1..4) }
        val ortSpinning = (args.getString("ortSpinning") ?: "true").toBooleanStrict()
        val trackRows = args.getString("trackRows") == "true"
        val collectPerformance = YouTubeBenchmarkOptions.performanceRequested(
            args.getString("collectPerformance"), args.getString("measurePerformance"))
        val maximumDuration = if (requestedProvider != "gemini") 90000L else 45000L
        val durationMs = (args.getString("durationMs")?.toLongOrNull() ?: 45000L).also { require(it in 5000L..maximumDuration) }
        val maxPosts = (args.getString("maxPosts")?.toIntOrNull() ?: 8).also { require(it in 1..8) }
        val recordScreen = args.getString("recordScreen") == "true"
        val stem = (args.getString("reportStem") ?: "youtube-native").also {
            require(it.matches(Regex("[a-z0-9-]{1,60}")))
        }
        fun fraction(name: String, default: Float) = (args.getString(name)?.toFloatOrNull() ?: default).also {
            require(it.isFinite() && it in 0f..1f)
        }
        val roi = RectF(fraction("roiLeft", .06f), fraction("roiTop", .67f),
            fraction("roiRight", .94f), fraction("roiBottom", .94f))
        require(roi.left < roi.right && roi.top < roi.bottom)
        val device = UiDevice.getInstance(instrumentation)
        val configurator = Configurator.getInstance()
        val originalIdleTimeout = configurator.waitForIdleTimeout
        val translationProvider = requestedProvider
        var offline: AndroidOfflineProbe? = null
        var mt60: com.gemini.ocr.mtprobe.NativeMt60Probe? = null
        var paddle: PaddleOcrProbe? = null
        var modelPrepareMs: Long? = null
        var modelFirstCallMs: Long? = null
        val localCalls = AtomicInteger()
        val nativeModelDiagnostics = JSONArray()
        var recording: OwnedPublicScreenRecording? = null
        var recordingCleanStop: Boolean? = null
        var adWaitMs = 0L
        var adsSkipped = 0
        var playbackProgressVerified = false
        var observedStartSeconds: Long? = null
        var seekMethod = "timestamp_already_matches"
        val settings = AppSettings(context)
        val original = OriginalSettings.read(settings)
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "verification").apply { mkdirs() }
        val traceFile = File(directory, "$stem-trace.jsonl")
        val reportFile = File(directory, "$stem.json")
        check(!reportFile.exists() && !traceFile.exists()) { "Preserve previous report; choose a new reportStem" }
        val safeEvents = ConcurrentLinkedQueue<String>()
        val subtitleStates = mutableListOf<JSONObject>()
        val performanceSamples = JSONArray()
        var lastPerformanceSampleAt = 0L
        val dispatches = AtomicInteger()
        val denied = AtomicInteger()
        val postBudgetReachedAt = AtomicLong()
        val postBudgetDenied = AtomicInteger()
        val inactiveDispatchesDenied = AtomicInteger()
        val armed = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        var observingAt = 0L
        var stoppedAt = 0L
        var performanceFinal: JSONObject? = null
        var advertisementInterrupted = false
        var stopReason = "not_started"
        var setupErrorType: String? = null
        var setupFailureReason: String? = null
        var completed = false
        var lastState = ""
        var roiSourceEvidenceSaved = false
        val roiEvidenceAttempts = JSONArray()
        var viewportGeometry: JSONObject? = null
        val viewportChecks = JSONArray()
        fun exactPlayer() = device.findObject(By.res("com.google.android.youtube:id/watch_player"))
        fun exactPlayerGeometryCoversDisplay(): Boolean = YouTubePlaybackProgress.latestOrNull {
            val player = exactPlayer()?.visibleBounds
            val video = device.findObject(By.res("com.google.android.youtube:id/player_view"))?.visibleBounds
            YouTubeViewport.coversDisplay(player, device.displayWidth, device.displayHeight) &&
                YouTubeViewport.coversDisplay(video, device.displayWidth, device.displayHeight)
        } ?: false
        fun viewportSnapshot(label: String): JSONObject? = YouTubePlaybackProgress.latestOrNull {
            val player = exactPlayer() ?: return@latestOrNull null
            val video = device.findObject(By.res("com.google.android.youtube:id/player_view")) ?: return@latestOrNull null
            val control = device.findObject(By.res("com.google.android.youtube:id/fullscreen_button"))
            val action = control?.contentDescription
            val bounds = player.visibleBounds
            val videoBounds = video.visibleBounds
            if (!YouTubeViewport.isFullscreen(bounds, videoBounds, action, device.displayWidth, device.displayHeight))
                return@latestOrNull null
            JSONObject().put("label", label).put("verified_at_ms", SystemClock.elapsedRealtime())
                .put("display_width", device.displayWidth).put("display_height", device.displayHeight)
                .put("player_resource", "com.google.android.youtube:id/watch_player")
                .put("video_resource", "com.google.android.youtube:id/player_view")
                .put("fullscreen_control_description", action)
                .put("player_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("video_bounds", JSONArray(listOf(videoBounds.left, videoBounds.top, videoBounds.right, videoBounds.bottom)))
                .put("covers_display", true)
        }
        suspend fun requireFullscreen(label: String, mayEnter: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + if (mayEnter) 15000L else 1500L
            var stableSince: Long? = null
            var lastActionAt = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                val observed = viewportSnapshot(label)
                val now = SystemClock.elapsedRealtime()
                if (observed != null) {
                    if (stableSince == null) stableSince = now
                    if (now - stableSince!! >= 350L) {
                        observed.put("stable_for_ms", now - stableSince!!)
                        viewportChecks.put(observed)
                        viewportGeometry = observed
                        return
                    }
                } else {
                    stableSince = null
                    if (now - lastActionAt >= 500L && (mayEnter || exactPlayerGeometryCoversDisplay())) {
                        val enter = if(mayEnter) device.findObject(By.res("com.google.android.youtube:id/fullscreen_button")
                            .desc(Pattern.compile("Chuyển sang chế độ toàn màn hình|Enter full screen|Enter fullscreen"))) else null
                        if (enter != null) YouTubePlaybackProgress.latestOrNull { enter.click() }
                        else YouTubePlaybackProgress.latestOrNull { exactPlayer()?.visibleBounds }?.let { bounds ->
                            device.click(bounds.left + 8, bounds.top + bounds.height() / 3)
                        }
                        lastActionAt = now
                    }
                }
                delay(150)
            }
            error("Exact YouTube player/video bounds and Exit fullscreen control were not stable at $label")
        }
        fun captureRoiEvidence(label: String) {
            val attempt = JSONObject().put("label", label).put("at_ms", SystemClock.elapsedRealtime())
                .put("capture_method", "isolated_timeout_screencap")
            roiEvidenceAttempts.put(attempt)
            var temporary: File? = null
            try {
                if (device.currentPackageName != "com.google.android.youtube") {
                    attempt.put("status", "skipped_not_public_youtube"); return
                }
                val output = File.createTempFile(".codex-youtube-roi-", ".png", directory).also { temporary = it }
                // BlueStacks can SIGSEGV while importing screenshot graphics buffers.
                // A child screencap may fail without killing UiAutomation/the benchmark.
                var commandOutputBytes = 0L
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    publicYouTubeScreenshotCommand(output))).use { input ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        commandOutputBytes += count // Drain without retaining arbitrary shell output.
                    }
                }
                attempt.put("shell_output_bytes", commandOutputBytes)
                if (device.currentPackageName != "com.google.android.youtube") {
                    attempt.put("status", "discarded_foreground_changed"); return
                }
                val bitmap = BitmapFactory.decodeFile(output.absolutePath)
                if (bitmap == null) { attempt.put("status", "screencap_failed_or_no_decodable_png"); return }
                try {
                    File(directory, "$stem-$label-screen.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    val left = (roi.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (roi.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (roi.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = (roi.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                    val cropped = android.graphics.Bitmap.createBitmap(bitmap, left, top, right-left, bottom-top)
                    try { File(directory, "$stem-$label-roi.png").outputStream().use {
                        check(cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { if (cropped !== bitmap) cropped.recycle() }
                    attempt.put("status", "saved").put("screen_width", bitmap.width).put("screen_height", bitmap.height)
                        .put("roi_pixels", JSONArray(listOf(left, top, right, bottom)))
                } finally { bitmap.recycle() }
            } catch (error: Exception) {
                // Evidence collection must never turn an otherwise valid observation into failure.
                attempt.put("status", "capture_exception").put("error_type", error.javaClass.simpleName)
            } finally { temporary?.delete() } // Remove only the helper's newly created temporary image.
        }

        fun events(): List<JSONObject> = safeEvents.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        fun recordSubtitleState() {
            // Public video OCR/output only. Never save status/error text, preferences or API payloads.
            val source = SessionState.lastOcr.value.take(2000)
            val translation = SessionState.lastTranslation.value.take(4000)
            val identity = source + "\u0000" + translation
            if (identity == lastState || (source.isEmpty() && translation.isEmpty())) return
            lastState = identity
            subtitleStates.add(JSONObject().put("at_ms", SystemClock.elapsedRealtime())
                .put("source_zh", source).put("translation_vi", translation)
                .put("source_hash", PipelineTrace.hash(source)).put("translation_hash", PipelineTrace.hash(translation)))
        }
        fun persist() {
            val rows = events().filter { it.optLong("at_ms") >= startedAt }
            traceFile.writeText(rows.sortedBy { it.optLong("at_ms") }.joinToString("\n", postfix = "\n"))
            val measurement = rows.filter { observingAt > 0 && it.optLong("at_ms") in observingAt..stoppedAt }
            val draws = measurement.filter { it.optString("event") == "overlay_drawn" }
            val requests = measurement.filter { it.optString("event") == "request_sent" && it.optString("transport") == "standalone" }
            val results = measurement.filter { it.optString("event") in setOf("result_received", "result_discarded", "request_failed") }
            val times = draws.mapNotNull { if (it.has("observed_to_draw_ms")) it.optLong("observed_to_draw_ms") else null }.sorted()
            fun percentile(percent: Int): Any = if (times.isEmpty()) JSONObject.NULL else times[(times.size * percent + 99) / 100 - 1]
            val snapshot = JSONObject().put("kind", "REAL_PUBLIC_YOUTUBE_NATIVE_BOUNDED_OBSERVATION")
                .put("video_id", target.videoId).put("public_watch_url", target.uri.toString())
                .put("public_target_input", if (copiedLink) "single parent-observed Copy link; tracking removed" else "explicit public watch link")
                .put("video_start_seconds_requested", target.startSeconds).put("package", context.packageName)
                .put("video_launch_seconds_preroll", YouTubePlaybackProgress.seekLeadInSeconds(target.startSeconds))
                .put("run_started_at_ms", startedAt).put("observation_started_at_ms", observingAt)
                .put("observation_stopped_at_ms", stoppedAt).put("requested_observation_ms", durationMs)
                .put("completed_observation", completed).put("stop_reason", stopReason)
                .put("setup_error_type", setupErrorType ?: JSONObject.NULL)
                .put("setup_failure_reason", setupFailureReason ?: JSONObject.NULL)
                .put("translation_provider", translationProvider)
                .put("mt60_context_protocol", if (translationProvider == "moxhi60") mtContextProtocol else JSONObject.NULL)
                .put("ocr_engine", ocrEngine)
                .put("ocr_candidate_integrated_in_product", false)
                .put("ocr_weight_redistribution_license_verified", ocrEngine != "paddle_v4")
                .put("ocr_candidate_threads", if (ocrEngine != "mlkit") ocrThreads else JSONObject.NULL)
                .put("ort_thread_spinning", ortSpinning)
                .put("track_source_rows", trackRows)
                .put("advertisement_interrupted_observation", advertisementInterrupted)
                .put("performance_final_snapshot", performanceFinal ?: JSONObject.NULL)
                .put("ocr_candidate_load_ms", paddle?.loadMs ?: JSONObject.NULL)
                .put("ocr_candidate_blank_warmup_ms", paddle?.warmupMs ?: JSONObject.NULL)
                .put("ocr_confidence_threshold_calibrated", false)
                .put("offline_candidate_integrated_in_product", false)
                .put("uiautomator_idle_timeout_ms", 0)
                .put("on_device_model_prepare_ms", modelPrepareMs ?: JSONObject.NULL)
                .put("on_device_first_call_separate_ms", modelFirstCallMs ?: JSONObject.NULL)
                .put("on_device_calls", localCalls.get())
                .put("native_model_call_diagnostics", nativeModelDiagnostics)
                .put("ad_setup_wait_ms", adWaitMs).put("ads_skipped", adsSkipped)
                .put("player_clock_progress_verified_before_observation", playbackProgressVerified)
                .put("actual_player_seconds_before_observation", observedStartSeconds ?: JSONObject.NULL)
                .put("seek_method", seekMethod)
                .put("screen_recording", if (recordScreen) "$stem.mp4" else JSONObject.NULL)
                .put("screen_recording_clean_stop", recordingCleanStop ?: JSONObject.NULL)
                .put("maximum_post_dispatches", maxPosts).put("post_dispatches_allowed", dispatches.get())
                .put("post_budget_reached_at_ms", postBudgetReachedAt.get().takeIf { it > 0 } ?: JSONObject.NULL)
                .put("post_budget_dispatch_attempts_denied", postBudgetDenied.get())
                .put("inactive_dispatch_attempts_denied", inactiveDispatchesDenied.get())
                .put("post_budget_observation_continues_to_fixed_deadline", true)
                .put("actual_google_requests_received", JSONObject.NULL)
                .put("budget_or_inactive_dispatches_denied", denied.get()).put("automatic_retries", 0)
                .put("logical_requests_observed", requests.size).put("terminal_result_events", results.size)
                .put("overlay_draw_events", draws.size).put("frame_to_canvas_p50_ms", percentile(50))
                .put("frame_to_canvas_p95_ms", percentile(95)).put("frame_to_canvas_under_1000ms", times.count { it < 1000 })
                .put("subtitle_onset_to_canvas_ms", JSONObject.NULL).put("source_onset_ground_truth", "not collected by this observer")
                .put("under_one_second_product_acceptance_verified", false)
                .put("quality_review", "unscored; public source/output observations require review")
                .put("cache_hits_observed", draws.count { it.optBoolean("phone_cache") })
                .put("roi", JSONArray(listOf(roi.left, roi.top, roi.right, roi.bottom)))
                .put("viewport_geometry_before_observation", viewportGeometry ?: JSONObject.NULL)
                .put("fullscreen_state_checks", viewportChecks)
                .put("public_roi_evidence_attempts", roiEvidenceAttempts)
                .put("public_subtitle_state_observations", JSONArray(subtitleStates))
                .put("performance_sampling_enabled", collectPerformance)
                .put("performance_process_samples", performanceSamples)
                .put("limitations", JSONArray(listOf(
                    "This observes public YouTube playback and actual output from the reported provider. No fake translations or private/local video files are used.",
                    "Source subtitle onset is not the first OCR detection. The reported frame-to-Canvas values exclude time before its source snapshot; no end-to-end under-one-second claim follows.",
                    "Playback start position/full-screen/autoplay depend on YouTube UI and must be independently checked. Ads, buffering, missing/late subtitles remain part of the recorded window.",
                    "The harness never reads the API key. The normal isolated app accesses its own stored key. Existing process quota limits remain; cancellations after dispatch still spend quota.",
                    "The dispatch gate is immediately before HTTP enqueue. It bounds allowed POSTs, but cancellation can prevent an allowed request from reaching Google; received request count is unknown.",
                    "The POST cap never shortens the requested observation window. After the cap, later fresh cues remain in the fixed-window denominator but may be unattempted because of budget. Denied dispatch attempts are not distinct cue counts or model quality failures; per-cue review must label budget-unattempted separately from translated, late, failed or missed cues.",
                    "Translation cache and cache-only draws are reported separately; not every logical request dispatches or draws. Unobserved video cues cannot be counted as zero misses.",
                    "Only this disposable app's non-sensitive capture settings are temporarily changed and restored; no app data is cleared."
                )))
            reportFile.writeText(snapshot.toString(2))
        }
        try {
            // A live video may never be UI-idle. A default ten-second UI wait in
            // currentPackageName must not become part of the measured HTTP path.
            configurator.setWaitForIdleTimeout(0)
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            withTimeout(10000) { while (SessionState.running.value) delay(50) }
            require(StandaloneTranslator.overrideGeminiTranslator == null && StandaloneTranslator.overrideOnDeviceTranslator == null)
            require(StandaloneTranslator.overrideMt60TranslatorForVerification == null)
            require(StandaloneTranslator.beforeNetworkCallForTest == null && PipelineTrace.observerForTest == null)
            require(com.gemini.ocr.ocr.OcrProcessor.factoryForVerification == null)
            // This only clears the disposable process's transient translation/context LRU.
            // No persistent phone cache, bank, pairing, preference or user key is read or cleared.
            StandaloneTranslator.clearDirectCache()
            if (ocrEngine != "mlkit") {
                paddle = withTimeout(30000) { PaddleOcrProbe.prepare(context, v4 = ocrEngine == "paddle_v4", threads = ocrThreads, allowSpinning = ortSpinning) }
                paddle!!.attach()
            }
            if (translationProvider == "mlkit") {
                val began = SystemClock.elapsedRealtime()
                val candidate = AndroidOfflineProbe().also { offline = it }
                withTimeout(180000) { candidate.prepare() }
                modelPrepareMs = SystemClock.elapsedRealtime() - began
                val first = SystemClock.elapsedRealtime()
                candidate.translate("请把窗户打开。") // Different source, recorded warmup only.
                modelFirstCallMs = SystemClock.elapsedRealtime() - first
                StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
                    texts.map { text -> localCalls.incrementAndGet(); candidate.translate(text) }
                }
            }
            if (translationProvider == "moxhi60") {
                val began = SystemClock.elapsedRealtime()
                val codec = com.gemini.ocr.mtprobe.OrtxMt60TokenCodec.create(File(directory, "mt60-tokenizer/graphs"))
                val candidate = com.gemini.ocr.mtprobe.NativeMt60Probe.create(context,
                    File(directory, "mt60-model"), codec, threads = 2, allowSpinning = ortSpinning).also { mt60 = it }
                modelPrepareMs = SystemClock.elapsedRealtime() - began
                val first = SystemClock.elapsedRealtime()
                candidate.translate("请把窗户打开。")
                modelFirstCallMs = SystemClock.elapsedRealtime() - first
                StandaloneTranslator.verificationMt60ContextMode = if (mtContextProtocol == "source_only")
                    StandaloneTranslator.VerificationMt60ContextMode.SOURCE_ONLY else
                    StandaloneTranslator.VerificationMt60ContextMode.RAW_FORCED_PREFIX
                StandaloneTranslator.overrideMt60TranslatorForVerification = { texts, frozenContext, _ ->
                    val accepted = frozenContext.lastOrNull()?.takeIf { mtContextProtocol == "raw_forced_prefix" }?.let {
                        com.gemini.ocr.mtprobe.Mt60DecodePolicy.AcceptedDialoguePair(it.first, it.second)
                    }
                    check(armed.get()) { "Public-video observation is not armed" }
                    texts.map { text ->
                        val diagnostic = JSONObject().put("attempt", localCalls.incrementAndGet())
                            .put("started_at_ms", SystemClock.elapsedRealtime()).put("source_zh", text)
                            .put("previous_source_zh", accepted?.source ?: JSONObject.NULL)
                            .put("previous_translation_vi", accepted?.translation ?: JSONObject.NULL)
                        try {
                            val output = candidate.translate(text, accepted)
                            diagnostic.put("translation_vi", output.text).put("process_ms", output.processNs / 1_000_000.0)
                                .put("prefix_tokens", output.contextPrefixTokens).put("input_tokens", output.inputTokens)
                            output.text
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            diagnostic.put("cancelled", true)
                            throw cancelled
                        } catch (failure: Exception) {
                            diagnostic.put("failure_type", failure.javaClass.simpleName)
                            val failureCode = when (failure.message) {
                                "Model emitted an unsupported PAD/BOS/UNK token" -> "generated_control_or_unknown_token"
                                "Model returned an empty or truncated translation" -> "empty_or_truncated_suffix"
                                "Model did not produce EOS in 128 generated tokens" -> "no_eos_within_bound"
                                "Target prefix must contain ordinary HF tokens without PAD/BOS/EOS/UNK" -> "unsupported_context_prefix"
                                else -> "native_validation_or_runtime_failure"
                            }
                            diagnostic.put("failure_code", failureCode)
                            throw com.gemini.ocr.network.TranslationOutputGuard.RejectedOutput(
                                com.gemini.ocr.network.TranslationOutputGuard.Reason.NATIVE_MODEL_CONTRACT)
                        } finally {
                            diagnostic.put("finished_at_ms", SystemClock.elapsedRealtime())
                            synchronized(nativeModelDiagnostics) {
                                if (nativeModelDiagnostics.length() < 128) nativeModelDiagnostics.put(diagnostic)
                            }
                        }
                    }
                }
            }
            settings.apply {
                region = "subtitle"; subtitleCapture = "auto"; translationMode = "gemini_direct"
                connectionMode = "standalone"; fullScreen = true; interval = 150
                subtitleRegion = roi; trackSourceRow = trackRows; turboLatency = true
                fastOcrDownscale = false; ocrPreCorrection = false; stabilizeBeforeCloud = false
                costSavingMode = false; overlay = "subtitle"
            }
            PipelineTrace.observerForTest = { safeEvents.add(it); Unit }
            StandaloneTranslator.beforeNetworkCallForTest = {
                if (translationProvider != "gemini") throw IOException("Cloud is disabled during the Android offline probe")
                val inYouTube = device.currentPackageName == "com.google.android.youtube"
                synchronized(dispatches) {
                    if (!armed.get() || !inYouTube) {
                        denied.incrementAndGet()
                        inactiveDispatchesDenied.incrementAndGet()
                        throw IOException("YouTube verification dispatch not armed")
                    }
                    if (dispatches.get() >= maxPosts) {
                        denied.incrementAndGet()
                        postBudgetDenied.incrementAndGet()
                        throw IOException("YouTube verification POST budget reached; observation continues")
                    }
                    // Never refunded after dispatch, including cancellation. Reaching
                    // the cap does not stop OCR, recording or the fixed observation window.
                    if (dispatches.incrementAndGet() == maxPosts)
                        postBudgetReachedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                }
            }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val start = device.wait(Until.findObject(By.text("Bật dịch màn hình")), 10000)
            assertNotNull("Capture start control must be observed in the isolated app", start)
            start!!.click()
            val consent = device.wait(Until.findObject(By.res("android:id/button1").text(Pattern.compile(
                "Start now|Start recording|Start sharing|Share screen|Start|Bắt đầu ngay|Bắt đầu", Pattern.CASE_INSENSITIVE))), 10000)
            assertNotNull("MediaProjection consent was not observed; no permission UI is guessed", consent)
            consent!!.click()
            withTimeout(15000) { while (!SessionState.running.value) delay(50) }
            // Do not spend any dispatch permits on app transitions or advertisements.
            SessionState.paused.value = true
            // Opening exactly at the target used up its three-second tolerance while
            // confirming fullscreen/ads. Launch the already-tested lead-in and still
            // require the observed clock to cross the original target before arming.
            val launchTarget = target.copy(startSeconds = YouTubePlaybackProgress.seekLeadInSeconds(target.startSeconds))
            context.startActivity(Intent(Intent.ACTION_VIEW, launchTarget.uri).setPackage("com.google.android.youtube")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(10000) { while (device.currentPackageName != "com.google.android.youtube") delay(50) }
            // ACTION_VIEW can leave YouTube in its split view. Confirm the viewport
            // before interpreting a fixed full-screen ROI as missing subtitles.
            requireFullscreen("before_ad_handling", mayEnter = true)
            captureRoiEvidence("viewport")
            (device.findObject(By.desc("Phát video")) ?: device.findObject(By.desc("Play video")))?.click()
            val adStartedAt = SystemClock.elapsedRealtime()
            val adDeadline = adStartedAt + 60000L
            var contentSince: Long? = null
            while (SystemClock.elapsedRealtime() < adDeadline) {
                val isAd = listOf("Truy cập trang của nhà quảng cáo", "Visit advertiser", "Visit advertiser's site").any {
                    device.hasObject(By.text(it)) || device.hasObject(By.desc(it))
                } || device.hasObject(By.textContains("quảng cáo")) || device.hasObject(By.descContains("quảng cáo")) ||
                    device.hasObject(By.textContains("Được tài trợ")) || device.hasObject(By.textContains("Sponsored"))
                if (isAd) contentSince = null
                else if (contentSince == null) contentSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - contentSince >= 2000) break
                val skip = device.findObject(By.text("Bỏ qua")) ?: device.findObject(By.text("Bỏ qua quảng cáo"))
                    ?: device.findObject(By.desc("Bỏ qua quảng cáo")) ?: device.findObject(By.desc("Skip ad"))
                if (skip != null) { skip.click(); adsSkipped++ }
                delay(200)
            }
            adWaitMs = SystemClock.elapsedRealtime() - adStartedAt
            fun readClock() = YouTubePlaybackProgress.latestOrNull {
                device.findObject(By.res("com.google.android.youtube", "timestamps_container"))?.contentDescription
            }
            if (readClock() == null) {
                YouTubePlaybackProgress.latestOrNull { exactPlayer()?.visibleBounds }?.let { bounds ->
                    device.click(bounds.left + 8, bounds.top + bounds.height() / 3)
                }
                delay(250)
            }
            val duration = requireNotNull(YouTubePlaybackProgress.durationSeconds(readClock())) {
                "The public video's duration must be observed before selecting the comparison segment"
            }
            val current = requireNotNull(YouTubePlaybackProgress.elapsedSeconds(readClock()))
            if (YouTubePlaybackProgress.needsComparisonSeek(current, target.startSeconds)) {
                val seekBar = requireNotNull(device.findObject(By.clazz("android.widget.SeekBar"))) {
                    "YouTube ignored the requested timestamp and no seek bar was observed"
                }
                val bounds = requireNotNull(YouTubePlaybackProgress.latestOrNull { seekBar.visibleBounds })
                val fraction = YouTubePlaybackProgress.seekFraction(
                    YouTubePlaybackProgress.seekLeadInSeconds(target.startSeconds), duration)
                // A tap can merely reveal modern YouTube controls. Prefer its
                // accessibility progress action; otherwise drag the observed thumb.
                var performed = false
                val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                instrumentation.uiAutomation.rootInActiveWindow?.let { nodes.add(it) }
                var inspected = 0
                while (nodes.isNotEmpty() && inspected++ < 2000) {
                    val node = nodes.removeFirst()
                    try {
                        if (!performed && node.packageName?.toString() == "com.google.android.youtube" &&
                            node.className?.toString() == "android.widget.SeekBar" && node.rangeInfo != null) {
                            val range = node.rangeInfo
                            val arguments = android.os.Bundle().apply { putFloat(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                                (range.min + (range.max - range.min) * fraction).toFloat()) }
                            performed = node.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments)
                        }
                        if (!performed) for (child in 0 until node.childCount) node.getChild(child)?.let { nodes.add(it) }
                    } finally { @Suppress("DEPRECATION") node.recycle() }
                }
                while (nodes.isNotEmpty()) { @Suppress("DEPRECATION") nodes.removeFirst().recycle() }
                if (performed) seekMethod = "observed_accessibility_progress"
                else {
                    val from = YouTubePlaybackProgress.seekFraction(current.coerceAtMost(duration - 1).toInt(), duration)
                    device.drag(bounds.left + ((bounds.width() - 1) * from).toInt(), bounds.centerY(),
                        bounds.left + ((bounds.width() - 1) * fraction).toInt(), bounds.centerY(), 20)
                    seekMethod = "observed_seekbar_thumb_drag"
                }
                delay(500)
            }
            // Ad skipping and seek can restore the tablet split layout after the
            // first geometry check. Re-enter now, before the original clock gate.
            requireFullscreen("after_ads_and_seek", mayEnter = true)
            // ACTION_VIEW/seek may pause after our initial Play lookup. Require a
            // changing observed player clock, not merely a full-screen still image.
            var initialClock: Long? = null
            var lastRevealAt = 0L
            val playbackDeadline = SystemClock.elapsedRealtime() + 35000L
            while (SystemClock.elapsedRealtime() < playbackDeadline) {
                val play = device.findObject(By.desc("Phát video")) ?: device.findObject(By.desc("Play video"))
                if (play != null) { YouTubePlaybackProgress.latestOrNull { play.click() }; delay(250) }
                val clock = YouTubePlaybackProgress.elapsedSeconds(readClock())
                if (clock != null) {
                    observedStartSeconds = clock
                    if (initialClock == null || clock < initialClock!!) initialClock = clock
                    else if (YouTubePlaybackProgress.verifiedObservationStart(initialClock!!, clock, target.startSeconds) != null) {
                        playbackProgressVerified = true; break
                    }
                } else if (SystemClock.elapsedRealtime() - lastRevealAt >= 2000L) {
                    val observedPlayer = exactPlayer()
                    YouTubePlaybackProgress.latestOrNull { observedPlayer?.visibleBounds }?.let { bounds ->
                        device.click(bounds.left + 8, bounds.top + bounds.height() / 3)
                    }
                    lastRevealAt = SystemClock.elapsedRealtime()
                }
                delay(200)
            }
            require(playbackProgressVerified) { "The observed YouTube player clock did not advance; no live benchmark was armed" }
            // Never arm from the pre-ad snapshot or a generic description/root.
            // Fail closed if layout changed after the clock gate; do not relax its tolerance.
            requireFullscreen("immediately_before_observation", mayEnter = false)
            captureRoiEvidence("armed-viewport")
            var finalClock = YouTubePlaybackProgress.elapsedSeconds(readClock())
            if (finalClock == null) {
                // Saving the PNG can outlast YouTube's controls auto-hide timer.
                // Reveal only the observed exact public player, then poll briefly;
                // never substitute the earlier clock or widen the original target gate.
                val bounds = if (device.currentPackageName == "com.google.android.youtube")
                    YouTubePlaybackProgress.latestOrNull { exactPlayer()?.visibleBounds } else null
                if (bounds != null) {
                    device.click(bounds.left + 8, bounds.top + bounds.height() / 3)
                    val finalClockDeadline = SystemClock.elapsedRealtime() + 500L
                    do {
                        finalClock = YouTubePlaybackProgress.elapsedSeconds(readClock())
                        if (finalClock != null) break
                        delay(50)
                    } while (SystemClock.elapsedRealtime() < finalClockDeadline)
                }
            }
            if (finalClock == null) {
                setupFailureReason = "final_player_clock_missing_after_bounded_reveal"
                error("The final public player clock was unavailable after a bounded controls reveal")
            }
            val verifiedFinalClock = requireNotNull(finalClock)
            if (verifiedFinalClock !in target.startSeconds.toLong()..target.startSeconds.toLong() + 3) {
                setupFailureReason = "final_player_clock_outside_original_target_tolerance"
                error("Fullscreen evidence completed outside the original requested start tolerance")
            }
            observedStartSeconds = verifiedFinalClock
            if (recordScreen) {
                // Public YouTube viewport only. The fixed command has a bounded lifetime;
                // it cannot navigate, read media files or upload recordings.
                recording = OwnedPublicScreenRecording.start(instrumentation.uiAutomation,
                    File(directory, "$stem.mp4"), (durationMs / 1000 + 5).coerceAtMost(95).toInt())
                delay(150)
            }
            require(exactPlayerGeometryCoversDisplay()) { "YouTube left the verified fullscreen viewport before arming" }
            observingAt = SystemClock.elapsedRealtime()
            AndroidPerformanceTelemetry.counters.setEnabled(collectPerformance)
            armed.set(true)
            SessionState.paused.value = false
            PipelineTrace.event("youtube_observation_started", "provider" to translationProvider,
                "ocr_engine" to ocrEngine, "duration_ms" to durationMs, "ad_setup_wait_ms" to adWaitMs)
            stopReason = "duration_elapsed"
            val deadline = observingAt + durationMs
            var nextAdCheck = observingAt
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(50)
                recordSubtitleState()
                if (!roiSourceEvidenceSaved && SessionState.lastOcr.value.isNotBlank()) {
                    captureRoiEvidence("source")
                    roiSourceEvidenceSaved = true
                }
                val performance = AndroidPerformanceTelemetry.snapshot.value
                if (collectPerformance && performance.sampledAtMs != null &&
                    performance.sampledAtMs != lastPerformanceSampleAt) {
                    lastPerformanceSampleAt = performance.sampledAtMs
                    performanceSamples.put(JSONObject().put("at_ms", performance.sampledAtMs)
                        .put("window_ms", performance.windowMs ?: JSONObject.NULL)
                        .put("cpu_one_core_percent", performance.cpuOneCorePercent ?: JSONObject.NULL)
                        .put("pss_kib", performance.pssKiB ?: JSONObject.NULL)
                        .put("capture_fps", performance.frames[PerformanceFrameStage.CAPTURE_ACQUIRED]?.perSecond ?: JSONObject.NULL)
                        .put("ocr_fps", performance.frames[PerformanceFrameStage.OCR_COMPLETED]?.perSecond ?: JSONObject.NULL)
                        .put("canvas_fps", performance.frames[PerformanceFrameStage.CANVAS_DRAWN]?.perSecond ?: JSONObject.NULL))
                }
                if (device.currentPackageName != "com.google.android.youtube") {
                    stopReason = "left_youtube"; break
                }
                if (SystemClock.elapsedRealtime() >= nextAdCheck) {
                    nextAdCheck = SystemClock.elapsedRealtime() + 1000
                    if (!exactPlayerGeometryCoversDisplay()) {
                        armed.set(false)
                        viewportChecks.put(JSONObject().put("label", "observation_geometry_lost")
                            .put("verified_at_ms", SystemClock.elapsedRealtime()).put("covers_display", false))
                        stopReason = "left_verified_fullscreen_viewport"
                        break
                    }
                    val adVisible = device.hasObject(By.text("Truy cập trang của nhà quảng cáo")) ||
                        device.hasObject(By.desc("Truy cập trang của nhà quảng cáo")) ||
                        device.hasObject(By.text("Visit advertiser")) ||
                        device.hasObject(By.textContains("Được tài trợ")) || device.hasObject(By.textContains("Sponsored"))
                    if (adVisible) {
                        armed.set(false)
                        advertisementInterrupted = true
                        stopReason = "advertisement_interrupted_observation"
                        break
                    }
                }
                val status = SessionState.status.value
                if (Regex("\\((?:4|5)[0-9]{2}\\)").containsMatchIn(status)) {
                    stopReason = "provider_http_error"; break
                }
            }
            if (stopReason == "duration_elapsed" && postBudgetReachedAt.get() > 0L)
                stopReason = "duration_elapsed_with_post_budget_cap"
            completed = stopReason in setOf("duration_elapsed", "duration_elapsed_with_post_budget_cap")
        } catch (error: Throwable) {
            runCatching { captureRoiEvidence("failure") }
            runCatching { device.dumpWindowHierarchy(File(directory, "$stem-failure-window.xml")) }
            setupErrorType = error.javaClass.simpleName
            stopReason = "setup_or_observation_exception"
            throw error
        } finally {
            armed.set(false)
            stoppedAt = SystemClock.elapsedRealtime()
            withContext(NonCancellable) {
                recording?.let { owned ->
                    recordingCleanStop = try { owned.stop(); true } catch (_: Exception) { false }
                }
            }
            if (collectPerformance) {
                val sample = AndroidPerformanceTelemetry.snapshot.value
                performanceFinal = JSONObject().put("enabled", sample.enabled)
                    .put("sampled_at_ms", sample.sampledAtMs ?: JSONObject.NULL)
                    .put("cpu_one_core_percent", sample.cpuOneCorePercent ?: JSONObject.NULL)
                    .put("cpu_window_ms", sample.cpuWindowMs ?: JSONObject.NULL)
                    .put("pss_kib_snapshot_not_peak", sample.pssKiB ?: JSONObject.NULL)
                    .put("frame_window_ms", sample.windowMs ?: JSONObject.NULL)
                    .put("last_visible_source", sample.lastVisibleTranslationSource?.name ?: JSONObject.NULL)
                for ((stage, value) in sample.frames) performanceFinal!!.put(stage.name,
                    JSONObject().put("count", value.completed).put("per_second", value.perSecond ?: JSONObject.NULL))
                for ((tier, value) in sample.caches) performanceFinal!!.put(tier.name,
                    JSONObject().put("hits", value.hits).put("misses", value.misses)
                        .put("lookup_unit", tier.lookupUnit).put("hit_rate", value.hitRate ?: JSONObject.NULL))
            }
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            try {
                withContext(NonCancellable) { withTimeout(10000) { while (SessionState.running.value) delay(50) } }
            } finally {
                StandaloneTranslator.beforeNetworkCallForTest = null
                StandaloneTranslator.overrideGeminiTranslator = null
                StandaloneTranslator.overrideMt60TranslatorForVerification = null
                StandaloneTranslator.verificationMt60ContextMode = StandaloneTranslator.VerificationMt60ContextMode.RAW_FORCED_PREFIX
                PipelineTrace.observerForTest = null
                try {
                    withContext(NonCancellable) { paddle?.releaseAfterCaptureStopped() }
                } finally {
                    try {
                        withContext(NonCancellable) { mt60?.release() }
                    } finally {
                        offline?.close()
                        configurator.setWaitForIdleTimeout(originalIdleTimeout)
                        StandaloneTranslator.clearDirectCache()
                        original.restore(settings)
                        persist()
                    }
                }
            }
        }
    }
}
