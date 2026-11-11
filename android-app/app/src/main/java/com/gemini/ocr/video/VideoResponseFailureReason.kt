package com.gemini.ocr.video

/** Stable numeric diagnostics only; never carry provider text, subtitle IDs or response bodies. */
internal enum class VideoResponseFailureReason(val id: Int, val label: String) {
    UNKNOWN(0, "Chưa xác định bước đọc phản hồi"),
    ENVELOPE(1, "Cấu trúc phản hồi dịch vụ không hợp lệ"),
    FINISH_REASON(2, "Trạng thái kết thúc phản hồi chưa được hỗ trợ"),
    EMPTY_CONTENT(3, "Phản hồi không có nội dung bản dịch"),
    JSON_SYNTAX(4, "Nội dung bản dịch không phải JSON hợp lệ"),
    SCHEMA(5, "Cấu trúc danh sách bản dịch không hợp lệ"),
    UNKNOWN_ID(6, "Phản hồi chứa mã câu không thuộc nhóm đang dịch"),
    DUPLICATE_ID(7, "Phản hồi lặp mã câu nguồn"),
    SIZE_LIMIT(8, "Phản hồi vượt giới hạn dung lượng an toàn");

    companion object {
        fun fromId(id: Int): VideoResponseFailureReason = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
