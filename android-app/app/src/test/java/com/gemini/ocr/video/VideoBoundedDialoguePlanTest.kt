package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBoundedDialoguePlanTest {
    private fun cues(count: Int, source: (Int) -> String = { "这是第${it}句原文" }) = List(count) {
        VideoCue("ocr-${it + 1}", it * 1000L, it * 1000L + 800, source(it))
    }

    @Test fun earlyCapAvoidsOversizedCandidateRequestsAndKeepsTheWholeDialogue() {
        val original = cues(1891).mapIndexed { index, cue ->
            when (index) {
                0 -> cue.copy(translation = "Câu đã duyệt", approved = true)
                3 -> cue.copy(translation = "Đã dịch", refinementState = "done")
                else -> cue
            }
        }
        val inspected = mutableListOf<Int>()
        val plan = VideoFullDialoguePlan.next(original, 1_891_000, "Hồ sơ phim", 16) {
            inspected += it.cueIds.size
            VideoTranslationClient.buildRequest(it, "modern", "")
            true
        }!!
        assertEquals(16, plan.cueIds.size)
        assertTrue(inspected.all { it <= 16 })
        assertTrue(plan.cueIds.none { it == original[0].id || it == original[3].id })
        val context = JSONObject(plan.videoContext)
        assertTrue(context.getBoolean("contextComplete"))
        assertEquals(1891, context.getJSONArray("dialogue").length())
        assertEquals("Hồ sơ phim", context.getString("filmContext"))
        assertTrue(plan.maxOutputTokens in 8192..VideoFullDialoguePlan.MAX_OUTPUT_TOKENS)
        assertTrue(plan.videoContext.toByteArray(Charsets.UTF_8).size <= VideoFullDialoguePlan.MAX_CONTEXT_BYTES)
    }

    @Test fun cappedPlansEqualTheOldMonotonePreflightWithFullAndWindowedUnicodeContext() {
        for (original in listOf(cues(1000), cues(300) { "中".repeat(1500) + "𠮷$it" })) {
            for (cap in listOf(8, 16, 64)) {
                val fits: (VideoTranslationPlan.Batch) -> Boolean = {
                    it.videoContext.toByteArray(Charsets.UTF_8).size <= 256 * 1024
                }
                val old = VideoFullDialoguePlan.next(original, original.size * 1000L, "Ngữ cảnh") {
                    it.cueIds.size <= cap && fits(it)
                }
                val bounded = VideoFullDialoguePlan.next(original, original.size * 1000L, "Ngữ cảnh", cap, fits)
                assertEquals(old, bounded)
                assertTrue(bounded!!.cueIds.size <= cap)
                assertTrue(bounded.videoContext.toByteArray(Charsets.UTF_8).size <= VideoFullDialoguePlan.MAX_CONTEXT_BYTES)
            }
        }
    }

    @Test fun repeatedCappedPlansCoverEveryPendingIdOnceWithoutChangingSourcesOrApprovedRows() {
        val original = cues(77).mapIndexed { index, cue ->
            if (index == 7) cue.copy(translation = "Bản đã duyệt", approved = true) else cue
        }
        val before = original.map { listOf(it.id, it.source, it.startMs, it.endMs) }
        var current = original
        val seen = mutableSetOf<String>()
        var calls = 0
        while (true) {
            val plan = VideoFullDialoguePlan.next(current, 77_000, maxTargets = 16) ?: break
            calls++
            assertTrue(plan.cueIds.size <= 16)
            assertEquals(77, JSONObject(plan.videoContext).getJSONArray("dialogue").length())
            assertTrue(plan.cueIds.none { it in seen || it == original[7].id })
            seen += plan.cueIds
            current = VideoTranslationPlan.applyCloudResult(current, plan,
                plan.cueIds.map { "Bản Việt $it" }, "synthetic-model", true)
            assertTrue(calls < 10)
        }
        assertEquals(5, calls)
        assertEquals(original.map { it.id }.filter { it != original[7].id }.toSet(), seen)
        assertEquals(before, current.map { listOf(it.id, it.source, it.startMs, it.endMs) })
        assertEquals(original[7], current[7])
        assertTrue(current.all { VideoTranslationPlan.isComplete(it, true) })
    }

    @Test fun explicitCapValidatesItsDomainAndOriginalOverloadKeepsItsContract() {
        val original = cues(100)
        assertEquals(100, VideoFullDialoguePlan.next(original, 100_000)!!.cueIds.size)
        assertEquals(16, VideoFullDialoguePlan.next(original, 100_000, maxTargets = 16)!!.cueIds.size)
        assertThrows(IllegalArgumentException::class.java) {
            VideoFullDialoguePlan.next(original, 100_000, maxTargets = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoFullDialoguePlan.next(original, 100_000, maxTargets = -1)
        }
    }
}
