package com.gemini.ocr.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringWriter
import java.io.Writer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryImportConverterTest {
    private fun convert(format: MemoryImportConverter.Format, text: String): Pair<MemoryImportConverter.ConversionSummary, List<JSONObject>> {
        val output = StringWriter()
        val summary = MemoryImportConverter.convert(format, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), output)
        return summary to output.toString().lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Malformed input must be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun csvHandlesQuotedCommasNewlinesAndNeverApprovesItself() {
        val (_, rows) = convert(MemoryImportConverter.Format.CSV,
            "source,translation,quality,confidence\r\n\"你好，朋友\",\"Chào bạn,\nngười bạn của tôi.\",USER_APPROVED,1\r\n")
        assertEquals(1, rows.size)
        assertEquals("Chào bạn,\nngười bạn của tôi.", rows.single().getString("translation"))
        assertEquals("DRAFT", rows.single().getString("quality"))
    }
    @Test fun csvRejectsUnterminatedQuotesAndDuplicateHeaders() {
        rejects { convert(MemoryImportConverter.Format.CSV, "source,translation\n\"你好,Chào bạn") }
        rejects { convert(MemoryImportConverter.Format.CSV, "source,source,translation\n你好,你好,Chào bạn") }
    }
    @Test fun jsonlRejectsDuplicateKeysUnknownFieldsAndNonJsonExtensions() {
        val (summary, rows) = convert(MemoryImportConverter.Format.JSONL, """
            {"source":"你好","source":"再见","translation":"Chào bạn"}
            {'source':'你好','translation':'Chào bạn'}
            {"source":"你好","translation":"Chào bạn","rights_confirmed":true}
            {"source":"你好","translation":"Chào bạn","quality":"USER_APPROVED","confidence":0.99}
        """.trimIndent())
        assertEquals(1L, summary.accepted)
        assertEquals(3L, summary.rejected)
        assertEquals("DRAFT", rows.single().getString("quality"))
    }
    @Test fun srtAcceptsOnlyBilingualPairsInTheSameValidCue() {
        val (summary, rows) = convert(MemoryImportConverter.Format.SRT, """
            1
            00:00:01,000 --> 00:00:02,000
            <b>你好</b>
            Chào bạn.

            2
            00:00:03,000 --> 00:00:04,000
            再见

            3
            00:00:05,000 --> 00:00:06,000
            Tạm biệt.
        """.trimIndent())
        assertEquals(1L, summary.accepted)
        assertEquals(2L, summary.rejected)
        assertEquals("你好", rows.single().getString("source"))
    }
    @Test fun vttIgnoresNotesAndRejectsReversedTimes() {
        val (summary, rows) = convert(MemoryImportConverter.Format.VTT, """
            WEBVTT

            NOTE Reference fixture created for tests

            id-a
            00:01.000 --> 00:02.000 align:center
            你好
            Chào bạn.

            00:05.000 --> 00:04.000
            再见
            Tạm biệt.
        """.trimIndent())
        assertEquals(1L, summary.accepted)
        assertEquals(1L, summary.rejected)
        assertEquals(1, rows.size)
    }
    @Test fun assPreservesTextCommasAndHonorsExplicitFormat() {
        val (_, rows) = convert(MemoryImportConverter.Format.ASS,
            "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:02.00,{\\b1}你好\\NChào bạn, bạn ơi.\n")
        assertEquals("Chào bạn, bạn ơi.", rows.single().getString("translation"))
        val (missing, _) = convert(MemoryImportConverter.Format.SSA,
            "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,你好\\NChào bạn\n")
        assertEquals(1L, missing.rejected)
    }
    @Test fun lrcPairsOnlyWithinOneRecordWithoutAligningNeighboringTimestamps() {
        val (summary, rows) = convert(MemoryImportConverter.Format.LRC,
            "[ti:Synthetic test]\n[00:01.00]你好|Chào bạn.\n[00:02.00]再见\n[00:02.00]Tạm biệt.\n")
        assertEquals(1L, summary.accepted)
        assertEquals(2L, summary.rejected)
        assertEquals(1, rows.size)
    }
    @Test fun tmxUsesExplicitLanguageTagsAndRejectsAmbiguousVariants() {
        val (summary, rows) = convert(MemoryImportConverter.Format.TMX, """
            <tmx version="1.4"><body>
            <tu><tuv xml:lang="vi"><seg>Chào bạn.</seg></tuv><tuv xml:lang="zh-CN"><seg>你好</seg></tuv></tu>
            <tu><tuv xml:lang="zh"><seg>你好</seg></tuv><tuv xml:lang="zh"><seg>再见</seg></tuv><tuv xml:lang="vi"><seg>Tạm biệt.</seg></tuv></tu>
            </body></tmx>
        """.trimIndent())
        assertEquals(1L, summary.accepted)
        assertEquals(1L, summary.rejected)
        assertEquals("你好", rows.single().getString("source"))
    }
    @Test fun tmxNeverResolvesDtdOrExternalEntities() {
        rejects { convert(MemoryImportConverter.Format.TMX,
            "<!DOCTYPE tmx [<!ENTITY x SYSTEM 'https://invalid.example/never-fetch'>]><tmx><body><tu><tuv xml:lang='zh'><seg>&x;</seg></tuv></tu></body></tmx>") }
        rejects { convert(MemoryImportConverter.Format.TMX, "<tmx>".repeat(34) + "</tmx>".repeat(34)) }
    }
    @Test fun provenanceAndRecordSizeAreCheckedBeforeStaging() {
        val (summary, _) = convert(MemoryImportConverter.Format.JSONL,
            """{"source":"你好","translation":"Chào bạn","source_url":"https://example.test/data?api%5Fkey=secret-placeholder","license_id":"test-owned"}""")
        assertEquals(1L, summary.rejected)
        rejects { convert(MemoryImportConverter.Format.JSONL, "x".repeat(MemoryImportConverter.MAX_LINE_CHARS + 1)) }
    }
    @Test fun converterWritesBeforeReadingTheEntireCorpus() {
        val line = "{\"source\":\"你好\",\"translation\":\"Chào bạn\"}\n".toByteArray(Charsets.UTF_8)
        val total = line.size * 10000
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int = if (consumed == total) -1 else line[consumed++ % line.size].toInt() and 255
        }
        class Stop : RuntimeException()
        var writes = 0
        val output = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) { if (++writes == 20) throw Stop() }
            override fun flush() { }
            override fun close() { }
        }
        try { MemoryImportConverter.convert(MemoryImportConverter.Format.JSONL, input, output); fail("Writer must interrupt") }
        catch (_: Stop) { assertTrue(consumed < total / 2) }
    }
}
