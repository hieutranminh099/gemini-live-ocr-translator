package com.gemini.ocr.video

import org.json.JSONObject
import java.util.ArrayDeque

/** Largest chronological pending prefix within the local budgets; no target cue is discarded. */
internal object VideoFullDialoguePlan {
    const val REQUEST_TIMEOUT_MS=300_000L
    const val MAX_OUTPUT_TOKENS=65_536
    const val MAX_CONTEXT_BYTES=1024*1024
    private const val OUTPUT_OVERHEAD=2048L

    /** Optional pure preflight includes HTTP escaping/profile/glossary without a network call. */
    fun next(cues:List<VideoCue>,durationMs:Long,filmContext:String="",
        fitsRequest:((VideoTranslationPlan.Batch)->Boolean)?=null):VideoTranslationPlan.Batch? =
        next(cues,durationMs,filmContext,Int.MAX_VALUE,fitsRequest)

    /** Bound targets before encoding candidate requests; context still contains every row that fits. */
    fun next(cues:List<VideoCue>,durationMs:Long,filmContext:String="",maxTargets:Int,
        fitsRequest:((VideoTranslationPlan.Batch)->Boolean)?=null):VideoTranslationPlan.Batch? {
        require(maxTargets>0){"Giới hạn số câu mỗi nhóm phải lớn hơn 0."}
        val chronological=VideoTimeline.validate(cues,durationMs)
        val originalById=cues.indices.associateBy{cues[it].id}
        val ordered=chronological.map{originalById.getValue(it.id)}
        val pending=ordered.filter{!VideoTranslationPlan.isComplete(cues[it],true)}
        if(pending.isEmpty())return null
        val encoded=Rows(cues,ordered,durationMs,filmContext,pending.size)
        val windowBase=encoded.context(emptyList(),emptyList(),false).utf8Size().toLong()
        val fullBase=encoded.context(emptyList(),emptyList(),true).utf8Size().toLong()
        val selected=mutableListOf<Int>()
        var output=OUTPUT_OVERHEAD
        var mandatoryBytes=windowBase
        for(index in pending){
            if(selected.size>=maxTargets)break
            val rowCost=encoded.row(index).utf8Size().toLong()
            val idCost=JSONObject.quote(cues[index].id).utf8Size().toLong()
            val nextBytes=mandatoryBytes+rowCost+idCost+(if(selected.isEmpty())0 else 2)
            val nextOutput=output+outputCost(cues[index])
            val finalScopeAdjustment=if(selected.size+1==cues.size)fullBase-windowBase else 0L
            if(nextBytes+finalScopeAdjustment>MAX_CONTEXT_BYTES || nextOutput>MAX_OUTPUT_TOKENS)break
            selected+=index;mandatoryBytes=nextBytes;output=nextOutput
        }
        require(selected.isNotEmpty()){"Một câu nguồn hoặc ngữ cảnh phim vượt giới hạn gửi Gemini. Nguồn đã lưu; kiểm tra câu đó trước khi tiếp tục. Không tự cắt hoặc bỏ câu."}

        fun make(targets:List<Int>,contextRows:List<Int>,complete:Boolean):VideoTranslationPlan.Batch {
            val indices=targets.sorted() // preserve persisted result-mapping indices
            val rows=indices.map{cues[it]}
            return VideoTranslationPlan.Batch(indices,rows.map{it.id},rows.map{it.source},
                rows.map{it.localDraft.ifBlank{it.translation}},encoded.context(contextRows,indices,complete || contextRows.toSet().size==cues.size),
                (OUTPUT_OVERHEAD+targets.sumOf{outputCost(cues[it])}).toInt().coerceIn(8192,MAX_OUTPUT_TOKENS),fullDialogue=true)
        }
        fun fits(batch:VideoTranslationPlan.Batch)=fitsRequest?.invoke(batch)?:true
        var targets=selected.toList()
        var mandatory=make(targets,targets,false)
        if(!fits(mandatory)){
            // Mandatory content grows monotonically. Only a failed HTTP-size preflight
            // needs this local search; ordinary output-bound parts take one check.
            var low=1;var high=targets.size-1;var best=0
            while(low<=high){
                val middle=(low+high) ushr 1
                if(fits(make(selected.take(middle),selected.take(middle),false))){best=middle;low=middle+1}
                else high=middle-1
            }
            require(best>0){"Một câu nguồn cùng cấu hình/ngữ cảnh vượt dung lượng yêu cầu Gemini. Nguồn đã lưu; chưa gửi dữ liệu."}
            targets=selected.take(best);mandatory=make(targets,targets,false)
        }

        // Optional context never makes a target part smaller. Prefer all dialogue if it
        // fits; otherwise keep every target and fill spare space with nearby whole rows.
        val idBytes=targets.sumOf{JSONObject.quote(cues[it].id).utf8Size().toLong()}+(targets.size-1).coerceAtLeast(0)
        var fullBytes=fullBase+idBytes
        for((position,index) in ordered.withIndex()){
            fullBytes+=encoded.row(index).utf8Size()+(if(position==0)0 else 1)
            if(fullBytes>MAX_CONTEXT_BYTES)break
        }
        if(fullBytes<=MAX_CONTEXT_BYTES){
            val full=make(targets,ordered,true)
            if(fits(full))return full
        }
        val targetSet=targets.toSet()
        var used=windowBase+targets.sumOf{encoded.row(it).utf8Size().toLong()+JSONObject.quote(cues[it].id).utf8Size()}+
            2L*(targets.size-1).coerceAtLeast(0)
        val optional=mutableListOf<Int>()
        for(index in nearby(ordered,targetSet)){
            val extra=encoded.row(index).utf8Size().toLong()+1
            if(used+extra<=MAX_CONTEXT_BYTES){optional+=index;used+=extra}
        }
        if(optional.isEmpty())return mandatory
        val enriched=make(targets,targets+optional,false)
        if(fits(enriched))return enriched
        var low=0;var high=optional.size-1;var best=0
        while(low<=high){
            val middle=(low+high) ushr 1
            if(fits(make(targets,targets+optional.take(middle),false))){best=middle;low=middle+1}
            else high=middle-1
        }
        return if(best==0)mandatory else make(targets,targets+optional.take(best),false)
    }

