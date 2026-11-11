package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class VideoAudioPcmTest {
    @Test fun stereoDownmixAnd48kResampleKeepMediaTimestamp() {
        val pcm=ByteBuffer.allocate(4800*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4800){i->val value=(sin(2*PI*1000*i/48000)*12000).toInt().toShort();pcm.putShort(value);pcm.putShort(value)}
        pcm.flip()
        val block=VideoPcm16k().convert(pcm,48000,2,2,2_000_000)
        assertEquals(32000L,block.firstSample);assertEquals(1600,block.samples.size)
        repeat(1600){i->assertEquals((sin(2*PI*1000*i/16000)*12000/32768).toFloat(),block.samples[i],.0001f)}
    }
    @Test fun repeatedCodecPtsNeverRepeatAudioAndSeekDropsEarlierSamples() {
        val converter=VideoPcm16k(1600)
        val pcm=ByteBuffer.allocate(3200*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(3200){pcm.putShort(1000)};pcm.flip()
        val first=converter.convert(pcm,16000,1,2,0)
        assertEquals(1600L,first.firstSample);assertEquals(1600,first.samples.size)
        assertEquals(0,converter.convert(pcm,16000,1,2,0).samples.size)
    }
    @Test fun floatPcmSanitizesNonFiniteAndDownmixesWithoutOverflow() {
        val pcm=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(1f).putFloat(-1f).putFloat(1f).apply{flip()}
        val block=VideoPcm16k().convert(pcm,16000,2,4,0)
        assertArrayEquals(floatArrayOf(.5f,0f),block.samples,.00001f)
    }
    @Test fun negativePrimingPtsDoesNotDisplaceAudioAtZero() {
        val converter=VideoPcm16k()
        val priming=ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0,converter.convert(priming,16000,1,2,-10_000).samples.size)
        val audio=ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        repeat(160){audio.putShort(4000)};audio.flip()
        val block=converter.convert(audio,16000,1,2,0)
        assertEquals(0L,block.firstSample)
        assertEquals(160,block.samples.size)
        assertEquals(4000f/32768f,block.samples.first(),.00001f)
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedDecodedBufferIsRejected() {
        VideoPcm16k().convert(ByteBuffer.allocate(16001*2),8000,1,2,0)
    }
}
