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
    private val latestImage = AtomicReference<Image?>(null)
    private var overlay: TranslationOverlay? = null
    private var ocr: OcrProcessor? = null
    private var network: TranslationClient? = null
    private var captureJob: Job? = null
    private var pendingId = ""
    private var candidate = ""
    private var sentSignature = ""
    private val stability = StableText()
    private val memory = TranslationMemory()
    private var memoryAllowed = false
    private val currentTranslations = mutableMapOf<String, String>()
    private var pendingTexts = emptyMap<String, String>()
    private var pendingSince = 0L
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
    private var lastFrameId = 0L
    private var lastQueueMs = 0L
    private var displayRequestId = ""
    private var lastDrawGeneration = -1L
    private var sourceImageSequence = 0L
    // Hardware image arrival differs from acquiredAt: static screens can reuse the same image.
    private var sourceImageAt = 0L
    private var latestSnapshotFingerprint: OcrFrameFingerprint? = null
    private val oneFrame: Boolean get() = settings.subtitleFocus && settings.subtitleCapture == "frame"
    private fun textKey(text: String) = if (settings.subtitleFocus) SubtitleText.identity(text) else text
    private fun memoryKey(text: String) = "${settings.language}|${settings.profile}|${settings.mode}|${textKey(text)}"
    private fun retryKey(signature: String) = "${settings.language}|${settings.profile}|${settings.mode}|$signature"

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        imageThread.start()
        imageHandler = Handler(imageThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "TOGGLE" -> { toggle(); return START_NOT_STICKY }
        }
        if (SessionState.running.value || intent == null) { if (intent == null) stopSelf(); return START_NOT_STICKY }
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null || settings.token.isBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, notification())
            SessionState.running.value = true
            SessionState.paused.value = false
            SessionState.blocks.value = 0; SessionState.cacheHits.value = 0
            SessionState.latency.value = 0; SessionState.ocrMs.value = 0; SessionState.ocrFrames.value = 0
            SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
            ocr = OcrProcessor(settings.language)
            overlay = TranslationOverlay(this, settings, { toggle() }, { stopSelf() }, { requestFrame() }, { selectRegion() }, { cancelPending() })
            network = TranslationClient(settings, scope, { onMessage(it) }, {
                resetFrame(clearMemory = true)
                overlay?.feedback(if (oneFrame) "Đã kết nối Windows • Chạm 1 KHUNG để dịch" else "Đã kết nối Windows • Tự động theo dõi phụ đề")
            }).also { it.connect() }
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
        // Retain one hardware frame so static screens can be sampled even if the compositor emits no new frame.
        imageHandler.post { latestImage.getAndSet(null)?.close(); previous?.close() }
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.let { image ->
                        if (source === reader) {
                            sourceImageSequence++; sourceImageAt = SystemClock.elapsedRealtime()
                            latestImage.getAndSet(image)?.close()
                        } else image.close()
                    }
                } catch (_: IllegalStateException) { }
            }, imageHandler)
        }
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
    private suspend fun snapshot(epoch: Long): CapturedFrame? = withContext(imageHandler.asCoroutineDispatcher()) {
        val started = SystemClock.elapsedRealtime()
        val image = latestImage.get() ?: return@withContext null
        val plane = image.planes[0]
        val selected = if (settings.subtitleFocus) SubtitleRegion.pixels(settings.subtitleRegion, image.width, image.height) else null
        val crop = selected?.let { if (settings.trackSourceRow && !oneFrame) rowTracker.captureRegion(it, started) else it }
        if (crop != null && plane.pixelStride == 4) {
            // Copy only the selected strip from the RGBA plane. No full-screen bitmap allocation.
            val pixels = ByteBuffer.allocateDirect(crop.width() * crop.height() * 4)
            val source = plane.buffer.duplicate()
            for (y in crop.top until crop.bottom) {
                source.clear()
                val offset = y * plane.rowStride + crop.left * plane.pixelStride
                source.position(offset); source.limit(offset + crop.width() * 4)
                pixels.put(source)
            }
            pixels.flip()
            val fingerprint = CRC32().apply { update(pixels.duplicate()) }.value
            val bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
            try { bitmap.copyPixelsFromBuffer(pixels) }
            catch (error: Exception) { bitmap.recycle(); throw error }
            return@withContext CapturedFrame(bitmap, image.width, image.height, crop, epoch, ++frameSequence, started,
                SystemClock.elapsedRealtime() - started, fingerprint, sourceImageSequence, sourceImageAt)
        }
        val paddedWidth = plane.rowStride / plane.pixelStride
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
    private fun cancelFrame() {
        pendingId = ""; pendingTexts = emptyMap(); pendingSince = 0; pendingSignature = ""
        pendingToken = null; replyAt = 0
        overlay?.busy(false)
        network?.cancel()
    }
    private fun resetFrame(clearMemory: Boolean = false) {
        settleCaptureJob?.cancel(); settleCaptureJob = null
        captureWakeup.reset()
        captureGeneration++
        pipeline.updateRegion(PipelineTrace.hash("${settings.language}|${settings.profile}|${settings.mode}|${settings.subtitleRegion}|${reader?.width}|${reader?.height}"))
        pipeline.reset()
        rowTracker.reset()
        debounce.reset()
        cancelFrame(); stability.reset(); currentTranslations.clear()
        candidate = ""; sentSignature = ""; translatedFrame = null
        frameRequested = false
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        if (clearMemory) memory.clear()
        SessionState.timings.value = ""; displayRequestId = ""; replyAt = 0
        SessionState.frameToDraw.value = 0
        overlay?.clear()
    }
    private fun blocked(): Boolean = regionSelector != null || SessionState.paused.value || SessionState.activityVisible.value || !captureVisible ||
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
    /** Capture owns a one-slot mailbox; OCR has exactly one consumer and never awaits Gemini. */
    private suspend fun captureLoop() = coroutineScope {
        val frames = Channel<CapturedFrame>(1, BufferOverflow.DROP_OLDEST) { it.bitmap.recycle() }
        val producer = launch {
            var previousCaptureAt = 0L
            try {
                while (isActive) {
                    val period = (if (settings.subtitleFocus && !oneFrame) minOf(settings.interval, 100L) else settings.interval).coerceAtLeast(75)
                    val wakeup = captureWakeup.awaitNext(maxOf(period, SessionState.ocrMs.value * 3 / 2))
                    if (wakeup != null) {
                        // A reply can arrive while native OCR still owns the preceding frame.
                        // Keep one bounded mailbox and prevent hints from creating a busy loop.
                        val spacing = 75L - (SystemClock.elapsedRealtime() - previousCaptureAt)
                        if (spacing > 0) delay(spacing)
                    }
                    if (blocked()) {
                        if (!blockedPreviously) resetFrame()
                        blockedPreviously = true
                        continue
                    }
                    blockedPreviously = false
                    if (pendingId.isNotEmpty() && !SessionState.connected.value) cancelFrame()
                    if (pendingId.isNotEmpty() && SystemClock.elapsedRealtime() - pendingSince > 55000) {
                        pendingToken?.let { pipeline.retry(it) }
                        cancelFrame(); sentSignature = ""
                        val failure = retries.failed(retryKey(candidate), "request_timeout", "Gemini chưa trả lời trong thời gian chờ.", SystemClock.elapsedRealtime())
                        SessionState.status.value = failure.status(SystemClock.elapsedRealtime())
                        overlay?.feedback(failure.feedback(SystemClock.elapsedRealtime()), true)
                    }
                    if (pendingId.isNotEmpty() && pendingSignature == candidate)
                        overlay?.progress((SystemClock.elapsedRealtime() - pendingSince) / 1000, SessionState.lastOcr.value)
                    if (oneFrame && !frameRequested) continue
                    frameRequested = false
                    val epoch = captureGeneration
                    try {
                        if (settings.adjacentSubtitles && overlay?.prepareForCapture() != true) continue
                        if (settings.fullScreen && !settings.adjacentSubtitles) { overlay?.sampling(true); delay(80) }
                        // Keep ownership across the dispatcher return, even during service cancellation.
                        val frame = withContext(NonCancellable) { snapshot(epoch) }
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
                    if (frame.epoch != captureGeneration || blocked()) continue
                    val started = SystemClock.elapsedRealtime()
                    lastQueueMs = started - frame.acquiredAt - frame.captureMs
                    // ML Kit Tasks cannot be cancelled. One native task owns its bitmap until completion.
                    val crop = frame.crop ?: if (settings.subtitleFocus) SubtitleRegion.pixels(settings.subtitleRegion, frame.width, frame.height) else null
                    val raw = withContext(Dispatchers.Default + NonCancellable) {
                        val cropped = if (frame.crop == null && crop != null) Bitmap.createBitmap(frame.bitmap, crop.left, crop.top, crop.width(), crop.height()) else frame.bitmap
                        try { ocr!!.process(cropped, settings.subtitleFocus && settings.language in setOf("zh", "ja", "ko")) }
                        finally { if (cropped !== frame.bitmap) cropped.recycle() }
                    }
                    currentCoroutineContext().ensureActive()
                    SessionState.ocrMs.value = SystemClock.elapsedRealtime() - started
                    if (frame.epoch != captureGeneration || blocked()) {
                        PipelineTrace.event("ocr_discarded", "frame_id" to frame.id, "reason" to "capture_epoch")
                        continue
                    }
                    // A periodic full-region reacquisition and a tracked narrow strip have
                    // different pixels by construction. Their CRC difference says nothing about
                    // subtitle freshness. Preserve that OCR result so tracking can reacquire;
                    // capture epochs and request generations still reject obsolete sessions/text.
                    if (frame.fingerprintStamp().supersededByPixelChange(latestSnapshotFingerprint)) {
                        PipelineTrace.event("ocr_discarded", "frame_id" to frame.id, "reason" to "newer_roi_frame",
                            "ocr_ms" to SessionState.ocrMs.value)
                        continue
                    }
                    if (crop != null && settings.trackSourceRow && !oneFrame) {
                        val rows = SourceRowTracker.selectSourceRows(raw, settings.language, true).map { block ->
                            block.copy(rect = android.graphics.Rect(block.rect).apply { offset(crop.left, crop.top) })
                        }
                        rowTracker.observe(rows, SubtitleRegion.pixels(settings.subtitleRegion, frame.width, frame.height))
                    }
                    val recognized = if (crop != null) SubtitleRegion.merge(raw, crop, settings.language, !oneFrame) else raw
                    SessionState.ocrMs.value = SystemClock.elapsedRealtime() - started
                    SessionState.ocrFrames.value++
                    SessionState.ocrArea.value = crop?.let { (100L * it.width() * it.height() / (frame.width.toLong() * frame.height)).toInt() } ?: 100
                    val displayed = translatedFrame?.let { value -> (0 until value.length()).joinToString(" ") { value.getJSONObject(it).getString("text") } }
                    if (settings.subtitleFocus && displayed != null && SubtitleText.isRenderedCopy(recognized.joinToString(" ") { it.text }, displayed)) continue
                    val filtered = recognized.filter {
                        val middle = it.rect.exactCenterY() / frame.height
                        when (settings.region) { "dialogue" -> middle >= .5f; "center" -> middle in .2f.. .85f; else -> true }
                    }
                    val stable = if (settings.subtitleFocus) filtered.map { it.text }.toSet() else stability.observe(filtered.map { it.text })
                    val blocks = filtered.filter { it.text in stable }.mapIndexed { i, block -> block.copy(id = "b$i") }
                    val signature = StableText.signature(blocks.map { textKey(it.text) })
                    lastBlocks = blocks; lastSize = Pair(frame.width, frame.height)
                    lastFrameAt = frame.acquiredAt; lastFrameId = frame.id
                    if (pipeline.observe(signature)) {
                        if (!settings.subtitleFocus || oneFrame) cancelFrame()
                        currentTranslations.clear(); translatedFrame = null; replyAt = 0; displayRequestId = ""; overlay?.clear()
                        SessionState.lastTranslation.value = ""; SessionState.latency.value = 0
                        SessionState.frameToDraw.value = 0
                        candidate = signature; sentSignature = ""; observedAt = frame.acquiredAt
                        detectedAt = SystemClock.elapsedRealtime()
                        debounce.changed(detectedAt, blocks.isEmpty())
                        scheduleSettledCapture(signature, blocks.isEmpty())
                        PipelineTrace.event("source_changed", "frame_id" to frame.id, "region_id" to pipeline.regionId,
                            "screen_generation" to pipeline.screenGeneration, "source_hash" to PipelineTrace.hash(blocks.joinToString(" ") { it.text }),
                            "empty" to blocks.isEmpty(), "capture_ms" to frame.captureMs, "ocr_queue_ms" to lastQueueMs,
                            "ocr_ms" to SessionState.ocrMs.value, "source_image_id" to frame.sourceId,
                            "source_image_age_ms" to frame.acquiredAt - frame.sourceAt, "stabilization_ms" to 0,
                            "adaptive_period_ms" to maxOf(minOf(settings.interval, 100L).coerceAtLeast(75), SessionState.ocrMs.value * 3 / 2))
                    }
                    SessionState.lastOcr.value = blocks.joinToString(" ") { it.text }
                    if (blocks.isEmpty()) {
                        SessionState.lastTranslation.value = ""; overlay?.clear()
                        if (pendingId.isNotEmpty() && latestPendingSupported && !oneFrame) {
                            network?.send(JSONObject().put("type", "cancel").put("preserve_session", true))
                            pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSignature = ""; pendingSince = 0
                            overlay?.busy(false)
                        }
                        SessionState.status.value = "Không có chữ nguồn trong vùng đã chọn"
                        overlay?.feedback(if (oneFrame) "Chưa thấy chữ • Chọn VÙNG rồi thử 1 KHUNG" else "Không có chữ nguồn • Đang theo dõi phụ đề mới")
                        continue
                    }
                    if (memoryAllowed) blocks.forEach { block -> memory[memoryKey(block.text)]?.let { currentTranslations[textKey(block.text)] = it } }
                    // A reply must be checked against a fresh OCR snapshot taken AFTER its arrival.
                    if (replyAt == 0L || frame.acquiredAt >= replyAt) renderCurrent()
                    if (signature == sentSignature) continue
                    if (pendingId.isNotEmpty() && !latestPendingSupported) {
                        if (pendingSignature != candidate) overlay?.feedback("Đã nhận câu mới • Chờ Gemini\nOCR: ${SessionState.lastOcr.value.take(72)}")
                        continue
                    }
                    sendLatest()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { overlay?.feedback("Chưa đọc được khung hình • Kiểm tra VÙNG và ngôn ngữ", true) }
                finally { frame.bitmap.recycle() }
            }
        } finally { producer.cancel(); frames.cancel() }
    }
    /** Recheck the current source at its deadline instead of sleeping another adaptive period. */
    private fun scheduleSettledCapture(signature: String, empty: Boolean) {
        settleCaptureJob?.cancel(); settleCaptureJob = null
        if (empty || oneFrame || !settings.subtitleFocus) return
        val epoch = captureGeneration
        val generation = pipeline.screenGeneration
        settleCaptureJob = scope.launch {
            val remaining = debounce.remainingMs(SystemClock.elapsedRealtime())
            if (remaining > 0) delay(remaining)
            if (epoch == captureGeneration && generation == pipeline.screenGeneration &&
                candidate == signature && sentSignature != signature && !blocked()) {
                captureWakeup.request(CaptureWakeup.Reason.SETTLED_SOURCE)
            }
        }
    }
    private fun sendLatest() {
        if ((pendingId.isNotEmpty() && !latestPendingSupported) || blocked() || lastBlocks.isEmpty()) return
        val blocks = lastBlocks
        val signature = candidate
        if (memoryAllowed) blocks.forEach { block -> memory[memoryKey(block.text)]?.let { currentTranslations[textKey(block.text)] = it } }
        renderCurrent()
        val missing = blocks.filter { textKey(it.text) !in currentTranslations }
        if (missing.isEmpty()) {
            if (pendingId.isNotEmpty() && pendingSignature != signature && latestPendingSupported) {
                network?.send(JSONObject().put("type", "cancel").put("preserve_session", true))
                pendingId = ""; pendingToken = null; pendingTexts = emptyMap(); pendingSignature = ""; pendingSince = 0
                overlay?.busy(false)
            }
            sentSignature = signature
            SessionState.blocks.value += blocks.size; SessionState.cacheHits.value += blocks.size
            SessionState.latency.value = 0
            SessionState.status.value = "Dùng cache trên điện thoại • Không gọi AI"
            overlay?.feedback("Đã dịch từ cache • Theo dõi câu mới\nOCR: ${SessionState.lastOcr.value.take(72)}")
            PipelineTrace.event("phone_cache", "region_id" to pipeline.regionId, "screen_generation" to pipeline.screenGeneration)
            return
        }
        if (SessionState.connected.value) {
            val now = SystemClock.elapsedRealtime()
            retries.waiting(retryKey(signature), now)?.let { failure ->
                SessionState.status.value = failure.status(now)
                overlay?.busy(false)
                overlay?.feedback(failure.feedback(now), true)
                return
            }
            if (!oneFrame && debounce.remainingMs(SystemClock.elapsedRealtime()) > 0) return
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
        } else {
            SessionState.status.value = "Đã đọc chữ • Chưa kết nối Windows. Mở Companion và kiểm tra địa chỉ/cổng."
            overlay?.feedback(SessionState.status.value, true)
        }
    }
    private fun onMessage(json: JSONObject) {
        when (json.optString("type")) {
            "hello", "invalidate" -> {
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
                    if (matchesCurrentFrame) currentTranslations[textKey(original)] = translated
                    if (memoryAllowed) memory.put(memoryKey(original), translated)
                }
                if (matchesCurrentFrame) {
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
        if (replyAt > 0 && !oneFrame && lastFrameAt < replyAt) return
        val blocks = JSONArray()
        lastBlocks.forEach { block ->
            currentTranslations[textKey(block.text)]?.let { translated ->
                blocks.put(block.json(lastSize.first, lastSize.second).put("text", translated).put("cached", true))
            }
        }
        translatedFrame = blocks
        SessionState.lastTranslation.value = (0 until blocks.length()).joinToString("\n") { blocks.getJSONObject(it).getString("text") }
        if (blocks.length() == 0) { overlay?.clear(); return }
        val generation = pipeline.screenGeneration
        val regionId = pipeline.regionId
        val readyAt = SystemClock.elapsedRealtime()
        val translatedHash = PipelineTrace.hash(SessionState.lastTranslation.value)
        overlay?.show(blocks) {
            if (generation == pipeline.screenGeneration && regionId == pipeline.regionId && lastDrawGeneration != generation) {
                lastDrawGeneration = generation
                val drawnAt = SystemClock.elapsedRealtime()
                SessionState.frameToDraw.value = drawnAt - observedAt
                overlay?.feedback("Khung OCR → hiện: ${drawnAt - observedAt} ms • ${if (displayRequestId.isEmpty()) "Cache" else "Máy chủ: ${SessionState.latency.value} ms"}\nOCR: ${SessionState.lastOcr.value.take(72)}")
                PipelineTrace.event("overlay_drawn", "request_id" to displayRequestId, "region_id" to regionId,
                    "phone_cache" to displayRequestId.isEmpty(),
                    "screen_generation" to generation, "source_hash" to PipelineTrace.hash(SessionState.lastOcr.value),
                    "translation_hash" to translatedHash, "overlay_ms" to drawnAt - readyAt,
                    "received_to_draw_ms" to if (replyAt > 0) drawnAt - replyAt else 0,
                    "observed_to_draw_ms" to drawnAt - observedAt)
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
        captureJob?.cancel(); scope.cancel()
        captureWakeup.reset()
        network?.close(); overlay?.close(); memory.clear(); currentTranslations.clear()
        val processor = ocr
        captureJob?.invokeOnCompletion { processor?.close() } ?: processor?.close()
        display?.release(); projection?.stop()
        val closingReader = reader
        reader = null
        closingReader?.setOnImageAvailableListener(null, null)
        imageHandler.post { latestImage.getAndSet(null)?.close(); closingReader?.close(); imageThread.quitSafely() }
        SessionState.running.value = false; SessionState.paused.value = false
        SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
