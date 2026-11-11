package com.gemini.ocr.mtprobe

import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Mt60DecodePolicyTest {
    @Test fun pinnedDimensionsDoNotReuseTheOlderModelsHeadWidth() {
        assertEquals(2, Mt60DecodePolicy.LAYERS)
        assertEquals(8, Mt60DecodePolicy.HEADS)
        assertEquals(72, Mt60DecodePolicy.HEAD_WIDTH)
        assertEquals(Mt60DecodePolicy.HIDDEN, Mt60DecodePolicy.HEADS * Mt60DecodePolicy.HEAD_WIDTH)
    }
    @Test fun sourceRequiresOneTerminalEosWithoutSilentTruncation() {
        assertThrows(IllegalArgumentException::class.java) { plan(longArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { plan(LongArray(193) { 2 }) }
        assertThrows(IllegalArgumentException::class.java) { plan(longArrayOf(4, 5)) }
        assertThrows(IllegalArgumentException::class.java) { plan(longArrayOf(4, 2, 5, 2)) }
        assertThrows(IllegalArgumentException::class.java) { plan(longArrayOf(-1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { plan(longArrayOf(24000, 2)) }
        plan(LongArray(192) { if (it == 191) 2 else 4 })
    }
    @Test fun decoderStartsWithPadAndTheEntireAcceptedPrefixExactlyOnce() {
        val accepted = Mt60DecodePolicy.InputPlan(longArrayOf(4, 2), longArrayOf(23, 8, 9))
        assertArrayEquals(longArrayOf(0, 23, 8, 9), accepted.initialDecoderIds())
        assertArrayEquals(longArrayOf(0), plan(longArrayOf(4, 2)).initialDecoderIds())
    }
    @Test fun acceptedPrefixCannotContainSpecialOrUnknownIdsAndIsBounded() {
        for (id in listOf(-1L, 0L, 1L, 2L, 3L, 24000L)) {
            assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.InputPlan(longArrayOf(4, 2), longArrayOf(id)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.InputPlan(longArrayOf(4, 2), LongArray(193) { 4 })
        }
        Mt60DecodePolicy.InputPlan(longArrayOf(4, 2), LongArray(192) { 4 })
    }
    @Test fun contextIsOneFrozenPairAndDoesNotContainTheNewReferenceTranslation() {
        val pair = Mt60DecodePolicy.AcceptedDialoguePair("老师，我能先走吗？", "Thưa thầy, em đi trước được không?")
        assertEquals("老师，我能先走吗？\n你先走吧。", Mt60DecodePolicy.encoderText("你先走吧。", pair))
        assertEquals("你先走吧。", Mt60DecodePolicy.encoderText("你先走吧。", null))
        assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.AcceptedDialoguePair("旧句", "") }
        assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.encoderText("", null) }
    }
    @Test fun emptyOrCapReachedSuffixCannotBecomeACompletedTranslation() {
        for (output in listOf(emptyList(), listOf(2L), List(128) { 5L }, List(129) { if (it == 128) 2L else 5L }, listOf(5L, 2L, 2L))) {
            assertThrows(Mt60DecodePolicy.InvalidModelOutput::class.java) { Mt60DecodePolicy.suffixForDecode(output) }
        }
        assertArrayEquals(longArrayOf(10, 11, 2), Mt60DecodePolicy.suffixForDecode(listOf(10, 11, 2)))
        assertEquals(128, Mt60DecodePolicy.suffixForDecode(List(128) { if (it == 127) 2L else 5L }).size)
    }
    @Test fun generatedSpecialOrUnknownTokenFailsClosedInsteadOfBeingSilentlyDroppedByDetokenizer() {
        for (token in listOf(-1L, 0L, 1L, 3L, 24000L)) {
            assertThrows(Mt60DecodePolicy.InvalidModelOutput::class.java) { Mt60DecodePolicy.generatedToken(token) }
        }
        Mt60DecodePolicy.generatedToken(2)
        Mt60DecodePolicy.generatedToken(23999)
    }
    @Test fun cachedEmptyCrossOutputsPreserveTheInitialEncoderKeys() {
        val real = List(4) { longArrayOf(1, 8, 17, 72) }
        assertTrue(Mt60DecodePolicy.replacesCrossCache(real, 17, initial = true))
        assertFalse(Mt60DecodePolicy.replacesCrossCache(List(4) { longArrayOf(0, 8, 1, 72) }, 17, initial = false))
        assertFalse(Mt60DecodePolicy.replacesCrossCache(List(4) { longArrayOf(1, 8, 0, 72) }, 17, initial = false))
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.replacesCrossCache(List(4) { longArrayOf(0, 8, 1, 72) }, 17, initial = true)
        }
    }
    @Test fun incompatibleOrPartiallyEmptyCrossCacheCannotChangeOwners() {
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.replacesCrossCache(List(4) { longArrayOf(1, 8, 17, 64) }, 17, initial = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.replacesCrossCache(listOf(longArrayOf(1, 8, 17, 72)) + List(3) { longArrayOf(0, 8, 1, 72) }, 17, initial = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.replacesCrossCache(List(4) { longArrayOf(1, -1, 17, 72) }, 17, initial = false)
        }
    }
    @Test fun decoderCacheSequenceMustIncludePrefixAndEveryProcessedInputToken() {
        Mt60DecodePolicy.decoderCacheShape(longArrayOf(1, 8, 7, 72), 7)
        assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.decoderCacheShape(longArrayOf(1, 8, 6, 72), 7) }
        assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.decoderCacheShape(longArrayOf(1, 8, 7, 64), 7) }
    }
    @Test fun greedySelectionUsesLastLogitsRowAndNumpyFirstIndexTiePolicy() {
        val values = FloatArray(2 * 24000) { -2f }
        values[19] = 100f
        values[24000 + 22] = 3f; values[24000 + 27] = 3f
        assertEquals(22L, Mt60DecodePolicy.greedyToken(FloatBuffer.wrap(values), longArrayOf(1, 2, 24000), 2))
    }
    @Test fun malformedNonfiniteAndOutOfVocabularyLogitsAreRejected() {
        val values = FloatArray(24000) { -2f }.also { it[8] = 3f }
        assertThrows(IllegalArgumentException::class.java) {
            Mt60DecodePolicy.greedyToken(FloatBuffer.wrap(values), longArrayOf(1, 1, 23999), 1)
        }
        values[12] = Float.NaN
        assertThrows(Mt60DecodePolicy.InvalidModelOutput::class.java) {
            Mt60DecodePolicy.greedyToken(FloatBuffer.wrap(values), longArrayOf(1, 1, 24000), 1)
        }
        assertThrows(IllegalArgumentException::class.java) { Mt60DecodePolicy.elementCount(longArrayOf(Long.MAX_VALUE, 2)) }
    }
    private fun plan(ids: LongArray) = Mt60DecodePolicy.InputPlan(ids, longArrayOf())
}
