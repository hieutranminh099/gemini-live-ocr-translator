package com.gemini.ocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.SelfLearningDatabase
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.settings.GeminiApiKeySettingsPanel
import com.gemini.ocr.video.VideoActivity
import com.gemini.ocr.movie.MovieLibraryScreen
import com.gemini.ocr.ui.ocean.*
import com.gemini.ocr.ui.GeminiTheme
import com.gemini.ocr.ui.AppTopBar
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

private val Mint = Color(0xFF6EE7C7)
private val Background = Color(0xFF0C1115)
private val Panel = Color(0xFF151D23)
private val Muted = Color(0xFFB3C1C8)

class MainActivity : ComponentActivity() {
    private lateinit var settings: AppSettings
    private val requestedTab = MutableStateFlow<Int?>(null)
    private var returnToCaller by mutableStateOf(false)

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("data", result.data))
        } else {
            SessionState.status.value = "Bạn chưa cấp quyền thu màn hình."
        }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginProjection()
    }

    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            startTranslation()
        } else {
            SessionState.status.value = "Cần quyền hiển thị trên ứng dụng khác để hiện bản dịch."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        returnToCaller = savedInstanceState?.getBoolean("ux_return_to_caller") ?: false
        routeIntent(intent)
        setContent {
            GeminiTheme {
                Surface(Modifier.fillMaxSize(), color = Background) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(incoming: Intent?) {
        if (incoming?.hasExtra("ux_tab") == true) {
            returnToCaller = incoming.getBooleanExtra("ux_return_to_caller", false)
            val destination = incoming.getIntExtra("ux_tab", -1)
            if (destination in 0..4) requestedTab.value = destination
            incoming.removeExtra("ux_tab")
            incoming.removeExtra("ux_return_to_caller")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ux_return_to_caller", returnToCaller)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        SessionState.activityVisible.value = true
    }

    override fun onStop() {
        SessionState.activityVisible.value = false
        super.onStop()
    }

    private fun startTranslation() {
        settings.connectionMode = "standalone"
        settings.language = "zh"
        if (!Settings.canDrawOverlays(this)) {
            overlays.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginProjection()
        }
    }

    private fun beginProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34 && settings.fullScreen) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        capture.launch(request)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val savedTabs = rememberSaveableStateHolder()
        val route by requestedTab.collectAsState()
        LaunchedEffect(route) { route?.let { tab = it; requestedTab.value = null } }
        BackHandler(enabled = tab != 0 || returnToCaller) { if (returnToCaller) finish() else tab = 0 }
        val running by SessionState.running.collectAsState()
        val paused by SessionState.paused.collectAsState()
        val connected by SessionState.connected.collectAsState()
        val standaloneActive by SessionState.standaloneActive.collectAsState()
        val backend by SessionState.backend.collectAsState()
        val status by SessionState.status.collectAsState()
        val overlayVisible by SessionState.overlayVisible.collectAsState()
        val lastOcr by SessionState.lastOcr.collectAsState()
        val lastTranslation by SessionState.lastTranslation.collectAsState()
        val timings by SessionState.timings.collectAsState()
        val latency by SessionState.latency.collectAsState()
        val ocr by SessionState.ocrMs.collectAsState()
        val frameToDraw by SessionState.frameToDraw.collectAsState()
        val offlineModelStatus by SessionState.offlineModelStatus.collectAsState()
        val bankSize by SessionState.bankSize.collectAsState()
        val savedApiCalls by SessionState.savedApiCalls.collectAsState()
        val preloadedHits by SessionState.preloadedHits.collectAsState()
        var translationMode by rememberSaveable { mutableStateOf(settings.translationMode) }
        var captureMode by rememberSaveable { mutableStateOf(settings.subtitleCapture) }
        var autoPreset by rememberSaveable { mutableStateOf(false) }
        var oneFramePreset by rememberSaveable { mutableStateOf(false) }
        var preparingModel by remember { mutableStateOf(false) }
        var regionDialog by rememberSaveable { mutableStateOf(false) }
        var regionPreset by rememberSaveable { mutableStateOf(settings.region) }
        var connectionRequest by rememberSaveable { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) {
                SelfLearningDatabase.initializeOnIo(this@MainActivity)
                OcrProcessor.preWarm("zh")
                StandaloneTranslator.preWarm(this@MainActivity, "zh")
            }
        }
        LaunchedEffect(tab, running) {
            if (running) regionDialog = false
            translationMode = settings.translationMode
            captureMode = settings.subtitleCapture
            regionPreset = settings.region
            launch(Dispatchers.IO) { SelfLearningDatabase.size(this@MainActivity) }
        }
        val labels = listOf("Dịch trực tiếp", "Kho thoại", "Kho phim", "Tùy chỉnh", "Hiệu năng")
        val tags = listOf("tab_live", "tab_memory", "tab_movies", "tab_settings", "tab_performance")
        val icons = listOf(Icons.Outlined.Translate, Icons.Outlined.Forum, Icons.Outlined.VideoLibrary,
            Icons.Outlined.Tune, Icons.Outlined.Speed)
        val destinations = labels.indices.map { OceanDestination(labels[it], icons[it], tags[it]) }
        val shell: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = tab == 0 && maxWidth >= 840.dp && maxWidth > maxHeight
        Scaffold(containerColor = if (tab == 0) OceanColors.Base else Background, bottomBar = {
            if (tab == 0) {
                if (!rail) OceanNavigationBar(destinations, tab, { tab = it })
            } else NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icons[index], null) },
                        label = { Text(label, maxLines = 2, fontSize = 11.sp) },
                        modifier = Modifier.testTag(tags[index]))
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize()) {
                if (rail) OceanNavigationRail(destinations, tab, { tab = it })
            savedTabs.SaveableStateProvider(tab) {
                if (tab == 2) MovieLibraryScreen(Modifier.padding(padding))
                else Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 1080.dp).fillMaxWidth().fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (tab) {
                            0 -> {
                                OceanLiveHub(
                                    state = OceanLiveState(
                                        running, paused,
                                        liveStageLabel(running, paused, overlayVisible, lastOcr, status, lastTranslation),
                                        status, liveNeedsAttention(status),
                                        connected || (running && standaloneActive && backend.provider == "standalone"),
                                        backend.badge,
                                        if (backend.provider == "standalone") null else backend.translationAvailable,
                                        captureMode, when (regionPreset) {
                                            "subtitle" -> "Dải phụ đề"
                                            "dialogue" -> "Nửa dưới"
                                            "center" -> "Vùng giữa"
                                            else -> "Toàn màn hình"
                                        },
                                        if (translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT) "Chế độ 2 · Gemini"
                                        else "Chế độ 1 · Kho thoại + Gemini",
                                    ),
                                    onSession = {
                                        if (running) startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("TOGGLE"))
                                        else startTranslation()
                                    },
                                    onStop = { startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("STOP")) },
                                    onRegion = {
                                        if (running) startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("SELECT_REGION"))
                                        else regionDialog = true
                                    },
                                    onCapture = { startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("CAPTURE_FRAME")) },
                                    onScanMode = {
                                        captureMode = it; settings.subtitleCapture = it
                                        if (it == "frame") { settings.region = "subtitle"; regionPreset = "subtitle" }
                                    },
                                    onSettings = { tab = 3 },
                                    onConnection = { connectionRequest++; tab = 3 },
                                )
                                OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, VideoActivity::class.java)) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("open_video_translation")) {
                                    Icon(Icons.Outlined.VideoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Dịch video trong máy")
                                }
                                ExpandableSection("Chế độ và ngữ cảnh", "Chọn cách dùng Gemini và thể loại nội dung") {
                                    Text("Chế độ dịch", style = MaterialTheme.typography.titleSmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilterChip(selected = translationMode == StandaloneTranslator.MODE_HYBRID_BANK_GEMINI,
                                            onClick = { translationMode = StandaloneTranslator.MODE_HYBRID_BANK_GEMINI; settings.translationMode = translationMode },
                                            enabled = !running, label = { Text("Kho thoại + Gemini") }, modifier = Modifier.testTag("mode_hybrid_gemini_card"))
                                        FilterChip(selected = translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT,
                                            onClick = { translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT; settings.translationMode = translationMode },
                                            enabled = !running, label = { Text("Gemini") }, modifier = Modifier.testTag("mode_bank_only_card"))
                                    }
                                    Text("Câu mới gửi tới Gemini cần mạng và khóa đã cấu hình. Khi thiếu điều kiện, ứng dụng báo lỗi để bạn xử lý.", color = Muted)
                                    TextButton(onClick = { tab = 3 }) { Text("Thiết lập Gemini") }
                                    ContextChoice(running)
                                }
                                ExpandableSection("Kiểu phụ đề", "Xem trước vị trí, cỡ chữ và màu sắc") { SubtitleAppearanceEditor(running) }
                                if (running && lastOcr.isNotBlank()) Section("Khung vừa đọc") {
                                    Text("OCR: $lastOcr", color = Muted)
                                    Text(lastTranslation.ifBlank { "Đang chờ bản dịch…" }, color = Mint)
                                }
                                ExpandableSection("Cài đặt nâng cao", "Nhận diện chữ, cấu hình có sẵn và thông tin phiên") {
                                    Text("Cấu hình có sẵn", style = MaterialTheme.typography.titleSmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = { settings.useAutoSubtitlePreset(); regionPreset = settings.region; captureMode = "auto"; autoPreset = true; oneFramePreset = false }, enabled = !running) {
                                            Text(if (autoPreset) "Đã chọn · Tự động tăng tốc" else "Tự động tăng tốc · Gemini")
                                        }
                                        OutlinedButton(onClick = { settings.useSubtitlePreset(); regionPreset = settings.region; captureMode = "frame"; oneFramePreset = true; autoPreset = false }, enabled = !running) {
                                            Text(if (oneFramePreset) "Đã chọn · Phụ đề 1 khung" else "Dùng kiểu phụ đề 1 khung")
                                        }
                                    }
                                    Text(if (captureMode == "frame") "Dừng video ở câu cần dịch rồi dùng nút một khung." else "Theo dõi phụ đề tự động.", color = Muted)
                                    RecognitionSettings(running) {
                                        regionPreset = settings.region
                                        captureMode = settings.subtitleCapture
                                    }
                                    OutlinedButton(onClick = {
                                        preparingModel = true
                                        scope.launch {
                                            try { StandaloneTranslator.prepareOfflineModel(this@MainActivity, "zh") { SessionState.offlineModelStatus.value = it } }
                                            catch (error: Exception) { SessionState.offlineModelStatus.value = error.message ?: "Chưa chuẩn bị được kho thoại." }
                                            finally { preparingModel = false }
                                        }
                                    }, enabled = !preparingModel, modifier = Modifier.testTag("prepare_offline_model_button")) {
                                        Text(if (preparingModel) "Đang chuẩn bị kho thoại…" else "Chuẩn bị kho thoại trên máy")
                                    }
                                    if (offlineModelStatus.isNotBlank()) Text(offlineModelStatus, color = Muted, modifier = Modifier.testTag("offline_model_status"))
                                    Text(timings.ifBlank { if (frameToDraw > 0) SessionState.formatMeasuredTelemetry(ocr, latency, frameToDraw)
                                        else "Chưa có số đo cho phiên này" }, color = Mint, modifier = Modifier.testTag("telemetry_status_bar"))
                                    Text("Kho thoại: $bankSize câu · Dùng lại $preloadedHits kết quả · Giảm $savedApiCalls lượt gọi", color = Muted)
                                }
                            }
                            1 -> MemoryReviewScreen(running, settings)
                            3 -> SettingsScreen(running, connectionRequest)
                            4 -> { AppTopBar("Hiệu năng", "Số đo của phiên hiện tại"); PerformancePanel() }
                        }
                    }
                }
            }
        }
        }
        }
        }
        if (tab == 0) OceanTheme { shell() } else shell()
        if (regionDialog && !running) OceanTheme {
            AlertDialog(onDismissRequest = { regionDialog = false },
                modifier = Modifier.testTag("ocean_roi_dialog"),
                title = { Text("Chọn vùng phụ đề") },
                text = {
                    Column {
                        Text("Vùng có sẵn dùng cho lần bật dịch tiếp theo. Khoanh vùng chính xác bằng nút VÙNG khi đang dịch.")
                        linkedMapOf("subtitle" to "Dải phụ đề", "dialogue" to "Nửa dưới", "center" to "Vùng giữa", "full" to "Toàn màn hình").forEach { (value, label) ->
                            TextButton(onClick = {
                                settings.region = value; regionPreset = value
                                if (value != "subtitle" && settings.overlay == "cover") settings.overlay = "subtitle"
                                regionDialog = false
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ocean_roi_$value")) {
                                RadioButton(selected = regionPreset == value, onClick = null)
                                Spacer(Modifier.width(10.dp)); Text(label, Modifier.weight(1f))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { regionDialog = false }) { Text("Đóng") } })
        }
    }

    @Composable
    private fun SettingsScreen(running: Boolean, connectionRequest: Int = 0) {
        var apiKeyInput by remember { mutableStateOf(settings.standaloneApiKey) }
        var keyRevision by remember { mutableIntStateOf(0) }
        val videoKeyReady = remember(apiKeyInput, keyRevision) { settings.hasGeminiVideoKey() }
        var localGlossary by remember { mutableStateOf(settings.localGlossary) }
        var cacheClearedMessage by rememberSaveable { mutableStateOf("") }
        var clearDialog by rememberSaveable { mutableStateOf(false) }
        AppTopBar("Tùy chỉnh", "Thiết lập cho thiết bị này")
        if (running) Text("Kết thúc phiên dịch để thay đổi cấu hình.", color = Muted)
        key(connectionRequest) { ExpandableSection("Kết nối Gemini", if (settings.effectiveStandaloneApiKey().isNotBlank()) "Khóa đã được lưu trên thiết bị"
            else if (videoKeyReady) "Đã cấu hình khóa cho video" else "Chưa có khóa Gemini",
            initiallyExpanded = connectionRequest > 0 || settings.effectiveStandaloneApiKey().isBlank()) {
            Text("Nhập khóa của bạn để dịch câu mới bằng Gemini. Khóa được lưu bảo mật trên thiết bị.", color = Muted)
            OutlinedTextField(apiKeyInput, { apiKeyInput = it; settings.standaloneApiKey = it.trim() },
                label = { Text("Khóa Gemini API") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = !running, modifier = Modifier.fillMaxWidth().testTag("standalone_api_key_input"))
            Text(if (settings.effectiveStandaloneApiKey().isNotBlank()) "Đã cấu hình khóa Gemini API."
                else if (videoKeyReady) "Đã cấu hình khóa cho video. Dịch trực tiếp cần khóa chính."
                else "Chưa có khóa. Bạn vẫn có thể tra các câu đã lưu trong Kho thoại.", color = Muted)
            GeminiApiKeySettingsPanel(settings, enabled = !running, onChanged = { keyRevision++ })
        }
        }
        Section("Kiểu phụ đề") { SubtitleAppearanceEditor(running) }
        ExpandableSection("Ngôn ngữ và ngữ cảnh", "Trung → Việt") {
            ContextChoice(running)
            OutlinedTextField(localGlossary, { localGlossary = it; settings.localGlossary = it },
                label = { Text("Tên riêng và cách xưng hô") }, supportingText = { Text("Mỗi dòng: chữ Trung = cách gọi tiếng Việt") },
                minLines = 3, enabled = !running, modifier = Modifier.fillMaxWidth().testTag("local_glossary_input"))
        }
        ExpandableSection("Cài đặt nâng cao", "Vùng nhận diện, tốc độ quét và bộ nhớ tạm") {
            RecognitionSettings(running)
            HorizontalDivider()
            Text("Bộ nhớ tạm", style = MaterialTheme.typography.titleSmall)
            Text("Đặt lại khi kết quả tạm không còn phù hợp. Các câu mới có thể cần dịch lại.", color = Muted)
            OutlinedButton(onClick = { clearDialog = true }, enabled = !running,
                modifier = Modifier.testTag("clear_phone_cache_button")) { Text("Đặt lại bộ nhớ tạm") }
            if (cacheClearedMessage.isNotBlank()) Text(cacheClearedMessage, color = Mint)
        }
        ExpandableSection("Thiết bị và quyền ứng dụng", "Quyền màn hình, thông báo và cửa sổ nổi") {
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = Muted)
            Text("Hiển thị trên ứng dụng khác: ${if (Settings.canDrawOverlays(this@MainActivity)) "Đã cấp" else "Chưa cấp"}")
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) {
                Text("Mở thông tin ứng dụng")
            }
            Text("Gemini OCR ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false },
            title = { Text("Đặt lại bộ nhớ tạm?") },
            text = { Text("Kết quả dịch tạm sẽ được đặt lại và nạp lại khi cần. Dự án video và Kho phim được giữ nguyên.") },
            confirmButton = { TextButton(onClick = {
                clearDialog = false
                PhoneTranslationCache.clear(this@MainActivity)
                cacheClearedMessage = "Đã đặt lại bộ nhớ tạm."
            }, enabled = !running) { Text("Đặt lại") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Giữ nguyên") } })
    }

    @Composable
    private fun ContextChoice(running: Boolean) {
        Text("Trung → Việt", color = Mint, style = MaterialTheme.typography.labelLarge)
        Choice("Ngữ cảnh", linkedMapOf("manga" to "Cổ trang / Tu tiên", "document" to "Phim hiện đại", "game" to "Game / Kiếm hiệp"),
            settings.profile, !running) { settings.profile = it }
    }

    @Composable
    private fun RecognitionSettings(running: Boolean, onChanged: () -> Unit = {}) {
        Choice("Vùng nhận diện", linkedMapOf("subtitle" to "Dải phụ đề", "dialogue" to "Nửa dưới", "center" to "Vùng giữa", "full" to "Toàn màn hình"),
            settings.region, !running) {
            settings.region = it
            if (it != "subtitle" && settings.overlay == "cover") settings.overlay = "subtitle"
            onChanged()
        }
        Choice("Cách lấy khung", linkedMapOf("frame" to "Chạm dịch 1 khung", "auto" to "Tự động"),
            settings.subtitleCapture, !running) { settings.subtitleCapture = it; onChanged() }
        var interval by rememberSaveable(settings.interval) { mutableFloatStateOf(settings.interval.toFloat().coerceIn(75f, 1000f)) }
        Text("Khoảng cách giữa các lần quét · ${interval.toInt()} ms", color = Muted)
        Slider(interval, { interval = it; settings.interval = it.toLong() }, valueRange = 75f..1000f, enabled = !running,
            modifier = Modifier.testTag("live_scan_interval"))
        var fast by rememberSaveable(settings.fastOcrDownscale) { mutableStateOf(settings.fastOcrDownscale) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Thu nhỏ ảnh nhận diện"); Text("Giảm dữ liệu xử lý khi vùng chữ đủ rõ.", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(fast, { fast = it; settings.fastOcrDownscale = it }, enabled = !running, modifier = Modifier.testTag("fast_ocr_downscale_switch"))
        }
        var correction by rememberSaveable(settings.ocrPreCorrection) { mutableStateOf(settings.ocrPreCorrection) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Sửa lỗi chữ theo kho đã xác nhận"); Text("Dùng quy tắc và câu đã xác nhận phù hợp với chế độ đang chọn.", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(correction, { correction = it; settings.ocrPreCorrection = it }, enabled = !running, modifier = Modifier.testTag("ocr_pre_correction_switch"))
        }
        var fullScreen by rememberSaveable(settings.fullScreen) { mutableStateOf(settings.fullScreen) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Thu toàn màn hình", Modifier.weight(1f))
            Switch(fullScreen, { fullScreen = it; settings.fullScreen = it }, enabled = !running && Build.VERSION.SDK_INT >= 34)
        }
        Text("Chọn cửa sổ hoặc toàn màn hình trong hộp cấp quyền của Android nếu thiết bị hỗ trợ.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SubtitleAppearanceEditor(running: Boolean) {
        var font by rememberSaveable(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat().coerceIn(12f, 30f)) }
        var opacity by rememberSaveable(settings.opacity) { mutableFloatStateOf(settings.opacity.coerceIn(.4f, 1f)) }
        var y by rememberSaveable(settings.subtitleY) { mutableFloatStateOf(settings.subtitleY.coerceIn(.1f, .85f)) }
        var color by rememberSaveable(settings.textColor) { mutableIntStateOf(settings.textColor) }
        var background by rememberSaveable(settings.subtitleBackground) { mutableStateOf(settings.subtitleBackground) }
        var outline by rememberSaveable(settings.subtitleOutline) { mutableStateOf(settings.subtitleOutline) }
        var saved by rememberSaveable { mutableStateOf(false) }
        Text("Xem trước", style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF22343F))
            .testTag("live_subtitle_preview")) {
            Text("Nội dung trên màn hình", Modifier.align(Alignment.Center), color = Muted, style = MaterialTheme.typography.bodySmall)
            val captionHeight = with(LocalDensity.current) { font.toInt().sp.toDp() * 1.4f + 12.dp }
            Text("Xin chào", fontSize = font.toInt().sp, color = Color(color), maxLines = 1,
                style = TextStyle(shadow = if (outline) Shadow(Color.Black, Offset(1f, 1f), 4f) else null),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (maxHeight - captionHeight).coerceAtLeast(0.dp) * y)
                    .fillMaxWidth(.92f).background(if (background) Color(0xFF0D181F).copy(alpha = opacity) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Text("Vị trí · ${(y * 100).toInt()}% từ trên xuống", color = Muted)
        Slider(y, { y = it; saved = false }, valueRange = .1f.. .85f, enabled = !running, modifier = Modifier.testTag("live_position_slider"))
        Text("Cỡ chữ · ${font.toInt()} sp", color = Muted)
        Slider(font, { font = it; saved = false }, valueRange = 12f..30f, enabled = !running, modifier = Modifier.testTag("live_font_slider"))
        Choice("Màu chữ", linkedMapOf("-1" to "Trắng", "-9508921" to "Bạc hà", "-11930" to "Vàng"), color.toString(), !running) {
            color = it.toInt(); saved = false
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(background, { background = it; saved = false }, enabled = !running, modifier = Modifier.testTag("live_background")); Text("Nền phụ đề")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(outline, { outline = it; saved = false }, enabled = !running, modifier = Modifier.testTag("live_outline")); Text("Viền chữ")
            }
        }
        Text("Độ đậm nền · ${(opacity * 100).toInt()}%", color = Muted)
        Slider(opacity, { opacity = it; saved = false }, valueRange = .4f..1f, enabled = !running && background,
            modifier = Modifier.testTag("live_opacity_slider"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                settings.fontSize = font.toInt(); settings.opacity = opacity; settings.subtitleY = y
                settings.textColor = color; settings.subtitleBackground = background; settings.subtitleOutline = outline
                saved = true
            }, enabled = !running, modifier = Modifier.testTag("live_save_style")) { Text("Lưu cấu hình") }
            TextButton(onClick = { font = 20f; opacity = .9f; y = .78f; color = android.graphics.Color.WHITE; background = true; outline = false; saved = false },
                enabled = !running, modifier = Modifier.testTag("live_reset_style")) { Text("Đặt lại mặc định") }
        }
        if (saved) Text("Đã lưu cấu hình phụ đề.", color = Mint)
        ExpandableSection("Kiểu hiển thị nâng cao", "Phụ đề rời hoặc phủ vùng chữ gốc") {
            Choice("Kiểu hiển thị", linkedMapOf("subtitle" to "Phụ đề rời", "replace" to "Phủ từng đoạn", "cover" to "Đè phụ đề gốc"),
                settings.overlay, !running) {
                settings.overlay = it
                if (it == "cover") settings.region = "subtitle"
            }
        }
    }

}

internal fun liveNeedsAttention(status: String): Boolean {
    val text = status.lowercase(java.util.Locale.ROOT)
    return listOf("lỗi", "không thể", "không kết nối", "chưa cấp quyền", "cần quyền", "hết hạn mức",
        "giới hạn lượt", "chưa cấu hình", "không có khóa", "chưa nhập khóa", "chưa có khóa").any { it in text }
}

internal fun liveStageLabel(running: Boolean, paused: Boolean, visible: Boolean, source: String,
    status: String, translation: String = ""): String = when {
    !running -> if (liveNeedsAttention(status)) "Cần kiểm tra" else "Sẵn sàng"
    paused -> "Đã tạm dừng"
    liveNeedsAttention(status) -> "Cần kiểm tra"
    visible -> "Đang hiển thị"
    translation.isNotBlank() -> "Đã có bản dịch"
    source.isNotBlank() -> "Đang dịch"
    status.contains("chờ", ignoreCase = true) -> "Đang chờ phụ đề"
    else -> "Đang nhận diện"
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Mint)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(
    label: String,
    values: Map<String, String>,
    initial: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { (key, value) ->
            FilterChip(
                selected = selected == key,
                onClick = { selected = key; onChange(key) },
                label = { Text(value) },
                enabled = enabled
            )
        }
    }
}
