package com.gemini.ocr.video

import com.gemini.ocr.ocr.OcrRecoveryPolicy.Bounds
import org.json.JSONArray
import org.json.JSONObject

/** Shared surface only. Legacy acceptance remains in VideoOcrStabilizer. */
interface VideoOcrTimeline {
    val cues:MutableList<VideoCue>
    val needsConfirmation:Boolean
    fun add(ms:Long,text:String?)
    fun snapshot():JSONObject
    fun finish(end:Long):List<VideoCue>
}

/** One active occurrence and at most one undecided change. Unresolved readings remain reviewable. */
internal class VideoDraftTimeline(
    existing: List<VideoCue> = emptyList(),
    saved: JSONObject = JSONObject(),
    private val continuity: VideoOcrContinuity = VideoOcrContinuity()
) : VideoOcrTimeline {
    private data class Run(
        val representative: VideoOcrReading, val start: Long, val last: Long = start, val count: Int = 1,
        val uncertain: Boolean = false, val lastBox: Bounds? = representative.bounds,
        val alternatives: List<String> = emptyList()
    )
    private data class Pending(val ms: Long, val reading: VideoOcrReading)

    override val cues=existing.toMutableList()
    private val ids=existing.map{it.id}.toMutableSet()
    private var nextId=saved.optInt("draftNextId",existing.size+1).coerceAtLeast(1)
    private var last=saved.optLong("scanLast",saved.optLong("last",-1))
    private var unknownGap=saved.optBoolean("draftUnknownGap")
    private var current: Run? = null
    private var pending: Pending? = null
    var consolidatedVariants: Long = saved.optLong("draftConsolidatedVariants").coerceAtLeast(0)
        private set
    val representativeSource get() = current?.representative?.displayText.orEmpty()
    override val needsConfirmation get() =
        unknownGap || pending != null || current?.let { it.count < 2 || it.uncertain } == true

    init {
        require(ids.size==existing.size)
        val storedRun = saved.optJSONObject("draftRun")
        if (storedRun != null) {
            val reading = VideoOcrReading.from(storedRun)
            val start = storedRun.getLong("start")
            val tail = storedRun.getLong("last")
            require(reading.displayText.isNotBlank() && start >= 0 && tail in start..last)
            val variants = storedRun.optJSONArray("alternatives")?.let { texts ->
                List(minOf(texts.length(), MAX_ALTERNATIVES)) { texts.getString(it) }
            }.orEmpty()
            current = Run(reading, start, tail, storedRun.optInt("count", 1).coerceIn(1, 3),
                storedRun.optBoolean("uncertain"), VideoOcrReading.readBounds(storedRun.optJSONArray("lastBox")),
                variants)
            saved.optJSONObject("draftPending")?.let {
                val ms = it.getLong("ms")
                val candidate = VideoOcrReading.from(it)
                require(ms > tail && ms == last && candidate.displayText.isNotBlank())
                pending = Pending(ms, candidate)
            }
        }else if(saved.optString("source").isNotBlank()){
            // Older carries have no per-frame confidence/confirmation history. Retain their
            // actual recognized source as a bounded review draft, not invented stable evidence.
            val start=saved.optLong("start").coerceAtLeast(0)
            current=Run(VideoOcrReading(saved.getString("source")), start, uncertain = true)
        }
    }

    override fun add(ms: Long, text: String?) = addReading(ms, text?.let { VideoOcrReading(it) })

    fun addReading(ms: Long, reading: VideoOcrReading?) {
        require(ms >= 0 && ms >= last)
        if (ms == last) return
        last = ms

        val waiting = pending
        if (waiting != null) {
            val active = checkNotNull(current)
            if (reading != null && ms - active.last <= continuity.windowMs &&
                continuity.sameBox(waiting.reading.bounds, reading.bounds) &&
                continuity.sameBox(active.lastBox, reading.bounds)
            ) {
                val winner = continuity.representative(active.representative, waiting.reading, reading, active.count > 1)
                if (winner != null) {
                    val readings = listOf(active.representative, waiting.reading, reading)
                    val alternates = (active.alternatives + readings.filter { it.normalizedText != winner.normalizedText }
                        .map { it.raw }).distinct().take(MAX_ALTERNATIVES)
                    consolidatedVariants += readings.drop(if (active.count > 1) 1 else 0)
                        .count { it.normalizedText != winner.normalizedText }
                    current = active.copy(representative = winner, last = ms, lastBox = reading.bounds,
                        count = (active.count + 2).coerceAtMost(3), alternatives = alternates)
                    pending = null
                    return
                }
            }
            startPending()
        }

        if (reading == null || reading.displayText.isBlank()) {
            val wasUncertain = current?.uncertain == true
            if (reading == null) current = current?.copy(uncertain = true)
            commit(ms, conservativeTail = wasUncertain)
            unknownGap = reading == null
            return
        }

        val active = current
        if (active != null && !active.uncertain && !unknownGap) {
            val sameBox = continuity.sameBox(active.lastBox, reading.bounds)
            val edgeAnchor = if (sameBox && ms - active.last <= continuity.windowMs)
                continuity.edgeStrokeAnchor(active.representative, reading) else null
            if (edgeAnchor != null) {
                val edgeReading = if (edgeAnchor === reading) active.representative else reading
                consolidatedVariants += if (edgeAnchor === reading) active.count.toLong() else 1L
                current = active.copy(representative = edgeAnchor, last = ms, lastBox = reading.bounds,
                    count = (active.count + 1).coerceAtMost(3),
                    alternatives = (active.alternatives + edgeReading.raw).distinct().take(MAX_ALTERNATIVES))
                return
            }
            if (active.representative.normalizedText == reading.normalizedText &&
                (sameBox || active.lastBox == null || reading.bounds == null)
            ) {
                val previous = active.representative
                val previousLines = previous.displayText.count { it == '\n' }
                val incomingLines = reading.displayText.count { it == '\n' }
                val stronger = reading.confidence != null && previous.confidence != null &&
                    reading.confidence - previous.confidence >= .05f
                val preferred = if (active.count == 1 && (incomingLines > previousLines ||
                        incomingLines == previousLines && stronger)) reading else previous
                current = active.copy(representative = preferred, last = ms,
                    lastBox = reading.bounds, count = (active.count + 1).coerceAtMost(3))
                return
            }
            if (sameBox && ms - active.last < continuity.windowMs &&
                continuity.possibleVariant(active.representative, reading)
            ) {
                pending = Pending(ms, reading)
                return
            }
        }
        commit(ms, conservativeTail = active?.uncertain == true)
        current = Run(reading, ms)
        unknownGap = false
    }

    private fun startPending() {
        val waiting = pending ?: return
        commit(waiting.ms)
        current = Run(waiting.reading, waiting.ms)
        pending = null
    }

    private fun commit(nextMs:Long,conservativeTail:Boolean=false) {
        val active=current?:return
        val end=if(conservativeTail)minOf(nextMs,active.last+200) else nextMs
        if(end>active.start){
            require(cues.size<VideoProject.MAX_CUES){"Có quá nhiều quan sát OCR chưa ổn định; kiểm tra các câu đã lưu trước khi tiếp tục."}
            var id:String
            do{id="ocr-draft-${nextId++}"}while(!ids.add(id))
            val flags=buildList {
                if(active.count<2){add("OCR_UNSTABLE_SINGLE_FRAME");add("OCR_SHORT_QUORUM")}
                if(active.uncertain || end<nextMs)add("OCR_UNCERTAIN_INTERVAL")
            }
            val representative = active.representative
            cues += VideoCue(id, active.start, end, representative.displayText, origin = "OCR",
                rawZh = representative.raw, correctedZh = representative.displayText.takeIf { it != representative.raw }.orEmpty(),
                qualityFlags = flags, normalizedOcrText = representative.normalizedText, ocrAlternatives = active.alternatives)
        }
        current=null
    }
    override fun snapshot():JSONObject=JSONObject().put("draftVersion",3).put("scanLast",last).put("last",last)
        .put("draftNextId",nextId).put("draftUnknownGap",unknownGap)
        .put("source",representativeSource).put("start",current?.start?:0)
        .put("draftConsolidatedVariants", consolidatedVariants)
        .put("draftRun", current?.let { run -> run.representative.json().put("start", run.start)
            .put("last", run.last).put("count",run.count).put("uncertain",run.uncertain)
            .put("lastBox", run.lastBox?.toJson() ?: JSONObject.NULL).put("alternatives", JSONArray(run.alternatives))
        } ?: JSONObject.NULL)
        .put("draftPending", pending?.let { it.reading.json().put("ms", it.ms) } ?: JSONObject.NULL)
    override fun finish(end:Long):List<VideoCue> {
        require(end>=0)
        startPending()
        // Legacy callers without actual final-frame evidence cannot certify an arbitrary tail.
        commit(end,conservativeTail=true)
        return cues.toList()
    }
    fun finishAtValidatedEnd(end:Long,finalFrameMs:Long):List<VideoCue> {
        require(end>=0 && finalFrameMs in 0..end)
        startPending()
        val verified=current?.let{it.last==finalFrameMs && !unknownGap && !it.uncertain}==true
        commit(end,conservativeTail=!verified)
        return cues.toList()
    }

    private companion object { const val MAX_ALTERNATIVES = 4 }
}
