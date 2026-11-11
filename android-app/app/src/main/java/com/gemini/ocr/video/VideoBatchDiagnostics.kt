package com.gemini.ocr.video

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Serializes AtomicFile readers with writers; openRead may otherwise restore a live writer's backup. */
internal object VideoMetricFile {
    private const val MAX_BYTES = 16 * 1024
    private val lock = Any()
    private val metricName = Regex("[a-z_]{1,50}")

    fun read(path: File): JSONObject = synchronized(lock) {
        runCatching {
            val candidates = listOf(path, File(path.path + ".bak")).filter { it.isFile }
            require(candidates.isNotEmpty() && candidates.all { it.length() <= MAX_BYTES })
            val bytes = AtomicFile(path).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val raw = JSONObject(bytes.toString(Charsets.UTF_8))
            JSONObject().also { safe ->
                raw.keys().forEach { name ->
                    val number = raw.opt(name)
                    if (metricName.matches(name) && (number is Int || number is Long) && (number as Number).toLong() >= 0)
                        safe.put(name, (number as Number).toLong())
                }
            }
        }.getOrElse { JSONObject() }
    }

    fun write(path: File, values: JSONObject) = synchronized(lock) {
        val bytes = values.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}

internal data class VideoDiagnosticCauses(
    val rateLimit: Long = 0, val service: Long = 0, val network: Long = 0,
    val authorization: Long = 0, val quota: Long = 0, val response: Long = 0, val other: Long = 0
)

/** Only explicitly selected counters reach the UI. Never load project JSON or media. */
internal data class VideoBatchDiagnosticSnapshot(
    val requestedProjects: Int = 0,
    val measuredProjects: Int = 0,
    val limited: Boolean = false,
    val requests: Long = 0,
    val responses: Long = 0,
    val partialResponses: Long = 0,
    val emptyResponses: Long = 0,
    val truncatedResponses: Long = 0,
    val acceptedCues: Long = 0,
    val missingCues: Long = 0,
    val retryWaits: Long = 0,
    val failedRequests: Long = 0,
    val retryCauses: VideoDiagnosticCauses = VideoDiagnosticCauses(),
    val failureCauses: VideoDiagnosticCauses = VideoDiagnosticCauses(),
    val lastRequestCues: Long = 0,
    val requestCapacity: Long = 0,
    val requestCapacityCeiling: Long = 0,
    val retryAtMs: Long = 0,
    val updatedMs: Long = 0,
    val responseFailureReason: Int = 0
)

internal object VideoBatchDiagnostics {
    private const val MAX_PROJECTS = 200
    private val projectId = Regex("[a-f0-9-]{36}")

    fun read(context: Context, projectIds: List<String>): VideoBatchDiagnosticSnapshot {
        val ids = projectIds.distinct()
        val selected = ids.filter(projectId::matches).take(MAX_PROJECTS)
        val directory = File(context.applicationContext.filesDir, "video-projects")
        val measurements = selected.map { id -> VideoMetricFile.read(File(File(directory, id), "smart-batch-metrics.json")) }
            .filter { it.length() > 0 }
        return aggregate(measurements, ids.size, ids.count(projectId::matches) > MAX_PROJECTS)
    }

    internal fun aggregate(rows: List<JSONObject>, projects: Int, limited: Boolean = false): VideoBatchDiagnosticSnapshot {
        fun sum(name: String) = rows.fold(0L) { total, row -> diagnosticSum(total, counter(row, name)) }
        fun causes(prefix: String) = VideoDiagnosticCauses(sum(prefix + "rate_limit"), sum(prefix + "service"),
            sum(prefix + "network"), sum(prefix + "authorization"), sum(prefix + "quota"),
            sum(prefix + "response"), sum(prefix + "other"))
        val lastRequest = rows.maxByOrNull { counter(it, "last_request_at_ms") }
        val lastFailure = rows.maxByOrNull { counter(it, "response_failure_at_ms") }
        val lastCapacity = rows.filter { counter(it, "request_capacity") in 1L..8L &&
            counter(it, "request_capacity_ceiling") in counter(it, "request_capacity")..8L }
            .maxByOrNull { counter(it, "request_capacity_at_ms") }
        val responses = rows.fold(0L) { total, row -> diagnosticSum(total,
            if (row.has("responses")) counter(row, "responses") else diagnosticSum(
                counter(row, "responses_with_token_usage"), counter(row, "responses_without_token_usage"))) }
        return VideoBatchDiagnosticSnapshot(projects, rows.size, limited,
            sum("requests"), responses, sum("partial_responses"), sum("empty_responses"), sum("truncated_responses"),
            sum("accepted_cues"), sum("missing_cues"), sum("retry_waits"), sum("failed_requests"),
            causes("retry_cause_"), causes("failure_cause_"), lastRequest?.let { counter(it, "last_request_cues") } ?: 0L,
            lastCapacity?.let { counter(it, "request_capacity") } ?: 0L,
            lastCapacity?.let { counter(it, "request_capacity_ceiling") } ?: 0L,
            rows.maxOfOrNull { maxOf(counter(it, "active_retry_at_ms"), counter(it, "retry_after_ms")) } ?: 0L,
            rows.maxOfOrNull { counter(it, "updated_ms") } ?: 0L,
            lastFailure?.let { VideoResponseFailureReason.fromId(counter(it, "response_failure_reason").toInt()).id } ?: 0)
    }

    private fun counter(row: JSONObject, name: String): Long = when (val value = row.opt(name)) {
        is Long -> value.coerceAtLeast(0)
        is Int -> value.toLong().coerceAtLeast(0)
        else -> 0
    }
}

internal fun diagnosticSum(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