    private fun outputCost(cue:VideoCue)=cue.source.codePointCount(0,cue.source.length)*2L+
        maxOf(8,(cue.id.length+1)/2)+16

    /** Multi-source breadth-first traversal: nearest context first, without quadratic scans. */
    private fun nearby(ordered:List<Int>,targets:Set<Int>):Sequence<Int> = sequence {
        val queue=ArrayDeque<Int>();val visited=BooleanArray(ordered.size)
        ordered.forEachIndexed{position,index->if(index in targets){visited[position]=true;queue.addLast(position)}}
        while(queue.isNotEmpty()){
            val position=queue.removeFirst()
            for(next in intArrayOf(position-1,position+1))if(next in ordered.indices && !visited[next]){
                visited[next]=true;queue.addLast(next);yield(ordered[next])
            }
        }
    }

    private class Rows(val cues:List<VideoCue>,val ordered:List<Int>,val durationMs:Long,
        val filmContext:String,val pendingCount:Int) {
        private val cache=arrayOfNulls<String>(cues.size)
        private var cachedBytes=0L
        fun row(index:Int):String {
            cache[index]?.let{return it}
            val cue=cues[index]
            val objectRow=JSONObject().put("id",cue.id).put("index",index)
                .put("startMs",cue.startMs).put("endMs",cue.endMs).put("source",cue.source)
                .put("approved",cue.protectedText)
            if(cue.protectedText || VideoTranslationPlan.isComplete(cue,true)){
                if(cue.translation.isNotBlank())objectRow.put("vietnamese",cue.translation)
            }else cue.localDraft.ifBlank{cue.translation}.takeIf{it.isNotBlank()}?.let{objectRow.put("localDraft",it)}
            val text=objectRow.toString();val cost=text.utf8Size()
            // Pathological optional drafts must not retain unbounded copies during a 20k-row scan.
            if(cachedBytes+cost<=3L*MAX_CONTEXT_BYTES){cache[index]=text;cachedBytes+=cost}
            return text
        }
        fun context(contextRows:List<Int>,targets:List<Int>,complete:Boolean):String {
            val include=contextRows.toHashSet()
            val header=JSONObject().put("schema",2).put("promptRevision",VideoDialoguePrompt.REVISION)
                .put("task","translate_full_dialogue_from_original_chinese")
                .put("durationMs",durationMs).put("filmContext",filmContext)
                .put("contextScope",if(complete)"full"else "window").put("contextComplete",complete)
                .put("totalCueCount",cues.size).put("pendingCount",pendingCount).toString()
            return header.dropLast(1)+",\"dialogue\":"+
                ordered.asSequence().filter{it in include}.joinToString(",","[","]"){row(it)}+
                ",\"targetIds\":"+targets.joinToString(",","[","]"){JSONObject.quote(cues[it].id)}+"}"
        }
    }
}

private fun String.utf8Size()=toByteArray(Charsets.UTF_8).size
