package com.gemini.ocr.batch

import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoPolishPolicy
import com.gemini.ocr.video.VideoSourceMode
import com.gemini.ocr.video.VideoTranslationMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class BatchKind { MOVIE_PARTS, SERIES }
enum class BatchDelivery { SOURCE_LIBRARY, LIBRARY_AND_MP4, MP4_ONLY }

data class BatchItem(
    val projectId: String, val name: String, val bytes: Long = 0, val episode: String = "1",
    val state: String = "PENDING", val error: String = "", val durationMs: Long = 0,
    val stage: BatchPipelineStage = BatchPipelineStage.EXTRACT, val stageStatus: String = "PENDING",
    val errorCode: String = ""
) {
    fun json() = JSONObject().put("projectId", projectId).put("name", name).put("bytes", bytes)
        .put("episode", episode).put("state", state).put("error", error).put("durationMs", durationMs)
        .put("stage",stage.name).put("stageStatus",stageStatus).put("errorCode", errorCode)
    companion object {
        fun from(j: JSONObject) = BatchItem(j.getString("projectId"), j.getString("name"),
            j.optLong("bytes"), j.getString("episode"), j.optString("state", "PENDING"),
            j.optString("error"), j.optLong("durationMs"), BatchPipelineStage.valueOf(j.optString("stage","EXTRACT")),
            j.optString("stageStatus","PENDING"), j.optString("errorCode").take(64))
    }
}

data class BatchOutput(val episode: String, val title: String, val path: String, val durationMs: Long, val inputFingerprint: String = "") {
    fun json() = JSONObject().put("episode", episode).put("title", title).put("path", path).put("durationMs", durationMs).put("inputFingerprint", inputFingerprint)
    companion object {
        fun from(j: JSONObject) = BatchOutput(j.getString("episode"), j.getString("title"), j.getString("path"), j.getLong("durationMs"), j.optString("inputFingerprint"))
    }
}

