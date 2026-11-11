package com.gemini.ocr.video

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoOcrFrameTest {
    private val full=listOf(0f,0f,1f,1f)
    @Test fun bulkRowsRespectDecoderStrideCropOriginAndLeaveTheNativeBufferPositionAlone(){
        val plane=ByteBuffer.allocate(7*4)
        for(y in 0..1)for(x in 0..2)plane.put((y+1)*7+x+1,(1+y*3+x).toByte())
        plane.position(2)
        val frame=VideoLumaCrop.sample(50,plane,7,1,1,1,3,2,full,0)
        assertEquals(3,frame.width);assertEquals(2,frame.height)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6),frame.luma)
        assertEquals(2,plane.position())
        plane.put(8,99.toByte())
        assertEquals("Retained samples must own their bytes after MediaCodec releases the frame",1,frame.luma[0].toInt())
    }
    @Test fun quarterTurnMappingsMatchTheDisplayRoiForAllSupportedRotations(){
        val plane=ByteBuffer.wrap(byteArrayOf(1,2,3,99,99,4,5,6,99,99))
        val expected=mapOf(0 to byteArrayOf(1,2,3,4,5,6),90 to byteArrayOf(4,1,5,2,6,3),
            180 to byteArrayOf(6,5,4,3,2,1),270 to byteArrayOf(3,6,2,5,1,4))
        for((rotation,bytes) in expected){
            val f=VideoLumaCrop.sample(0,plane,5,1,0,0,3,2,full,rotation)
            assertEquals(if(rotation%180==0)3 else 2,f.width)
            assertEquals(if(rotation%180==0)2 else 3,f.height)
            assertArrayEquals("rotation $rotation",bytes,f.luma)
        }
    }
    @Test fun fallbackRespectsPixelStrideAndCropsTheRequestedBand(){
        val plane=ByteBuffer.allocate(8*3)
        for(y in 0..2)for(x in 0..3)plane.put(y*8+x*2,(y*4+x+1).toByte())
        val f=VideoLumaCrop.sample(0,plane,8,2,0,0,4,3,listOf(.25f,1f/3f,.75f,1f),0)
        assertEquals(2,f.width);assertEquals(2,f.height)
        assertArrayEquals(byteArrayOf(6,7,10,11),f.luma)
    }
    @Test fun tinyTwoLineHardsubBandIsEnlargedBeforeConfidenceFilteringWithinThePixelBudget(){
        assertEquals(1280 to 216,VideoOcrInputSize.forRoi(640,108))
        assertEquals(1280 to 720,VideoOcrInputSize.forRoi(1280,720))
        for((w,h) in listOf(1920 to 150,4096 to 2000,1280 to 179,10000 to 10000)){
            val target=VideoOcrInputSize.forRoi(w,h)
            assertTrue(target.first.toLong()*target.second<=VideoOcrInputSize.MAX_PIXELS)
            assertTrue(target.first<=2560 && target.second<=1440)
            assertTrue(kotlin.math.abs(target.first.toDouble()/target.second-w.toDouble()/h)<.05)
        }
    }
    @Test fun lumaBitmapKeepsBlackWhiteContrastAndDoesNotKeepARecycledNativeFrame(){
        val frame=VideoOcrFrame(0,2,1,byteArrayOf(16,235.toByte()))
        val bitmap=frame.bitmap()
        try {
            assertEquals(4,bitmap.width);assertEquals(2,bitmap.height)
            assertEquals(0,Color.red(bitmap.getPixel(0,0)))
            assertEquals(255,Color.red(bitmap.getPixel(3,0)))
            assertEquals(Color.red(bitmap.getPixel(3,0)),Color.green(bitmap.getPixel(3,0)))
        }finally{bitmap.recycle()}
        assertArrayEquals(byteArrayOf(16,235.toByte()),frame.luma)
    }
}
