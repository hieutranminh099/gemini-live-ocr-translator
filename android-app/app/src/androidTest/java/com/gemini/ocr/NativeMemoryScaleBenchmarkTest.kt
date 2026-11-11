package com.gemini.ocr

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.network.ContextualTranslationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil

/**
 * Opt-in synthetic storage measurement, never a translation/quality/YouTube latency result.
 * Only a newly generated disposable database in the separate verification APK is opened.
 * Rows stream into the production store's 64-row import batches; no whole corpus is retained.
 */
@RunWith(AndroidJUnit4::class)
class NativeMemoryScaleBenchmarkTest {
    private data class Limits(
        val scales: List<Int>, val scaleMs: Long, val totalMs: Long,
        val databaseBytes: Long, val javaHeapBytes: Long,
        val coldSamples: Int, val hotSamples: Int, val missSamples: Int,
    )
    private class ResourceLimit(val reason: String) : IOException(reason)
    private val provenance = ContextualTranslationMemory.Provenance(
        "user://codex-synthetic-memory-scale", "owner-generated-test-fixture")

    private fun source(index: Int) = "测试字幕编号${index.toString().padStart(8, '0')}，请等待下一句。"
    private fun translation(index: Int) = "Phụ đề thử số ${index.toString().padStart(8, '0')}: xin chờ câu tiếp theo."
    private fun scope() = ContextualTranslationMemory.Scope("synthetic-scale-only", "test-fixture",
        "fixture-no-model-v1", "fixture-empty-glossary-v1",
        ContextualTranslationMemory.Scope.emptyContextFingerprint())
    private fun elapsedMs(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
    private fun databaseBytes(context: Context, name: String): Long {
        val path = context.getDatabasePath(name)
        return listOf(path, File(path.path + "-wal"), File(path.path + "-shm")).sumOf { it.length() }
    }
    private fun memorySample(): JSONObject {
        val runtime = Runtime.getRuntime()
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject().put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("java_committed_bytes", runtime.totalMemory()).put("java_max_bytes", runtime.maxMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("process_pss_bytes", memory.totalPss.toLong() * 1024)
    }
    private fun percentiles(nanos: List<Long>): JSONObject {
        require(nanos.isNotEmpty())
        val sorted = nanos.sorted()
        fun percentile(percent: Int) = sorted[(ceil(sorted.size * percent / 100.0).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)] / 1_000_000.0
        return JSONObject().put("count", sorted.size).put("method", "nearest_rank")
            .put("p50_ms", percentile(50)).put("p90_ms", percentile(90))
            .put("p95_ms", percentile(95)).put("p99_ms", percentile(99))
            .put("max_ms", sorted.last() / 1_000_000.0)
    }

    private fun disposableName(label: String) = "codex_memory_scale_${label}_${UUID.randomUUID().toString().replace("-", "")}.db"
    private fun deleteOwnDatabase(context: Context, name: String) {
        require(name.matches(Regex("codex_memory_scale_[a-z0-9]+_[a-f0-9]{32}\\.db")))
        val path = context.getDatabasePath(name)
        require(path.name == name)
        context.deleteDatabase(name)
        check(listOf(path, File(path.path + "-wal"), File(path.path + "-shm")).none { it.exists() }) {
            "Disposable test database cleanup failed"
        }
    }

    /** Generates at most one encoded row at a time, including for the optional million rows. */
    private inner class FixtureStream(private val rows: Int, private val guard: () -> Unit) : InputStream() {
        private var next = 0
        private var offset = 0
        private var current = ByteArray(0)
        private var closed = false
        private fun nextRow(): Boolean {
            check(!closed)
            if (offset < current.size) return true
            if (next >= rows) return false
            if (next % 64 == 0) guard()
            current = (JSONObject().put("source", source(next)).put("translation", translation(next))
                .put("confidence", 1.0).toString() + "\n").toByteArray(Charsets.UTF_8)
            next++; offset = 0
            return true
        }
        override fun read(): Int = if (nextRow()) current[offset++].toInt() and 0xff else -1
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            if (length == 0) return 0
            var copied = 0
            while (copied < length && nextRow()) {
                val count = minOf(length - copied, current.size - offset)
                current.copyInto(bytes, start + copied, offset, offset + count)
                offset += count; copied += count
            }
            return if (copied == 0) -1 else copied
        }
        override fun close() { closed = true; current = ByteArray(0) }
    }

    @Test fun optInSyntheticScalesMeasureIndexedAnd128EntryHotLookup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMemoryScale") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value) { "Stop capture before the memory-only benchmark" }
        val scales = (args.getString("memoryScales") ?: "1000,10000,100000").split(',').map {
            it.toIntOrNull() ?: throw IllegalArgumentException("Invalid memory scale")
        }.also { values ->
            require(values.isNotEmpty() && values.size <= 4 && values.distinct().size == values.size)
            require(values.all { it in setOf(1000, 10000, 100000, 1000000) })
            require(1000000 !in values || args.getString("allowMillionRows") == "true")
        }
        fun boundedLong(name: String, default: Long, range: LongRange) =
            (args.getString(name)?.toLongOrNull() ?: default).also { require(it in range) }
        val limits = Limits(scales,
            boundedLong("memoryScaleMaxMs", 120000, 1000L..1200000L),
            boundedLong("memoryTotalMaxMs", 300000, 1000L..1800000L),
            boundedLong("memoryMaxDatabaseMiB", 256, 16L..1024L) * 1024 * 1024,
            boundedLong("memoryMaxJavaHeapMiB", 256, 32L..1024L) * 1024 * 1024,
            boundedLong("memoryColdSamples", 256, 16L..1024L).toInt(),
            boundedLong("memoryHotSamples", 512, 128L..4096L).toInt(),
            boundedLong("memoryMissSamples", 128, 16L..1024L).toInt())
        val stem = (args.getString("reportStem") ?: "memory-scale-${SystemClock.elapsedRealtime()}").also {
            require(it.matches(Regex("[a-z0-9-]{1,64}")))
        }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "verification").apply { mkdirs() }
        val reportFile = File(directory, "$stem.json")
        check(!reportFile.exists()) { "Choose a new reportStem to preserve previous results" }
        val results = JSONArray()
        val report = JSONObject().put("schema", 1)
            .put("benchmark", "synthetic_scoped_memory_microbenchmark")
            .put("synthetic_only", true).put("api_requests", 0).put("models_used", false)
            .put("fresh_translation_e2e_measured", false).put("translation_quality_measured", false)
            .put("fuzzy_lookup", "unsupported_not_run").put("hot_capacity", 128)
            .put("cold_definition", "same-process reopened store, empty app LRU; SQLite/OS page cache uncontrolled")
            .put("startup_definition", "constructor and first lookup after same-process reopen; not app startup")
            .put("lookup_includes", "store mutex, indexed lookup or app LRU, SQLite usage-counter write; invoked on IO")
            .put("resource_limits", JSONObject().put("per_scale_ms", limits.scaleMs)
                .put("total_ms", limits.totalMs).put("database_bytes", limits.databaseBytes)
                .put("java_heap_bytes", limits.javaHeapBytes).put("checks", "between phases/queries and each 64 generated rows"))
            .put("sdk", Build.VERSION.SDK_INT).put("scales", JSONArray(scales))
            .put("results", results).put("completed", false)
        var failure: Throwable? = null
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            withTimeout(limits.totalMs) {
                withContext(Dispatchers.IO) {
                    for (rows in limits.scales) {
                        val rowReport = JSONObject().put("rows_requested", rows).put("completed", false)
                        results.put(rowReport)
                        benchmarkScale(context, rows, limits, rowReport)
                    }
                }
            }
            report.put("completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("error_type", error.javaClass.simpleName)
                .put("abort_reason", if (error is ResourceLimit) error.reason else if (error is kotlinx.coroutines.TimeoutCancellationException)
                    "total_duration_cap" else "correctness_or_runtime_failure")
        } finally {
            report.put("elapsed_ms", elapsedMs(started))
            withContext(NonCancellable + Dispatchers.IO) { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        }
        failure?.let { throw it }
        assertEquals(limits.scales.size, results.length())
    }

