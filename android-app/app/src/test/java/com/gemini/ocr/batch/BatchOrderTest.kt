package com.gemini.ocr.batch

import org.junit.Assert.*
import org.junit.Test

class BatchOrderTest {
    private fun item(name: String, episode: String = "1") = BatchItem(name, name, episode = episode)
    @Test fun fiveImportedEpisodesSortNaturallyInsteadOfLexically() {
        val input = listOf("EP10.mp4", "EP02.mp4", "EP01.mp4", "EP12.mp4", "EP03.mp4").map(::item)
        assertEquals(listOf("EP01.mp4", "EP02.mp4", "EP03.mp4", "EP10.mp4", "EP12.mp4"), BatchOrder.suggested(input).map { it.name })
    }
    @Test fun vietnameseChineseAndPartMarkersRecognizeNumbers() {
        assertEquals("10", BatchOrder.identify("Phim Tập 10.mp4").episode)
        assertEquals("2", BatchOrder.identify("phim 第02集.mp4").episode)
        assertEquals(listOf("Part 1.mp4", "Part 2.mp4", "Part 10.mp4"),
            BatchOrder.suggested(listOf("Part 10.mp4", "Part 2.mp4", "Part 1.mp4").map(::item)).map { it.name })
        assertEquals("S2E10", BatchOrder.episodeKey(BatchOrder.identify("Show.S02E10.part02.mp4")))
    }
    @Test fun ambiguousAndMixedNamesKeepTheOwnersImportOrder() {
        val uncertain = listOf("clip10.mp4", "clip2.mp4", "clip1.mp4").map(::item)
        assertEquals(uncertain, BatchOrder.suggested(uncertain))
        val mixed = listOf(item("EP10.mp4"), item("Part2.mp4"), item("EP01.mp4"))
        assertEquals(mixed, BatchOrder.suggested(mixed))
    }
    @Test fun numericRunsDoNotOverflowOrMergeDifferentNumbers() {
        assertTrue(BatchOrder.natural("EP99999999999999999999", "EP100000000000000000000") < 0)
        assertEquals(0, BatchOrder.natural("EP0002", "EP2"))
        assertTrue(BatchOrder.natural("EP2", "EP10") < 0)
    }
    @Test fun manualOrderAndExplicitEpisodeGroupsSurviveRestart() {
        val input = listOf(item("EP01 Part1.mp4", "1"), item("EP02.mp4", "2"), item("EP01 Part2.mp4", "1"))
        val ordered = BatchPlan.move(input, 2, 1)
        val batch = BatchProject("test", "Series", BatchKind.SERIES, ordered)
        assertEquals(listOf("EP01 Part1.mp4", "EP01 Part2.mp4"), BatchPlan.groups(batch).getValue("1").map { it.name })
        assertEquals(listOf("EP02.mp4"), BatchPlan.groups(batch).getValue("2").map { it.name })
        assertEquals(ordered, BatchPlan.interrupted(batch.copy(state = "RUNNING")).items)
    }
    @Test fun duplicateEpisodeAndUnnamedMoviePartsImportAsSeparateOutputsUntilGrouped() {
        val inputs = listOf("EP01 Part1.mp4", "EP01 Part2.mp4", "Part 1.mp4", "Part 2.mp4")
        val occupied = mutableSetOf<String>()
        val items = inputs.mapIndexed { index, name ->
            val projectId = "imported-$index"
            val episode = BatchPlan.importedEpisode(projectId, name, occupied)
            assertTrue(occupied.add(episode))
            BatchItem(projectId, name, episode = episode)
        }
        val batch = BatchProject("fixture", "Movie", BatchKind.MOVIE_PARTS, items)
        assertEquals("1", items.first().episode)
        assertEquals(4, BatchPlan.groups(batch).size)
        val grouped = batch.copy(items = listOf(items[0], items[1].copy(episode = "1")) + items.drop(2))
        assertEquals(inputs.take(2), BatchPlan.groups(grouped).getValue("1").map { it.name })
        assertEquals(3, BatchPlan.groups(grouped).size)
    }
    @Test fun interruptionPreservesFinishedAndFailedItemsAndOutputs() {
        val output = BatchOutput("1", "Tập 1", "/temporary/one.mp4", 1000)
        val batch = BatchProject("test", "Series", BatchKind.SERIES,
            listOf(item("EP01").copy(state = "DONE"), item("EP02").copy(state = "FAILED", error = "network"),
                item("EP03").copy(state = "EXPORTING")), listOf(output), "RUNNING")
        val recovered = BatchPlan.interrupted(batch)
        assertEquals("PAUSED", recovered.state)
        assertEquals(listOf("DONE", "FAILED", "PENDING"), recovered.items.map { it.state })
        assertEquals("network", recovered.items[1].error)
        assertEquals(listOf(output), recovered.outputs)
    }
}
