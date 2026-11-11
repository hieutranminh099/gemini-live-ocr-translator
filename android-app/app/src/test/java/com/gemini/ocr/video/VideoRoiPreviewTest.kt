package com.gemini.ocr.video
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.gemini.ocr.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoRoiPreviewTest {
    @Test fun previewCannotCompeteWithAnActiveLiveSessionOrOpenTheSource():Unit=runBlocking {
        SessionState.running.value=true
        try {
            try{VideoRoiPreview.recognize(ApplicationProvider.getApplicationContext<Context>(),
                VideoProject("fixture","fixture","file:///must-not-be-opened",1000,640,360),0,VideoSubtitleFrame.BOTTOM);fail()}
            catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("Tạm dừng dịch trực tiếp"))}
        }finally{SessionState.running.value=false}
    }
}
