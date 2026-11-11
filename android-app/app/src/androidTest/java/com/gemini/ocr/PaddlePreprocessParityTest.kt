package com.gemini.ocr

import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.OcrPreprocessProfile
import com.paddle.ocr.preprocess.DetPreprocessor
import com.paddle.ocr.preprocess.RecPreprocessor
import com.paddle.ocr.util.OpenCVUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class PaddlePreprocessParityTest {
    @Test fun coloredPixelsVerifyBgrDetectorNormalizationForBothProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gemini.ocr.codexbench")
        check(OpenCVUtils.init(context))
        val bgr = Mat(32, 32, CvType.CV_8UC3, Scalar(10.0, 80.0, 230.0))
        try {
            for (profile in OcrPreprocessProfile.entries) {
                val output = DetPreprocessor.preprocess(bgr, 32, "min", 32, "BGR", profile)
                assertArrayEquals(longArrayOf(1, 3, 32, 32), output.shape)
                for ((channel, pixel) in listOf(10, 80, 230).withIndex()) {
                    assertEquals(profile.normalizedDetectorSample(channel, pixel),
                        output.tensorData[channel * 32 * 32].toDouble(), .00001)
                }
            }
        } finally { bgr.release() }
    }

    @Test fun recognitionChannelOrderAndZeroPaddingMatchEachExplicitProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gemini.ocr.codexbench")
        check(OpenCVUtils.init(context))
        val bgr = Mat(32, 32, CvType.CV_8UC3, Scalar(10.0, 80.0, 230.0))
        try {
            for (profile in OcrPreprocessProfile.entries) {
                val output = RecPreprocessor.preprocessBatch(listOf(bgr), profile)
                val width = if (profile == OcrPreprocessProfile.RAPIDOCR_V4) 320 else 48
                assertArrayEquals(longArrayOf(1, 3, 48, width.toLong()), output.shape)
                val channels = if (profile.recognitionUsesRgb) listOf(230, 80, 10) else listOf(10, 80, 230)
                for ((channel, pixel) in channels.withIndex()) {
                    assertEquals(pixel / 127.5 - 1.0, output.tensorData[channel * 48 * width].toDouble(), .00001)
                    if (width > 48) assertEquals(0f, output.tensorData[channel * 48 * width + 48], 0f)
                }
                assertEquals(32, bgr.rows()); assertEquals(32, bgr.cols())
            }
        } finally { bgr.release() }
    }
}
