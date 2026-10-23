package com.gemini.ocr.overlay

import android.content.Context
import android.graphics.*
import android.view.*

/** A live region editor. It never takes or stores a screenshot. */
class SubtitleRegionSelector(context: Context, initial: RectF, private val done: (RectF?) -> Unit) : View(context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val d = resources.displayMetrics.density
    private val selection = RectF(initial)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val save = RectF(); private val cancel = RectF()
    private var downX = 0f; private var downY = 0f; private var original = RectF()
    private var drag = ""; private var ended = false
    private val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
    }
    init { contentDescription = "Chọn vùng phụ đề: kéo góc để đổi cỡ, kéo bên trong để di chuyển, rồi Lưu vùng"; wm.addView(this, params) }
    fun close() { if (!ended) { ended = true; runCatching { wm.removeView(this) } } }
    private fun finish(value: RectF?) { close(); done(value) }
    override fun onDraw(canvas: Canvas) {
        val r = RectF(selection.left * width, selection.top * height, selection.right * width, selection.bottom * height)
        p.color = Color.argb(165, 5, 15, 22); p.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, p); canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), p)
        canvas.drawRect(0f, r.top, r.left, r.bottom, p); canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, p)
        p.color = Color.rgb(110, 231, 199); p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; canvas.drawRect(r, p)
        p.style = Paint.Style.FILL
        for (point in listOf(PointF(r.left, r.top), PointF(r.right, r.bottom))) canvas.drawCircle(point.x, point.y, 9 * d, p)
        p.textSize = 17 * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = Color.WHITE
        canvas.drawText("KHOANH ĐÚNG DẢI PHỤ ĐỀ", 20 * d, 36 * d, p)
        p.textSize = 12 * d; p.typeface = Typeface.DEFAULT
        canvas.drawText("Kéo góc đổi cỡ • Kéo trong khung để di chuyển", 20 * d, 58 * d, p)
        val buttonY = if (r.bottom > height * .75f) 76 * d else height - 70 * d
        cancel.set(20 * d, buttonY, 128 * d, buttonY + 44 * d)
        save.set(144 * d, buttonY, 270 * d, buttonY + 44 * d)
        p.color = Color.rgb(35, 53, 63); canvas.drawRoundRect(cancel, 10 * d, 10 * d, p)
        p.color = Color.rgb(110, 231, 199); canvas.drawRoundRect(save, 10 * d, 10 * d, p)
        p.textSize = 14 * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = Color.WHITE
        canvas.drawText("Hủy", cancel.left + 34 * d, cancel.top + 28 * d, p)
        p.color = Color.rgb(7, 26, 24); canvas.drawText("Lưu vùng", save.left + 28 * d, save.top + 28 * d, p)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x / width).coerceIn(0f, 1f); val y = (event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; original = RectF(selection)
                val radius = 32 * d
                drag = when {
                    save.contains(event.x, event.y) -> "save"
                    cancel.contains(event.x, event.y) -> "cancel"
                    kotlin.math.abs(event.x - selection.left * width) < radius && kotlin.math.abs(event.y - selection.top * height) < radius -> "start"
                    kotlin.math.abs(event.x - selection.right * width) < radius && kotlin.math.abs(event.y - selection.bottom * height) < radius -> "end"
                    selection.contains(x, y) -> "move"
                    else -> "new"
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    "start" -> { selection.left = x.coerceAtMost(selection.right - .1f); selection.top = y.coerceAtMost(selection.bottom - .035f) }
                    "end" -> { selection.right = x.coerceAtLeast(selection.left + .1f); selection.bottom = y.coerceAtLeast(selection.top + .035f) }
                    "move" -> { selection.set(original); selection.offset((x - downX).coerceIn(-original.left, 1 - original.right), (y - downY).coerceIn(-original.top, 1 - original.bottom)) }
                    "new" -> selection.set(minOf(downX, x), minOf(downY, y), maxOf(downX, x), maxOf(downY, y))
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (drag == "save" && save.contains(event.x, event.y)) {
                    if (selection.width() >= .1f && selection.height() >= .035f) finish(RectF(selection))
                } else if (drag == "cancel" && cancel.contains(event.x, event.y)) finish(null)
                performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
