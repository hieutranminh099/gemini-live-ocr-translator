package com.gemini.ocr.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-memory synthetic databases only; no model, network, credential or legacy bank access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContextualTranslationMemoryTest {
    private lateinit var memory: ContextualTranslationMemory
    private var now = 1000L
    private val scope = ContextualTranslationMemory.Scope("fictional-film-a", "modern", "candidate-v1", "glossary-v1",
        ContextualTranslationMemory.Scope.emptyContextFingerprint())
    private val provenance = ContextualTranslationMemory.Provenance("user://owned-fixture", "owner-provided-test-fixture")
    private fun entry(source: String = "我知道了。", vi: String = "Tôi hiểu rồi.", confidence: Double = 1.0) =
        ContextualTranslationMemory.Entry(scope, source, vi, "fictional-dialogue", provenance, confidence)
    private fun manifest(version: String, confirmed: Boolean = true) =
        ContextualTranslationMemory.ImportManifest(version, scope, "fictional-dialogue", provenance, confirmed)
    private fun jsonl(vararg pairs: Pair<String, String>): ByteArrayInputStream = ByteArrayInputStream(
        pairs.joinToString("\n") { (source, vi) -> JSONObject().put("source", source).put("translation", vi)
            .put("confidence", 1.0).toString() }.toByteArray(Charsets.UTF_8))
    private suspend fun importReviewed(version: String, vararg pairs: Pair<String, String>) {
        memory.stageJsonl(manifest(version), jsonl(*pairs))
        memory.publishImport(version, reviewedByUser = true)
    }

    private fun numberedRows(count: Int) = (1..count).map { index ->
        JSONObject().put("source", "第${index}句。").put("translation", "Câu $index.")
            .put("confidence", 1.0).toString()
    }
    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    /** Native reads stop exactly after 64 complete records, with no store mutex held. */
    private class BatchGateStream(private val bytes: ByteArray, private val boundary: Int) : InputStream() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closed = false
        private var offset = 0
        private fun gate() {
            if (offset >= boundary && release.count > 0) {
                reached.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic stream gate timeout")
            }
        }
        override fun read(): Int {
            gate()
            return if (offset < bytes.size) bytes[offset++].toInt() and 0xff else -1
        }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            gate()
            if (offset == bytes.size) return -1
            val end = if (offset < boundary) boundary else bytes.size
            val count = minOf(length, end - offset)
            bytes.copyInto(target, start, offset, offset + count); offset += count
            return count
        }
        override fun close() { closed = true; release.countDown() }
    }

    @Before fun createOnlyANewSyntheticInMemoryStore() {
        memory = ContextualTranslationMemory(ApplicationProvider.getApplicationContext<Context>(), databaseName = null,
            hotCapacity = 3, clock = { now++ })
    }
    @After fun closeOnlyTheTestStore() = runBlocking { memory.close() }

    @Test fun rawUtf8ByteBudgetCountsBomAndNewlinesAndAcceptsAnExactBoundary() = runBlocking {
        val text = "\uFEFF" + JSONObject().put("source", "请等一下。").put("translation", "Xin chờ một chút.")
            .put("confidence", 1.0).toString() + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > text.length)
        val exact = TrackingStream(bytes)
        assertEquals(1, memory.stageJsonl(manifest("exact-byte-budget"), exact, maxBytes = bytes.size.toLong()).uniqueRows)
        assertTrue(exact.closed)
        val oversized = TrackingStream(bytes)
        try {
            memory.stageJsonl(manifest("one-byte-over"), oversized, maxBytes = bytes.size - 1L)
            fail("Raw UTF-8 budget did not include BOM/newline")
        } catch (_: IllegalArgumentException) { }
        assertTrue(oversized.closed)
        assertEquals("failed", memory.recentImports(scope).single { it.version == "one-byte-over" }.state)
        assertNull(memory.lookup(scope, "请等一下。"))
    }

    @Test fun byteOverflowAfterACommittedBatchLeavesItInvisibleAndPreservesApprovedData() = runBlocking {
        importReviewed("previous-approved", "请坐。" to "Mời ngồi.")
        memory.saveReviewed(entry(vi = "Tôi đã hiểu."))
        val rows = numberedRows(130)
        val bytes = rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val limit = rows.take(64).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).size + 3L
        val input = TrackingStream(bytes)
        try {
            memory.stageJsonl(manifest("byte-overflow-late"), input, maxBytes = limit)
            fail("Oversized import accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(input.closed)
        val failed = memory.recentImports(scope).single { it.version == "byte-overflow-late" }
        assertEquals("failed", failed.state); assertEquals(64, failed.rowCount)
        assertEquals(64, memory.previewImport("byte-overflow-late").entries.size)
        assertNull(memory.lookup(scope, "第1句。"))
        assertEquals("Mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
        assertEquals("Tôi đã hiểu.", memory.lookup(scope, "我知道了。")!!.translation)
        try { memory.publishImport("byte-overflow-late", reviewedByUser = true); fail("Failed pack published") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun discardFailedImportDeletesOnlyTheNamedFailedPack() = runBlocking {
        importReviewed("previous-approved", "请坐。" to "Mời ngồi.")
        memory.saveReviewed(entry(vi = "Tôi đã hiểu."))
        val rows = numberedRows(65)
        val bytes = rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val limit = rows.take(64).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).size.toLong()
        for (version in listOf("failed-a", "failed-b")) {
            try { memory.stageJsonl(manifest(version), TrackingStream(bytes), maxBytes = limit); fail("Expected byte limit") }
            catch (_: IllegalArgumentException) { }
        }
        memory.stageJsonl(manifest("staged-only"), jsonl("请等一下。" to "Xin chờ."))
        assertEquals(64, memory.discardFailedImport("failed-a"))
        assertTrue(memory.previewImport("failed-a").entries.isEmpty())
        assertFalse(memory.recentImports(scope).any { it.version == "failed-a" })
        assertEquals(64, memory.previewImport("failed-b").entries.size)
        assertEquals("failed", memory.recentImports(scope).single { it.version == "failed-b" }.state)
        for (protected in listOf("manual", "previous-approved", "staged-only")) {
            try { memory.discardFailedImport(protected); fail("Protected data discarded") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, memory.discardFailedImport("never-created"))
        assertEquals("Mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
        assertEquals("Tôi đã hiểu.", memory.lookup(scope, "我知道了。")!!.translation)
        assertEquals(1, memory.previewImport("staged-only").entries.size)
    }

    @Test fun rollbackCannotAcknowledgeAnImportThatIsStillStagingAfterBatch64() = runBlocking {
        val rows = numberedRows(130)
        val input = BatchGateStream(rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            rows.take(64).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).size)
        val worker = async(Dispatchers.IO) { memory.stageJsonl(manifest("gated-staging"), input) }
        try {
            assertTrue(input.reached.await(5, TimeUnit.SECONDS))
            assertEquals(64, memory.previewImport("gated-staging").entries.size)
            try { memory.rollbackImport("gated-staging"); fail("Rollback silently accepted an active import") }
            catch (_: IllegalArgumentException) { }
            assertEquals("staging", memory.recentImports(scope).single { it.version == "gated-staging" }.state)
            input.release.countDown()
            assertEquals(130, withTimeout(5000) { worker.await() }.uniqueRows)
            assertEquals("staged", memory.recentImports(scope).single { it.version == "gated-staging" }.state)
            assertTrue(memory.rollbackImport("gated-staging"))
            assertEquals("rolled_back", memory.recentImports(scope).single { it.version == "gated-staging" }.state)
            try { memory.publishImport("gated-staging", reviewedByUser = true); fail("Rolled-back pack revived") }
            catch (_: IllegalArgumentException) { }
        } finally { input.release.countDown(); worker.cancel(); worker.join() }
    }

    @Test fun cancelledStreamingImportStaysFailedAndCanBeExplicitlyDiscarded() = runBlocking {
        importReviewed("previous-approved", "请坐。" to "Mời ngồi.")
        val rows = numberedRows(130)
        val input = BatchGateStream(rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            rows.take(64).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).size)
        val worker = launch(Dispatchers.IO) { memory.stageJsonl(manifest("cancel-after-batch"), input) }
        try {
            assertTrue(input.reached.await(5, TimeUnit.SECONDS))
            assertEquals(64, memory.previewImport("cancel-after-batch").entries.size)
            worker.cancel(); input.release.countDown()
            withTimeout(5000) { worker.join() }
            assertTrue(worker.isCancelled); assertTrue(input.closed)
            assertEquals("failed", memory.recentImports(scope).single { it.version == "cancel-after-batch" }.state)
            assertNull(memory.lookup(scope, "第1句。"))
            assertEquals(64, memory.discardFailedImport("cancel-after-batch"))
            assertEquals("Mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
        } finally { input.release.countDown(); worker.cancel(); worker.join() }
    }

    @Test fun duplicateVersionFailureDoesNotMarkTheExistingPublishedPackFailedAndClosesItsStream() = runBlocking {
        importReviewed("previous-approved", "请坐。" to "Mời ngồi.")
        val input = TrackingStream(jsonl("请坐。" to "Wrong duplicate.").readBytes())
        try { memory.stageJsonl(manifest("previous-approved"), input); fail("Version reused") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(input.closed)
        assertEquals("published", memory.recentImports(scope).single().state)
        assertEquals("Mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
    }

    @Test fun alreadyCancelledCallerClosesItsInputWithoutCreatingAStagingManifest() = runBlocking {
        val input = TrackingStream(jsonl("请坐。" to "Mời ngồi.").readBytes())
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext()[Job]!!.cancel()
            memory.stageJsonl(manifest("already-cancelled"), input)
        }
        withTimeout(5000) { worker.join() }
        assertTrue(worker.isCancelled); assertTrue(input.closed)
        assertFalse(memory.recentImports(scope).any { it.version == "already-cancelled" })
    }

    @Test fun invalidByteBudgetClosesItsInputWithoutCreatingAnyManifest() = runBlocking {
        for (limit in listOf(0L, ContextualTranslationMemory.DEFAULT_MAX_IMPORT_BYTES + 1)) {
            val input = TrackingStream(jsonl("请坐。" to "Mời ngồi.").readBytes())
            try { memory.stageJsonl(manifest("invalid-byte-budget"), input, maxBytes = limit); fail("Invalid budget") }
            catch (_: IllegalArgumentException) { }
            assertTrue(input.closed)
        }
        assertTrue(memory.recentImports(scope).isEmpty())
    }

    @Test fun draftCannotPromoteItselfOrOverwriteAUserApprovedTranslation() = runBlocking {
        assertTrue(memory.recordDraft(entry().copy(quality = ContextualTranslationMemory.Quality.USER_APPROVED)))
        assertNull(memory.lookup(scope, "我知道了。"))
        assertTrue(memory.saveReviewed(entry(vi = "Em hiểu rồi, thưa thầy.")))
        assertFalse(memory.recordDraft(entry(vi = "Unreviewed replacement")))
        assertEquals("Em hiểu rồi, thưa thầy.", memory.lookup(scope, "我知道了。")!!.translation)
        assertFalse(memory.saveReviewed(entry(vi = "Lower-ranked replacement"), ContextualTranslationMemory.Quality.VERIFIED))
        assertEquals(ContextualTranslationMemory.Quality.USER_APPROVED, memory.lookup(scope, "我知道了。")!!.quality)
    }

    @Test fun filmDomainModelGlossaryAndDialogueAreAllPartOfTheExactScope() = runBlocking {
        memory.saveReviewed(entry())
        for (different in listOf(scope.copy(filmId = "fictional-film-b"), scope.copy(domain = "historical"),
            scope.copy(modelVersion = "candidate-v2"), scope.copy(glossaryVersion = "glossary-v2"),
            scope.copy(contextFingerprint = "a".repeat(64)))) {
            assertNull(memory.lookup(different, "我知道了。"))
        }
        memory.saveReviewed(entry(vi = "Cậu hiểu rồi.").copy(scope = scope.copy(contextFingerprint = "a".repeat(64))))
        assertEquals("Tôi hiểu rồi.", memory.lookup(scope, "我知道了。")!!.translation)
        assertEquals("Cậu hiểu rồi.", memory.lookup(scope.copy(contextFingerprint = "a".repeat(64)), "我知道了。")!!.translation)
    }

    @Test fun negationNumbersNamesAndSentenceBoundariesAreNotFuzzyMatched() = runBlocking {
        memory.saveReviewed(entry("我会去。", "Tôi sẽ đi."))
        memory.saveReviewed(entry("还有2小时。", "Còn 2 giờ."))
        memory.saveReviewed(entry("告诉李明。", "Báo cho Lý Minh."))
        for (different in listOf("我不会去。", "还有3小时。", "告诉王明。", "我 会去。", "我会去？")) {
            assertNull(memory.lookup(scope, different))
        }
        assertEquals("Còn 2 giờ.", memory.lookup(scope, "  还有２小时。  ")!!.translation)
    }

    @Test fun explicitRevisionInvalidatesTheHotEntryWithoutLosingItsUsageHistory() = runBlocking {
        memory.saveReviewed(entry())
        val first = memory.lookup(scope, "我知道了。")!!
        memory.lookup(scope, "我知道了。")
        memory.saveReviewed(entry(vi = "Tôi đã hiểu."))
        val updated = memory.lookup(scope, "我知道了。")!!
        assertEquals(first.id, updated.id)
        assertEquals(first.createdAt, updated.createdAt)
        assertEquals("Tôi đã hiểu.", updated.translation)
        assertEquals(3L, updated.usageCount)
        assertTrue(updated.updatedAt > first.updatedAt)
    }

    @Test fun hotMemoryIsBoundedAndEvictionDoesNotLoseIndexedRows() = runBlocking {
        for (index in 1..20) {
            memory.saveReviewed(entry("第${index}句。", "Câu $index."))
            assertNotNull(memory.lookup(scope, "第${index}句。"))
            assertTrue(memory.hotSize() <= 3)
        }
        assertEquals(3, memory.hotSize())
        assertEquals("Câu 1.", memory.lookup(scope, "第1句。")!!.translation)
        assertEquals(3, memory.hotSize())
    }

    @Test fun evenReviewedLowConfidenceOutputIsNotAutomaticallyReused() = runBlocking {
        memory.saveReviewed(entry(confidence = .7))
        assertNull(memory.lookup(scope, "我知道了。"))
        memory.saveReviewed(entry(confidence = 1.0))
        assertNotNull(memory.lookup(scope, "我知道了。"))
    }

    @Test fun correctionNeedsExactScopeTwoExplicitConfirmationsAndHighConfidence() = runBlocking {
        val raw = "我己经到了。"; val corrected = "我已经到了。"
        memory.proposeCorrection(scope, raw, corrected, .5, provenance)
        assertNull(memory.lookupCorrection(scope, raw))
        memory.confirmCorrection(scope, raw, corrected, 1.0, provenance)
        assertNull(memory.lookupCorrection(scope, raw))
        memory.confirmCorrection(scope, raw, corrected, 1.0, provenance)
        assertEquals(corrected, memory.lookupCorrection(scope, raw)!!.corrected)
        assertNull(memory.lookupCorrection(scope, "他己经到了。"))
        assertNull(memory.lookupCorrection(scope.copy(filmId = "other-film"), raw))
        assertFalse(memory.proposeCorrection(scope, raw, "我还没到。", 1.0, provenance))
        assertEquals(corrected, memory.lookupCorrection(scope, raw)!!.corrected)
    }

    @Test fun changingACorrectionTargetOrKeepingLowConfidenceRequiresReviewAgain() = runBlocking {
        repeat(2) { memory.confirmCorrection(scope, "侯时候", "候时候", .5, provenance) }
        assertNull(memory.lookupCorrection(scope, "侯时候"))
        memory.confirmCorrection(scope, "侯时候", "候时候", 1.0, provenance)
        assertNotNull(memory.lookupCorrection(scope, "侯时候"))
        memory.confirmCorrection(scope, "侯时候", "侯时侯", 1.0, provenance)
        assertNull(memory.lookupCorrection(scope, "侯时候"))
    }

    @Test fun stagedImportIsPreviewableButOnlyExplicitReviewedPublicationEnablesReuse() = runBlocking {
        val preview = memory.stageJsonl(manifest("fixture-v1"), jsonl("我知道了。" to "Tôi hiểu rồi.", "我知道了。" to "Tôi hiểu rồi."))
        assertEquals(2, preview.inputLines); assertEquals(1, preview.uniqueRows); assertEquals(1, preview.duplicateRows)
        assertEquals(ContextualTranslationMemory.Quality.DRAFT, memory.previewImport("fixture-v1").entries.single().quality)
        assertNull(memory.lookup(scope, "我知道了。"))
        memory.publishImport("fixture-v1") // Publishing alone is not a quality certification.
        assertNull(memory.lookup(scope, "我知道了。"))
        importReviewed("fixture-v2", "我知道了。" to "Tôi đã hiểu.")
        assertEquals("Tôi đã hiểu.", memory.lookup(scope, "我知道了。")!!.translation)
    }

    @Test fun importFileCannotGrantRightsApprovalOrAScopeForAnotherFilm() = runBlocking {
        val spoof = JSONObject().put("source", "我知道了。").put("translation", "Tôi hiểu rồi.")
            .put("confidence", 1.0).put("quality", "USER_APPROVED").toString()
        memory.stageJsonl(manifest("unreviewed-file"), ByteArrayInputStream(spoof.toByteArray()))
        memory.publishImport("unreviewed-file")
        assertNull(memory.lookup(scope, "我知道了。"))
        memory.stageJsonl(manifest("rights-not-confirmed", false), jsonl("请坐。" to "Mời ngồi."))
        try { memory.publishImport("rights-not-confirmed", reviewedByUser = true); fail("Missing rights accepted") }
        catch (_: IllegalArgumentException) { }
        assertNull(memory.lookup(scope, "请坐。"))
        val wrongScope = JSONObject(spoof).put("scope", JSONObject(scope.copy(filmId = "another-film").serialized())).toString()
        try { memory.stageJsonl(manifest("wrong-scope"), ByteArrayInputStream(wrongScope.toByteArray())); fail("Wrong scope accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun conflictingDuplicateTranslationsRejectThePackInsteadOfSilentlyChoosingTheFirst() = runBlocking {
        try {
            memory.stageJsonl(manifest("conflicting"), jsonl("我知道了。" to "Em hiểu rồi.", "我知道了。" to "Tôi hiểu rồi."))
            fail("Conflicting translations accepted")
        } catch (_: IllegalArgumentException) { }
        assertNull(memory.lookup(scope, "我知道了。"))
        try { memory.publishImport("conflicting", reviewedByUser = true); fail("Conflicting pack published") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rollbackRevealsEarlierPackAndKeepsManualApprovalIndependent() = runBlocking {
        importReviewed("pack-v1", "请坐。" to "Mời ngồi.")
        importReviewed("pack-v2", "请坐。" to "Xin mời ngồi.")
        assertEquals("Xin mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
        assertTrue(memory.rollbackImport("pack-v2"))
        assertEquals("Mời ngồi.", memory.lookup(scope, "请坐。")!!.translation)
        memory.saveReviewed(entry("请坐。", "Ngồi đi, anh."))
        assertTrue(memory.rollbackImport("pack-v1"))
        assertEquals("Ngồi đi, anh.", memory.lookup(scope, "请坐。")!!.translation)
        assertFalse(memory.rollbackImport("never-created"))
    }

    @Test fun invalidLateRecordLeavesAllStagedBatchesInvisible() = runBlocking {
        val rows = (1..130).joinToString("\n") { index -> JSONObject().put("source", "第${index}句。")
            .put("translation", "Câu $index.").put("confidence", 1.0).toString() } + "\n{broken"
        try { memory.stageJsonl(manifest("late-invalid"), ByteArrayInputStream(rows.toByteArray())); fail("Invalid input published") }
        catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("broken")) }
        assertNull(memory.lookup(scope, "第1句。"))
        try { memory.publishImport("late-invalid", reviewedByUser = true); fail("Failed pack published") }
        catch (_: IllegalArgumentException) { }
        assertEquals(10, memory.previewImport("late-invalid", limit = 10).entries.size)
    }

    @Test fun boundedStreamingImportAndExportDoNotFillTheHotLruOrReturnWholeCorpora() = runBlocking {
        val rows = (1..260).joinToString("\n") { index -> JSONObject().put("source", "第${index}句。")
            .put("translation", "Câu $index.").put("confidence", 1.0).toString() }
        var largestReadRequest = 0
        val stream = object : ByteArrayInputStream(rows.toByteArray(Charsets.UTF_8)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestReadRequest = maxOf(largestReadRequest, length)
                return super.read(buffer, offset, length)
            }
        }
        val preview = memory.stageJsonl(manifest("streamed-pack"), stream)
        assertEquals(260, preview.uniqueRows)
        assertTrue(largestReadRequest <= 8192)
        assertEquals(0, memory.hotSize())
        memory.publishImport("streamed-pack", reviewedByUser = true)
        val page = memory.exportPage(scope, limit = 13)
        assertEquals(13, page.entries.size); assertNotNull(page.nextId)
        val output = StringWriter()
        assertEquals(260, memory.exportJsonl(scope, output, pageSize = 13))
        assertEquals(260, output.toString().lineSequence().count { it.isNotBlank() })
        assertEquals(0, memory.hotSize())
    }

    @Test fun exportKeepsTheEffectiveReviewedVersionInsteadOfAnOlderPackDuplicate() = runBlocking {
        importReviewed("older", "请坐。" to "Old pack text.")
        importReviewed("newer", "请坐。" to "New pack text.")
        memory.saveReviewed(entry("请坐。", "User-reviewed text."))
        val output = StringWriter()
        assertEquals(1, memory.exportJsonl(scope, output))
        assertEquals("User-reviewed text.", JSONObject(output.toString().trim()).getString("translation"))
    }

    @Test fun jsonlRoundTripRetainsValidatedPerRecordProvenanceAndNeverTrustsFileApproval() = runBlocking {
        val originalProvenance = ContextualTranslationMemory.Provenance("https://example.invalid/owned-fixture", "CC0-1.0")
        memory.saveReviewed(entry().copy(provenance = originalProvenance, topic = "reviewed-example"))
        val output = StringWriter()
        memory.exportJsonl(scope, output)
        memory.stageJsonl(manifest("round-trip"), ByteArrayInputStream(output.toString().toByteArray(Charsets.UTF_8)))
        val preview = memory.previewImport("round-trip").entries.single()
        assertEquals(originalProvenance, preview.provenance)
        assertEquals("reviewed-example", preview.topic)
        assertEquals(ContextualTranslationMemory.Quality.DRAFT, preview.quality)
        val invalid = JSONObject(output.toString().trim()).put("source_url", "https://example.invalid/?api%5Fkey=private")
        try {
            memory.stageJsonl(manifest("invalid-record-provenance"), ByteArrayInputStream(invalid.toString().toByteArray()))
            fail("Credential-like per-record provenance accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun oversizedMalformedOrCredentialLikeMetadataNeverBecomesAReusableRecord() = runBlocking {
        for (action in listOf(
            { ContextualTranslationMemory.Provenance("https://example.invalid/source?api_key=private", "CC0-1.0") },
            { ContextualTranslationMemory.Provenance("https:missing-host", "CC0-1.0") },
            { ContextualTranslationMemory.Provenance("https://user:password@example.invalid/source", "CC0-1.0") },
            { ContextualTranslationMemory.Provenance("https://example.invalid/source", "") },
            { scope.copy(contextFingerprint = "") }, { entry(confidence = Double.NaN) })) {
            try { action(); fail("Invalid metadata accepted") } catch (_: IllegalArgumentException) { }
        }
        val huge = "x".repeat(32769)
        try { memory.stageJsonl(manifest("oversized"), ByteArrayInputStream(huge.toByteArray())); fail("Oversized line accepted") }
        catch (_: IllegalArgumentException) { }
        val wrongType = JSONObject().put("source", 7).put("translation", "Seven").put("confidence", "1.0").toString()
        try { memory.stageJsonl(manifest("wrong-types"), ByteArrayInputStream(wrongType.toByteArray())); fail("Coercion accepted") }
        catch (_: IllegalArgumentException) { }
        assertNull(memory.lookup(scope, "7"))
    }
}
