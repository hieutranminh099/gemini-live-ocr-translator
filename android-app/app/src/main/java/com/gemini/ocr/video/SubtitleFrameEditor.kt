package com.gemini.ocr.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Overlay on the aspect-correct video viewport. Drag inside to move, edges/corners to resize,
 * or outside to draw another rectangle. Only the completed gesture is persisted by the caller. */
@Composable
fun SubtitleFrameEditor(
    frame: VideoSubtitleFrame,
    onFrameCommit: (VideoSubtitleFrame) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color(0xFFFFC857)
) = SubtitleFrameEditor(frame, onFrameCommit, modifier, enabled, color, viewport = null)

@Composable
internal fun SubtitleFrameEditor(
    frame: VideoSubtitleFrame,
    onFrameCommit: (VideoSubtitleFrame) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color(0xFFFFC857),
    viewport: VideoViewport?
) {
    var draft by remember(frame) { mutableStateOf(frame) }
    val commit by rememberUpdatedState(onFrameCommit)
    Canvas(modifier.testTag("video_subtitle_frame_editor")
        .semantics { contentDescription = "Khung phụ đề Việt: kéo trong khung để di chuyển, kéo góc hoặc cạnh để đổi kích thước" }
        .then(if (enabled) Modifier.pointerInput(frame, viewport) {
            var start = Offset.Zero
            var origin = frame
            var handle: SubtitleFrameHandle? = null
            var move = false
            var total = Offset.Zero
            var accepted = false
            var video = viewport ?: VideoViewport(0f, 0f, size.width.toFloat().coerceAtLeast(1f), size.height.toFloat().coerceAtLeast(1f))
            detectDragGestures(
                onDragStart = { point ->
                    video = viewport ?: VideoViewport(0f, 0f, size.width.toFloat().coerceAtLeast(1f), size.height.toFloat().coerceAtLeast(1f))
                    accepted = size.width > 0 && size.height > 0 && video.contains(point.x, point.y)
                    start = point
                    total = Offset.Zero
                    origin = draft
                    val x = video.normalizedX(point.x)
                    val y = video.normalizedY(point.y)
                    val hitX = minOf(22.dp.toPx(), origin.width * video.width / 3)
                    val hitY = minOf(22.dp.toPx(), origin.height * video.height / 3)
                    val atLeft = abs(point.x - video.screenX(origin.left)) <= hitX
                    val atRight = abs(point.x - video.screenX(origin.right)) <= hitX
                    val atTop = abs(point.y - video.screenY(origin.top)) <= hitY
                    val atBottom = abs(point.y - video.screenY(origin.bottom)) <= hitY
                    val withinX = x in origin.left..origin.right
                    val withinY = y in origin.top..origin.bottom
                    handle = when {
                        atLeft && atTop -> SubtitleFrameHandle.TOP_LEFT
                        atRight && atTop -> SubtitleFrameHandle.TOP_RIGHT
                        atLeft && atBottom -> SubtitleFrameHandle.BOTTOM_LEFT
                        atRight && atBottom -> SubtitleFrameHandle.BOTTOM_RIGHT
                        atLeft && withinY -> SubtitleFrameHandle.LEFT
                        atRight && withinY -> SubtitleFrameHandle.RIGHT
                        atTop && withinX -> SubtitleFrameHandle.TOP
                        atBottom && withinX -> SubtitleFrameHandle.BOTTOM
                        else -> null
                    }
                    move = handle == null && withinX && withinY
                },
                onDrag = { change, delta ->
                    if (accepted) {
                        change.consume()
                        total += delta
                        val dx = total.x / video.width
                        val dy = total.y / video.height
                        draft = when {
                            handle != null -> origin.resize(checkNotNull(handle), dx, dy)
                            move -> origin.move(dx, dy)
                            else -> VideoSubtitleFrame.between(video.normalizedX(start.x), video.normalizedY(start.y),
                                video.normalizedX(change.position.x), video.normalizedY(change.position.y))
                        }
                    }
                },
                onDragEnd = { if (accepted) commit(draft) },
                onDragCancel = { draft = frame }
            )
        } else Modifier)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val video = viewport ?: VideoViewport(0f, 0f, size.width, size.height)
        val left = video.screenX(draft.left)
        val top = video.screenY(draft.top)
        val right = video.screenX(draft.right)
        val bottom = video.screenY(draft.bottom)
        val tint = color
        clipRect(maxOf(0f, video.left), maxOf(0f, video.top), minOf(size.width, video.right), minOf(size.height, video.bottom)) {
            drawRect(tint.copy(alpha = .08f), Offset(left, top), Size(right - left, bottom - top))
            drawRect(tint, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            listOf(Offset(left, top), Offset(centerX, top), Offset(right, top),
                Offset(left, centerY), Offset(right, centerY),
                Offset(left, bottom), Offset(centerX, bottom), Offset(right, bottom)).forEach {
                drawCircle(Color.Black, 6.dp.toPx(), it)
                drawCircle(tint, 4.dp.toPx(), it)
            }
        }
    }
}
