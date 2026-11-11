package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoTimelineTest {
    @Test fun differentFollowingSubtitleConfirmsASingleBlankAsTheEndOfPreviousCue() {
        val acc=VideoCueAccumulator()
        acc.add(250,"你好");acc.add(4500,"");acc.add(4750,"再见")
        assertEquals(listOf(250L to 4500L,4750L to 6000L),acc.finish(6000).map{it.startMs to it.endMs})
    }
    @Test fun repeatedDialogueAfterConfirmedDisappearanceRemainsTwoOccurrences() {
        val accumulator = VideoCueAccumulator()
        accumulator.add(100, "别走")
        accumulator.add(200, "别走")
        accumulator.add(300, "")
        accumulator.add(400, "")
        accumulator.add(700, "别走")
        accumulator.add(800, "别走")

        val cues = accumulator.finish(1000)
        assertEquals(listOf("别走", "别走"), cues.map { it.source })
        assertEquals(listOf(100L to 300L, 700L to 1000L), cues.map { it.startMs to it.endMs })
        assertEquals(2, cues.map { it.id }.distinct().size)
        assertTrue(cues.all { it.origin == "OCR" })
    }

    @Test fun uncertainOcrAndOneTransientBlankCannotSplitContinuousDialogue() {
        val accumulator = VideoCueAccumulator()
        accumulator.add(100, "现在回家")
        accumulator.add(250, null)
        accumulator.add(400, "")
        accumulator.add(500, null)
        accumulator.add(650, "现在回家")

        val cue = accumulator.finish(900).single()
        assertEquals("现在回家", cue.source)
        assertEquals(100L, cue.startMs)
        assertEquals(900L, cue.endMs)
    }

    @Test fun sourceChangeClosesThePreviousCueAtTheSameBoundary() {
        val accumulator = VideoCueAccumulator()
        accumulator.add(0, "你好")
        accumulator.add(500, "再见")
        val cues = VideoTimeline.validate(accumulator.finish(1200), 1200)

        assertEquals(listOf(0L to 500L, 500L to 1200L), cues.map { it.startMs to it.endMs })
        assertEquals("你好", VideoTimeline.active(cues, 499)?.source)
        assertEquals("再见", VideoTimeline.active(cues, 500)?.source)
        assertNull(VideoTimeline.active(cues, 1200))
    }

    @Test fun checkpointRoundTripResumesTheOpenCueAndPendingBlankWithoutDuplicates() {
        fun firstHalf(accumulator: VideoCueAccumulator) {
            accumulator.add(100, "你好")
            accumulator.add(300, "别走")
            accumulator.add(450, "")
        }
        fun secondHalf(accumulator: VideoCueAccumulator): List<VideoCue> {
            accumulator.add(500, null)
            accumulator.add(550, "")
            accumulator.add(700, "别走")
            return accumulator.finish(1000)
        }
        val uninterrupted = VideoCueAccumulator().also(::firstHalf)
        val paused = VideoCueAccumulator().also(::firstHalf)
        val existing = paused.cues.map { it.copy(translation = "Chào bạn", approved = true, model = "reviewed") }
        val project = VideoProject("project", "Phim 中文", "content://selected/video", 1000, 1920, 1080,
            sourceMode = VideoSourceMode.OCR, stage = VideoStage.PAUSED, resumeStage = VideoStage.EXTRACTING,
            cues = existing, scanMs = 450, carry = paused.snapshot(), glossary = "军哥=anh Quân")

        val restored = VideoProject.from(JSONObject(project.json().toString()))
        val resumed = secondHalf(VideoCueAccumulator(restored.cues, restored.carry))
        val expected = secondHalf(uninterrupted)
        assertEquals(expected.map { Triple(it.startMs, it.endMs, it.source) },
            resumed.map { Triple(it.startMs, it.endMs, it.source) })
        assertEquals(expected.map { it.id }, resumed.map { it.id })
        assertEquals("Chào bạn", resumed.first().translation)
        assertTrue(resumed.first().approved)
        assertEquals("reviewed", resumed.first().model)
        assertEquals(450L, restored.scanMs)
        assertEquals(VideoStage.EXTRACTING, restored.resumeStage)
        assertEquals("军哥=anh Quân", restored.glossary)
        assertEquals(listOf(100L to 300L, 300L to 450L, 700L to 1000L), resumed.map { it.startMs to it.endMs })
    }

    @Test fun timelineValidationSortsInputAndUsesHalfOpenCueBoundaries() {
        val early = VideoCue("early", 100, 500, "你好")
        val late = VideoCue("late", 700, 1000, "再见")
        val cues = VideoTimeline.validate(listOf(late, early), 1000)

        assertEquals(listOf(early, late), cues)
        assertNull(VideoTimeline.active(cues, 99))
        assertEquals(early, VideoTimeline.active(cues, 100))
        assertNull(VideoTimeline.active(cues, 500))
        assertNull(VideoTimeline.active(cues, 699))
        assertEquals(late, VideoTimeline.active(cues, 700))
        assertNull(VideoTimeline.active(cues, 1000))
    }

    @Test fun editedOverlapInvalidBoundsAndDuplicateIdsAreRejectedBeforePlayback() {
        val first = VideoCue("a", 100, 500, "你好", "Chào bạn")
        val second = VideoCue("b", 500, 900, "再见", "Tạm biệt")
        assertEquals(2, VideoTimeline.validate(listOf(first, second), 1000).size)
        rejects { VideoTimeline.validate(listOf(first, second.copy(startMs = 499)), 1000) }
        rejects { VideoTimeline.validate(listOf(first.copy(startMs = -1)), 1000) }
        rejects { VideoTimeline.validate(listOf(first.copy(endMs = 100)), 1000) }
        rejects { VideoTimeline.validate(listOf(first.copy(endMs = 2000)), 1000) }
        rejects { VideoTimeline.validate(listOf(first, second.copy(id = "a")), 1000) }
        rejects { VideoTimeline.validate(listOf(first.copy(source = "  ")), 1000) }
    }

    @Test fun endBeyondActualVideoIsRejectedEvenInsideTheOldToleranceAndInputIsPreserved(){
        val first=VideoCue("first",0,700,"甲","Một")
        val outside=VideoCue("tail",700,1001,"乙","Hai")
        val input=listOf(outside,first)
        rejects{VideoTimeline.validate(input,1000)}
        assertEquals(listOf(outside,first),input)
        assertEquals(1001L,outside.endMs)
        rejects{VideoTimeline.srt(input,1000)}
        rejects{VideoTimeline.ass(VideoProject("project","Phim","file:///unused.mp4",1000,640,360,cues=input))}
    }

    @Test fun serializersUseChronologicalProjectionWithoutMergingSeparateOccurrences(){
        val early=VideoCue("early",100,500,"别走","Đừng đi")
        val late=VideoCue("late",700,1000,"别走","Đừng đi")
        val input=listOf(late,early)
        val srt=VideoTimeline.srt(input,1000)
        assertTrue(srt.indexOf("00:00:00,100")<srt.indexOf("00:00:00,700"))
        assertEquals(2,srt.lineSequence().count{it.contains(" --> ")})
        val ass=VideoTimeline.ass(VideoProject("project","Phim","file:///unused.mp4",1000,640,360,cues=input))
        assertTrue(ass.indexOf("0:00:00.10")<ass.indexOf("0:00:00.70"))
        assertEquals(2,ass.lineSequence().count{it.startsWith("Dialogue:")})
        assertEquals(listOf(late,early),input)
        val rows=VideoTimeline.validate(input,1000)
        for(time in listOf(0L,100L,499L,500L,699L,700L,999L,1000L,700L,100L,999L,500L,1000L)){
            val expected=when(time){in 100L..499L->early;in 700L..999L->late;else->null}
            assertEquals(expected,VideoTimeline.active(rows,time))
        }
    }

    @Test fun overlapIsReportedWithoutDeletingOrMovingEitherDialogue(){
        val a=VideoCue("a",100,800,"甲","Một")
        val b=VideoCue("b",700,1000,"乙","Hai")
        val input=listOf(a,b)
        rejects{VideoTimeline.validate(input,1000)}
        rejects{VideoTimeline.srt(input,1000)}
        assertEquals(listOf(a,b),input)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun framedAssUsesTheSameSortedIntervalsAsPlainAssAndSrt(){
        val early=VideoCue("early",100,500,"甲","Một")
        val late=VideoCue("late",700,1000,"乙","Hai")
        val project=VideoProject("project","Phim","file:///unused.mp4",1000,640,360,
            cues=listOf(late,early),style=VideoSubtitleStyle(background=true,frame=VideoSubtitleFrame.BOTTOM))
        val rows=VideoTimeline.ass(project).lineSequence().filter{it.startsWith("Dialogue:")}.toList()
        assertEquals(4,rows.size) // Background plus text for each actual cue.
        assertTrue(rows.take(2).all{it.contains("0:00:00.10,0:00:00.50")})
        assertTrue(rows.drop(2).all{it.contains("0:00:00.70,0:00:01.00")})
        assertEquals(listOf(late,early),project.cues)
    }

    @Test fun backwardsObservationCannotCorruptAResumedTimeline() {
        val accumulator = VideoCueAccumulator()
        accumulator.add(500, "你好")
        val restored = VideoCueAccumulator(accumulator.cues, JSONObject(accumulator.snapshot().toString()))
        rejects { restored.add(499, "再见") }
        assertEquals("你好", restored.finish(900).single().source)
    }

    @Test fun exportKeepsVietnameseAndPreventsAssOverrideInjection() {
        val cue = VideoCue("one", 1250, 3750, "你好", "Đừng đi {\\pos(0,0)}\nC:\\phim, người bạn!")
        val project = VideoProject("project", "Phim", "content://selected/video", 4000, 1280, 720,
            cues = listOf(cue), style = VideoSubtitleStyle(position = .8f))

        val ass = VideoTimeline.ass(project).toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        assertTrue(ass.contains("Dialogue: 0,0:00:01.25,0:00:03.75,"))
        assertTrue(ass.contains("Đừng đi ｛＼pos(0,0)｝\\NC:＼phim, người bạn!"))
        assertFalse(ass.contains("{\\pos(0,0)}"))
        assertEquals(1, ass.lineSequence().count { it.startsWith("Dialogue:") })
        assertTrue(ass.contains("PlayResX: 1280"))
        assertTrue(ass.contains("PlayResY: 720"))
    }

    @Test fun legacyProjectWithoutHybridFieldsOrStageTimersKeepsItsOriginalTimelineAndReviewedText(){
        val cue=VideoCue("legacy",100,800,"你好","Chào bạn",approved=true,model="reviewed")
        val original=VideoProject("legacy-project","Video cũ","content://selected/video",1000,640,360,
            translationMode=VideoTranslationMode.GEMINI,cues=listOf(cue))
        val json=original.json()
        json.remove("extractionElapsedMs");json.remove("translationElapsedMs");json.remove("exportElapsedMs");json.remove("ocrMetrics")
        json.getJSONArray("cues").getJSONObject(0).apply{remove("localDraft");remove("refinementState")}
        val restored=VideoProject.from(JSONObject(json.toString()))
        assertEquals(VideoTranslationMode.GEMINI,restored.translationMode)
        assertEquals(cue,restored.cues.single())
        assertEquals(0L,restored.extractionElapsedMs);assertEquals(0L,restored.translationElapsedMs);assertEquals(0L,restored.exportElapsedMs)
        assertEquals(0,restored.ocrMetrics.length())
        assertEquals("",restored.cues.single().localDraft);assertEquals("",restored.cues.single().refinementState)
    }

    @Test fun hybridCheckpointPersistsOriginalLocalDraftPendingErrorAndStageMetricsSeparately(){
        val pending=VideoCue("pending",100,800,"不卖250匹马","Bán 250 con ngựa",model="ML Kit",error="Chưa hiệu đính",
            localDraft="Bán 250 con ngựa",refinementState="pending")
        val done=VideoCue("done",900,1400,"再见","Tạm biệt",model="Gemini",localDraft="Tạm biệt cũ",refinementState="done")
        val original=VideoProject("hybrid-project","Phim","content://selected/video",2000,1280,720,
            translationMode=VideoTranslationMode.HYBRID,cues=listOf(pending,done),stage=VideoStage.FAILED,
            resumeStage=VideoStage.TRANSLATING,extractionElapsedMs=1100,translationElapsedMs=2200,exportElapsedMs=3300,
            ocrMetrics=JSONObject().put("samples",41).put("confirmations",7))
        val restored=VideoProject.from(JSONObject(original.json().toString()))
        assertEquals(VideoTranslationMode.HYBRID,restored.translationMode)
        assertEquals(original.cues,restored.cues)
        assertEquals(VideoStage.TRANSLATING,restored.resumeStage)
        assertEquals(1100L,restored.extractionElapsedMs);assertEquals(2200L,restored.translationElapsedMs);assertEquals(3300L,restored.exportElapsedMs)
        assertEquals(41,restored.ocrMetrics.getInt("samples"))
        assertFalse(VideoTranslationPlan.isComplete(restored.cues[0],true))
        assertTrue(VideoTranslationPlan.isComplete(restored.cues[1],true))
        val batch=VideoTranslationPlan.nextCloudBatch(restored.cues,true)!!
        assertEquals(listOf(0),batch.indices)
        assertEquals(listOf("不卖250匹马"),batch.texts)
        assertEquals(listOf("Bán 250 con ngựa"),batch.offlineDrafts)
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("An invalid or overlapping timeline must not be accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
