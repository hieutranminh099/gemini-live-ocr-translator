@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Immutable, bounded render input; contains no video pixels and never calls OCR or an API. */
internal data class SubtitleBackdropPlan(val left: Float, val top: Float, val right: Float, val bottom: Float,
    val intervals: List<Pair<Long, Long>>) {
    fun activeAt(timeUs: Long, playbackStartMs: Long = 0): Boolean {
        // Media3 playback timestamps are relative to the prepared position, including negative
        // timestamps when seeking behind it. Transformer timestamps already use the source clock.
        val relativeMs = Math.floorDiv(timeUs, 1000L)
        val timeMs = playbackStartMs + relativeMs
        if (timeMs < 0) return false
        var low = 0
        var high = intervals.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val interval = intervals[middle]
            when {
                timeMs < interval.first -> high = middle - 1
                timeMs >= interval.second -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

internal fun subtitleBackdropPlan(project: VideoProject): SubtitleBackdropPlan? {
    val style = project.style.displayStyle()
    if (!style.blurOriginal || style.background || project.sourceMode == VideoSourceMode.ASR) return null
    val roi = project.roi
    if (roi.size != 4 || roi.any { !it.isFinite() || it !in 0f..1f } || roi[2] <= roi[0] || roi[3] <= roi[1]) return null
    val rows = VideoTimeline.validate(project.cues, project.durationMs).filter {
        it.translation.isNotBlank() && !it.origin.startsWith("ASR")
    }
    if (rows.isEmpty()) return null
    // A broad OCR search area is not a request to obscure the lower quarter of the picture.
    // Narrow user-selected regions are kept exact; broad ones cover only a caption-height strip.
    val height = minOf(roi[3] - roi[1], .09f)
    val center = (roi[1] + roi[3]) / 2f
    val top = (center - height / 2f).coerceIn(0f, 1f - height)
    return SubtitleBackdropPlan(roi[0], top, roi[2], top + height,
        rows.map { it.startMs to it.endMs })
}

/** The same GPU effect runs in the player and before subtitle compositing in MP4 export. */
internal fun subtitleBackdropEffects(project: VideoProject, playbackStartMs: () -> Long = { 0L }): List<Effect> =
    subtitleBackdropPlan(project)?.let { listOf(SubtitleBackdropEffect(it, playbackStartMs)) } ?: emptyList()

internal class SubtitleBackdropEffect(private val plan: SubtitleBackdropPlan, private val playbackStartMs: () -> Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SubtitleBackdropShaderProgram(plan, useHdr, playbackStartMs)
}

/** Only pixels inside the chosen source-caption strip take the bounded 25-tap blur path. */
internal class SubtitleBackdropShaderProgram(private val plan: SubtitleBackdropPlan, useHdr: Boolean, private val playbackStartMs: () -> Long) :
    BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    init {
        try {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            program.setFloatsUniform("uRegion", floatArrayOf(plan.left, plan.top, plan.right, plan.bottom))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        // Radius is expressed in source-video pixels. Resolution/orientation never changes.
        val spacing = (inputHeight * .006f).coerceAtLeast(2f)
        program.setFloatsUniform("uStep", floatArrayOf(spacing / inputWidth, spacing / inputHeight))
        program.setFloatsUniform("uFeather", floatArrayOf(2f / inputWidth, 2f / inputHeight))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uImage", inputTexId, 0)
            program.setFloatUniform("uActive", if (plan.activeAt(presentationTimeUs, playbackStartMs())) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try { program.delete() } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        } finally { super.release() }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = (aPosition.xy + 1.0) * 0.5;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uImage;
            uniform vec4 uRegion;
            uniform vec2 uStep;
            uniform vec2 uFeather;
            uniform float uActive;
            varying vec2 vUv;
            float weight(int n) {
                if (n == 0) return 6.0;
                if (n == -1 || n == 1) return 4.0;
                return 1.0;
            }
            void main() {
                vec4 original = texture2D(uImage, vUv);
                vec2 position = vec2(vUv.x, 1.0 - vUv.y);
                if (uActive < 0.5 || position.x < uRegion.x || position.x > uRegion.z ||
                    position.y < uRegion.y || position.y > uRegion.w) {
                    gl_FragColor = original;
                    return;
                }
                vec4 blurred = vec4(0.0);
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        vec2 samplePoint = clamp(vUv + vec2(float(x), float(y)) * uStep,
                            vec2(0.001), vec2(0.999));
                        blurred += texture2D(uImage, samplePoint) * weight(x) * weight(y);
                    }
                }
                vec2 edge = min(position - uRegion.xy, uRegion.zw - position);
                float blend = smoothstep(0.0, uFeather.x, edge.x) * smoothstep(0.0, uFeather.y, edge.y);
                gl_FragColor = mix(original, blurred / 256.0, blend);
            }
        """
    }
}
