package com.gemini.ocr.movie

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ocr.OcrRecoveryPolicy
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoSubtitleAppearance
import com.gemini.ocr.video.VideoSubtitleStyle
import org.json.JSONObject

internal enum class MovieSubtitleMode(val label: String) {
    TRANSLATED("Tiếng Việt"), SOURCE("Tiếng Trung"), BOTH("Trung–Việt"), OFF("Tắt lớp chữ")
}

internal enum class MovieTextColor(val label: String, val argb: Int?) {
    ORIGINAL("Theo phim", null), WHITE("Trắng", 0xFFFFFFFF.toInt()),
    YELLOW("Vàng", 0xFFFFEB3B.toInt()), BLACK("Đen", 0xFF000000.toInt()),
    CYAN("Xanh nhạt", 0xFFB2EBF2.toInt())
}

/** Settings belong to the viewer. They never change a saved episode, source media or export style. */
internal data class MoviePlaybackSettings(
    val mode: MovieSubtitleMode = MovieSubtitleMode.TRANSLATED,
    val autoNext: Boolean = false,
    val controlTimeout: Int = 2500,
    val size: Float? = null,
    val position: Float? = null,
    val text: MovieTextColor = MovieTextColor.ORIGINAL,
    val outline: MovieTextColor = MovieTextColor.BLACK,
    val thickness: Float? = null,
    val shadow: Boolean = false,
    val darkBackground: Boolean? = null,
    val spacing: Float = 1f
) {
    fun style(original: VideoSubtitleStyle): VideoSubtitleStyle {
        if (size == null && position == null && thickness == null && darkBackground == null) return original
        val style = original.displayStyle()
        return style.copy(sizeFraction = size ?: style.sizeFraction, position = position ?: style.position,
            frame = style.frame?.let { frame -> position?.let { frame.move(0f, it - frame.bottom) } ?: frame },
            outline = thickness?.let { it > 0f } ?: style.outline,
            background = darkBackground ?: style.background, legacyDefault = false)
    }

    fun appearance() = VideoSubtitleAppearance(text.argb, outline.argb ?: 0xFF000000.toInt(),
        thickness ?: .08f, shadow, spacing)

    fun save(context: Context) {
        val json = JSONObject()
            .put("mode", mode.name).put("autoNext", autoNext).put("controlTimeout", controlTimeout)
            .put("size", size ?: JSONObject.NULL).put("position", position ?: JSONObject.NULL)
            .put("text", text.name).put("outline", outline.name).put("thickness", thickness ?: JSONObject.NULL)
            .put("shadow", shadow).put("darkBackground", darkBackground ?: JSONObject.NULL).put("spacing", spacing)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("settings", json.toString()).apply()
    }

    companion object {
        private const val PREFS = "movie-playback-settings"
        val timeouts = listOf(2500, 4000, 6000)
        fun load(context: Context): MoviePlaybackSettings = runCatching {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("settings", "{}") ?: "{}"
            val json = JSONObject(stored)
            fun decimal(key: String, interval: ClosedFloatingPointRange<Float>) =
                json.optDouble(key).toFloat().takeIf { it in interval }
            MoviePlaybackSettings(
                MovieSubtitleMode.entries.firstOrNull { it.name == json.optString("mode") } ?: MovieSubtitleMode.TRANSLATED,
                json.optBoolean("autoNext"), json.optInt("controlTimeout").takeIf { it in timeouts } ?: 2500,
                decimal("size", .02f.. .09f), decimal("position", .12f.. .95f),
                MovieTextColor.entries.firstOrNull { it.name == json.optString("text") } ?: MovieTextColor.ORIGINAL,
                MovieTextColor.entries.firstOrNull { it.name == json.optString("outline") } ?: MovieTextColor.BLACK,
                decimal("thickness", 0f.. .16f), json.optBoolean("shadow"),
                json.opt("darkBackground") as? Boolean, decimal("spacing", .9f..1.5f) ?: 1f
            )
        }.getOrDefault(MoviePlaybackSettings())
    }
}

