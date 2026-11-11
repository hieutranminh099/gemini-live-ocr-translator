package com.gemini.ocr.capture

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import com.gemini.ocr.MainActivity
import com.gemini.ocr.R
import com.gemini.ocr.SessionState
import com.gemini.ocr.TranslationBackendState
import com.gemini.ocr.network.CostOptimizationEngine
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.SelfLearningDatabase
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.network.TranslationClient
import com.gemini.ocr.ocr.OcrBlock
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.overlay.TranslationOverlay
import com.gemini.ocr.overlay.SubtitleRegionSelector
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.util.zip.CRC32

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private val imageThread = HandlerThread("OCRFrames")
    private lateinit var imageHandler: Handler
    private val imageMailbox = LatestImageMailbox<ImageReader, Image>()
    private val frameTimeline = FrameTimeline()
    private val ocrReuse = OcrReuseGuard()
    private var lastNativeOcr: List<OcrBlock>? = null
    private var lastOcrWorkMs = 0L
    private var sourceContextItems = emptyList<Pair<String, String>>()
    private var sourceCacheNamespace = ""
    private val isProcessing = AtomicBoolean(false)
    private val frameDiffer = SubtitleRoiCropper.SubtitleFrameDiffer()
    private var overlay: TranslationOverlay? = null
    private var ocr: OcrProcessor? = null
    private var network: TranslationClient? = null
    private var captureJob: Job? = null
    private var standaloneJob: Job? = null
    private var standaloneSession = false
    private var pendingId = ""
    private var candidate = ""
    private var sentSignature = ""
    private val stability = StableText()
    private val cloudStability = StableText()
    private val memory = TranslationMemory()
    private var memoryAllowed = false
    private val currentTranslations = mutableMapOf<String, String>()
    private val correctedVisibleSources = mutableMapOf<String, String>()
    private var visibleDialogueScope: Pair<String, Long>? = null
    private val visibleDialoguePairs = mutableSetOf<Pair<String, String>>()
    private val translationSources = mutableMapOf<String, com.gemini.ocr.PerformanceTranslationSource>()
    private var pendingTexts = emptyMap<String, String>()
    private var pendingSince = 0L
    private var quotaWaitUntil = 0L
    private var captureGeneration = 0L
    private var sequence = 0L
    private val retries = TranslationRetryState()
    private var lastBlocks = emptyList<OcrBlock>()
    private var lastSize = Pair(1, 1)
    private var foregroundApp: String? = null
    private var blockedPreviously = false
    private var captureVisible = true
    private var translatedFrame: JSONArray? = null
    private var frameRequested = false
    private var forceHighPrecisionNext = false
    private var regionSelector: SubtitleRegionSelector? = null
    private var pendingSignature = ""
    private val pipeline = SubtitlePipeline()
    private val rowTracker = SourceRowTracker()
    private val debounce = SubtitleDebounce()
    private val captureWakeup = CaptureWakeup()
    private var settleCaptureJob: Job? = null
    private var detectedAt = 0L
    private var pendingToken: SubtitlePipeline.RequestToken? = null
    private var latestPendingSupported = false
    private var captureStateSupported = false
    private var frameSequence = 0L
    private var observedAt = 0L
    private var replyAt = 0L
    private var lastFrameAt = 0L
    private var lastFrameSourceAt = 0L
    private var sourceObservationUncertain = false
    private var lastFrameId = 0L
    private var lastQueueMs = 0L
    private var displayRequestId = ""
    private var lastDrawGeneration = -1L
    private var latestSnapshotFingerprint: OcrFrameFingerprint? = null
    private val oneFrame: Boolean get() = settings.subtitleFocus && settings.subtitleCapture == "frame"
    private fun isStandaloneActive(): Boolean =
        standaloneSession || (SessionState.running.value && !SessionState.connected.value)
    private fun textKey(text: String) = if (settings.subtitleFocus) SubtitleText.sourceIdentity(text, settings.language) else text
    private fun memoryKey(text: String): String {
        val modeKey = if (isStandaloneActive() && sourceCacheNamespace.isNotEmpty())
            "${settings.mode}:${settings.translationMode}:$sourceCacheNamespace" else settings.mode
        return "${settings.language}|${settings.profile}|$modeKey|${textKey(text)}"
    }
    private fun retryKey(signature: String) = "${settings.language}|${settings.profile}|${settings.mode}|$signature"

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        SelfLearningDatabase.ensureInitialized(this)
        imageThread.start()
        imageHandler = Handler(imageThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "TOGGLE" -> { toggle(); return START_NOT_STICKY }
            "SELECT_REGION" -> {
                if (SessionState.running.value) selectRegion() else stopSelf()
                return START_NOT_STICKY
            }
            "CAPTURE_FRAME" -> {
                if (SessionState.running.value) requestFrame() else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (SessionState.running.value || intent == null) { if (intent == null) stopSelf(); return START_NOT_STICKY }
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, notification())
            SessionState.running.value = true
            SessionState.paused.value = false
            SessionState.blocks.value = 0; SessionState.cacheHits.value = 0; SessionState.preloadedHits.value = 0
            SessionState.fuzzyHits.value = 0; SessionState.savedApiCalls.value = 0; SessionState.noiseFiltered.value = 0
            SessionState.ocrCorrectedCount.value = 0; SessionState.bankSize.value = SelfLearningDatabase.size(this)
            SessionState.latency.value = 0; SessionState.instantMs.value = 0; SessionState.ocrMs.value = 0; SessionState.ocrFrames.value = 0
            SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
            StandaloneTranslator.clearDirectCache() // A new viewing session must not inherit another film's context.
            standaloneSession = true
            SessionState.standaloneActive.value = true
            memoryAllowed = true
            latestPendingSupported = true
            ocr = OcrProcessor.create(settings.language)
            scope.launch {
                while (isActive) {
                    delay(1000)
                    try {
                        if (com.gemini.ocr.AndroidPerformanceTelemetry.sampleIfDue()) {
                            PipelineTrace.event("performance_sampled")
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        PipelineTrace.event("performance_sample_failed", "error_type" to error.javaClass.simpleName)
                    }
                }
            }
            overlay = TranslationOverlay(this, settings, { toggle() }, { stopSelf() }, { requestFrame() }, { selectRegion() }, { cancelPending() })
            SessionState.provider.value = "standalone"
            SessionState.backend.value = TranslationBackendState(
                provider = "standalone",
                mode = settings.translationMode,
                translationAvailable = true
            )
            SessionState.status.value = if (oneFrame) "Đang chạy độc lập • Chạm 1 KHUNG để dịch" else "Đang chạy độc lập • Tự động dịch trên điện thoại"
            overlay?.feedback(SessionState.status.value)
            scope.launch {
                StandaloneTranslator.preWarm(this@ScreenCaptureService, settings.language)
                StandaloneTranslator.prepareOfflineModel(this@ScreenCaptureService, settings.language) { msg ->
                    SessionState.offlineModelStatus.value = msg
                }
            }
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) { if (display != null) configureReader(width, height) }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    captureVisible = isVisible
                    if (!isVisible) resetFrame()
                }
            }, Handler(Looper.getMainLooper()))
            val wm = getSystemService(WindowManager::class.java)
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            configureReader(bounds.width(), bounds.height())
            display = projection!!.createVirtualDisplay("GeminiOCR", reader!!.width, reader!!.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
            captureJob = scope.launch { captureLoop() }
        } catch (_: Exception) {
            SessionState.status.value = "Không thể thu màn hình. Kiểm tra quyền rồi bật dịch lại."
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction("TOGGLE"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Gemini OCR đang dịch màn hình")
            .setContentText("Chạm nút nổi để tạm dừng; giữ để kết thúc.").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tạm dừng / Tiếp tục", pause).build())
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build()).build()
    }
    private fun configureReader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val scale = minOf(1.0, 1920.0 / maxOf(w, h))
        val width = (w * scale).roundToInt().coerceAtLeast(1)
        val height = (h * scale).roundToInt().coerceAtLeast(1)
        if (reader?.width == width && reader?.height == height) return
        val previous = reader
        // Retain a static image independently of native OCR, which owns a copied Bitmap.
        previous?.let { old ->
            imageHandler.post { imageMailbox.deactivate(old); old.close() }
        }
        val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
            LatestImageMailbox.REQUIRED_MAX_IMAGES)
        imageMailbox.activate(replacement)
        reader = replacement
        replacement.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.let { image ->
                    com.gemini.ocr.AndroidPerformanceTelemetry.counters.recordFrame(com.gemini.ocr.PerformanceFrameStage.CAPTURE_ACQUIRED)
                    // offer owns rejected images too; it never waits for native OCR.
                    imageMailbox.offer(source, image, SystemClock.elapsedRealtime())
                }
            } catch (_: IllegalStateException) {
                // A replaced/closed reader can race with an already queued callback.
            }
        }, imageHandler)
        display?.apply { resize(width, height, resources.displayMetrics.densityDpi); surface = reader!!.surface }
        resetFrame()
        overlay?.updateBounds()
    }
    private data class CapturedFrame(val bitmap: Bitmap, val width: Int, val height: Int, val crop: android.graphics.Rect?,
        val epoch: Long, val id: Long, val acquiredAt: Long, val captureMs: Long,
        val fingerprint: Long, val sourceId: Long, val sourceAt: Long) {
        fun fingerprintStamp() = OcrFrameFingerprint(id, fingerprint, crop?.left ?: 0, crop?.top ?: 0,
            crop?.right ?: width, crop?.bottom ?: height)
    }
    private suspend fun snapshot(epoch: Long, forceOcr: Boolean = false, minimumSourceAt: Long? = null): CapturedFrame? = withContext(imageHandler.asCoroutineDispatcher()) {
        val started = SystemClock.elapsedRealtime()
        val source = reader ?: return@withContext null
        imageMailbox.withLatest(source) { entry ->
            val image = entry.image
            val sourceImageSequence = entry.sequence
            val sourceImageAt = entry.acquiredAtMs
            if (minimumSourceAt != null && sourceImageAt < minimumSourceAt) {
                PipelineTrace.event("capture_skipped", "reason" to "pre_response_image",
                    "source_image_id" to sourceImageSequence, "source_image_age_ms" to started - sourceImageAt)
                return@withLatest null
            }
            val plane = image.planes[0]
            val selected = if (settings.subtitleFocus) SubtitleRegion.pixels(settings.subtitleRegion, image.width, image.height) else null
            val crop = selected?.let { if (settings.trackSourceRow && !oneFrame) rowTracker.captureRegion(it, started) else it }
            if (crop != null && plane.pixelStride >= 4) {
                // Copy only the selected ROI from the RGBA plane with exact rowStride & pixelStride math (BlueStacks safe).
                val (safeCrop, pixels) = SubtitleRoiCropper.extractRoiRgbaBuffer(
                    planeBuffer = plane.buffer,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    requestedCrop = crop
                ) ?: return@withLatest null
                // Requirement 4.2: Fast frame diffing — skip OCR if ROI pixels did not change within 300ms
                if (!oneFrame && !forceOcr && frameDiffer.shouldSkipOcr(pixels, safeCrop.width(), safeCrop.height(), started)) {
                    PipelineTrace.event("capture_skipped", "reason" to "roi_unchanged",
                        "source_image_id" to sourceImageSequence, "source_image_age_ms" to started - sourceImageAt)
                    return@withLatest null
                }
                val fingerprint = CRC32().apply { update(pixels.duplicate()) }.value
                val bitmap = Bitmap.createBitmap(safeCrop.width(), safeCrop.height(), Bitmap.Config.ARGB_8888)
                try { bitmap.copyPixelsFromBuffer(pixels) }
                catch (error: Exception) { bitmap.recycle(); throw error }
                return@withLatest CapturedFrame(bitmap, image.width, image.height, safeCrop, epoch, ++frameSequence, started,
                    SystemClock.elapsedRealtime() - started, fingerprint, sourceImageSequence, sourceImageAt)
            }
            val paddedWidth = (plane.rowStride / plane.pixelStride).coerceAtLeast(image.width)
            val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(plane.buffer.duplicate().apply { rewind() })
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                if (cropped !== bitmap) bitmap.recycle()
                CapturedFrame(cropped, image.width, image.height, null, epoch, ++frameSequence, started,
                    SystemClock.elapsedRealtime() - started, CRC32().apply { update(plane.buffer.duplicate()) }.value,
                    sourceImageSequence, sourceImageAt)
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34) {
            val metrics = resources.displayMetrics
            configureReader(metrics.widthPixels, metrics.heightPixels)
        }
    }
    private fun toggle() {
        SessionState.paused.value = !SessionState.paused.value
        sendCaptureState()
        if (SessionState.paused.value) { SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = "" }
        overlay?.paused(SessionState.paused.value)
        resetFrame()
        SessionState.status.value = if (SessionState.paused.value) "Đã tạm dừng" else if (oneFrame) "Dừng video ở câu cần dịch, rồi chạm 1 KHUNG" else "Đang nhận diện văn bản…"
    }
    private fun requestFrame() {
        if (regionSelector != null) return
        SessionState.paused.value = false; overlay?.paused(false)
        sendCaptureState()
        resetFrame(); frameRequested = true; retries.clearConfigurationFailures()
        captureWakeup.request(CaptureWakeup.Reason.EXPLICIT_FRAME)
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        SessionState.timings.value = ""; displayRequestId = ""; replyAt = 0
        SessionState.status.value = if (oneFrame) "Đang lấy một khung phụ đề…" else "Đang quét lại • Tiếp tục theo dõi phụ đề"
        overlay?.feedback(if (oneFrame) "Đang đọc một khung phụ đề…" else SessionState.status.value)
    }
    private fun retranslateHighPrecision() {
        if (regionSelector != null) return
        SessionState.paused.value = false; overlay?.paused(false)
        forceHighPrecisionNext = true
        retries.clearConfigurationFailures()
        if (lastBlocks.isNotEmpty()) {
            cancelFrame()
            currentTranslations.clear(); translationSources.clear(); correctedVisibleSources.clear()
            sentSignature = ""
            SessionState.status.value = "Đang dịch lại với chế độ chính xác cao (Gemini Pro)…"
            overlay?.feedback("Đang gọi Gemini Pro (Chính xác cao)…\nOCR: ${SessionState.lastOcr.value.take(72)}")
            sendLatest()
        } else {
            resetFrame()
            forceHighPrecisionNext = true
            frameRequested = true
            captureWakeup.request(CaptureWakeup.Reason.EXPLICIT_FRAME)
            SessionState.status.value = "Đang quét khung hình để dịch chính xác cao (Gemini Pro)…"
            overlay?.feedback(SessionState.status.value)
        }
    }
    private fun cancelPending() {
        if (!oneFrame) { SessionState.paused.value = true; overlay?.paused(true) }
        sendCaptureState()
        resetFrame()
        SessionState.status.value = if (oneFrame) "Đã hủy chờ dịch • Chạm 1 KHUNG để thử lại" else "Đã tạm dừng dịch • Chạm ▶ hoặc QUÉT LẠI để tiếp tục"
        overlay?.feedback(SessionState.status.value)
    }
    private fun sendCaptureState() {
        if (captureStateSupported) network?.send(JSONObject().put("type", "capture_state").put("active", !SessionState.paused.value))
    }
    private fun selectRegion() {
        if (regionSelector != null) return
        resetFrame(); overlay?.editing(true)
        regionSelector = SubtitleRegionSelector(this, settings.subtitleRegion) { selected ->
            regionSelector = null
            if (selected != null) settings.subtitleRegion = selected
            overlay?.editing(false); resetFrame()
            SessionState.status.value = if (oneFrame) "Đã chọn vùng • Dừng video rồi chạm 1 KHUNG" else "Đã chọn vùng • Tự động theo dõi phụ đề mới"
            overlay?.feedback(SessionState.status.value)
        }
    }
    private fun cancelSupersededSourceRequest() {
        if (!settings.subtitleFocus || oneFrame || isStandaloneActive()) cancelFrame()
    }
    private fun cancelFrame() {
        quotaWaitUntil = 0L
        standaloneJob?.cancel(); standaloneJob = null
        pendingId = ""; pendingTexts = emptyMap(); pendingSince = 0; pendingSignature = ""
        pendingToken = null; replyAt = 0
        overlay?.busy(false)
        network?.cancel()
    }
    private fun lookupCachedTranslation(text: String): String? {
        if (forceHighPrecisionNext || !isStandaloneActive()) return null
        // An accepted current answer keeps its text and provider provenance.
        // Re-reading an older cache row must not replace it or count as a lookup.
        currentTranslations[textKey(text)]?.let { return it }
        val found = lookupCachedTranslationImpl(text)
        com.gemini.ocr.AndroidPerformanceTelemetry.counters.recordCacheLookups(
            com.gemini.ocr.PerformanceCacheTier.PHONE_EXACT, if (found != null) 1 else 0, if (found == null) 1 else 0)
        if (found != null) translationSources[textKey(text)] = com.gemini.ocr.PerformanceTranslationSource.PHONE_CACHE
        return found
    }
    private fun lookupCachedTranslationImpl(text: String): String? {
        if (forceHighPrecisionNext) return null
        // The server owns connected dialogue snapshots. Until the protocol exposes
        // that namespace, a phone cache keyed only by source text can change roles.
        if (!isStandaloneActive()) return null
        val mKey = memoryKey(text)
        memory[mKey]?.let { return it }
        // A newly reviewed answer must win over old global machine/legacy caches.
        // The scoped store is queried on IO by translateStandaloneMode, never here on Main.
        if (settings.memoryFilmId.isNotBlank()) return null
        if (!isStandaloneActive()) return null
        if (settings.translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT) {
            StandaloneTranslator.lookupDirectLru(text, sourceCacheNamespace)?.let { directHit ->
                memory.put(mKey, directHit)
                return directHit
            }
            return null
        }
        val persistent = PhoneTranslationCache.lookup(
            context = this,
            cacheKey = mKey,
            sourceText = text,
            rawGlossary = settings.localGlossary,
            preloadedBankEnabled = sourceContextItems.isEmpty(),
            fuzzyEnabled = settings.costSavingMode && sourceContextItems.isEmpty()
        ) ?: return null
        memory.put(mKey, persistent)
        return persistent
    }
    private fun resetFrame(clearMemory: Boolean = false) {
        settleCaptureJob?.cancel(); settleCaptureJob = null
        captureWakeup.reset()
        lastFrameSourceAt = 0L
        sourceObservationUncertain = false
        frameTimeline.clear()
        ocrReuse.clear(); lastNativeOcr = null; lastOcrWorkMs = 0
        frameDiffer.reset()
        isProcessing.set(false)
        captureGeneration++
        pipeline.updateRegion(PipelineTrace.hash("${settings.language}|${settings.profile}|${settings.mode}|${settings.subtitleRegion}|${reader?.width}|${reader?.height}"))
        pipeline.reset()
        rowTracker.reset()
        debounce.reset()
        cancelFrame(); stability.reset(); cloudStability.reset(); currentTranslations.clear(); translationSources.clear(); correctedVisibleSources.clear()
        candidate = ""; sentSignature = ""; translatedFrame = null
        frameRequested = false; forceHighPrecisionNext = false
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        if (clearMemory) memory.clear()
        SessionState.timings.value = ""; displayRequestId = ""; replyAt = 0
        SessionState.frameToDraw.value = 0
        overlay?.clear()
    }
    private fun blocked(): Boolean = regionSelector != null || SessionState.paused.value || SessionState.activityVisible.value || SessionState.videoActivityVisible.value || SessionState.batchActivityVisible.value || !captureVisible ||
        !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked || excluded()
    private fun excluded(): Boolean {
        try {
        val excluded = settings.excludedApps.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (excluded.isEmpty()) return false
        val ops = getSystemService(AppOpsManager::class.java)
        val allowed = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
                      else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        if (allowed != AppOpsManager.MODE_ALLOWED) return true // Fail closed when exclusion cannot be verified.
        val now = System.currentTimeMillis()
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 60000, now) ?: return true
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundApp = event.packageName
        }
        return foregroundApp == null || foregroundApp in excluded
        } catch (_: Exception) {
            // OEM/profile restrictions must fail closed, not terminate the capture coroutine.
            return true
        }
    }
    private fun capturePeriodMs() = CaptureCadence.periodMs(settings.interval, settings.subtitleFocus,
        isStandaloneActive(), settings.turboLatency, oneFrame, lastOcrWorkMs,
        awaitingConfirmation = ResponseFrameGate.requiredImageAt(oneFrame, replyAt, lastFrameSourceAt) != null)
    /** Capture owns a one-slot mailbox; OCR has exactly one consumer and never awaits Gemini. */
    private suspend fun captureLoop() = coroutineScope {
        val frames = Channel<CapturedFrame>(1, BufferOverflow.DROP_OLDEST) { it.bitmap.recycle() }
        val producer = launch {
            var previousCaptureAt = 0L
            try {
                while (isActive) {
                    val minSpacing = CaptureCadence.minSpacingMs(settings.turboLatency)
                    val period = capturePeriodMs()
                    val wakeup = captureWakeup.awaitNext(period)
                    if (wakeup != null) {
                        // A reply can arrive while native OCR still owns the preceding frame.
                        // Keep one bounded mailbox and prevent hints from creating a busy loop.
                        val spacing = minSpacing - (SystemClock.elapsedRealtime() - previousCaptureAt)
                        if (spacing > 0) delay(spacing)
                    }
                    if (blocked()) {
                        if (!blockedPreviously) resetFrame()
                        blockedPreviously = true
                        continue
                    }
                    blockedPreviously = false
                    if (pendingId.isNotEmpty() && !SessionState.connected.value && !isStandaloneActive()) cancelFrame()
                    if (pendingId.isNotEmpty() && SystemClock.elapsedRealtime() - pendingSince > 55000) {
                        pendingToken?.let { pipeline.retry(it) }
                        cancelFrame(); sentSignature = ""
                        val failure = retries.failed(retryKey(candidate), "request_timeout", "Gemini chưa trả lời trong thời gian chờ.", SystemClock.elapsedRealtime())
                        SessionState.status.value = failure.status(SystemClock.elapsedRealtime())
                        overlay?.feedback(failure.feedback(SystemClock.elapsedRealtime()), true)
                    }
                    if (pendingId.isNotEmpty() && pendingSignature == candidate)
                        overlay?.progress((SystemClock.elapsedRealtime() - pendingSince) / 1000, SessionState.lastOcr.value,
                            ((quotaWaitUntil - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0))
                    if (oneFrame && !frameRequested) continue
                    frameRequested = false
                    val epoch = captureGeneration
                    try {
                        if (settings.adjacentSubtitles && overlay?.prepareForCapture() != true) continue
                        if (settings.fullScreen && !settings.adjacentSubtitles) { overlay?.sampling(true); delay(if (settings.turboLatency) 45 else 80) }
                        // Keep ownership across the dispatcher return, even during service cancellation.
                        val minimumSourceAt = ResponseFrameGate.requiredImageAt(oneFrame, replyAt, lastFrameSourceAt)
                        val frame = withContext(NonCancellable) {
                            snapshot(epoch, forceOcr = wakeup != null || minimumSourceAt != null, minimumSourceAt = minimumSourceAt)
                        }
                        if (frame != null) {
                            if (!isActive) { frame.bitmap.recycle(); break }
                            previousCaptureAt = frame.acquiredAt
                            if (wakeup != null) PipelineTrace.event("capture_wakeup", "reason" to wakeup.name.lowercase(),
                                "frame_id" to frame.id, "source_image_id" to frame.sourceId,
                                "source_image_age_ms" to frame.acquiredAt - frame.sourceAt)
                            latestSnapshotFingerprint = frame.fingerprintStamp()
                            frames.send(frame)
                        }
                        else if (oneFrame) frameRequested = true
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { overlay?.feedback("Chưa lấy được khung hình • Kiểm tra quyền thu màn hình", true) }
                    finally { overlay?.sampling(false) }
                }
            } finally { frames.close() }
        }
        try {
            for (frame in frames) {
                try {
                    isProcessing.set(true)
                    if (frame.epoch != captureGeneration || blocked()) continue
                    if (!ResponseFrameGate.canConfirm(oneFrame, replyAt, frame.sourceAt)) {
                        PipelineTrace.event("ocr_discarded", "frame_id" to frame.id, "reason" to "pre_response_image")
                        continue
                    }
                    val started = SystemClock.elapsedRealtime()
                    lastQueueMs = started - frame.acquiredAt - frame.captureMs
                    // ML Kit Tasks cannot be cancelled. One native task owns its bitmap until completion.
                    val crop = frame.crop ?: if (settings.subtitleFocus) SubtitleRegion.pixels(settings.subtitleRegion, frame.width, frame.height) else null
                    val reuseStamp = OcrReuseGuard.Stamp(frame.epoch, pipeline.regionId,
                        pipeline.screenGeneration, pipeline.signature, frame.width, frame.height,
                        OcrReuseGuard.Bounds(crop?.left ?: 0, crop?.top ?: 0,
                            crop?.right ?: frame.width, crop?.bottom ?: frame.height),
                        frame.fingerprint, frame.acquiredAt, frame.sourceAt)
                    val reused = settings.subtitleFocus && lastNativeOcr != null &&
                        ocrReuse.canReuse(reuseStamp, replyAt.takeIf { it > 0 }, allowEmpty = true)
                    val observation = if (reused) com.gemini.ocr.ocr.OcrObservation(
                        checkNotNull(lastNativeOcr), nativePasses = 0) else withContext(Dispatchers.Default + NonCancellable) {
                        val cropped = if (frame.crop == null && crop != null) Bitmap.createBitmap(frame.bitmap, crop.left, crop.top, crop.width(), crop.height()) else frame.bitmap
                        try {
                            ocr!!.processObservation(
                                bitmap = cropped,
                                retainSourceFragments = settings.subtitleFocus,
                                fastDownscale = settings.fastOcrDownscale,
                                ocrPreCorrection = settings.ocrPreCorrection && settings.memoryFilmId.isBlank(),
                                traceFrameId = frame.id,
                                enforceSubtitlePolicy = settings.subtitleFocus
                            )
                        } finally {
                            if (cropped !== frame.bitmap && !cropped.isRecycled) {
                                cropped.recycle()
                            }
                        }
                    }
                    val raw = observation.blocks
                    currentCoroutineContext().ensureActive()
                    val completion = OcrCompletionGate.afterNative(frame.epoch, captureGeneration,
                        blocked(), oneFrame, replyAt, frame.sourceAt, frame.fingerprintStamp(), latestSnapshotFingerprint)
                    if (!completion.canPublish) {
                        PipelineTrace.event("ocr_discarded", "frame_id" to frame.id, "reason" to completion.rejection)
                        continue
                    }
                    var nativeOcrMs = if (reused) 0L else SystemClock.elapsedRealtime() - started
                    lastOcrWorkMs = nativeOcrMs
                    if (!reused) SessionState.ocrMs.value = nativeOcrMs
                    // Moving scenery can change every ROI frame without changing the caption.
                    // Diagnose that motion; the next OCR observation owns text-generation changes.
                    // Session and post-response source-image guards above remain mandatory.
                    if (completion.pixelsAdvanced) {
                        PipelineTrace.event("ocr_frame_advanced", "frame_id" to frame.id,
                            "latest_frame_id" to latestSnapshotFingerprint?.frameId, "ocr_ms" to nativeOcrMs)
                    }
                    SessionState.ocrFrames.value++
                    SessionState.ocrArea.value = crop?.let { (100L * it.width() * it.height() / (frame.width.toLong() * frame.height)).toInt() } ?: 100
                    if (observation.uncertain && !oneFrame) {
                        deferUncertainSource()
                        PipelineTrace.event("ocr_uncertain", "frame_id" to frame.id,
                            "native_passes" to observation.nativePasses, "recovery_reason" to observation.recoveryReason,
                            "capture_ms" to frame.captureMs, "ocr_queue_ms" to lastQueueMs, "ocr_ms" to nativeOcrMs,
                            "source_image_id" to frame.sourceId, "source_image_age_ms" to frame.acquiredAt - frame.sourceAt)
                        continue
                    }
                    if (crop != null && settings.trackSourceRow && !oneFrame) {
                        val rows = SourceRowTracker.selectSourceRows(raw, settings.language, true).map { block ->
                            block.copy(rect = android.graphics.Rect(block.rect).apply { offset(crop.left, crop.top) })
                        }
                        rowTracker.observe(rows, SubtitleRegion.pixels(settings.subtitleRegion, frame.width, frame.height))
                    }
                    val displayed = translatedFrame?.let { value -> (0 until value.length()).joinToString(" ") { value.getJSONObject(it).getString("text") } }
                    val selection = OcrSourceSelection.select(raw, crop, settings.language, frame.height,
                        settings.region, settings.subtitleFocus, oneFrame,
                        isStandaloneActive() && settings.turboLatency, settings.stabilizeBeforeCloud,
                        displayed, stability, cloudStability)
                    nativeOcrMs = if (reused) 0L else SystemClock.elapsedRealtime() - started
                    lastOcrWorkMs = nativeOcrMs
                    if (!reused) SessionState.ocrMs.value = nativeOcrMs
                    if (reused) PipelineTrace.event("ocr_reused", "frame_id" to frame.id,
                        "region_id" to pipeline.regionId, "screen_generation" to pipeline.screenGeneration,
                        "source_image_id" to frame.sourceId, "response_confirmation" to (replyAt > 0),
                        "reuse_ms" to SystemClock.elapsedRealtime() - started)
                    selection.trace(frame.id, frame.captureMs, lastQueueMs, nativeOcrMs,
                        frame.sourceId, frame.acquiredAt - frame.sourceAt)
                    if (selection.renderedCopy) continue
                    sourceObservationUncertain = false
                    val blocks = selection.blocks
                    val signature = StableText.signature(blocks.map { textKey(it.text) })
                    lastBlocks = blocks; lastSize = Pair(frame.width, frame.height)
                    lastFrameAt = frame.acquiredAt; lastFrameSourceAt = frame.sourceAt; lastFrameId = frame.id
                    if (pipeline.observe(signature)) {
                        cancelSupersededSourceRequest()
                        currentTranslations.clear(); translationSources.clear(); correctedVisibleSources.clear(); translatedFrame = null; replyAt = 0; displayRequestId = ""; overlay?.clear()
                        SessionState.lastTranslation.value = ""; SessionState.latency.value = 0
                        SessionState.frameToDraw.value = 0; SessionState.timings.value = ""
                        candidate = signature; sentSignature = ""; observedAt = frame.acquiredAt
                        detectedAt = SystemClock.elapsedRealtime()
                        sourceContextItems = StandaloneTranslator.getRecentContextSnapshot(1)
                        sourceCacheNamespace = StandaloneTranslator.cacheNamespace(
                            settings.profile, settings.localGlossary, sourceContextItems, settings.memoryFilmId, settings.memoryDomain)
                        frameTimeline.clear()
                        if (blocks.isNotEmpty()) frameTimeline.observed(FrameTimeline.Source(
                            pipeline.regionId, pipeline.screenGeneration, frame.id, frame.acquiredAt,
                            frame.captureMs, lastQueueMs, nativeOcrMs, detectedAt))
                        debounce.changed(detectedAt, blocks.isEmpty())
                        scheduleSettledCapture(signature, blocks.isEmpty())
                        PipelineTrace.event("source_changed", "frame_id" to frame.id, "region_id" to pipeline.regionId,
                            "screen_generation" to pipeline.screenGeneration, "source_hash" to PipelineTrace.hash(blocks.joinToString(" ") { it.text }),
                            "empty" to blocks.isEmpty(), "capture_ms" to frame.captureMs, "ocr_queue_ms" to lastQueueMs,
                            "ocr_ms" to nativeOcrMs, "source_image_id" to frame.sourceId,
                            "source_image_age_ms" to frame.acquiredAt - frame.sourceAt, "stabilization_ms" to 0,
                            "adaptive_period_ms" to capturePeriodMs())
                    }
                    if (!reused) {
                        lastNativeOcr = raw
                        ocrReuse.recordAcceptedOcr(reuseStamp.copy(screenGeneration = pipeline.screenGeneration,
                            sourceIdentity = pipeline.signature))
                    }
                    SessionState.lastOcr.value = blocks.joinToString(" ") { it.text }
                    if (blocks.isEmpty()) {
                        SessionState.lastTranslation.value = ""; overlay?.clear()
                        if (pendingId.isNotEmpty() && latestPendingSupported && !oneFrame) {
                            standaloneJob?.cancel(); standaloneJob = null
                            network?.send(JSONObject().put("type", "cancel").put("preserve_session", true))
                            pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSignature = ""; pendingSince = 0
                            overlay?.busy(false)
                        }
                        SessionState.status.value = "Không có chữ nguồn trong vùng đã chọn"
                        overlay?.feedback(if (oneFrame) "Chưa thấy chữ • Chọn VÙNG rồi thử 1 KHUNG" else "Chưa thấy chữ trong VÙNG đã chọn • Chạm VÙNG để kiểm tra")
                        continue
                    }
                    if (memoryAllowed && !forceHighPrecisionNext) {
                        blocks.forEach { block -> lookupCachedTranslation(block.text)?.let { currentTranslations[textKey(block.text)] = it } }
                    }
                    // A reply must be checked against a fresh OCR snapshot taken AFTER its arrival.
                    if (replyAt == 0L || frame.acquiredAt >= replyAt) renderCurrent()
                    if (signature == sentSignature && !forceHighPrecisionNext) continue
                    if (pendingId.isNotEmpty() && !latestPendingSupported) {
                        if (pendingSignature != candidate) overlay?.feedback("Đã nhận câu mới • Chờ Gemini\nOCR: ${SessionState.lastOcr.value.take(72)}")
                        continue
                    }
                    sendLatest()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { overlay?.feedback("Chưa đọc được khung hình • Kiểm tra VÙNG phụ đề Tiếng Trung", true) }
                finally {
                    isProcessing.set(false)
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                }
            }
        } finally { producer.cancel(); frames.cancel() }
    }
    /** Uncertain native OCR neither confirms an old line nor proves a new empty source. */
    private fun deferUncertainSource() {
        sourceObservationUncertain = true
        lastFrameSourceAt = 0L
        translatedFrame = null
        ocrReuse.clear(); lastNativeOcr = null
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        SessionState.status.value = "Đang đọc lại chữ nguồn"
        overlay?.clear()
        overlay?.feedback("Đang đọc lại chữ nguồn • Theo dõi khung mới")
    }
    /** Recheck the current source at its deadline instead of sleeping another adaptive period. */
    private fun scheduleSettledCapture(signature: String, empty: Boolean) {
        settleCaptureJob?.cancel(); settleCaptureJob = null
        if (empty || oneFrame || !settings.subtitleFocus) return
        val epoch = captureGeneration
        val generation = pipeline.screenGeneration
        settleCaptureJob = scope.launch {
            val remaining = debounce.remainingMs(
                now = SystemClock.elapsedRealtime(),
                turbo = settings.turboLatency,
                standaloneZeroDebounce = isStandaloneActive() && settings.turboLatency
            )
            if (remaining > 0) delay(remaining)
            if (epoch == captureGeneration && generation == pipeline.screenGeneration &&
                candidate == signature && sentSignature != signature && !blocked()) {
                captureWakeup.request(CaptureWakeup.Reason.SETTLED_SOURCE)
            }
        }
    }
    private fun sendLatest() {
        if (sourceObservationUncertain || (pendingId.isNotEmpty() && !latestPendingSupported) || blocked() || lastBlocks.isEmpty()) return
        val blocks = lastBlocks
        val signature = candidate
        val highPrecision = forceHighPrecisionNext
        if (memoryAllowed && !highPrecision) {
            blocks.forEach { block -> lookupCachedTranslation(block.text)?.let { currentTranslations[textKey(block.text)] = it } }
        }
        renderCurrent()
        val missing = if (highPrecision) blocks else blocks.filter { textKey(it.text) !in currentTranslations }
        if (missing.isEmpty()) {
            if (pendingId.isNotEmpty() && pendingSignature != signature && latestPendingSupported) {
                standaloneJob?.cancel(); standaloneJob = null
                network?.send(JSONObject().put("type", "cancel").put("preserve_session", true))
                pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSignature = ""; pendingSince = 0
                overlay?.busy(false)
            }
            sentSignature = signature
            SessionState.blocks.value += blocks.size; SessionState.cacheHits.value += blocks.size
            SessionState.latency.value = 1L
            val totalMs = (SystemClock.elapsedRealtime() - observedAt).coerceAtLeast(SessionState.ocrMs.value + 1L)
            val compactStats = SessionState.formatCompactTelemetry(SessionState.ocrMs.value, 1L, totalMs)
            SessionState.timings.value = compactStats
            SessionState.status.value = "Dùng cache trên điện thoại • $compactStats"
            overlay?.feedback("$compactStats\nOCR: ${SessionState.lastOcr.value.take(72)}")
            PipelineTrace.event("phone_cache", "region_id" to pipeline.regionId, "screen_generation" to pipeline.screenGeneration)
            return
        }
        if (SessionState.connected.value && !standaloneSession && !highPrecision) {
            SessionState.standaloneActive.value = false
            val now = SystemClock.elapsedRealtime()
            retries.waiting(retryKey(signature), now)?.let { failure ->
                SessionState.status.value = failure.status(now)
                overlay?.busy(false)
                overlay?.feedback(failure.feedback(now), true)
                return
            }
            if (!oneFrame && debounce.remainingMs(SystemClock.elapsedRealtime(), settings.turboLatency) > 0) return
            val id = (++sequence).toString()
            val token = pipeline.request(id) ?: return
            val json = JSONObject().put("type", "translate").put("request_id", id).put("source", settings.language)
                .put("profile", settings.profile).put("mode", settings.mode)
                .put("blocks", JSONArray().apply { missing.forEach { put(it.json(lastSize.first, lastSize.second)) } })
            if (latestPendingSupported) json.put("region_id", token.regionId).put("screen_generation", token.screenGeneration).put("latest_only", true)
            if (network?.send(json) == true) {
                pendingToken = token; replyAt = 0
                pendingId = id; pendingTexts = missing.associate { it.id to it.text }
                pendingSince = SystemClock.elapsedRealtime(); sentSignature = signature; pendingSignature = signature
                frameTimeline.requested(token, pendingSince)
                overlay?.busy(true)
                overlay?.progress(0, pendingTexts.values.joinToString(" "))
                SessionState.status.value = if (oneFrame) "Đang dịch một khung • Giữ nguyên câu trên video" else "Đang dịch…"
                PipelineTrace.event("request_sent", "request_id" to id, "region_id" to token.regionId,
                    "source_hash" to PipelineTrace.hash(SessionState.lastOcr.value),
                    "screen_generation" to token.screenGeneration, "frame_id" to lastFrameId,
                    "ocr_ms" to SessionState.ocrMs.value, "ocr_queue_ms" to lastQueueMs, "observed_to_send_ms" to pendingSince - observedAt)
                PipelineTrace.event("request_settled", "request_id" to id, "screen_generation" to token.screenGeneration,
                    "stabilization_ms" to pendingSince - detectedAt)
            } else {
                pipeline.retry(token)
                SessionState.status.value = "Không gửi được sang Windows • Kiểm tra kết nối máy tính"
                overlay?.busy(false)
                overlay?.feedback(SessionState.status.value, true)
            }
        } else if (isStandaloneActive() || highPrecision) {
            SessionState.standaloneActive.value = true
            val now = SystemClock.elapsedRealtime()
            if (!highPrecision) {
                retries.waiting(retryKey(signature), now)?.let { failure ->
                    SessionState.status.value = failure.status(now)
                    overlay?.busy(false)
                    overlay?.feedback(failure.feedback(now), true)
                    return
                }
                if (!oneFrame && debounce.remainingMs(now, settings.turboLatency, standaloneZeroDebounce = settings.turboLatency) > 0) return
            }
            forceHighPrecisionNext = false
            val id = (++sequence).toString()
            val token = pipeline.request(id, replaceCurrent = highPrecision) ?: return
            pendingToken = token; replyAt = 0
            pendingId = id; pendingTexts = missing.associate { it.id to it.text }
            pendingSince = now; sentSignature = signature; pendingSignature = signature
            frameTimeline.requested(token, pendingSince)
            PipelineTrace.event("request_sent", "transport" to "standalone", "request_id" to id,
                "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                "frame_id" to lastFrameId, "observed_to_send_ms" to now - observedAt)
            PipelineTrace.event("request_settled", "transport" to "standalone", "request_id" to id,
                "screen_generation" to token.screenGeneration, "stabilization_ms" to now - detectedAt)
            standaloneJob?.cancel()
            val requestNamespace = sourceCacheNamespace
            val requestContext = sourceContextItems.toList()
            standaloneJob = scope.launch {
                translateStandalone(token, signature, missing, highPrecision, requestNamespace, requestContext)
            }
        } else {
            SessionState.status.value = "Đã đọc chữ • Chưa kết nối Windows. Mở Companion và kiểm tra địa chỉ/cổng."
            overlay?.feedback(SessionState.status.value, true)
        }
    }
    private suspend fun translateStandalone(
        token: SubtitlePipeline.RequestToken,
        signature: String,
        missing: List<OcrBlock>,
        @Suppress("UNUSED_PARAMETER") forceHighPrecision: Boolean = false,
        requestNamespace: String = sourceCacheNamespace,
        requestContext: List<Pair<String, String>> = sourceContextItems
    ) {
        val mode = settings.translationMode
        val reviewedScope = com.gemini.ocr.network.ContextualMemoryRepository.snapshotScope(settings, requestNamespace)
        val reviewedStore = reviewedScope?.let { com.gemini.ocr.network.ContextualMemoryRepository.store(this) }
        val apiKey = settings.effectiveStandaloneApiKey()
        val requestSentAt = pendingSince
        overlay?.busy(true)
        overlay?.progress(0, missing.joinToString(" ") { it.text })

        try {
            val outcome = try { LiveRequestPolicy.request {
                StandaloneTranslator.translateStandaloneMode(
                    context = this@ScreenCaptureService,
                    texts = missing.map { it.text },
                    translationMode = mode,
                    apiKey = apiKey,
                    glossaryRaw = settings.localGlossary,
                    profile = settings.profile,
                    deferSideEffects = true,
                    cacheNamespace = requestNamespace,
                    contextItems = requestContext,
                    allowContextFreeBank = requestContext.isEmpty() && reviewedScope == null,
                    reviewedMemory = reviewedStore,
                    memoryScope = reviewedScope,
                    applyReviewedCorrections = settings.ocrPreCorrection,
                    applyLegacyOcrCorrection = settings.ocrPreCorrection && reviewedScope == null,
                    onQuotaWait = { remaining ->
                        withContext(Dispatchers.Main.immediate) {
                            if (pendingId == token.requestId && pipeline.accepts(token)) {
                                quotaWaitUntil = SystemClock.elapsedRealtime() + remaining
                                SessionState.status.value = "Đang chờ lượt Gemini"
                            }
                        }
                    }
                ) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (pendingId != token.requestId || !pipeline.accepts(token)) {
                    PipelineTrace.event("result_discarded", "request_id" to token.requestId,
                        "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                        "reason" to "stale_failure")
                    return
                }
                if (error is com.gemini.ocr.network.TranslationOutputGuard.RejectedOutput) {
                    // Keep this source submitted: invalid model output must not trigger a
                    // quota-consuming retry on every OCR pass. A new source or explicit
                    // rescan owns the next attempt. No context/cache/learning is committed.
                    pendingId = ""; pendingToken = null; pendingTexts = emptyMap()
                    pendingSince = 0; pendingSignature = ""; sentSignature = signature
                    overlay?.busy(false)
                    SessionState.status.value = error.message.orEmpty()
                    overlay?.feedback(SessionState.status.value, true)
                    PipelineTrace.event("result_rejected", "request_id" to token.requestId,
                        "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                        "reason" to error.reason.traceCode, "automatic_retry" to false)
                    return
                }
                throw error
            }
            val receivedAt = SystemClock.elapsedRealtime()
            val accepted = pendingId == token.requestId && pipeline.accepts(token) && !blocked()
            PipelineTrace.event(if (accepted) "result_received" else "result_discarded",
                "transport" to "standalone", "request_id" to token.requestId,
                "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                "request_to_result_ms" to receivedAt - requestSentAt, "translation_ms" to outcome.latencyMs,
                "quota_wait_ms" to outcome.quotaWaitMs, "from_bank" to outcome.fromBank,
                *(outcome.transportMetrics?.traceFields() ?: emptyArray()))
            if (!accepted) return
            frameTimeline.received(token, receivedAt)

            val acceptedTexts = missing.map { it.text }
            StandaloneTranslator.commitAcceptedOutcome(acceptedTexts, outcome, requestNamespace, updateContext = false)
            reviewedScope?.let { frozen ->
                missing.indices.forEach { index ->
                    com.gemini.ocr.network.ContextualMemoryRepository.publishCandidate(
                        com.gemini.ocr.network.ContextualMemoryRepository.ReviewCandidate(frozen,
                            outcome.correctedSources.getOrNull(index) ?: missing[index].text,
                            outcome.translations[index], token.requestId, token.regionId,
                            token.screenGeneration, outcome.provider))
                }
            }
            val cacheWrites = missing.mapIndexed { index, block ->
                val translated = outcome.translations[index]
                currentTranslations[textKey(block.text)] = translated
                correctedVisibleSources[textKey(block.text)] = outcome.correctedSources.getOrNull(index) ?: block.text
                translationSources[textKey(block.text)] = outcome.telemetrySource
                val key = memoryKey(block.text)
                memory.put(key, translated)
                Triple(key, block.text, translated)
            }
            retries.succeeded(retryKey(signature))
            replyAt = if (oneFrame) 0L else receivedAt
            displayRequestId = token.requestId
            lastDrawGeneration = -1L
            if (outcome.fromBank) {
                SessionState.instantMs.value = outcome.latencyMs
            }
            SessionState.latency.value = outcome.latencyMs
            if (!oneFrame) captureWakeup.request(CaptureWakeup.Reason.RESPONSE)
            renderCurrent()
            pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSince = 0; pendingSignature = ""
            overlay?.busy(false)
            SessionState.blocks.value += missing.size
            val initialOcrMs = frameTimeline.drawing(SystemClock.elapsedRealtime())?.source?.ocrMs ?: SessionState.ocrMs.value
            if (SessionState.frameToDraw.value > 0) {
                SessionState.timings.value = SessionState.formatMeasuredTelemetry(initialOcrMs,
                    outcome.latencyMs, SessionState.frameToDraw.value)
            }
            SessionState.status.value = if(!ResponseFrameGate.canConfirm(oneFrame,replyAt,lastFrameSourceAt))
                "Đã dịch • Đang xác nhận chữ còn trên màn hình" else "${outcome.provider} • Đã nhận bản dịch"
            if(!ResponseFrameGate.canConfirm(oneFrame,replyAt,lastFrameSourceAt)) {
                overlay?.feedback(SessionState.status.value)
                PipelineTrace.event("result_awaiting_source_confirmation","request_id" to token.requestId,
                    "region_id" to token.regionId,"screen_generation" to token.screenGeneration)
            }
            // Persist only an accepted result, after scheduling its Canvas draw. Never block Main on SQLite.
            scope.launch(Dispatchers.IO) {
                com.gemini.ocr.network.BackgroundPersistence.run(write = {
                    cacheWrites.forEach { (key, source, translated) ->
                        PhoneTranslationCache.put(this@ScreenCaptureService, key, translated, source, saveToBank = false)
                    }
                    StandaloneTranslator.persistAcceptedOutcome(this@ScreenCaptureService, acceptedTexts,
                        outcome, mode, allowContextFreeBank = requestContext.isEmpty() && reviewedScope == null)
                    // Machine output is a Draft, not evidence of accuracy. Never overwrite
                    // reviewed memory, and keep this IO work off the Canvas path.
                    if (reviewedScope != null && reviewedStore != null && !outcome.fromBank) {
                        acceptedTexts.indices.forEach { index ->
                            reviewedStore.recordDraft(com.gemini.ocr.network.ContextualTranslationMemory.Entry(
                                reviewedScope, outcome.correctedSources.getOrNull(index) ?: acceptedTexts[index],
                                outcome.translations[index], reviewedScope.domain,
                                com.gemini.ocr.network.ContextualTranslationMemory.Provenance(
                                    "model://generated", "generated-output-unreviewed"), 0.0))
                        }
                    }
                }, failed = { error ->
                    PipelineTrace.event("cache_persist_failed", "request_id" to token.requestId,
                        "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                        "error_type" to error.javaClass.simpleName)
                })
            }
        } catch (error: CancellationException) {
            releaseStandaloneRequest(token)
            throw error
        } catch (error: Exception) {
            if (!releaseStandaloneRequest(token)) {
                PipelineTrace.event("result_discarded", "request_id" to token.requestId,
                    "region_id" to token.regionId,"screen_generation" to token.screenGeneration,"reason" to "stale_failure")
                return
            }
            val policy=LiveRequestPolicy.failure(error)
            val now=SystemClock.elapsedRealtime()
            val failure=retries.failed(retryKey(signature),policy.code,
                if(policy.code=="request_timeout")"Gemini phản hồi quá chậm. Đã hủy yêu cầu; có thể tiếp tục dịch câu mới."
                else error.message?:"Chưa dịch được câu mới. Kiểm tra kết nối Gemini.",now,policy.delayMs)
            SessionState.status.value=failure.status(now)
            overlay?.feedback(failure.feedback(now),true)
            PipelineTrace.event("request_failed","transport" to "standalone","request_id" to token.requestId,
                "region_id" to token.regionId,"screen_generation" to token.screenGeneration,
                "code" to policy.code,"error_type" to error.javaClass.simpleName,"request_wall_ms" to now-requestSentAt,
                "automatic_retry" to failure.automaticRetry)
        } finally {
            // A response discarded while our Activity is visible must not leave an owned slot
            // occupied. An older cancelled coroutine cannot clear a newer request's state.
            releaseStandaloneRequest(token)
        }
    }
    private fun releaseStandaloneRequest(token:SubtitlePipeline.RequestToken):Boolean {
        if(pendingId!=token.requestId || pendingToken!=token)return false
        pipeline.retry(token)
        pendingId="";pendingToken=null;pendingTexts=emptyMap();pendingSince=0;pendingSignature="";sentSignature=""
        overlay?.busy(false)
        return true
    }
    private fun onMessage(json: JSONObject) {
        when (json.optString("type")) {
            "hello", "invalidate" -> {
                SessionState.standaloneActive.value = false
                SessionState.provider.value = json.optString("provider", "")
                SessionState.backend.value = TranslationBackendState(
                    provider = SessionState.provider.value,
                    mode = json.optString("backend_mode", ""),
                    translationAvailable = json.opt("translation_available") as? Boolean,
                    experimentalCliEnabled = json.opt("experimental_cli_enabled") == true,
                )
                memoryAllowed = json.optBoolean("cache_enabled", false)
                if (json.optString("type") == "hello") latestPendingSupported = json.optJSONArray("capabilities")?.let { array ->
                    (0 until array.length()).any { array.optString(it) == "latest_pending" }
                } == true
                if (json.optString("type") == "hello") {
                    captureStateSupported = json.optJSONArray("capabilities")?.let { array ->
                        (0 until array.length()).any { array.optString(it) == "capture_state_v1" }
                    } == true
                    sendCaptureState()
                }
                resetFrame(clearMemory = true); retries.clear()
            }
            "result" -> {
                if (json.optString("request_id") != pendingId || SessionState.paused.value || blockedPreviously) return
                if (blocked()) { resetFrame(); blockedPreviously = true; return }
                val token = pendingToken ?: return
                val metadataValid = !latestPendingSupported || (json.optString("region_id") == token.regionId &&
                    json.optLong("screen_generation", -1) == token.screenGeneration)
                if (!metadataValid) {
                    PipelineTrace.event("result_discarded", "request_id" to token.requestId, "reason" to "correlation_mismatch")
                    pipeline.retry(token); cancelFrame(); sentSignature = ""
                    return
                }
                val matchesCurrentFrame = pipeline.accepts(token)
                val receivedAt = SystemClock.elapsedRealtime()
                val rtt = receivedAt - pendingSince
                PipelineTrace.event(if (matchesCurrentFrame) "result_received" else "result_discarded", "request_id" to token.requestId,
                    "region_id" to token.regionId, "screen_generation" to token.screenGeneration,
                    "round_trip_ms" to rtt, "transport_residual_ms" to (rtt - json.optLong("latency_ms")).coerceAtLeast(0),
                    "server_timings_ms" to json.optJSONObject("timings_ms"), "diagnostics" to json.optJSONObject("diagnostics"))
                val blocks = json.getJSONArray("blocks")
                for (i in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(i)
                    val original = pendingTexts[block.optString("id")] ?: continue
                    val translated = block.getString("text")
                    if (matchesCurrentFrame) {
                        currentTranslations[textKey(original)] = translated
                        translationSources[textKey(original)] = com.gemini.ocr.PerformanceTranslationSource.WINDOWS
                    }
                    // Connected results stay in this frame only; contextual reuse is
                    // checked by the server against its frozen dialogue snapshot.
                }
                if (matchesCurrentFrame) {
                    frameTimeline.received(token, receivedAt)
                    retries.succeeded(retryKey(token.signature))
                    replyAt = receivedAt; displayRequestId = token.requestId
                    if (oneFrame) renderCurrent() // Explicit one-frame mode translates the selected frozen frame.
                    SessionState.latency.value = json.optLong("latency_ms")
                    val times = json.optJSONObject("timings_ms")
                    SessionState.timings.value = "OCR ${SessionState.ocrMs.value} ms • Hàng đợi ${times?.optLong("queue") ?: 0} ms • Gemini ${times?.optLong("provider") ?: 0} ms • Mạng và xử lý ${(rtt - json.optLong("latency_ms")).coerceAtLeast(0)} ms"
                } else sentSignature = ""
                SessionState.blocks.value += pendingTexts.size
                SessionState.cacheHits.value += pendingTexts.size - blocks.length() + (0 until blocks.length()).count { blocks.getJSONObject(it).optBoolean("cached") }
                pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSince = 0; pendingSignature = ""
                overlay?.busy(false)
                SessionState.status.value = if (!matchesCurrentFrame) "Phụ đề đã đổi • Đang lấy câu mới nhất" else if (oneFrame) "Đã dịch khung đã chọn • Chạm 1 KHUNG để dịch câu tiếp theo" else "Đang dịch trực tiếp • ${json.optLong("latency_ms")} ms"
                overlay?.feedback(if (matchesCurrentFrame) "Máy chủ: ${json.optLong("latency_ms")} ms • Đang xác nhận chữ nguồn\nOCR: ${SessionState.lastOcr.value.take(72)}" else SessionState.status.value)
                // Automatic mode still requires OCR taken after the reply. Request that capture
                // now; do not let an older response bypass current-source/generation checks.
                if (matchesCurrentFrame && !oneFrame) captureWakeup.request(CaptureWakeup.Reason.RESPONSE)
                if (!matchesCurrentFrame && !oneFrame) sendLatest()
            }
            "error" -> {
                if (json.optString("request_id") != pendingId) return
                val token = pendingToken ?: return
                if (latestPendingSupported && (json.optString("region_id") != token.regionId ||
                    json.optLong("screen_generation", -1) != token.screenGeneration)) {
                    PipelineTrace.event("error_discarded", "request_id" to token.requestId, "reason" to "correlation_mismatch")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val code = json.optString("code")
                val message = json.optString("message", "Máy chủ chưa sẵn sàng")
                val retryAfterMs = json.optLong("retry_after_ms", 0L).takeIf { it > 0 }
                // Quota belongs to the provider, even if this subtitle already disappeared.
                // Keep correlation checks above and never apply stale per-sentence failures.
                val cooldown = if (code in setOf("rate_limited", "quota_exceeded", "daily_limit"))
                    retries.failed(retryKey(token.signature), code, message, now, retryAfterMs) else null
                if (pendingToken?.let { !pipeline.accepts(it) } == true) {
                    pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSignature = ""; pendingSince = 0
                    overlay?.busy(false)
                    if (!oneFrame) sendLatest()
                    return
                }
                pipeline.retry(token)
                val failure = cooldown ?: retries.failed(retryKey(token.signature), code, message, now, retryAfterMs)
                SessionState.status.value = failure.status(now)
                sentSignature = ""; pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSince = 0; pendingSignature = ""
                overlay?.busy(false)
                overlay?.feedback(failure.feedback(now), true)
                PipelineTrace.event("request_failed", "request_id" to token.requestId, "region_id" to token.regionId,
                    "screen_generation" to token.screenGeneration, "code" to failure.code, "automatic_retry" to failure.automaticRetry)
            }
        }
    }
    private fun renderCurrent() {
        if (sourceObservationUncertain || !ResponseFrameGate.canConfirm(oneFrame, replyAt, lastFrameSourceAt)) return
        val blocks = JSONArray()
        // Freeze exactly the source/target rows handed to Canvas. Accepted but undrawn
        // results stay in their original cache namespace and cannot become dialogue.
        val visiblePairs = mutableListOf<Pair<String, String>>()
        lastBlocks.forEach { block ->
            val key = textKey(block.text)
            currentTranslations[key]?.let { translated ->
                blocks.put(block.json(lastSize.first, lastSize.second).put("text", translated).put("cached", true))
                visiblePairs += (correctedVisibleSources[key] ?: block.text) to translated
            }
        }
        translatedFrame = blocks
        SessionState.lastTranslation.value = (0 until blocks.length()).joinToString("\n") { blocks.getJSONObject(it).getString("text") }
        if (blocks.length() == 0) { overlay?.clear(); return }
        val generation = pipeline.screenGeneration
        val regionId = pipeline.regionId
        val requestId = displayRequestId
        val standaloneDraw = isStandaloneActive()
        val frozenVisiblePairs = visiblePairs.toList()
        val readyAt = SystemClock.elapsedRealtime()
        val translatedHash = PipelineTrace.hash(SessionState.lastTranslation.value)
        val visibleSources = lastBlocks.filter { currentTranslations.containsKey(textKey(it.text)) }
            .map { translationSources[textKey(it.text)] ?: com.gemini.ocr.PerformanceTranslationSource.UNKNOWN }.toSet()
        val visibleSource = visibleSources.singleOrNull() ?: com.gemini.ocr.PerformanceTranslationSource.MIXED
        val drawTiming = frameTimeline.drawing(readyAt)
        overlay?.show(blocks) {
            if (generation != pipeline.screenGeneration || regionId != pipeline.regionId ||
                requestId != displayRequestId || sourceObservationUncertain || blocked() ||
                !ResponseFrameGate.canConfirm(oneFrame, replyAt, lastFrameSourceAt)) return@show
            if (standaloneDraw && isStandaloneActive()) {
                val scope = regionId to generation
                if (visibleDialogueScope != scope) {
                    visibleDialogueScope = scope
                    visibleDialoguePairs.clear()
                }
                frozenVisiblePairs.forEach { pair ->
                    if (visibleDialoguePairs.add(pair)) StandaloneTranslator.recordContextPair(pair.first, pair.second)
                }
            }
            if (lastDrawGeneration != generation) {
                lastDrawGeneration = generation
                if (pendingId.isEmpty() && SessionState.status.value == "Đã dịch • Đang xác nhận chữ còn trên màn hình") {
                    SessionState.status.value = "Đã hiện bản dịch"
                }
                com.gemini.ocr.AndroidPerformanceTelemetry.counters.recordVisibleTranslation(visibleSource)
                val drawnAt = SystemClock.elapsedRealtime()
                SessionState.frameToDraw.value = drawnAt - observedAt
                SessionState.timings.value = SessionState.formatMeasuredTelemetry(
                    drawTiming?.source?.ocrMs ?: SessionState.ocrMs.value,
                    SessionState.latency.value, drawnAt - observedAt)
                overlay?.feedback("Khung OCR → hiện: ${drawnAt - observedAt} ms • ${if (displayRequestId.isEmpty()) "Cache" else "Dịch: ${SessionState.latency.value} ms"}\nOCR: ${SessionState.lastOcr.value.take(72)}")
                PipelineTrace.event("overlay_drawn", "request_id" to displayRequestId, "region_id" to regionId,
                    "phone_cache" to displayRequestId.isEmpty(),
                    "screen_generation" to generation, "source_hash" to PipelineTrace.hash(SessionState.lastOcr.value),
                    "translation_hash" to translatedHash,
                    *(drawTiming?.fields(drawnAt) ?: arrayOf(
                        "overlay_ms" to drawnAt - readyAt,
                        "received_to_draw_ms" to if (replyAt > 0) drawnAt - replyAt else null,
                        "observed_to_draw_ms" to drawnAt - observedAt)))
            }
        }
    }
    private fun remap(blocks: JSONArray) {
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            lastBlocks.find { it.id == b.optString("id") }?.let { b.put("box", it.json(lastSize.first, lastSize.second).getJSONObject("box")) }
        }
    }
    override fun onDestroy() {
        regionSelector?.close(); regionSelector = null
        standaloneJob?.cancel(); standaloneJob = null
        captureJob?.cancel(); scope.cancel()
        captureWakeup.reset()
        network?.close(); overlay?.close(); memory.clear(); currentTranslations.clear(); translationSources.clear(); correctedVisibleSources.clear()
        val processor = ocr
        captureJob?.invokeOnCompletion { processor?.close() } ?: processor?.close()
        display?.release(); projection?.stop()
        val closingReader = reader
        reader = null
        closingReader?.setOnImageAvailableListener(null, null)
        imageHandler.post { imageMailbox.close(); closingReader?.close(); imageThread.quitSafely() }
        com.gemini.ocr.AndroidPerformanceTelemetry.counters.setEnabled(false)
        SessionState.running.value = false; SessionState.paused.value = false
        SessionState.standaloneActive.value = false
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
