package com.gemini.ocr.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrLineDiagnosticsTest {
    @Test fun rawDetectionIsDistinguishedFromConfidenceAndOtherFiltering() {
        val empty = OcrLineDiagnostics(0, 0, 0, 0, 0, 0, 0, 0).fields().toMap()
        val lowConfidence = OcrLineDiagnostics(1, 0, 1, 0, 0, 0, 0, 0).fields().toMap()
        assertEquals(0, empty["detected_lines"])
        assertEquals(1, lowConfidence["detected_lines"])
        assertEquals(1, lowConfidence["confidence_rejected"])
        assertEquals(0, lowConfidence["returned_lines"])
        assertTrue(lowConfidence.values.all { it is Int })
    }

    @Test fun lineCapIsSeparateFromRecognizerOrFilteringLoss() {
        val fields = OcrLineDiagnostics(45, 0, 0, 0, 0, 0, 45, 40).fields().toMap()
        assertEquals(5, fields["line_cap_dropped"])
        assertEquals(45, fields["eligible_lines"])
    }

    @Test fun inconsistentOrNegativeCountsCannotMasqueradeAsAnEmptyRecognizerResult() {
        for (invalid in listOf({ OcrLineDiagnostics(1, 0, 0, 0, 0, 0, 0, 0) },
            { OcrLineDiagnostics(0, -1, 0, 0, 0, 0, 1, 0) },
            { OcrLineDiagnostics(1, 0, 0, 0, 0, 0, 1, 2) })) {
            try { invalid(); fail("Invalid counts accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
