package com.gemini.ocr.capture

import android.graphics.Rect
import com.gemini.ocr.ocr.OcrBlock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/** Fake ML Kit output, not a native OCR/video or network benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrSourceSelectionTest {
    private val crop = Rect(60, 670, 940, 940)
    private val stability = StableText()
    private val cloudStability = StableText()
    private var frameId = 0L

    companion object {
        private val traceFile = File("build/reports/codex-ocr-synthetic-trace.jsonl")
        @JvmStatic @BeforeClass fun clearTrace() {
            checkNotNull(traceFile.parentFile).mkdirs()
            traceFile.writeText("")
        }
    }

    @Before fun clearLog() { ShadowLog.clear() }
    @After fun saveSyntheticTrace() {
        traceFile.appendText(ShadowLog.getLogsForTag("OCRPipeline").joinToString("") { "${it.msg}\n" })
    }

    private fun rows(source: String) = listOf(
        OcrBlock("zh", source, Rect(20, 10, 500, 45)),
        OcrBlock("vi", "Đến lúc quan trọng lại hỏng việc", Rect(20, 80, 700, 115)),
    )

    private fun select(raw: List<OcrBlock>, turbo: Boolean = true, stabilize: Boolean = false,
                       region: String = "subtitle", displayed: String? = null): OcrSourceSelection.Result {
        val result = OcrSourceSelection.select(raw, if (region == "subtitle") crop else null,
            "zh", 1000, region, region == "subtitle", false, turbo, stabilize, displayed,
            stability, cloudStability)
        // Fixed fixture durations verify trace plumbing. They are not latency measurements.
        PipelineTrace.event("synthetic_frame", "frame_id" to ++frameId, "synthetic" to true)
        result.trace(frameId, 2, 7, 40, frameId, 0)
        return result
    }

    @Test fun bothReportedChineseLinesSurviveVietnameseRowAndFirstObservation() {
        for (turbo in listOf(false, true)) for (stabilize in listOf(false, true)) {
            for (source in listOf("关键时候掉链子", "还得是我兄弟")) {
                stability.reset(); cloudStability.reset()
                val selected = select(rows(source), turbo, stabilize, displayed = "Đến lúc quan trọng lại hỏng việc")
                assertFalse(selected.renderedCopy)
                assertEquals("accepted", selected.decision)
                assertEquals(source, selected.blocks.single().text)
                assertEquals(2, selected.rawCount)
                assertEquals(1, selected.recognizedCount)
            }
        }
    }

    @Test fun disappearanceAndNextSentenceInvalidateOldReplyWithTrace() {
        val pipeline = SubtitlePipeline("synthetic-roi")
        fun observe(result: OcrSourceSelection.Result) {
            val signature = StableText.signature(result.blocks.map { SubtitleText.identity(it.text) })
            if (pipeline.observe(signature)) PipelineTrace.event("source_changed",
                "synthetic" to true, "frame_id" to frameId, "empty" to result.blocks.isEmpty(),
                "region_id" to pipeline.regionId, "screen_generation" to pipeline.screenGeneration,
                "source_hash" to PipelineTrace.hash(signature), "capture_ms" to 2,
                "ocr_queue_ms" to 7, "ocr_ms" to 40)
        }
        observe(select(rows("关键时候掉链子")))
        val previous = pipeline.request("old")!!
        observe(select(emptyList()))
        assertFalse(pipeline.accepts(previous))
        assertEquals("", pipeline.signature)
        observe(select(rows("还得是我兄弟")))
        assertFalse(pipeline.accepts(previous))
        assertTrue(pipeline.accepts(pipeline.request("new")!!))
        val changes = ShadowLog.getLogsForTag("OCRPipeline").filter { it.msg.contains("source_changed") }
        assertEquals(3, changes.size)
        assertTrue(changes[1].msg.contains("\"empty\":true"))
        val evaluations = ShadowLog.getLogsForTag("OCRPipeline").filter { it.msg.contains("ocr_evaluated") }
        assertTrue(evaluations.all { it.msg.contains("\"ocr_queue_ms\":7") && it.msg.contains("\"ocr_ms\":40") })
        assertTrue(evaluations.none { it.msg.contains("关键") || it.msg.contains("Đến") })
    }

    @Test fun nativeEmptyAndMissingSourceScriptHaveDifferentReasons() {
        assertEquals("ocr_empty", select(emptyList()).decision)
        val vietnameseOnly = rows("还得是我兄弟").filter { it.id == "vi" }
        assertEquals("source_script_missing", select(vietnameseOnly).decision)
        assertTrue(select(vietnameseOnly).blocks.isEmpty())
    }
    @Test fun explicitChineseFrameNeverSendsPreviousVietnameseOverlayAsSource() {
        for (displayed in listOf(null, "Lại hỏng việc vào phút chót")) {
            val result = OcrSourceSelection.select(
                listOf(OcrBlock("vi", "Lại hỏng việc vào phút chót", Rect(10, 10, 600, 45))),
                crop, "zh", 1000, "subtitle", true, true, true, false, displayed,
                stability, cloudStability)
            assertTrue(result.blocks.isEmpty())
            assertEquals("source_script_missing", result.decision)
        }
        val result = OcrSourceSelection.select(rows("还得是我兄弟"), crop, "zh", 1000,
            "subtitle", true, true, true, false, null, stability, cloudStability)
        assertEquals("还得是我兄弟", result.blocks.single().text)
    }

    @Test fun actualRenderedCopySkipsObservationRatherThanBecomingNewEmptySource() {
        // Chinese+Vietnamese cannot equal this rendered visual key. A Latin-only recognition
        // in one-frame mode can, and must retain the existing early-return behavior.
        stability.observe(listOf("别走")); cloudStability.observe(listOf("别走"))
        val result = OcrSourceSelection.select(listOf(OcrBlock("vi", "Xin chào!", Rect(0, 0, 100, 30))),
            null, "zh", 1000, "subtitle", true, true, true, false, "Xin chao",
            stability, cloudStability)
        assertTrue(result.renderedCopy)
        assertEquals("rendered_copy", result.decision)
        assertTrue("Skipped overlay must not reset source streaks", stability.observe(listOf("别走")).contains("别走"))
        assertTrue(cloudStability.observe(listOf("别走")).contains("别走"))
        result.trace(1, 2, 7, 40, 1, 0)
        assertTrue(ShadowLog.getLogsForTag("OCRPipeline").last().msg.contains("\"applied\":false"))
        assertEquals("accepted", select(rows("还得是我兄弟"), displayed = "Xin chào").decision)
    }

    @Test fun generalScreenRegionAndStabilityRejectionsAreDiagnosable() {
        val top = listOf(OcrBlock("zh", "还得是我兄弟", Rect(20, 10, 500, 45)))
        assertEquals("region_filtered", select(top, region = "dialogue").decision)
        assertEquals("region_filtered", select(top, region = "center").decision)
        assertEquals("awaiting_stability", select(top, turbo = false, stabilize = true, region = "all").decision)
        assertEquals("accepted", select(top, turbo = false, stabilize = true, region = "all").decision)
    }
}
