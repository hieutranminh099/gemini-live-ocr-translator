package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject

/** A short, exact-text quorum rejects one-frame OCR fragments without merging distant occurrences. */
class VideoOcrStabilizer(existing:List<VideoCue> = emptyList(),saved:JSONObject=JSONObject(),private val requiredObservations:Int=3) : VideoOcrTimeline {
    private data class Observation(val ms:Long,val text:String)
    private val accumulator=VideoCueAccumulator(existing,saved)
    override val cues get()=accumulator.cues
    private val recent=ArrayDeque<Observation>()
    private var last=saved.optLong("scanLast",saved.optLong("last",-1))
    private var accepted=identity(saved.optString("source"))
    private var blankStart=saved.optLong("stableBlankStart",-1)
    private var blanks=saved.optInt("stableBlanks")
    override val needsConfirmation get()=blanks>0 || recent.lastOrNull()?.let{identity(it.text)!=accepted}==true
    init { require(requiredObservations in 2..3);saved.optJSONArray("stableRecent")?.let{rows->
        require(rows.length()<=5)
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);recent.addLast(Observation(row.getLong("ms"),row.getString("text")))}
    } }
    override fun add(ms:Long,text:String?) {
        require(ms>=last)
        val duplicateFrame=ms==last;last=ms
        if(requiredObservations==2 && duplicateFrame)return
        while(recent.isNotEmpty() && recent.first().ms<ms-400)recent.removeFirst()
        if(text==null)return
        val cleaned=VideoTimeline.identity(text);val sourceIdentity=identity(cleaned)
        if(sourceIdentity.isEmpty()){
            recent.clear()
            if(accepted.isNotEmpty()){
                if(blankStart<0)blankStart=ms
                blanks++
                if(blanks>=3){accumulator.add(blankStart,"");accumulator.add(ms,"");accepted="";blankStart=-1;blanks=0}
            }
            return
        }
        blankStart=-1;blanks=0
        recent.addLast(Observation(ms,cleaned));while(recent.size>5)recent.removeFirst()
        val winner=if(requiredObservations==2){
            // Only the latest exact candidate may advance a draft occurrence. An accepted old
            // group must not shadow a short following subtitle within the same 400 ms window.
            recent.filter{identity(it.text)==sourceIdentity}.takeIf{it.size>=requiredObservations}
        }else recent.groupBy{identity(it.text)}.entries.firstOrNull{it.value.size>=requiredObservations}?.value
        if(winner==null)return
        val winnerKey=identity(winner.first().text)
        if(winnerKey==accepted)return
        // The timestamps belong to observations, not the later decision or translation completion.
        val onset=winner.first().ms
        val representative=winner.minBy{it.text.length}.text
        accumulator.add(onset,representative)
        accepted=winnerKey
        if(requiredObservations==2){
            // Evidence before this transition cannot certify a later recurrence after one frame.
            recent.clear();recent.addAll(winner)
        }
    }
    override fun snapshot():JSONObject=accumulator.snapshot().put("scanLast",last)
        .put("stableBlankStart",blankStart).put("stableBlanks",blanks)
        .put("stableRecent",JSONArray(recent.map{JSONObject().put("ms",it.ms).put("text",it.text)}))
    override fun finish(end:Long):List<VideoCue>{
        return accumulator.finish(end)
    }
    companion object {
        internal fun identity(text:String):String {
            // Keep internal punctuation, negative signs and digits. Only terminal OCR decoration
            // and whitespace are ignored; e.g. 3.5, 35, -3 and 3 remain distinct identities.
            var result=VideoTimeline.identity(text).replace(Regex("(?<=[\u3400-\u9fff])\\s+(?=[\u3400-\u9fff])"),"")
                // NFKC maps fullwidth comma/period to ASCII. A comma read as a period between
                // the same Han words is common video OCR jitter; retain the separator, digits,
                // negation, question marks and every Han/Latin character. No fuzzy text match.
                .replace(Regex("(?<=[\u3400-\u9fff])[,。.](?=[\u3400-\u9fff])"),",")
                .trimEnd{it in ",，。!?！？;；:：()（）[]【】\"'"}
            if(result.endsWith('-') && result.dropLast(1).lastOrNull()?.isLetter()==true)result=result.dropLast(1)
            return result
        }
    }
}
