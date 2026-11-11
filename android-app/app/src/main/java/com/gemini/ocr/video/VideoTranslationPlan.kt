package com.gemini.ocr.video

/** Bounded, chronological video batches. Chinese source is authoritative; local text is a draft. */
object VideoTranslationPlan {
    private const val MAX_CUES=50
    private const val LEGACY_DEFAULT_CUES=12
    private const val SOURCE_BUDGET=1000
    private const val DRAFT_BUDGET=4000
    private const val CONTEXT_BUDGET=1800
    private const val MAX_SINGLE_SOURCE=2000
    private const val MAX_SINGLE_DRAFT=8000

    data class Batch(
        val indices:List<Int>,val cueIds:List<String>,val texts:List<String>,
        val offlineDrafts:List<String>,val videoContext:String,val maxOutputTokens:Int,
        val fullDialogue:Boolean=false
    )

    fun needsLocalDraft(cue:VideoCue,hybrid:Boolean):Boolean = !cue.protectedText && cue.translation.isBlank() &&
        (!hybrid || cue.localDraft.isBlank())

    fun needsCloudRefinement(cue:VideoCue):Boolean = !cue.protectedText &&
        (cue.refinementState!="done" || cue.translation.isBlank())

    fun isComplete(cue:VideoCue,hybrid:Boolean):Boolean = cue.protectedText ||
        (cue.translation.isNotBlank() && (!hybrid || cue.refinementState=="done"))

    fun hasUnexpectedCyrillic(text:String,glossary:String=""):Boolean = text.codePoints().anyMatch{codePoint->
        Character.UnicodeScript.of(codePoint)==Character.UnicodeScript.CYRILLIC &&
            !glossary.contains(String(Character.toChars(codePoint)))
    }

    /**
     * Select positions in an already dispatched batch for result acceptance/failure marking only.
     * Context/token metadata is retained and MUST NOT be reused as a subset network request; build
     * that request with nextCloudBatch so its targets and qualityIssue feedback match pending cues.
     */
    fun selectBatch(batch:Batch,positions:List<Int>):Batch {
        require(batch.indices.isNotEmpty() && batch.indices.size==batch.cueIds.size &&
            batch.indices.size==batch.texts.size && batch.indices.size==batch.offlineDrafts.size)
        require(batch.indices.distinct().size==batch.indices.size && batch.indices.sorted()==batch.indices)
        require(positions.isNotEmpty() && positions.distinct().size==positions.size && positions.sorted()==positions &&
            positions.all{it in batch.indices.indices}){"Vị trí câu cần giữ/thử lại không hợp lệ."}
        return batch.copy(indices=positions.map{batch.indices[it]},cueIds=positions.map{batch.cueIds[it]},
            texts=positions.map{batch.texts[it]},offlineDrafts=positions.map{batch.offlineDrafts[it]})
    }

    /**
     * Skip Approved/already finished cues without losing their position as scene context. A large
     * single cue is allowed alone within timeline limits; no source or local draft is truncated.
     */
    fun nextCloudBatch(cues:List<VideoCue>,hybrid:Boolean,maxCues:Int=LEGACY_DEFAULT_CUES,maxSourceChars:Int=SOURCE_BUDGET):Batch?{
        require(maxCues in 1..MAX_CUES && maxSourceChars in 1..SOURCE_BUDGET)
        val pending=cues.indices.filter{index->
            val cue=cues[index]
            !cue.protectedText && if(hybrid)needsCloudRefinement(cue) else cue.translation.isBlank()
        }
        if(pending.isEmpty())return null
        val indices=mutableListOf<Int>();var sourceChars=0;var draftChars=0
        for(index in pending){
            val cue=cues[index];val draft=if(hybrid)cue.localDraft.ifBlank{cue.translation} else ""
            require(cue.source.isNotBlank() && cue.source.length<=MAX_SINGLE_SOURCE){"Câu nguồn quá dài hoặc rỗng; hãy sửa/chia câu trước khi dịch."}
            require(!hybrid || draft.isNotBlank()){"Tạo bản nháp local trước khi Gemini hiệu đính; chưa gửi nhóm này."}
            require(draft.length<=MAX_SINGLE_DRAFT){"Bản nháp quá dài; hãy chia câu trước khi hiệu đính."}
            if(indices.isNotEmpty() && (indices.size>=maxCues || sourceChars+cue.source.length>maxSourceChars || draftChars+draft.length>DRAFT_BUDGET))break
            indices+=index;sourceChars+=cue.source.length;draftChars+=draft.length
        }
        return buildBatch(cues,hybrid,indices)
    }

