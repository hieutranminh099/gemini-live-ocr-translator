package com.gemini.ocr.capture

import org.junit.Assert.*
import org.junit.Test

class StableTextTest {
    @Test fun dialogueBecomesStableWhileHudChangesEveryFrame() {
        val state = StableText()
        assertTrue(state.observe(listOf("Time 01", "Welcome, traveler!")).isEmpty())
        assertEquals(setOf("Welcome, traveler!"), state.observe(listOf("Time 02", "Welcome, traveler!")))
        assertEquals(setOf("Welcome, traveler!"), state.observe(listOf("Time 03", "Welcome, traveler!")))
    }
    @Test fun disappearingDialogueCannotReusePreviousStability() {
        val state = StableText()
        state.observe(listOf("A")); state.observe(listOf("A"))
        assertTrue(state.observe(emptyList()).isEmpty())
        assertTrue(state.observe(listOf("A")).isEmpty())
        assertEquals(setOf("A"), state.observe(listOf("A")))
    }
    @Test fun duplicateTextInOneFrameDoesNotCountAsTwoObservations() {
        val state = StableText()
        assertTrue(state.observe(listOf("A", "A")).isEmpty())
        assertEquals(setOf("A"), state.observe(listOf("A", "A")))
    }
    @Test fun rotationAndPrivacyPauseRequireFreshSamples() {
        val state = StableText()
        state.observe(listOf("A")); state.observe(listOf("A")); state.reset()
        assertTrue(state.observe(listOf("A")).isEmpty())
    }
    @Test fun separatorsInsideTextCannotCollide() {
        assertNotEquals(StableText.signature(listOf("a|b", "c")), StableText.signature(listOf("a", "b|c")))
        assertNotEquals(StableText.signature(listOf("12:", "x")), StableText.signature(listOf("1", "2:x")))
    }
    @Test fun localCacheEvictsLeastRecentlyUsedAndCanBeInvalidated() {
        val memory = TranslationMemory(2)
        memory.put("A", "Một"); memory.put("B", "Hai")
        assertEquals("Một", memory["A"])
        memory.put("C", "Ba")
        assertNull(memory["B"]); assertEquals(2, memory.size)
        memory.clear(); assertNull(memory["A"]); assertEquals(0, memory.size)
    }
}
