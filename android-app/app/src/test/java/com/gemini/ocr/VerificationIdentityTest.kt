package com.gemini.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VerificationIdentityTest {
    @Test fun launcherLabelIdentifiesTheDisposablePackageBeforeTheUserOpensIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = if (BuildConfig.APPLICATION_ID == "com.gemini.ocr.codexbench")
            "Gemini OCR • Thử" else "Gemini OCR"
        assertEquals(expected, context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }
}
