package com.gemini.ocr.video

/** Displayed video bounds, including letterboxing (Fit) or the offscreen part of a crop (Fill).
 * Stored subtitle/OCR rectangles stay normalized to the complete, rotation-correct source. */
internal data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    init {
        require(listOf(left, top, width, height).all { it.isFinite() } && width > 0f && height > 0f)
    }

    val right get() = left + width
    val bottom get() = top + height

    fun screenX(normalizedX: Float): Float = left + normalizedX * width
    fun screenY(normalizedY: Float): Float = top + normalizedY * height
    fun normalizedX(screenX: Float): Float = (screenX - left) / width
    fun normalizedY(screenY: Float): Float = (screenY - top) / height
    fun contains(screenX: Float, screenY: Float): Boolean =
        screenX in left..right && screenY in top..bottom

    companion object {
        /** Source dimensions already include the media rotation; screen rotation changes the viewport. */
        fun calculate(sourceWidth: Int, sourceHeight: Int, viewportWidth: Int, viewportHeight: Int,
            fill: Boolean): VideoViewport {
            require(sourceWidth > 0 && sourceHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
            val scaleX = viewportWidth.toFloat() / sourceWidth
            val scaleY = viewportHeight.toFloat() / sourceHeight
            val scale = if (fill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
            val width = sourceWidth * scale
            val height = sourceHeight * scale
            return VideoViewport((viewportWidth - width) / 2f, (viewportHeight - height) / 2f, width, height)
        }
    }
}
