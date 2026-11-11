package com.gemini.ocr.capture

import com.gemini.ocr.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveRequestOwnershipTest {
    @Test fun cancelledOldOwnerCannotClearTheNextSubtitleRequest() {
        val controller=Robolectric.buildService(ScreenCaptureService::class.java).create()
        val service=controller.get()
        try {
            val pipeline=ReflectionHelpers.getField<SubtitlePipeline>(service,"pipeline")
            pipeline.updateRegion("roi");pipeline.observe("first")
            val old=pipeline.request("1")!!
            pipeline.observe("next");val next=pipeline.request("2")!!
            ReflectionHelpers.setField(service,"pendingId","2");ReflectionHelpers.setField(service,"pendingToken",next)
            ReflectionHelpers.setField(service,"pendingSignature","next");ReflectionHelpers.setField(service,"sentSignature","next")
            val method=ScreenCaptureService::class.java.getDeclaredMethod("releaseStandaloneRequest",SubtitlePipeline.RequestToken::class.java).apply{isAccessible=true}
            assertEquals(false,method.invoke(service,old));assertEquals("2",ReflectionHelpers.getField(service,"pendingId"))
            assertTrue(pipeline.accepts(next))
            assertEquals(true,method.invoke(service,next));assertEquals("",ReflectionHelpers.getField(service,"pendingId"))
            assertNotNull("The next request must not remain wedged",pipeline.request("3"))
        }finally{controller.destroy();SessionState.running.value=false}
    }
}
