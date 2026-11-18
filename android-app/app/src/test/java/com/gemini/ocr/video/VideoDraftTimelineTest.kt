package com.gemini.ocr.video

import com.gemini.ocr.ocr.OcrRecoveryPolicy.Bounds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoDraftTimelineTest {
    private val box = Bounds(100, 240, 220, 266)

    @Test fun threeAdjacentOcrVariantsOfOneCaptionProduceOneCue() {
        val timeline = VideoDraftTimeline()
        timeline.addReading(20_066, VideoOcrReading("奶子好大", box))
        timeline.addReading(20_133, VideoOcrReading("奶子好大pm", box.copy(right = 244)))
        timeline.addReading(20_200, VideoOcrReading("奶子好大。m", box.copy(left = 101, right = 237)))
        val cues = timeline.finishAtValidatedEnd(20_266, 20_200)
        assertEquals(listOf("奶子好大"), cues.map { it.source })
        assertEquals(20_066L to 20_266L, cues.single().let { it.startMs to it.endMs })
        assertEquals(listOf("奶子好大pm", "奶子好大。m"), cues.single().ocrAlternatives)
        assertEquals(2L, timeline.consolidatedVariants)
        val cleaned = VideoStagedPipeline.clean(cues)
        val batch = requireNotNull(VideoTranslationPlan.nextCloudBatch(cleaned, hybrid = false))
        assertEquals(listOf("奶子好大"), batch.texts)
        val translated = VideoTranslationPlan.applyCloudResult(cleaned, batch, listOf("Một câu"), "fixture", false)
        assertEquals("1\n00:00:20,066 --> 00:00:20,266\nMột câu\n", VideoTimeline.srt(translated))
        assertEquals(cues.single(), VideoCue.from(cues.single().json()))
    }

    @Test fun ambiguousMixedScriptWithItsOwnSupportOrTrustedConfidenceStaysIntact() {
        val timeline = VideoDraftTimeline()
        val readings = listOf(
            "见面时间" to .93f, "见面时间pm" to .94f, "见面时间pm" to .95f,
            "见面时间" to .93f, "见面时间am" to .95f, "见面时间" to .94f,
            "见面7pm" to .90f, "见面7am" to .90f
        )
        readings.forEachIndexed { index, (text, confidence) ->
            timeline.addReading(index * 67L, VideoOcrReading(text, box, confidence))
        }
        assertEquals(listOf("见面时间", "见面时间pm", "见面时间", "见面时间am", "见面时间", "见面7pm", "见面7am"),
            timeline.finishAtValidatedEnd(560, 469).map { it.source })
        assertEquals(0L, timeline.consolidatedVariants)
    }

    @Test fun deferredReadingSurvivesResumeAndAConfirmedBlankOrShiftSeparatesEvents() {
        val original = VideoDraftTimeline()
        original.addReading(100, VideoOcrReading("等你回来", box))
        original.addReading(180, VideoOcrReading("等你回来m", box))
        assertTrue(original.cues.isEmpty())
        assertTrue(original.needsConfirmation)
        val timeline = VideoDraftTimeline(original.cues, JSONObject(original.snapshot().toString()))
        timeline.addReading(180, VideoOcrReading("等你回来m", box)) // decoder replay
        timeline.addReading(231, VideoOcrReading("等你回来", box))
        timeline.add(301, "")
        timeline.addReading(405, VideoOcrReading("等你回来", box))
        timeline.addReading(472, VideoOcrReading("等你回来", box.copy(top = 120, bottom = 146)))
        val cues = timeline.finishAtValidatedEnd(520, 472)
        assertEquals(listOf(100L to 301L, 405L to 472L, 472L to 520L), cues.map { it.startMs to it.endMs })
        assertEquals(3, cues.map { it.id }.toSet().size)
        assertEquals(listOf("等你回来m"), cues.first().ocrAlternatives)
        assertNull(VideoTimeline.active(cues, 301))
        assertEquals(cues.last().id, VideoTimeline.active(cues, 472)?.id)
    }

    @Test fun insufficientSpatialOrTemporalEvidencePreservesUnresolvedReadings() {
        for (axis in listOf("space", "time", "missing", "end")) {
            val timeline = VideoDraftTimeline()
            timeline.addReading(0, VideoOcrReading("请你回来", box))
            timeline.addReading(67, VideoOcrReading("请你回来pm", if (axis == "missing") null else box))
            val next = if (axis == "time") 410L else 138L
            if (axis != "end") {
                val shift = if (axis == "space") box.copy(top = 150, bottom = 176) else box
                timeline.addReading(next, VideoOcrReading("请你回来。m", shift))
            }
            val last = if (axis == "end") 67L else next
            val expected = if (axis == "end") listOf("请你回来", "请你回来pm")
                else listOf("请你回来", "请你回来pm", "请你回来。m")
            assertEquals(axis, expected, timeline.finishAtValidatedEnd(last + 52, last).map { it.source })
        }
    }

    @Test fun singleObservationOfAShortCaptionAndRapidHanChangesRemainReviewable() {
        val timeline = VideoDraftTimeline()
        timeline.add(0, "")
        timeline.addReading(83, VideoOcrReading("好", box))
        timeline.addReading(403, VideoOcrReading("好的", box))
        timeline.addReading(477, VideoOcrReading("再见", box))
        timeline.add(516, "")
        val cues = timeline.finishAtValidatedEnd(625, 516)
        assertEquals(listOf("好", "好的", "再见"), cues.map { it.source })
        assertEquals(listOf(83L to 403L, 403L to 477L, 477L to 516L), cues.map { it.startMs to it.endMs })
    }

    @Test fun weakEdgeStrokesAtEitherSideOfAShortCaptionDoNotCreateExtraRuns() {
        val timeline = VideoDraftTimeline()
        val samples = listOf(
            1666L to ("好的" to .83f),
            1733L to ("|好的" to .63f),
            1800L to ("好的" to .86f),
            1866L to ("好的│" to .56f),
            2000L to ("|再见" to .48f),
            2100L to ("再见" to .72f)
        )
        for ((ms, sample) in samples) {
            timeline.addReading(ms, VideoOcrReading(sample.first, box, sample.second))
        }
        val cues = timeline.finishAtValidatedEnd(2400, 2100)
        assertEquals(listOf("好的", "再见"), cues.map { it.source })
        assertEquals(listOf(1666L to 2000L, 2000L to 2400L), cues.map { it.startMs to it.endMs })
        assertEquals(listOf("|好的", "好的│"), cues.first().ocrAlternatives)
        assertEquals(listOf("|再见"), cues.last().ocrAlternatives)
    }

    @Test fun pipeWithReliableOrUnavailableConfidenceRemainsLiteralAndInternalPipesNeverMatch() {
        val timeline = VideoDraftTimeline()
        timeline.addReading(0, VideoOcrReading("好的", box, .83f))
        timeline.addReading(67, VideoOcrReading("|好的", box, .92f))
        timeline.addReading(133, VideoOcrReading("好的", box, .83f))
        timeline.addReading(200, VideoOcrReading("好的|", box))
        timeline.addReading(267, VideoOcrReading("好|的", box, .41f))
        timeline.addReading(333, VideoOcrReading("好的", box, .87f))
        assertEquals(listOf("好的", "|好的", "好的", "好的|", "好|的", "好的"),
            timeline.finishAtValidatedEnd(360, 333).map { it.source })
    }

    @Test fun longLowConfidenceSubstitutionNeedsMatchingNeighborsAndCannotHideNegation() {
        val timeline = VideoDraftTimeline()
        val steady = "我们明天一起去看电影"
        val readings = listOf(steady, "我们明天一超去看电影", steady, "我们明天一不去看电影", steady)
        readings.forEachIndexed { index, text ->
            timeline.addReading(index * 65L, VideoOcrReading(text, box, if (index % 2 == 0) .95f else .4f))
        }
        val cues = timeline.finishAtValidatedEnd(320, 260)
        assertEquals(listOf(steady, readings[3], steady), cues.map { it.source })
        assertEquals(listOf(readings[1]), cues.first().ocrAlternatives)
    }

    @Test fun originalUnicodeAndLineBoundariesSurviveNormalizationAndV2Checkpoint() {
        val old = JSONObject().put("draftVersion", 2).put("scanLast", 10).put("draftNextId", 7)
            .put("draftRun", JSONObject().put("raw", "𠮷字\n编号１").put("start", 10).put("last", 10).put("count", 1))
        val timeline = VideoDraftTimeline(saved = old)
        timeline.add(90, "𠮷字\u200b\n编号1")
        timeline.add(191, "𠮷字\n编号①")
        val cues = timeline.finishAtValidatedEnd(240, 191)
        assertEquals(listOf("ocr-draft-7", "ocr-draft-8"), cues.map { it.id })
        assertEquals("𠮷字\n编号１", cues[0].rawZh)
        assertEquals("𠮷字 编号1", cues[0].normalizedOcrText)
        assertEquals("𠮷字\n编号①", cues[1].source)
    }

    @Test fun actualSingleFrameAndJitteringMultilineDraftsSurviveWithoutInventedCharacters(){
        val t=VideoDraftTimeline()
        val source=listOf("不知不觉就熟悉了每个人。\n聊个不停，舍不得离开。",
            "不知不觉就熟悉了每个人。\n聊个不停。舍不得离开。","不是三个","是三个")
        source.forEachIndexed{i,text->t.add(i*65L,text)}
        val cues=t.finish(300)
        assertEquals(source,cues.map{it.source});assertEquals(source,cues.map{it.rawZh})
        assertEquals(listOf(0L to 65L,65L to 130L,130L to 195L,195L to 300L),cues.map{it.startMs to it.endMs})
        assertTrue(cues.all{"OCR_UNSTABLE_SINGLE_FRAME" in it.qualityFlags})
        assertTrue(cues.all{it.translation.isBlank() && !it.approved})
    }
    @Test fun adjacentStableExactReadingsMergeButNegationAndDistantRepeatNeverDo(){
        val t=VideoDraftTimeline()
        t.add(0,"有3.5个");t.add(65,"有3.5个");t.add(130,"没有3.5个");t.add(195,"没有3.5个")
        t.add(500,"有3.5个");t.add(565,"有3.5个")
        val cues=t.finish(700)
        assertEquals(listOf("有3.5个","没有3.5个","有3.5个"),cues.map{it.source})
        assertEquals(listOf(0L to 130L,130L to 500L,500L to 700L),cues.map{it.startMs to it.endMs})
        assertTrue(cues.none{"OCR_UNSTABLE_SINGLE_FRAME" in it.qualityFlags})
        assertEquals(3,cues.map{it.id}.distinct().size)
    }
    @Test fun singleEvidenceIsCappedAcrossLongUnknownIntervalsAndNullDoesNotCreateBlankDialogue(){
        val t=VideoDraftTimeline()
        t.add(0,"实际出现");t.add(100,null)
        assertEquals(0L to 100L,t.cues.single().let{it.startMs to it.endMs})
        // Unknown is not a blank dialogue; the evidence interval ends at its actual PTS.
        t.add(5_000,null)
        assertEquals(0L to 100L,t.cues.single().let{it.startMs to it.endMs})
        t.add(10_000,"后来出现")
        val cues=t.finish(20_000)
        assertEquals(listOf("实际出现","后来出现"),cues.map{it.source})
        assertEquals(10_200L,cues.last().endMs)
        assertTrue(cues.all{"OCR_UNCERTAIN_INTERVAL" in it.qualityFlags})
    }
    @Test fun ordinaryTwentyFourFpsSamplingDoesNotSplitAnIdenticalRunAtRoundingBoundaries(){
        val t=VideoDraftTimeline()
        t.add(0,"连续字幕");t.add(209,"连续字幕");t.add(418,"连续字幕")
        val cues=t.finish(600)
        assertEquals(1,cues.size);assertEquals(0L to 600L,cues.single().let{it.startMs to it.endMs})
        assertFalse("OCR_UNSTABLE_SINGLE_FRAME" in cues.single().qualityFlags)
    }
    @Test fun checkpointRestoresPendingRunIdsRawLinesAndUnknownStateWithoutDuplicates(){
        val original=VideoDraftTimeline()
        original.add(0,"第一句");original.add(65,"第一句");original.add(130,"第二句\n保留换行")
        val resumed=VideoDraftTimeline(original.cues.toList(),original.snapshot())
        resumed.add(130,"第二句\n保留换行") // replay is not another independent frame
        resumed.add(195,null);resumed.add(260,"第三句")
        val rows=resumed.finish(400)
        assertEquals(listOf("第一句","第二句\n保留换行","第三句"),rows.map{it.source})
        assertEquals(original.cues.first(),rows.first());assertEquals(3,rows.map{it.id}.distinct().size)
        assertTrue("OCR_UNSTABLE_SINGLE_FRAME" in rows[1].qualityFlags)
        assertTrue("OCR_UNCERTAIN_INTERVAL" in rows[1].qualityFlags)
    }
    @Test fun pendingBufferStaysOneBoundedRunAndQualityUsesActualOccurrence(){
        val t=VideoDraftTimeline();val q=VideoOcrDraftQuality()
        repeat(100){index->
            val ms=index*65L;q.record("真实行\n第二行",.2f,listOf("LOW_OCR_CONFIDENCE"),"真实行 第二行",ms)
            t.add(ms,"真实行\n第二行")
        }
        val checkpoint=t.snapshot()
        assertTrue(checkpoint.toString().length<1000);assertEquals(0,t.cues.size)
        val row=q.close(t.finish(6600).single())
        assertEquals("真实行\n第二行",row.rawZh)
        assertEquals(.2f,row.ocrConfidence!!,0f)
        assertTrue("LOW_OCR_CONFIDENCE" in row.qualityFlags)
        assertEquals(0L,row.startMs);assertEquals(6600L,row.endMs)
    }
    @Test fun canonicalExactFormattingStaysOneOccurrenceAndKeepsFirstRaw(){
        val t=VideoDraftTimeline()
        val raw="价格３．５\n不要走。"
        t.add(0,raw);t.add(65,"价格3.5 不要走。");t.add(130,"价格3.5\n不要走。")
        val row=t.finishAtValidatedEnd(160,130).single()
        assertEquals(raw,row.source);assertEquals(raw,row.rawZh)
        assertEquals(0L to 160L,row.startMs to row.endMs)
        assertFalse("OCR_UNSTABLE_SINGLE_FRAME" in row.qualityFlags)
    }
    @Test fun punctuationDigitsAndNegationAreNotFormattingEquivalence(){
        val sources=listOf("可以?","可以!","有3.5个","有35个","有-3个","有3个","是三个","不是三个")
        val t=VideoDraftTimeline()
        sources.forEachIndexed{i,text->t.add(i*65L,text)}
        val rows=t.finishAtValidatedEnd(520,455)
        assertEquals(sources,rows.map{it.source})
        assertTrue(rows.all{"OCR_UNSTABLE_SINGLE_FRAME" in it.qualityFlags})
    }
    @Test fun lowFrameRateExactCaptionHasNoManufacturedGapsAndReachesVerifiedEnd(){
        val t=VideoDraftTimeline()
        for(ms in listOf(0L,333L,666L,999L))t.add(ms,"同一段字幕")
        val row=t.finishAtValidatedEnd(1000,999).single()
        assertEquals(0L to 1000L,row.startMs to row.endMs)
        assertEquals("同一段字幕",row.source)
    }
    @Test fun knownBoundaryPreservesSingleFrameRatherThanTruncatingAtGuessedSamplingInterval(){
        val t=VideoDraftTimeline()
        t.add(0,"第一帧");t.add(1000,"第二帧")
        val rows=t.finishAtValidatedEnd(2000,1000)
        assertEquals(listOf(0L to 1000L,1000L to 2000L),rows.map{it.startMs to it.endMs})
        assertTrue(rows.all{"OCR_UNSTABLE_SINGLE_FRAME" in it.qualityFlags})
    }
    @Test fun blankAndUnknownSeparateRepeatedDialogueAndCarryKeepsThatBoundary(){
        val t=VideoDraftTimeline()
        t.add(0,"别走");t.add(65,"别走");t.add(130,"")
        t.add(195,"别走");t.add(260,"别走");t.add(325,null)
        val resumed=VideoDraftTimeline(t.cues.toList(),t.snapshot())
        resumed.add(390,"别走");resumed.add(455,"别走")
        val rows=resumed.finishAtValidatedEnd(500,455)
        assertEquals(listOf(0L to 130L,195L to 325L,390L to 500L),rows.map{it.startMs to it.endMs})
        assertEquals(listOf("别走","别走","别走"),rows.map{it.source})
        assertEquals(3,rows.map{it.id}.distinct().size)
    }
    @Test fun legitimateShortChangedSentenceCannotBeHiddenByEarlierAndLaterIdenticalText(){
        val t=VideoDraftTimeline()
        t.add(0,"是");t.add(65,"是");t.add(130,"不是");t.add(195,"是");t.add(260,"是")
        val rows=t.finishAtValidatedEnd(300,260)
        assertEquals(listOf("是","不是","是"),rows.map{it.source})
        assertEquals(130L to 195L,rows[1].startMs to rows[1].endMs)
    }
    @Test fun finalShortCueUsesActualEndAndCannotReappearAfterEnd(){
        val t=VideoDraftTimeline()
        t.add(59_000,"前一句");t.add(59_500,"前一句");t.add(59_980,"末句")
        val rows=VideoTimeline.validate(t.finishAtValidatedEnd(60_000,59_980),60_000)
        assertEquals(59_980L to 60_000L,rows.last().startMs to rows.last().endMs)
        assertEquals("末句",VideoTimeline.active(rows,59_999)?.source)
        assertNull(VideoTimeline.active(rows,60_000))
        assertEquals(rows,t.finishAtValidatedEnd(60_000,59_980))
    }
    @Test fun eosWithoutMatchingFinalFrameCannotStretchUnverifiedText(){
        val t=VideoDraftTimeline();t.add(0,"只有一次")
        val row=t.finishAtValidatedEnd(10_000,9_999).single()
        assertEquals(200L,row.endMs)
        assertTrue("OCR_UNCERTAIN_INTERVAL" in row.qualityFlags)
    }
}
