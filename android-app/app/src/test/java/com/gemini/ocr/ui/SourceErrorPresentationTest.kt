package com.gemini.ocr.ui

import com.gemini.ocr.video.VideoApiException
import com.gemini.ocr.video.VideoStagedPipeline
import org.junit.Assert.*
import org.junit.Test

class SourceErrorPresentationTest {
    @Test fun persistedAndBracketedFrameTimeoutsExplainVideoRecoveryRatherThanNetwork() {
        for (message in listOf(
            "FRAME_DECODE_TIMEOUT: Bộ giải mã không tiến triển trong 30 giây.",
            "[FRAME_DECODE_TIMEOUT] Bộ giải mã không tiến triển trong 30 giây.",
            " frame_decode_timeout: tiến độ đã lưu",
            "FRAME_DECODE_INCOMPLETE: Chưa đọc hết track hình ảnh."
        )) {
            val error = friendlyError(message)
            assertEquals("Đọc video bị gián đoạn", error.title)
            assertTrue(error.guidance.contains("mốc đã lưu"))
            assertFalse(error.guidance.contains("mạng", ignoreCase = true))
            assertFalse(error.guidance.contains("khóa", ignoreCase = true))
        }
    }

    @Test fun codecDiagnosticsCannotBeMistakenForHttpStatusInSourceTimestamp() {
        for (code in listOf("FRAME_CODEC_STATE", "FRAME_CODEC_ERROR")) {
            for (timestamp in listOf(429_000, 401_000, 403_000)) {
                val message = "[$code] operation=dequeue_output; sourceMs=$timestamp; tiến độ đã lưu"
                assertEquals("Đọc video bị gián đoạn", friendlyError(message).title)
            }
        }
    }

    @Test fun invalidFramesAndRegionsHaveDistinctLocalGuidance() {
        for (code in listOf("FRAME_NULL", "FRAME_INVALID")) {
            val error = friendlyError("$code: Không lấy được ảnh nguồn.")
            assertEquals("Chưa lấy được khung hình", error.title)
            assertFalse(error.guidance.contains("mạng", ignoreCase = true))
        }
        val region = friendlyError("[ROI_INVALID] Vùng chữ không hợp lệ.")
        assertEquals("Vùng phụ đề cần kiểm tra", region.title)
        assertTrue(region.guidance.contains("Thiết lập phụ đề"))
    }

    @Test fun actualApiAndVisionTimeoutsStillHaveNetworkGuidance() {
        for (message in listOf("[API_TIMEOUT] Kết nối dịch vụ bị gián đoạn.",
            "[VISION_TIMEOUT] Gemini chưa nhận chữ xong.", "API_NETWORK: Kết nối bị ngắt.")) {
            val error = friendlyError(message)
            assertEquals("Kết nối đang gián đoạn", error.title)
            assertTrue(error.guidance.contains("mạng", ignoreCase = true))
        }
    }

    @Test fun existingAuthenticationQuotaAndDriveGuidanceIsPreserved() {
        assertEquals("Cần kiểm tra kết nối dịch", friendlyError("Gemini chưa cho phép truy cập (HTTP 401). Kiểm tra khóa.").title)
        assertEquals("Đang chờ hạn mức dịch", friendlyError("Gemini đang giới hạn lượt gọi (429).").title)
        assertEquals("Chưa thể kết nối Google Drive", friendlyError("Google authorization cần kiểm tra quyền.").title)
    }

    @Test fun unexplainedIllegalStateIsNotInventedAsACodecOrNetworkFailure() {
        assertEquals("Thao tác chưa hoàn tất", friendlyError("IllegalStateException").title)
        assertEquals("STAGE_NEEDS_REVIEW", VideoStagedPipeline.errorCode(IllegalStateException()))
        assertEquals("STAGE_NEEDS_REVIEW", VideoStagedPipeline.errorCode(IllegalStateException("IllegalStateException")))
    }

    @Test fun sourceCodesSurviveWorkerWrapperAndOldCheckpointMessageShapes() {
        for (code in listOf("FRAME_CODEC_STATE", "FRAME_CODEC_ERROR", "FRAME_DECODE_TIMEOUT",
            "FRAME_DECODE_INCOMPLETE", "FRAME_NULL", "FRAME_INVALID", "ROI_INVALID")) {
            for (message in listOf("$code: tiến độ đã lưu", "[$code] tiến độ đã lưu")) {
                assertEquals(code, VideoStagedPipeline.errorCode(IllegalStateException(message)))
            }
        }
        assertEquals("API_AUTH", VideoStagedPipeline.errorCode(VideoApiException("API_AUTH")))
    }
}
