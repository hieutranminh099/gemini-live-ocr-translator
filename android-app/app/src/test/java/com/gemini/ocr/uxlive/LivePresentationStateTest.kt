package com.gemini.ocr.uxlive

import com.gemini.ocr.liveNeedsAttention
import com.gemini.ocr.liveStageLabel
import org.junit.Assert.*
import org.junit.Test

class LivePresentationStateTest {
    @Test fun visibleSubtitleRequiresAnActiveUnpausedSession() {
        assertEquals("Sẵn sàng", liveStageLabel(false, false, true, "你好", "Đã dừng", "Chào bạn"))
        assertEquals("Đã tạm dừng", liveStageLabel(true, true, true, "你好", "Đã tạm dừng", "Chào bạn"))
        assertEquals("Đang hiển thị", liveStageLabel(true, false, true, "你好", "Đang hiển thị", "Chào bạn"))
        assertEquals("Đã có bản dịch", liveStageLabel(true, false, false, "你好", "Đang chờ", "Chào bạn"))
    }
    @Test fun missingPermissionAndQuotaRemainActionableRatherThanSuccessful() {
        assertTrue(liveNeedsAttention("Cần quyền hiển thị trên ứng dụng khác"))
        assertTrue(liveNeedsAttention("Gemini đang hết hạn mức"))
        assertEquals("Cần kiểm tra", liveStageLabel(false, false, false, "", "Chưa cấp quyền màn hình"))
        assertEquals("Cần kiểm tra", liveStageLabel(true, false, false, "你好", "Gemini đang hết hạn mức"))
        assertEquals("Đang dịch", liveStageLabel(true, false, false, "你好", "Đang dịch"))
        assertFalse(liveNeedsAttention("Đang chờ phụ đề"))
    }
}