internal fun movieSubtitleModes(project: VideoProject): List<MovieSubtitleMode> {
    val translated = project.cues.any { it.translation.isNotBlank() }
    // OCR subtitles may already be in the original pixels. Never overlay a duplicate Chinese line.
    val source = project.sourceMode != VideoSourceMode.OCR && project.cues.none { "OCR" in it.origin } &&
        project.cues.any { OcrRecoveryPolicy.hasHan(it.source) }
    return buildList {
        if (translated) add(MovieSubtitleMode.TRANSLATED)
        if (source) add(MovieSubtitleMode.SOURCE)
        if (source && translated) add(MovieSubtitleMode.BOTH)
        if (isNotEmpty()) add(MovieSubtitleMode.OFF)
    }
}

internal fun movieDisplayCues(cues: List<VideoCue>, mode: MovieSubtitleMode): List<VideoCue> = when (mode) {
    MovieSubtitleMode.TRANSLATED -> cues
    MovieSubtitleMode.OFF -> emptyList()
    MovieSubtitleMode.SOURCE, MovieSubtitleMode.BOTH -> cues.map { cue ->
        val source = cue.source.takeIf(OcrRecoveryPolicy::hasHan).orEmpty()
        cue.copy(translation = if (mode == MovieSubtitleMode.SOURCE) source else
            listOf(source, cue.translation).filter { it.isNotBlank() }.joinToString("\n").takeIf { it.length <= 8000 }
                ?: cue.translation)
    }
}

internal fun movieWatchedToEnd(position: Long, duration: Long): Boolean =
    duration > 0 && position > 0 && position >= duration - minOf(1000L, duration / 20)

internal fun movieResumePosition(position: Long, duration: Long): Long =
    if (movieWatchedToEnd(position, duration)) 0L else position.coerceIn(0L, duration.coerceAtLeast(0))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoviePlaybackSettingsDialog(
    settings: MoviePlaybackSettings, modes: List<MovieSubtitleMode>, original: VideoSubtitleStyle,
    onChange: (MoviePlaybackSettings) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cài đặt phát phim") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ẩn điều khiển sau", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoviePlaybackSettings.timeouts.forEach { timeout ->
                    FilterChip(settings.controlTimeout == timeout, { onChange(settings.copy(controlTimeout = timeout)) },
                        label = { Text(if (timeout == 2500) "2,5 giây" else "${timeout / 1000} giây") })
                }
            }
            FilterChip(settings.autoNext, { onChange(settings.copy(autoNext = !settings.autoNext)) },
                label = { Text("Tự phát tập tiếp đã có trên máy") })
            if (modes.isNotEmpty()) {
                val selected = settings.mode.takeIf { it in modes } ?: modes.first()
                Text("Phụ đề", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        FilterChip(selected == mode, { onChange(settings.copy(mode = mode)) }, label = { Text(mode.label) })
                    }
                }
                Text("Cỡ chữ")
                Slider(value = settings.size ?: original.sizeFraction, onValueChange = { onChange(settings.copy(size = it)) },
                    valueRange = .02f.. .09f)
                Text("Vị trí dọc")
                Slider(value = (settings.position ?: original.frame?.bottom ?: original.position).coerceIn(.12f, .95f),
                    onValueChange = { onChange(settings.copy(position = it)) }, valueRange = .12f.. .95f)
                Text("Màu chữ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MovieTextColor.entries.forEach { color ->
                        FilterChip(settings.text == color, { onChange(settings.copy(text = color)) }, label = { Text(color.label) })
                    }
                }
                Text("Màu viền")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MovieTextColor.BLACK, MovieTextColor.WHITE, MovieTextColor.CYAN).forEach { color ->
                        FilterChip(settings.outline == color, { onChange(settings.copy(outline = color)) }, label = { Text(color.label) })
                    }
                }
                Text("Độ dày viền")
                Slider(value = settings.thickness ?: if (original.outline) .08f else 0f,
                    onValueChange = { onChange(settings.copy(thickness = it)) }, valueRange = 0f.. .16f)
                Text("Khoảng cách dòng")
                Slider(value = settings.spacing, onValueChange = { onChange(settings.copy(spacing = it)) }, valueRange = .9f..1.5f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.shadow, { onChange(settings.copy(shadow = !settings.shadow)) }, label = { Text("Bóng chữ") })
                    val dark = settings.darkBackground ?: original.background
                    FilterChip(!dark, { onChange(settings.copy(darkBackground = !dark)) }, label = { Text("Nền trong suốt") })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Xong") } },
        dismissButton = { TextButton(onClick = { onChange(MoviePlaybackSettings()) }) { Text("Mặc định") } })
}