data class BatchProject(
    val id: String, val title: String, val kind: BatchKind, val items: List<BatchItem> = emptyList(),
    val outputs: List<BatchOutput> = emptyList(), val state: String = "READY", val message: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val translationMode: VideoTranslationMode = VideoTranslationMode.GEMINI,
    val sourceMode: VideoSourceMode = VideoSourceMode.AUTO,
    val useReviewedMemory: Boolean = false, val reviewedFilmId: String = "", val reviewedDomain: String = "",
    val reviewedProfile: String = "", val reviewedGlossary: String = "", val reviewedModel: String = "",
    val retryAfterMs: Long = 0,
    val delivery: BatchDelivery = BatchDelivery.MP4_ONLY,
    val detectNewSources: Boolean = false,
    val currentStage: BatchPipelineStage = BatchPipelineStage.EXTRACT, val contextProfile: String = "",
    val contextRevision: Int = 0, val sharedRoi: List<Float>? = null, val sharedRoiRevision: Int = 0,
    val offlinePolishPolicy: VideoPolishPolicy = VideoPolishPolicy.NONE,
    val autoRetryEnabled: Boolean = true,
    val autoRetryAtMs: Long = 0,
    val autoRetryAttempts: Int = 0,
    val mergeEpisodes: Boolean = false,
    val executionMode: BatchExecutionMode = BatchExecutionMode.SEQUENTIAL,
    val targetMovieId: String = ""
) {
    fun json() = JSONObject().put("schema", 1).put("id", id).put("title", title).put("kind", kind.name)
        .put("items", JSONArray(items.map { it.json() })).put("outputs", JSONArray(outputs.map { it.json() }))
        .put("state", state).put("message", message).put("updatedAt", updatedAt)
        .put("translationMode", translationMode.name).put("sourceMode", sourceMode.name)
        .put("useReviewedMemory", useReviewedMemory).put("reviewedFilmId", reviewedFilmId).put("reviewedDomain", reviewedDomain)
        .put("reviewedProfile", reviewedProfile).put("reviewedGlossary", reviewedGlossary).put("reviewedModel", reviewedModel)
        .put("retryAfterMs", retryAfterMs)
        .put("autoRetryEnabled", autoRetryEnabled).put("autoRetryAtMs", autoRetryAtMs)
        .put("autoRetryAttempts", autoRetryAttempts)
        .put("delivery", delivery.name).put("mergeEpisodes", mergeEpisodes)
        .put("executionMode", executionMode.name)
        .put("targetMovieId", targetMovieId)
        .apply { if (offlinePolishPolicy != VideoPolishPolicy.NONE) put("offlinePolishPolicy", offlinePolishPolicy.name) }
        .put("detectNewSources", detectNewSources).put("currentStage",currentStage.name).put("contextProfile",contextProfile).put("contextRevision",contextRevision).put("sharedRoi",sharedRoi?.let{JSONArray(it)}?:JSONObject.NULL).put("sharedRoiRevision",sharedRoiRevision)
    companion object {
        fun from(j: JSONObject): BatchProject {
            require(j.getInt("schema") == 1) { "Phiên bản dự án hàng loạt chưa được hỗ trợ." }
            val rows = j.getJSONArray("items"); val outputs = j.getJSONArray("outputs")
            val items = (0 until rows.length()).map { BatchItem.from(rows.getJSONObject(it)) }
            require(items.map { it.projectId }.distinct().size == items.size)
            require(items.all { it.episode.isNotBlank() && it.episode.length <= 80 })
            return BatchProject(j.getString("id"), j.getString("title"), BatchKind.valueOf(j.getString("kind")),
                items, (0 until outputs.length()).map { BatchOutput.from(outputs.getJSONObject(it)) },
                j.optString("state", "READY"), j.optString("message"), j.optLong("updatedAt"),
                VideoTranslationMode.valueOf(j.optString("translationMode", "GEMINI")),
                VideoSourceMode.valueOf(j.optString("sourceMode", "OCR")), j.optBoolean("useReviewedMemory"),
                j.optString("reviewedFilmId"), j.optString("reviewedDomain"), j.optString("reviewedProfile"),
                j.optString("reviewedGlossary"), j.optString("reviewedModel"), j.optLong("retryAfterMs").coerceAtLeast(0),
                BatchDelivery.valueOf(j.optString("delivery", "MP4_ONLY")), j.optBoolean("detectNewSources"),
                BatchPipelineStage.valueOf(j.optString("currentStage","EXTRACT")),j.optString("contextProfile"),j.optInt("contextRevision"),
                j.optJSONArray("sharedRoi")?.let{a->require(a.length()==4);(0..3).map{a.getDouble(it).toFloat()}.also{r->require(r.all{it.isFinite() && it in 0f..1f} && r[2]-r[0]>=.02f && r[3]-r[1]>=.02f)}},j.optInt("sharedRoiRevision"),
                VideoPolishPolicy.fromPersisted(j.optString("offlinePolishPolicy")),
                j.optBoolean("autoRetryEnabled", true), j.optLong("autoRetryAtMs").coerceAtLeast(0),
                j.optInt("autoRetryAttempts").coerceAtLeast(0),
                j.optBoolean("mergeEpisodes") && j.optString("delivery", "MP4_ONLY") != BatchDelivery.SOURCE_LIBRARY.name,
                BatchExecutionMode.fromPersisted(j.optString("executionMode")),
                j.optString("targetMovieId").also { require(it.isBlank() || it.matches(Regex("[a-f0-9-]{36}"))) })
        }
    }
}

