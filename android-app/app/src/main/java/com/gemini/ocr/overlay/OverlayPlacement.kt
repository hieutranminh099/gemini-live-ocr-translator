package com.gemini.ocr.overlay

import android.graphics.Rect
import kotlin.math.roundToInt

/** Places a complete overlay window outside an OCR rectangle, in physical screen pixels. */
object OverlayPlacement {
    fun outside(screen: Rect, avoid: Rect, width: Int, height: Int, gap: Int): Rect? {
        if (screen.isEmpty || width <= 0 || height <= 0 ||
            width > screen.width() || height > screen.height()) return null
        val spacing = gap.coerceAtLeast(0)
        val centeredX = (avoid.exactCenterX() - width / 2f).roundToInt()
            .coerceIn(screen.left, screen.right - width)
        val centeredY = (avoid.exactCenterY() - height / 2f).roundToInt()
            .coerceIn(screen.top, screen.bottom - height)
        val candidates = listOf(
            Rect(centeredX, avoid.top - spacing - height, centeredX + width, avoid.top - spacing),
            Rect(centeredX, avoid.bottom + spacing, centeredX + width, avoid.bottom + spacing + height),
            Rect(avoid.left - spacing - width, centeredY, avoid.left - spacing, centeredY + height),
            Rect(avoid.right + spacing, centeredY, avoid.right + spacing + width, centeredY + height)
        )
        // A partial window or clamping its vertical position into the ROI would cover source text.
        return candidates.firstOrNull { screen.contains(it) && !Rect.intersects(it, avoid) }
    }
}
