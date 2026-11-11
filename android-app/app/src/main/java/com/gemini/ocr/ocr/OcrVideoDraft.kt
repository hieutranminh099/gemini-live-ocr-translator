package com.gemini.ocr.ocr

import com.gemini.ocr.network.CostOptimizationEngine

/** Actual native OCR evidence for offline video review, never authoritative Live output. */
data class OcrVideoDraftLine(val text:String,val confidence:Float?,val bounds:OcrRecoveryPolicy.Bounds)

data class OcrVideoDraft(
    val text:String, val lines:List<OcrVideoDraftLine>, val minConfidence:Float?,
    val needsReview:Boolean, val flags:List<String>, val detectedHanLines:Int,
    val rejectedHanLines:Int, val nativePasses:Int=1
) {
    companion object {
        private const val MAX_LINES=40
        private const val MAX_CHARS=2000

        /** Keep low/unknown confidence as an explicit draft, without correcting any Han/digit. */
        internal fun from(raw:List<OcrRecoveryPolicy.Line>,width:Int,height:Int):OcrVideoDraft {
            require(width>0 && height>0)
            val flags=linkedSetOf<String>()
            val retained=mutableListOf<OcrVideoDraftLine>()
            var detected=0;var rejected=0;var chars=0
            for(line in raw.sortedWith(compareBy({it.bounds?.top?:Int.MAX_VALUE},{it.bounds?.left?:Int.MAX_VALUE}))){
                if(!OcrRecoveryPolicy.hasHan(line.text))continue
                detected++
                // ML Kit lines are already separate observations. Preserve their line boundaries.
                val text=line.text.replace(Regex("\\s+")," ").trim()
                val bounds=line.bounds?.clipped(width,height)
                if(bounds==null){rejected++;flags+="OCR_GEOMETRY_REJECTED";continue}
                if(CostOptimizationEngine.isOcrNoise(text,"zh",retainSourceFragments=true)){
                    rejected++;flags+="OCR_NOISE_REJECTED";continue
                }
                val cost=text.length+if(retained.isEmpty())0 else 1
                if(text.length>MAX_CHARS || chars+cost>MAX_CHARS || retained.size>=MAX_LINES){
                    rejected++;flags+="OCR_BUDGET_REJECTED";continue
                }
                val confidence=line.confidence.takeIf{it.isFinite() && it in 0.0..1.0}?.toFloat()
                when {
                    confidence==null -> flags+="OCR_CONFIDENCE_UNKNOWN"
                    confidence<OcrRecoveryPolicy.MIN_CONFIDENCE -> flags+="LOW_OCR_CONFIDENCE"
                }
                retained+=OcrVideoDraftLine(text,confidence,bounds);chars+=cost
            }
            return OcrVideoDraft(retained.joinToString("\n"){it.text},retained.toList(),
                retained.mapNotNull{it.confidence}.minOrNull(),flags.isNotEmpty(),flags.toList(),detected,rejected)
        }
    }
}
