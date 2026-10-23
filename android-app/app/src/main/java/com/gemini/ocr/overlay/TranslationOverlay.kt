package com.gemini.ocr.overlay

import android.content.Context
import android.graphics.*
import android.hardware.input.InputManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.SessionState
import com.gemini.ocr.capture.SubtitleRegion
import org.json.JSONArray

class TranslationOverlay(private val context: Context, private val settings: AppSettings,
    private val onToggle: () -> Unit, private val onStop: () -> Unit,
    private val onCapture: () -> Unit = {}, private val onRegion: () -> Unit = {},
    private val onCancel: () -> Unit = {}) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val renderer = RenderView(context).apply { visibility = View.INVISIBLE }
    private var renderKey = ""
    private var feedbackKey: Pair<String, Boolean>? = null
    private var placementBlocked = false
    private val bubble = TextView(context).apply {
        text = "Ⅱ"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(7, 26, 24))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.rgb(110, 231, 199))
        }
        contentDescription = "Chạm để tạm dừng hoặc tiếp tục; giữ để dừng; kéo để di chuyển"
    }
    private fun action(label: String, description: String, callback: () -> Unit) = TextView(context).apply {
        text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD; contentDescription = description
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * density; setColor(Color.rgb(25, 48, 58)) }
        setOnClickListener { callback() }
    }
    private var working = false
    private val idleAction: String get() = if (settings.subtitleCapture == "auto") "QUÉT LẠI" else "1 KHUNG"
    private val idleDescription: String get() = if (settings.subtitleCapture == "auto")
        "Quét lại vùng phụ đề và tiếp tục dịch tự động" else "Dịch 1 khung phụ đề"
    private val frameButton = action(idleAction, idleDescription) { if (working) onCancel() else onCapture() }
    private val regionButton = action("VÙNG", "Chọn vùng phụ đề", onRegion)
    private val statusLabel = TextView(context).apply {
        textSize = 11f; setTextColor(Color.rgb(190, 242, 227)); maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8 * density; setColor(Color.rgb(18, 34, 43)) }
    }
    private val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(bubble, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        if (settings.subtitleFocus) {
            addView(frameButton, LinearLayout.LayoutParams((72 * density).toInt(), (48 * density).toInt()).apply { marginStart = (6 * density).toInt() })
            addView(regionButton, LinearLayout.LayoutParams((58 * density).toInt(), (48 * density).toInt()).apply { marginStart = (6 * density).toInt() })
        }
    }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(buttonRow)
        if (settings.subtitleFocus) {
            addView(statusLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * density).toInt() })
        }
    }
    private val layerParams = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                // Android 12+ passes touches through untrusted overlays only below the obscuring threshold.
                alpha = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch * .95f else .75f
            }
    private val bubbleParams = WindowManager.LayoutParams(((if (settings.subtitleFocus) 256 else 48) * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (12 * density).toInt(); y = (100 * density).toInt()
    }
    init {
        wm.addView(renderer, layerParams)
        wm.addView(controls, bubbleParams)
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (settings.adjacentSubtitles) keepControlsOutsideRegion()
        }
        bubble.setOnClickListener { onToggle() }
        bubble.setOnLongClickListener { onStop(); true }
        feedback(if (settings.subtitleCapture == "auto") "${languageName()} → Việt • Tự động tăng tốc\nĐổi câu sẽ tự dịch • VÙNG để khoanh chữ." else "${languageName()} → Việt • Chạm 1 KHUNG\nDừng video ở câu cần dịch.")
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var started = 0L
        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = bubbleParams.x; startY = bubbleParams.y
                    started = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = (startX + event.rawX - downX).toInt().coerceAtLeast(0)
                    bubbleParams.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                    updateBounds()
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY) < 15 * density) {
                        if (System.currentTimeMillis() - started > 700) bubble.performLongClick() else bubble.performClick()
                    }
                }
            }
            true
        }
    }
    private var capturing = false
    fun show(blocks: JSONArray, onDrawn: (() -> Unit)? = null) {
        if (blocks.length() == 0) { clear(); return }
        val key = if (settings.adjacentSubtitles) {
            // Native OCR boxes can move by a pixel between frames. A stationary band
            // depends on the selected ROI and text, not that box jitter.
            (0 until blocks.length()).joinToString("\n") { blocks.getJSONObject(it).getString("text") } +
                "|${screenBounds()}|${settings.subtitleRegion}|${settings.fontSize}|${settings.textColor}"
        } else blocks.toString()
        if (key == renderKey) return
        renderer.blocks = blocks
        renderer.onDrawn = onDrawn
        if ((settings.overlay == "cover" || settings.adjacentSubtitles) && !layoutCover()) {
            clear()
            placementBlocked = true
            feedback("Thu hẹp VÙNG để chừa chỗ cho bản dịch không nháy.", true)
            return
        }
        placementBlocked = false
        renderKey = key
        renderer.visibility = if (capturing) View.INVISIBLE else View.VISIBLE
        renderer.invalidate(); SessionState.overlayVisible.value = !capturing
    }
    fun clear() {
        placementBlocked = false
        if (renderer.blocks.length() == 0 && renderer.visibility == View.INVISIBLE) return
        renderKey = ""; renderer.blocks = JSONArray(); renderer.onDrawn = null; renderer.visibility = View.INVISIBLE
        renderer.invalidate(); SessionState.overlayVisible.value = false
    }
    fun paused(value: Boolean) {
        bubble.text = if (value) "▶" else "Ⅱ"
        if (value) feedback("Đã tạm dừng • Chạm ▶ hoặc $idleAction để tiếp tục")
    }
    fun busy(value: Boolean) {
        working = value; frameButton.isEnabled = true
        frameButton.text = if (value) "HỦY AI" else idleAction
        frameButton.contentDescription = if (value) "Hủy yêu cầu dịch đang chờ" else idleDescription
    }
    fun progress(seconds: Long, original: String) {
        if (!working) return
        frameButton.text = "${seconds}s · HỦY"
        feedback("${languageName()} → Việt • Đang dịch ${seconds}s\nOCR: ${original.take(72)}\nChạm HỦY để dừng chờ.")
    }
    fun feedback(message: String, error: Boolean = false) {
        if (placementBlocked && !error) return
        if (feedbackKey == Pair(message, error)) return
        feedbackKey = Pair(message, error)
        statusLabel.text = message
        statusLabel.setTextColor(if (error) Color.rgb(255, 209, 128) else Color.rgb(190, 242, 227))
    }
    private fun languageName() = mapOf("en" to "Anh", "zh" to "Trung", "ja" to "Nhật", "ko" to "Hàn")[settings.language] ?: settings.language
    fun editing(value: Boolean) { controls.visibility = if (value) View.INVISIBLE else View.VISIBLE }
    fun sampling(value: Boolean) {
        if (settings.adjacentSubtitles) return // No periodic alpha/visibility changes in automatic mode.
        capturing = value
        renderer.visibility = if (value || renderer.blocks.length() == 0) View.INVISIBLE else View.VISIBLE
        controls.alpha = if (value) 0f else 1f
        SessionState.overlayVisible.value = !value && renderer.blocks.length() > 0
    }
    fun close() { SessionState.overlayVisible.value = false; runCatching { wm.removeView(renderer) }; runCatching { wm.removeView(controls) } }
    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
        else Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
    private fun sourceRegion(): Rect = screenBounds().let { screen ->
        SubtitleRegion.pixels(settings.subtitleRegion, screen.width(), screen.height()).apply { offset(screen.left, screen.top) }
    }
    private fun controlsRect(): Rect = Rect(bubbleParams.x, bubbleParams.y, bubbleParams.x + bubbleParams.width,
        bubbleParams.y + controls.measuredHeight.takeIf { it > 0 }.let { it ?: (120 * density).toInt() })
    private fun keepControlsOutsideRegion(): Boolean {
        if (!settings.adjacentSubtitles) return true
        val current = controlsRect()
        val screen = screenBounds()
        val roi = sourceRegion()
        val caption = if (renderer.blocks.length() > 0 && layerParams.width > 0 && layerParams.height > 0)
            Rect(layerParams.x, layerParams.y, layerParams.x + layerParams.width, layerParams.y + layerParams.height) else null
        fun safe(rect: Rect) = screen.contains(rect) && !Rect.intersects(rect, roi) && (caption == null || !Rect.intersects(rect, caption))
        if (safe(current)) return true
        val gap = (8 * density).toInt()
        // Prefer screen corners for controls so the adjacent caption has room by the ROI.
        val placed = listOf(
            Rect(screen.left + gap, screen.top + gap, screen.left + gap + current.width(), screen.top + gap + current.height()),
            Rect(screen.right - gap - current.width(), screen.top + gap, screen.right - gap, screen.top + gap + current.height()),
            Rect(screen.left + gap, screen.bottom - gap - current.height(), screen.left + gap + current.width(), screen.bottom - gap),
            Rect(screen.right - gap - current.width(), screen.bottom - gap - current.height(), screen.right - gap, screen.bottom - gap),
            OverlayPlacement.outside(screen, roi, current.width(), current.height(), gap)
        ).filterNotNull().firstOrNull { safe(it) } ?: return false
        if (bubbleParams.x != placed.left || bubbleParams.y != placed.top) {
            bubbleParams.x = placed.left; bubbleParams.y = placed.top
            wm.updateViewLayout(controls, bubbleParams)
        }
        return true
    }
    fun prepareForCapture(): Boolean {
        val safe = keepControlsOutsideRegion()
        if (!safe) feedback("Thu hẹp VÙNG để thanh nút nằm ngoài vùng OCR.", true)
        return safe
    }
    fun updateBounds() {
        val bounds = screenBounds()
        bubbleParams.x = bubbleParams.x.coerceIn(0, (bounds.width() - bubbleParams.width).coerceAtLeast(0))
        bubbleParams.y = bubbleParams.y.coerceIn(0, (bounds.height() - controls.measuredHeight.coerceAtLeast((48 * density).toInt())).coerceAtLeast(0))
        wm.updateViewLayout(controls, bubbleParams)
        keepControlsOutsideRegion()
        renderKey = ""
        if (renderer.blocks.length() > 0) show(renderer.blocks)
    }
    private fun fontPixels(): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat(), context.resources.displayMetrics)

    private fun layoutCover(): Boolean {
        val bounds = screenBounds()
        val region = settings.subtitleRegion
        val width = ((region.right - region.left) * bounds.width()).toInt().coerceAtLeast(1)
        val boxes = (0 until renderer.blocks.length()).map { renderer.blocks.getJSONObject(it).getJSONObject("box") }
        val top = boxes.minOf { it.getDouble("y") }.toFloat() * bounds.height()
        val bottom = boxes.maxOf { it.getDouble("y") + it.getDouble("height") }.toFloat() * bounds.height()
        val text = (0 until renderer.blocks.length()).joinToString("\n") { renderer.blocks.getJSONObject(it).getString("text") }
        val gap = (8 * density).toInt()
        val roi = sourceRegion()
        val availableHeight = if (settings.adjacentSubtitles) maxOf(roi.top - bounds.top - gap, bounds.bottom - roi.bottom - gap,
            (48 * density).toInt()) else (region.height() * bounds.height()).toInt()
        renderer.prepareCover(text, width, availableHeight)
        val height = (if (settings.adjacentSubtitles) renderer.coverHeight else maxOf(bottom - top + 16 * density,
            renderer.coverHeight.toFloat()).toInt()).coerceIn(1, bounds.height())
        val placed = if (settings.adjacentSubtitles) OverlayPlacement.outside(bounds, roi, width, height, gap) ?: return false
            else Rect((region.left * bounds.width()).toInt(), ((top + bottom - height) / 2).toInt().coerceIn(0, bounds.height() - height),
                (region.left * bounds.width()).toInt() + width, ((top + bottom - height) / 2).toInt().coerceIn(0, bounds.height() - height) + height)
        val changed = layerParams.width != placed.width() || layerParams.height != placed.height() ||
            layerParams.x != placed.left || layerParams.y != placed.top || layerParams.alpha != 1f
        layerParams.width = placed.width(); layerParams.height = placed.height()
        layerParams.x = placed.left; layerParams.y = placed.top
        // Opaque touchable caption window; automatic full-display mode keeps it outside the source ROI.
        // A full-screen opaque NOT_TOUCHABLE overlay would block input on Android 12+.
        layerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layerParams.alpha = 1f
        if (changed) wm.updateViewLayout(renderer, layerParams)
        return !settings.adjacentSubtitles || keepControlsOutsideRegion()
    }

    private inner class RenderView(context: Context) : View(context) {
        var blocks = JSONArray()
        var onDrawn: (() -> Unit)? = null
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var coverLines = emptyList<String>()
        private var coverFont = 20f
        var coverHeight = 1
        fun prepareCover(text: String, availableWidth: Int, availableHeight: Int) {
            textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textPaint.textSize = fontPixels()
            while (textPaint.textSize > 12 * density &&
                (wrap(text, availableWidth - 24 * density, 8).size * textPaint.textSize * 1.25f + 16 * density > availableHeight)) textPaint.textSize -= density
            coverFont = textPaint.textSize
            coverLines = wrap(text, availableWidth - 24 * density, 6)
            coverHeight = (coverLines.size * coverFont * 1.25f + 16 * density).toInt()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (blocks.length() == 0) return
            backgroundPaint.color = Color.argb((settings.opacity * 255).toInt(), 13, 24, 31)
            textPaint.color = settings.textColor
            textPaint.textSize = fontPixels()
            if (settings.overlay == "cover" || settings.adjacentSubtitles) {
                backgroundPaint.color = Color.argb(255, 10, 16, 22)
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8 * density, 8 * density, backgroundPaint)
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD); textPaint.textSize = coverFont
                val lineHeight = coverFont * 1.25f
                val y = (height - coverLines.size * lineHeight) / 2 - textPaint.ascent()
                coverLines.forEachIndexed { i, line -> canvas.drawText(line, (width - textPaint.measureText(line)) / 2, y + i * lineHeight, textPaint) }
            } else if (settings.overlay == "subtitle") {
                val text = (0 until blocks.length()).joinToString("\n") { blocks.getJSONObject(it).getString("text") }
                val maxWidth = width * .88f
                val lines = wrap(text, maxWidth - 32 * density, 8)
                val lineHeight = textPaint.textSize * 1.35f
                val h = lines.size * lineHeight + 24 * density
                val x = (width - maxWidth) / 2
                val y = (height * settings.subtitleY).coerceAtMost(height - h - 20 * density).coerceAtLeast(24 * density)
                canvas.drawRoundRect(x, y, x + maxWidth, y + h, 14 * density, 14 * density, backgroundPaint)
                lines.forEachIndexed { i, line -> canvas.drawText(line, x + 16 * density, y + 12 * density - textPaint.ascent() + i * lineHeight, textPaint) }
            } else {
                for (i in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(i)
                    val box = block.getJSONObject("box")
                    val x = (box.getDouble("x") * width).toFloat()
                    val y = (box.getDouble("y") * height).toFloat()
                    val w = (box.getDouble("width") * width).toFloat().coerceAtLeast(50 * density).coerceAtMost(width - x)
                    val h = (box.getDouble("height") * height).toFloat()
                    textPaint.textSize = fontPixels().coerceAtMost(h.coerceAtLeast(12 * density))
                    val lines = wrap(block.getString("text"), w - 12 * density, 6)
                    val lineHeight = textPaint.textSize * 1.25f
                    val actualH = maxOf(h, lines.size * lineHeight + 8 * density).coerceAtMost(height - y)
                    canvas.save()
                    canvas.clipRect(x, y, x + w, y + actualH)
                    canvas.drawRoundRect(x, y, x + w, y + actualH, 4 * density, 4 * density, backgroundPaint)
                    lines.forEachIndexed { j, line -> canvas.drawText(line, x + 6 * density, y + 4 * density - textPaint.ascent() + j * lineHeight, textPaint) }
                    canvas.restore()
                }
            }
        }
        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            onDrawn?.also { onDrawn = null }?.invoke()
        }
        private fun wrap(text: String, maxWidth: Float, maxLines: Int): List<String> {
            val output = mutableListOf<String>()
            for (paragraph in text.split('\n')) {
                var remaining = paragraph.trim()
                while (remaining.isNotEmpty() && output.size < maxLines) {
                    var count = textPaint.breakText(remaining, true, maxWidth.coerceAtLeast(10f), null).coerceAtLeast(1)
                    if (count < remaining.length) {
                        val space = remaining.lastIndexOf(' ', count - 1)
                        if (space > count / 2) count = space
                    }
                    output.add(remaining.take(count))
                    remaining = remaining.drop(count).trimStart()
                }
                if (output.size >= maxLines) {
                    if (remaining.isNotEmpty()) output[output.lastIndex] = output.last().dropLast(1) + "…"
                    break
                }
            }
            return output
        }
    }
}