/** Only an explicit episode marker identifies an episode. A bare number is never a merge instruction. */
object BatchOrder {
    data class Identity(val episode: String?, val part: String?, val season: String? = null)
    private val episode = Regex("(?:^|[\\s._\\-])(?:ep(?:isode)?|e|tập|tap)\\s*0*(\\d+)(?=$|[^\\d])", RegexOption.IGNORE_CASE)
    private val part = Regex("(?:^|[\\s._\\-])(?:part|pt|phần|phan|đoạn|doan)\\s*0*(\\d+)(?=$|[^\\d])", RegexOption.IGNORE_CASE)
    private val seasonal = Regex("(?:^|[^a-z0-9])s0*(\\d+)e0*(\\d+)(?=$|[^\\d])", RegexOption.IGNORE_CASE)
    private val chinese = Regex("第\\s*([0-9零〇一二两兩三四五六七八九十百千]+)\\s*[集话話]")
    fun identify(name: String): Identity {
        seasonal.find(name)?.let { return Identity(number(it.groupValues[2]), part.find(name)?.groupValues?.get(1)?.let(::number), number(it.groupValues[1])) }
        val ep = episode.find(name)?.groupValues?.get(1) ?: chinese.find(name)?.groupValues?.get(1)?.let(::chineseNumber)
        return Identity(ep?.let(::number), part.find(name)?.groupValues?.get(1)?.let(::number))
    }
    private fun chineseNumber(value:String):String? {
        if(value.all{it.isDigit()})return number(value)
        val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'兩' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        var total=0;var current=0
        for(c in value)when(c){
            '十','百','千'->{val unit=when(c){'十'->10;'百'->100;else->1000};total+=(if(current==0)1 else current)*unit;current=0}
            else->{val digit=digits[c]?:return null;current=current*10+digit}
        }
        return (total+current).takeIf{it>0}?.toString()
    }
    /** A numeric filename only suggests display order, never an episode/merge identity. */
    fun uncertainNumericOrder(items:List<BatchItem>):List<BatchItem>? {
        val leading=Regex("^\\s*([0-9]+)(?=[ ._\\-])")
        if(items.isEmpty() || items.any{identify(it.name).episode!=null || leading.find(it.name)==null})return null
        return items.sortedWith{a,b->natural(leading.find(a.name)!!.groupValues[1],leading.find(b.name)!!.groupValues[1])}
    }
    private fun number(value: String) = value.trimStart('0').ifBlank { "0" }
    fun episodeKey(identity: Identity): String? = identity.episode?.let { ep -> identity.season?.let { "S${it}E$ep" } ?: ep }
    fun suggested(items: List<BatchItem>): List<BatchItem> {
        val ids = items.map { identify(it.name) }
        if (ids.any { it.episode == null && it.part == null }) return items
        // Mixed episode-only and part-only names do not establish a common sequence safely.
        if (ids.any { it.episode != null } && ids.any { it.episode == null }) return items
        return items.sortedWith { a, b ->
            val x = identify(a.name); val y = identify(b.name)
            natural(x.season ?: "0", y.season ?: "0").takeIf { it != 0 }
                ?: natural(x.episode ?: "0", y.episode ?: "0").takeIf { it != 0 }
                ?: natural(x.part ?: "0", y.part ?: "0")
        }
    }
    /** Arbitrarily long numeric runs are compared without parsing them into a bounded integer. */
    fun natural(first: String, second: String): Int {
        val a = Regex("\\d+|\\D+").findAll(first.lowercase(Locale.ROOT)).map { it.value }.toList()
        val b = Regex("\\d+|\\D+").findAll(second.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]; val y = b[i]
            val compare = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = number(x); val ny = number(y)
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (compare != 0) return compare
        }
        return a.size.compareTo(b.size)
    }
}

/** Queue decisions are independent of worker lifetime; successful exports survive retries. */
object BatchPlan {
    internal const val ALL_EPISODES = "all-episodes"
    /** The explicit whole-series mode preserves the exact reviewed input order and episode labels. */
    fun groups(batch: BatchProject): Map<String, List<BatchItem>> =
        if (batch.mergeEpisodes && batch.delivery != BatchDelivery.SOURCE_LIBRARY && batch.items.isNotEmpty())
            linkedMapOf(ALL_EPISODES to batch.items)
        else batch.items.groupBy { it.episode }
    /** Suggested episode labels never add a new import to a merge group. */
    fun importedEpisode(projectId: String, name: String, occupied: Set<String>): String =
        BatchOrder.episodeKey(BatchOrder.identify(name))?.takeUnless { it in occupied } ?: "video-$projectId"
    fun interrupted(batch: BatchProject): BatchProject {
        val active = batch.state in setOf("RUNNING", "RETRY_WAIT")
        if (!active && batch.state !in setOf("PAUSED", "CANCELLED")) return batch
        val items = batch.items.map { item ->
            when {
                item.stageStatus == "RUNNING" -> item.copy(state = "PENDING", stageStatus = "PAUSED")
                item.state in setOf("TRANSLATING", "EXPORTING") -> item.copy(state = "PENDING")
                else -> item
            }
        }
        if (!active) return if (items == batch.items) batch else batch.copy(items = items)
        return batch.copy(state = "PAUSED", autoRetryAtMs = 0,
            message = "Đã lưu checkpoint. Bấm Tiếp tục để xử lý phần còn lại.", items = items)
    }
    fun move(items: List<BatchItem>, from: Int, to: Int): List<BatchItem> {
        require(from in items.indices && to in items.indices)
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
}