    private suspend fun benchmarkScale(context: Context, rows: Int, limits: Limits, output: JSONObject) {
        val databaseName = disposableName("r$rows")
        check(!context.getDatabasePath(databaseName).exists())
        var store: ContextualTranslationMemory? = null
        val activeScope = scope()
        var peakJava = 0L
        val samples = JSONArray()
        val start = SystemClock.elapsedRealtime()
        fun guard() {
            if (SystemClock.elapsedRealtime() - start > limits.scaleMs) throw ResourceLimit("per_scale_duration_cap")
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            peakJava = maxOf(peakJava, used)
            if (used > limits.javaHeapBytes) throw ResourceLimit("java_heap_cap")
            if (databaseBytes(context, databaseName) > limits.databaseBytes) throw ResourceLimit("database_size_cap")
        }
        fun sample(phase: String) {
            guard()
            val value = memorySample().put("phase", phase)
            peakJava = maxOf(peakJava, value.getLong("java_used_bytes"))
            samples.put(value)
        }
        output.put("ram_phase_samples", samples)
        try {
            sample("before_constructor")
            val constructorAt = SystemClock.elapsedRealtimeNanos()
            val writer = ContextualTranslationMemory(context, databaseName, hotCapacity = 128)
            store = writer
            output.put("constructor_ms", elapsedMs(constructorAt))
            val importedAt = SystemClock.elapsedRealtimeNanos()
            val imported = writer.stageJsonl(ContextualTranslationMemory.ImportManifest("synthetic-v1", activeScope,
                "synthetic-correctness-fixture", provenance, rightsConfirmedByUser = true), FixtureStream(rows, ::guard), rows)
            output.put("stage_import_ms", elapsedMs(importedAt)).put("rows_staged", imported.uniqueRows)
                .put("input_lines", imported.inputLines).put("duplicate_rows", imported.duplicateRows)
            assertEquals(rows, imported.uniqueRows)
            assertEquals(0, imported.duplicateRows)
            // Synthetic fixture decisions are explicit test inputs, never inferred from JSONL fields.
            assertNull(writer.lookup(activeScope, source(0)))
            val publishAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(rows, writer.publishImport("synthetic-v1", reviewedByUser = true, rightsConfirmedByUser = true))
            output.put("publish_ms", elapsedMs(publishAt)); sample("after_import_and_publication")
            writer.close(); store = null
            guard()
            val reopenAt = SystemClock.elapsedRealtimeNanos()
            val reader = ContextualTranslationMemory(context, databaseName, hotCapacity = 128)
            store = reader
            output.put("reopen_constructor_ms", elapsedMs(reopenAt))
            val firstAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(translation(rows - 1), reader.lookup(activeScope, source(rows - 1))?.translation)
            output.put("first_lookup_open_ms", elapsedMs(firstAt))
            val cold = ArrayList<Long>(limits.coldSamples)
            val coldCount = minOf(limits.coldSamples, rows - 1)
            var hitMisses = 0; var wrongTranslations = 0
            for (query in 0 until coldCount) {
                guard()
                // Distinct IDs exclude the first open query, so no app-LRU hit can enter this set.
                val index = (query.toLong() * (rows - 2) / (coldCount - 1)).toInt()
                val at = SystemClock.elapsedRealtimeNanos()
                val hit = reader.lookup(activeScope, source(index))
                cold += SystemClock.elapsedRealtimeNanos() - at
                if (hit == null) hitMisses++ else if (hit.translation != translation(index)) wrongTranslations++
            }
            output.put("indexed_cold_lookup", percentiles(cold)); sample("after_indexed_cold_queries")
            for (slot in 0 until 128) {
                guard()
                assertEquals(translation(rows - 1 - slot), reader.lookup(activeScope, source(rows - 1 - slot))?.translation)
            }
            assertEquals(128, reader.hotSize())
            val hot = ArrayList<Long>(limits.hotSamples)
            for (query in 0 until limits.hotSamples) {
                guard()
                val index = rows - 1 - query % 128
                val at = SystemClock.elapsedRealtimeNanos()
                val hit = reader.lookup(activeScope, source(index))
                hot += SystemClock.elapsedRealtimeNanos() - at
                if (hit == null) hitMisses++ else if (hit.translation != translation(index)) wrongTranslations++
            }
            output.put("128_entry_hot_lookup", percentiles(hot)); sample("after_hot_queries")
            val misses = ArrayList<Long>(limits.missSamples)
            var falseHits = 0
            for (query in 0 until limits.missSamples) {
                guard()
                val at = SystemClock.elapsedRealtimeNanos()
                if (reader.lookup(activeScope, source(rows + 100 + query)) != null) falseHits++
                misses += SystemClock.elapsedRealtimeNanos() - at
            }
            val mismatchedScopes = listOf(activeScope.copy(filmId = "other-film"), activeScope.copy(domain = "other-domain"),
                activeScope.copy(modelVersion = "other-model"), activeScope.copy(glossaryVersion = "other-glossary"),
                activeScope.copy(contextFingerprint = "a".repeat(64)))
            for (other in mismatchedScopes) {
                guard()
                if (reader.lookup(other, source(0)) != null) falseHits++
            }
            output.put("exact_miss_lookup", percentiles(misses)).put("miss_queries", limits.missSamples)
                .put("scope_mismatch_queries", mismatchedScopes.size).put("false_hits", falseHits)
                .put("expected_hit_query_misses", hitMisses).put("wrong_translations", wrongTranslations)
                .put("hot_entries_after", reader.hotSize()).put("database_bytes_before_close", databaseBytes(context, databaseName))
            sample("after_miss_queries")
            assertEquals(0, falseHits); assertEquals(0, hitMisses); assertEquals(0, wrongTranslations)
            assertTrue(reader.hotSize() <= 128)
            output.put("completed", true)
        } catch (error: Throwable) {
            output.put("error_type", error.javaClass.simpleName)
            if (error is ResourceLimit) output.put("abort_reason", error.reason)
            throw error
        } finally {
            output.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                .put("sampled_peak_java_used_bytes", peakJava)
            // Cancellation cannot leave this test's synthetic database behind.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    store?.close()
                    output.put("database_bytes_after_close", databaseBytes(context, databaseName))
                } finally {
                    deleteOwnDatabase(context, databaseName)
                    output.put("disposable_database_removed", true)
                }
            }
        }
    }

    /** Small native storage correctness test is separate from the scale/timing claim. */
    @Test fun optInNativeWarmStoreRetainsExactScopeAndNeverLoadsMoreThan128HotEntries() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeMemoryScale") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.gemini.ocr.codexbench")
        require(BuildConfig.GEMINI_API_KEY.isEmpty())
        check(!SessionState.running.value)
        val databaseName = disposableName("warm")
        val store = ContextualTranslationMemory(context, databaseName, hotCapacity = 128)
        val activeScope = scope()
        try {
            val imported = store.stageJsonl(ContextualTranslationMemory.ImportManifest("warm-v1", activeScope,
                "synthetic-correctness-fixture", provenance, rightsConfirmedByUser = true), FixtureStream(160) {}, 160)
            assertEquals(160, imported.uniqueRows)
            assertNull(store.lookup(activeScope, source(0)))
            assertEquals(160, store.publishImport("warm-v1", reviewedByUser = true, rightsConfirmedByUser = true))
            for (index in 0 until 160) assertEquals(translation(index), store.lookup(activeScope, source(index))?.translation)
            assertEquals(128, store.hotSize())
            repeat(4) { assertEquals(translation(159), store.lookup(activeScope, source(159))?.translation) }
            assertNull(store.lookup(activeScope.copy(filmId = "other-film"), source(159)))
            assertNull(store.lookup(activeScope, source(160)))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { store.close() } finally { deleteOwnDatabase(context, databaseName) }
            }
        }
    }
}
