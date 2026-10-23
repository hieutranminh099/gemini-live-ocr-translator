package com.gemini.ocr.capture

import android.graphics.Rect
import com.gemini.ocr.ocr.OcrBlock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceRowTrackerTest {
    private val selected = Rect(100, 400, 1500, 800)
    private fun row(text: String = "你好", top: Int = 500, bottom: Int = 530) =
        OcrBlock("source", text, Rect(400, top, 1100, bottom))

    @Test fun strictCjkSourceDoesNotTranslateVietnameseOrPinyinAfterChineseDisappears() {
        val otherRows = listOf(row("Nǐ hǎo"), row("Xin chào", 560, 590))
        assertTrue(SourceRowTracker.selectSourceRows(otherRows, "zh", sourceOnly = true).isEmpty())
        assertTrue(SourceRowTracker.selectSourceRows(otherRows, "ja", sourceOnly = true).isEmpty())
        assertTrue(SourceRowTracker.selectSourceRows(otherRows, "ko", sourceOnly = true).isEmpty())
        assertEquals(otherRows, SourceRowTracker.selectSourceRows(otherRows, "zh"))
        assertEquals(otherRows, SourceRowTracker.selectSourceRows(otherRows, "en", sourceOnly = true))
    }

    @Test fun sameSourceRowKeepsNamesGapsAndSupplementaryHan() {
        val source = listOf(
            OcrBlock("han", "你好", Rect(110, 10, 200, 40)),
            OcrBlock("name", "Alice", Rect(210, 11, 300, 41)),
            OcrBlock("gap", "___", Rect(310, 10, 400, 40)),
            OcrBlock("vi", "Xin chào Alice", Rect(110, 80, 500, 110)),
        )
        assertEquals(listOf("han", "name", "gap"), SourceRowTracker.selectSourceRows(source, "zh", true).map { it.id })
        // U+20000 is a Han character represented by two UTF-16 code units.
        assertEquals(1, SourceRowTracker.selectSourceRows(listOf(row("𠀀")), "zh", true).size)
    }

    @Test fun requiresTwoStableObservationsAndKeepsSelectedWidth() {
        val tracker = SourceRowTracker()
        assertEquals(selected, tracker.captureRegion(selected, 0))
        tracker.observe(listOf(row()), selected)
        assertEquals(selected, tracker.captureRegion(selected, 100))
        tracker.observe(listOf(row(top = 502, bottom = 532)), selected)
        val refined = tracker.captureRegion(selected, 200)
        assertEquals(selected.left, refined.left); assertEquals(selected.right, refined.right)
        assertTrue(refined.top <= 480); assertTrue(refined.bottom >= 551)
        assertTrue(refined.height() < selected.height())
    }

    @Test fun missingSourceRestoresFullRegionImmediately() {
        val tracker = SourceRowTracker()
        tracker.captureRegion(selected, 0)
        repeat(2) { tracker.observe(listOf(row()), selected) }
        assertNotEquals(selected, tracker.captureRegion(selected, 100))
        tracker.observe(emptyList(), selected)
        assertEquals(selected, tracker.captureRegion(selected, 101))
    }

    @Test fun fullRegionIsRescannedAtMostEvery750Milliseconds() {
        val tracker = SourceRowTracker()
        tracker.captureRegion(selected, 0)
        repeat(2) { tracker.observe(listOf(row()), selected) }
        assertNotEquals(selected, tracker.captureRegion(selected, 749))
        assertEquals(selected, tracker.captureRegion(selected, 750))
        // Periodic scans do not permanently disable refinement.
        assertNotEquals(selected, tracker.captureRegion(selected, 751))
    }

    @Test fun movementReleasesOldBandUntilTwoNewObservations() {
        val tracker = SourceRowTracker()
        tracker.captureRegion(selected, 0)
        repeat(2) { tracker.observe(listOf(row()), selected) }
        tracker.observe(listOf(row(top = 650, bottom = 680)), selected)
        assertEquals(selected, tracker.captureRegion(selected, 100))
        tracker.observe(listOf(row(top = 651, bottom = 681)), selected)
        val newBand = tracker.captureRegion(selected, 200)
        assertTrue(newBand.top > 600); assertTrue(newBand.bottom < selected.bottom)
    }

    @Test fun multipleSubtitleLinesAreRetainedButAmbiguousDistantRowsDoNotLock() {
        val tracker = SourceRowTracker()
        tracker.captureRegion(selected, 0)
        val twoLines = listOf(row(), row(top = 540, bottom = 570))
        repeat(2) { tracker.observe(twoLines, selected) }
        val twoLineBand = tracker.captureRegion(selected, 100)
        assertTrue(twoLineBand.top < 500); assertTrue(twoLineBand.bottom > 570)
        val ambiguous = listOf(row(top = 405, bottom = 435), row(top = 740, bottom = 770))
        repeat(2) { tracker.observe(ambiguous, selected) }
        assertEquals(selected, tracker.captureRegion(selected, 200))
    }

    @Test fun cropRelativeCoordinatesCannotMoveTrackerOutsideSelection() {
        val tracker = SourceRowTracker()
        repeat(2) { tracker.observe(listOf(OcrBlock("wrong", "你好", Rect(0, 10, 300, 40))), selected) }
        assertEquals(selected, tracker.captureRegion(selected, 100))
        repeat(2) { tracker.observe(listOf(row(top = 401, bottom = 431)), selected) }
        val atEdge = tracker.captureRegion(selected, 101)
        assertEquals(selected.top, atEdge.top)
        assertEquals(selected.left, atEdge.left); assertEquals(selected.right, atEdge.right)
    }

    @Test fun regionChangeResetAndReturnedBoundsCannotMutateTracker() {
        val tracker = SourceRowTracker()
        tracker.captureRegion(selected, 0)
        repeat(2) { tracker.observe(listOf(row()), selected) }
        val copy = tracker.captureRegion(selected, 100)
        copy.offset(3000, 3000)
        assertTrue(selected.contains(tracker.captureRegion(selected, 200)))
        val changed = Rect(200, 300, 1300, 700)
        assertEquals(changed, tracker.captureRegion(changed, 201))
        tracker.reset()
        assertEquals(selected, tracker.captureRegion(selected, 202))
    }
}
