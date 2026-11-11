package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject

/** Bounded metadata for exact current occurrences; never a fuzzy text cache/translation bank. */
internal class VideoOcrDraftQuality(saved:JSONArray?=null) {
    private data class Evidence(val raw:String,val confidence:Float?,val flags:Set<String>,val ms:Long)
    private data class Quality(val raw:String,val confidence:Float?,val flags:Set<String>,
        val firstMs:Long,val lastMs:Long,val evidence:List<Evidence>)
    private val recent=linkedMapOf<String,Quality>()
    init {
        if(saved!=null)for(i in 0 until minOf(saved.length(),16)){
            val row=saved.getJSONObject(i)
            val entries=row.optJSONArray("evidence")
            if(entries!=null){
                for(j in 0 until minOf(entries.length(),16)){
                    val e=entries.getJSONObject(j)
                    record(e.getString("raw"),confidence(e),flags(e),row.getString("raw"),e.optLong("ms",-1))
                }
                val key=VideoOcrStabilizer.identity(row.getString("raw"))
                recent[key]?.let{recent[key]=it.copy(raw=row.getString("raw"),confidence=confidence(row),
                    flags=flags(row).toSet(),firstMs=row.optLong("firstMs",it.firstMs))}
            }else record(row.getString("raw"),confidence(row),flags(row),frameMs=row.optLong("lastMs",-1))
        }
    }
    private fun confidence(row:JSONObject)=row.optDouble("confidence").takeIf{it.isFinite() && it in 0.0..1.0}?.toFloat()
    private fun flags(row:JSONObject)=row.optJSONArray("flags")?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()
    private fun minimum(values:List<Float?>):Float?=if(values.any{it==null})null else values.filterNotNull().minOrNull()
    private fun summarize(values:List<Evidence>):Quality {
        require(values.isNotEmpty())
        return Quality(values.first().raw,minimum(values.map{it.confidence}),values.flatMap{it.flags}.toSet(),
            values.first().ms,values.maxOf{it.ms},values)
    }
    fun record(text:String,confidence:Float?,flags:List<String>,pin:String="",frameMs:Long=-1) {
        if(text.isBlank())return
        val key=VideoOcrStabilizer.identity(text)
        var old=recent[key]
        // Unaccepted stale evidence cannot raise the quorum of an occurrence seconds later.
        if(old!=null && key!=VideoOcrStabilizer.identity(pin) && frameMs>=0 && old.lastMs>=0 && frameMs-old.lastMs>400)old=null
        val value=Evidence(text,confidence?.takeIf{it.isFinite() && it in 0f..1f},flags.toSet(),frameMs)
        val evidence=(old?.evidence.orEmpty().filterNot{frameMs>=0 && it.ms==frameMs}+value)
            .sortedBy{it.ms}.takeLast(16)
        val min=if(old==null)value.confidence else minimum(listOf(old.confidence,value.confidence))
        recent.remove(key)
        recent[key]=Quality(old?.raw?:text,min,old?.flags.orEmpty()+flags,minOf(old?.firstMs?:frameMs,frameMs),
            maxOf(old?.lastMs?:frameMs,frameMs),evidence)
        while(recent.size>128){
            val victim=recent.keys.firstOrNull{it!=VideoOcrStabilizer.identity(pin)}?:break
            recent.remove(victim)
        }
    }
    fun close(cue:VideoCue):VideoCue {
        val key=VideoOcrStabilizer.identity(cue.source)
        val quality=recent[key]?:return cue.copy(rawZh=cue.rawZh.ifBlank{cue.source},
            qualityFlags=(cue.qualityFlags+"OCR_CONFIDENCE_UNKNOWN"+"OCR_SHORT_QUORUM").distinct())
        val matching=quality.evidence.filter{it.ms<0 || it.ms>=cue.startMs && it.ms<cue.endMs}
        val future=quality.evidence.filter{it.ms>=cue.endMs}
        if(future.isEmpty())recent.remove(key) else recent[key]=summarize(future)
        val sameOccurrence=quality.firstMs<0 || quality.firstMs>=cue.startMs && quality.lastMs<cue.endMs
        val selected=when {
            sameOccurrence -> quality
            matching.isNotEmpty() -> summarize(matching)
            else -> quality.copy(confidence=null,flags=quality.flags+"OCR_CONFIDENCE_UNKNOWN")
        }
        // Support is distinct frames within 400 ms, not repeated old or replayed observations.
        val latest=matching.maxOfOrNull{it.ms}?:-1
        val observations=matching.filter{it.ms<0 || it.ms>=latest-400}.let{rows->
            rows.count{it.ms<0}+rows.filter{it.ms>=0}.map{it.ms}.distinct().size}
        return cue.copy(rawZh=cue.rawZh.ifBlank{selected.raw},ocrConfidence=selected.confidence,
            qualityFlags=(cue.qualityFlags+selected.flags+if(observations<3)listOf("OCR_SHORT_QUORUM")else emptyList()).distinct())
    }
    fun snapshot(pin:String):JSONArray {
        val key=VideoOcrStabilizer.identity(pin)
        val rows=recent.entries.filter{it.key==key}+recent.entries.filter{it.key!=key}.takeLast(8)
        return JSONArray(rows.map{(_,q)->JSONObject().put("raw",q.raw)
            .put("confidence",q.confidence?:JSONObject.NULL).put("flags",JSONArray(q.flags.toList()))
            .put("firstMs",q.firstMs).put("lastMs",q.lastMs).put("evidence",JSONArray(q.evidence.map{e->
                JSONObject().put("raw",e.raw).put("confidence",e.confidence?:JSONObject.NULL)
                    .put("flags",JSONArray(e.flags.toList())).put("ms",e.ms)
            }))})
    }
}
