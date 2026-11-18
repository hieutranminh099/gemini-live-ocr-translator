package com.gemini.ocr.movie

import com.gemini.ocr.video.VideoMetadata
import org.junit.Assert.*
import org.junit.Test

class MovieVerifiedMetadataTest {
    @Test fun cachesOnlyEqualCompleteFingerprintsAndEvictsOldEntries() {
        val cache=MovieVerifiedMetadata(2);var reads=0
        fun read()=VideoMetadata(1000,720,1280).also{reads++}
        val a="a".repeat(64) to 100L
        assertEquals(cache.get(a,::read),cache.get(a,::read));assertEquals(1,reads)
        cache.get(a.first to 101L,::read);cache.get("b".repeat(64) to 100L,::read)
        cache.get(a,::read);assertEquals(4,reads)
    }
    @Test fun failedOrInvalidReadsAreNotCached() {
        val cache=MovieVerifiedMetadata();val key="a".repeat(64) to 100L
        try{cache.get(key){error("read failed")};fail()}catch(_:IllegalStateException){}
        cache.get(key){VideoMetadata(0,0,0)}
        assertEquals(123L,cache.get(key){VideoMetadata(123,720,1280)}.durationMs)
    }
}
