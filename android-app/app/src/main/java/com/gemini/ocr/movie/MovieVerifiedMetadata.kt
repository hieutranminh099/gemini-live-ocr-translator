package com.gemini.ocr.movie

import com.gemini.ocr.video.VideoMetadata

/** Only call after hashing the whole source and matching its frozen SHA-256 and byte count.
 * A different URI can reuse container metadata only if its complete bytes were verified equal.
 * This never caches access permission, a successful hash check, subtitles or watch progress.
 */
internal class MovieVerifiedMetadata(private val capacity:Int=16) {
    private val entries=LinkedHashMap<Pair<String,Long>,VideoMetadata>(capacity,0.75f,true)
    @Synchronized fun get(verified:Pair<String,Long>,read:()->VideoMetadata):VideoMetadata {
        entries[verified]?.let{return it}
        val metadata=read()
        if(metadata.durationMs>0 && metadata.width>0 && metadata.height>0) {
            entries[verified]=metadata
            if(entries.size>capacity)entries.remove(entries.keys.first())
        }
        return metadata
    }
    companion object { val process=MovieVerifiedMetadata() }
}
