package com.gemini.ocr.video

import org.json.JSONObject

/** Shared surface only. Legacy acceptance remains in VideoOcrStabilizer. */
interface VideoOcrTimeline {
    val cues:MutableList<VideoCue>
    val needsConfirmation:Boolean
    fun add(ms:Long,text:String?)
    fun snapshot():JSONObject
    fun finish(end:Long):List<VideoCue>
}

/**
 * Reviewable source evidence for staged extraction. No text is invented or chosen by similarity:
 * an unsupported native reading gets its own flagged interval, rather than disappearing
 * because another actual reading changed one character before an exact quorum could form.
 */
internal class VideoDraftTimeline(existing:List<VideoCue> = emptyList(),saved:JSONObject=JSONObject()):VideoOcrTimeline {
    private data class Run(val raw:String,val start:Long,val last:Long,val count:Int,val uncertain:Boolean=false)
    override val cues=existing.toMutableList()
    private val ids=existing.map{it.id}.toMutableSet()
    private var nextId=saved.optInt("draftNextId",existing.size+1).coerceAtLeast(1)
    private var last=saved.optLong("scanLast",saved.optLong("last",-1))
    private var unknownGap=saved.optBoolean("draftUnknownGap")
    private var current:Run?=null
    override val needsConfirmation get()=unknownGap || current?.let{it.count<2 || it.uncertain}==true
    init {
        require(ids.size==existing.size)
        val pending=saved.optJSONObject("draftRun")
        if(pending!=null){
            val raw=pending.getString("raw")
            val start=pending.getLong("start");val tail=pending.getLong("last")
            require(raw.isNotBlank() && raw.length<=2000 && start>=0 && tail>=start && tail<=last)
            current=Run(raw,start,tail,pending.optInt("count",1).coerceIn(1,3),pending.optBoolean("uncertain"))
        }else if(saved.optString("source").isNotBlank()){
            // Older carries have no per-frame confidence/confirmation history. Retain their
            // actual recognized source as a bounded review draft, not invented stable evidence.
            val raw=saved.getString("source");val start=saved.optLong("start").coerceAtLeast(0)
            require(raw.length<=2000)
            current=Run(raw,start,start,1,true)
        }
    }
    override fun add(ms:Long,text:String?) {
        require(ms>=last)
        if(ms==last)return // replayed checkpoint/frame is not a second observation
        last=ms
        if(text==null){
            unknownGap=true
            val legacyUncertain=current?.uncertain==true
            current=current?.copy(uncertain=true)
            // The first unknown observation is the evidence boundary. It is neither an
            // observed blank nor permission to keep drawing the old reading through a gap.
            commit(ms,conservativeTail=legacyUncertain)
            return
        }
        val raw=text.replace("\r\n","\n").replace('\r','\n').trim()
        require(raw.length<=2000){"Một quan sát OCR vượt giới hạn; checkpoint trước được giữ."}
        val active=current
        if(raw.isBlank()){
            commit(ms,conservativeTail=active?.uncertain==true)
            unknownGap=false
            return
        }
        if(active!=null && !active.uncertain && !unknownGap && VideoTimeline.identity(raw)==VideoTimeline.identity(active.raw)){
            // Formatting equivalence only: preserve every Han, digit, negation and punctuation.
            // Native video PTS, not an assumed FPS/200ms interval, defines this occurrence.
            current=active.copy(last=ms,count=(active.count+1).coerceAtMost(3))
            unknownGap=false
            return
        }
        commit(ms,conservativeTail=active?.uncertain==true)
        current=Run(raw,ms,ms,1)
        unknownGap=false
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
            cues+=VideoCue(id,active.start,end,active.raw,origin="OCR",rawZh=active.raw,qualityFlags=flags)
        }
        current=null
    }
    override fun snapshot():JSONObject=JSONObject().put("draftVersion",2).put("scanLast",last).put("last",last)
        .put("draftNextId",nextId).put("draftUnknownGap",unknownGap)
        // Decoder quality metadata pins this actual source; no normalized/fuzzy surrogate.
        .put("source",current?.raw.orEmpty()).put("start",current?.start?:0)
        .put("draftRun",current?.let{run->JSONObject().put("raw",run.raw).put("start",run.start)
            .put("last",run.last).put("count",run.count).put("uncertain",run.uncertain)}?:JSONObject.NULL)
    override fun finish(end:Long):List<VideoCue> {
        require(end>=0)
        // Legacy callers without actual final-frame evidence cannot certify an arbitrary tail.
        commit(end,conservativeTail=true)
        return cues.toList()
    }
    fun finishAtValidatedEnd(end:Long,finalFrameMs:Long):List<VideoCue> {
        require(end>=0 && finalFrameMs in 0..end)
        val verified=current?.let{it.last==finalFrameMs && !unknownGap && !it.uncertain}==true
        commit(end,conservativeTail=!verified)
        return cues.toList()
    }
}