    /** Fresh network request for only the rejected positions, with current accepted-row context. */
    fun repairBatch(cues:List<VideoCue>,originalBatch:Batch,badPositions:List<Int>,hybrid:Boolean):Batch {
        validateMapping(cues,originalBatch)
        val selected=selectBatch(originalBatch,badPositions)
        require(selected.indices.all{index->
            val cue=cues[index]
            !cue.protectedText && if(hybrid)needsCloudRefinement(cue) else cue.translation.isBlank()
        }){"Chỉ hiệu chỉnh lại những câu chưa hoàn tất; không gửi lại câu đã duyệt hoặc đã xong."}
        val repair=buildBatch(cues,hybrid,selected.indices)
        return repair.copy(videoContext=repair.videoContext.replaceFirst("{\"schema\":1,","{\"schema\":1,\"qualityRepair\":true,"))
    }

    private fun buildBatch(cues:List<VideoCue>,hybrid:Boolean,indices:List<Int>):Batch {
        require(indices.size in 1..MAX_CUES && indices.distinct().size==indices.size && indices.sorted()==indices &&
            indices.all{it in cues.indices})
        val rows=indices.map{cues[it]}
        rows.forEach{cue->
            val draft=if(hybrid)cue.localDraft.ifBlank{cue.translation} else ""
            require(cue.source.isNotBlank() && cue.source.length<=MAX_SINGLE_SOURCE)
            require(!hybrid || draft.isNotBlank())
            require(draft.length<=MAX_SINGLE_DRAFT)
        }
        val sourceChars=rows.sumOf{it.source.length}
        val draftChars=rows.sumOf{if(hybrid)it.localDraft.ifBlank{it.translation}.length else 0}
        require(rows.size==1 || sourceChars<=SOURCE_BUDGET && draftChars<=DRAFT_BUDGET)
        val previous=(maxOf(0,indices.first()-3) until indices.first()).toList()
        val inside=(indices.first()..indices.last()).filter{it !in indices}
        val following=(indices.last()+1 until minOf(cues.size,indices.last()+3)).toList()
        // Prefer immediately preceding context, then any skipped Approved/refined lines inside the
        // span, then following lines. Each array stays chronological and keeps whole cue content.
        var contextChars=0
        fun select(candidates:List<Int>,nearestFirst:Boolean=false):List<Int>{
            val chosen=mutableListOf<Int>()
            for(index in if(nearestFirst)candidates.asReversed() else candidates){
                val cue=cues[index];val text=cue.translation
                val cost=cue.source.length+text.length
                if(contextChars+cost>CONTEXT_BUDGET)continue
                contextChars+=cost;chosen+=index
            }
            return chosen.sorted()
        }
        val before=select(previous,true);val between=select(inside);val after=select(following)
        fun contextRows(indices:List<Int>)=indices.joinToString(",",prefix="[",postfix="]"){index->
            val cue=cues[index]
            "{\"index\":$index,\"startMs\":${cue.startMs},\"endMs\":${cue.endMs},\"source\":${quote(cue.source)},\"vietnamese\":${quote(cue.translation)},\"approved\":${cue.approved}}"
        }
        val targets=rows.mapIndexed{i,cue->
            val draft=if(hybrid)cue.localDraft.ifBlank{cue.translation} else ""
            "{\"index\":${indices[i]},\"startMs\":${cue.startMs},\"endMs\":${cue.endMs},\"source\":${quote(cue.source)},\"localDraft\":${quote(draft)},\"qualityIssue\":${quote(cue.error)}}"
        }.joinToString(",",prefix="[",postfix="]")
        val context="{\"schema\":1,\"task\":${quote(if(hybrid)"refine_local_draft_from_original_chinese" else "translate_original_chinese")},\"previous\":${contextRows(before)},\"inside\":${contextRows(between)},\"following\":${contextRows(after)},\"targets\":$targets}"
        require(context.length<=24_000){"Ngữ cảnh video quá lớn; hãy chia câu."}
        val tokenLimit=(sourceChars*5+rows.size*64).coerceIn(1024,8192)
        return Batch(indices.toList(),rows.map{it.id},rows.map{it.source},rows.map{if(hybrid)it.localDraft.ifBlank{it.translation} else ""},context,tokenLimit)
    }

