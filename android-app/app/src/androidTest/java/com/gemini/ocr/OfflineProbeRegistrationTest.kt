package com.gemini.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** No download/inference: verifies the candidate registrar exists in the target APK. */
@RunWith(AndroidJUnit4::class)
class OfflineProbeRegistrationTest {
    @Test fun targetApkCanCreateAndReleaseTheOnDeviceTranslator() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.gemini.ocr.codexbench")
        AndroidOfflineProbe().close()
    }
}