    /** Validate every response before replacing any cue. Never change Chinese/timing/Approved. */
    fun applyCloudResult(cues:List<VideoCue>,batch:Batch,translations:List<String>,model:String,hybrid:Boolean,glossary:String=""):List<VideoCue>{
        validateMapping(cues,batch)
        require(translations.size==batch.indices.size && translations.all{it.isNotBlank() && it.length<=8000}){
            "Số câu trả về không khớp timeline hoặc có câu rỗng/quá dài; chưa lưu nhóm này."
        }
        require(model.isNotBlank())
        require(translations.none{hasUnexpectedCyrillic(it,glossary)}){"Gemini chèn ký tự Kirill không có trong từ điển. Bản nháp vẫn được giữ; bấm Tiếp tục hoặc sửa câu."}
        val updated=cues.toMutableList()
        batch.indices.forEachIndexed{i,index->
            val cue=cues[index]
            if(!cue.protectedText)updated[index]=cue.copy(translation=translations[i].trim(),model=model,error="",
                qualityFlags=cue.qualityFlags.filterNot{it=="TRANSLATION_NEEDS_REVIEW"},
                localDraft=if(hybrid)cue.localDraft.ifBlank{batch.offlineDrafts[i]} else cue.localDraft,
                refinementState=if(hybrid)"done" else cue.refinementState)
        }
        return updated
    }

    /** Local-only text remains visible and explicitly pending when cloud fails; resume retries it. */
    fun markCloudFailure(cues:List<VideoCue>,batch:Batch,message:String,hybrid:Boolean):List<VideoCue>{
        validateMapping(cues,batch)
        val updated=cues.toMutableList()
        batch.indices.forEachIndexed{i,index->
            val cue=cues[index]
            if(!isComplete(cue,hybrid))updated[index]=if(hybrid){
                val draft=cue.localDraft.ifBlank{batch.offlineDrafts[i]}
                cue.copy(translation=cue.translation.ifBlank{draft},localDraft=draft,refinementState="pending",error=message.take(400))
            }else cue.copy(error=message.take(400))
        }
        return updated
    }
    private fun validateMapping(cues:List<VideoCue>,batch:Batch){
        require(batch.indices.isNotEmpty() && batch.indices.size==batch.cueIds.size && batch.indices.size==batch.texts.size && batch.indices.size==batch.offlineDrafts.size)
        require(batch.indices.distinct().size==batch.indices.size && batch.indices.sorted()==batch.indices)
        batch.indices.forEachIndexed{i,index->
            require(index in cues.indices && cues[index].id==batch.cueIds[i] && cues[index].source==batch.texts[i]){
                "Timeline đã thay đổi; không lưu phản hồi vào câu khác."
            }
        }
    }
    private fun quote(value:String):String=buildString{
        append('"')
        for(char in value)when(char){
            '"'->append("\\\"");'\\'->append("\\\\");'\n'->append("\\n");'\r'->append("\\r");'\t'->append("\\t");'\b'->append("\\b");'\u000c'->append("\\f")
            else->if(char<' ')append("\\u"+char.code.toString(16).padStart(4,'0')) else append(char)
        }
        append('"')
    }
}
