# Ưu tiên bản giao dùng sớm — 28/10/2026

Theo yêu cầu chủ dự án trong chat ngày 28/10/2026: tạm dừng phát triển tính năng mới để giao một APK dùng được trước. Không tiếp tục backlog 1.8.0 cho đến khi chủ dự án yêu cầu.

Bản đang chuẩn bị: 1.7.5, versionCode 20, package com.gemini.ocr, từ checkpoint đã giao 1.7.4 tại build/milestones/checkpoint-20261027-200026. Chỉ sửa lỗi bảo toàn sửa tay/checkpoint và cỡ chữ phụ đề; không tích hợp blur/local-first/series đang dở của 1.8.0.

Nguồn bản giao và bằng chứng: build/priority-release-1.7.5/. Không reset/clean/chuyển nhánh hoặc ghi đè worktree companion-codex-dev. Toàn bộ mã đang dở của 1.8.0 vẫn nằm nguyên trong worktree đó.

APK dùng cùng khóa ký sản phẩm hiện có, không tạo khóa thay thế, không nhúng API key. Bản 1.8.0 sau này cần versionCode lớn hơn 20 để cập nhật từ bản giao này.

Trạng thái cuối: đã giao dist/priority-release-1.7.5/GeminiOCR-1.7.5.apk; 632 test tự động và 3 test native đạt, APK release đã cài cập nhật BlueStacks. Chưa kiểm ARM64/Gemini cloud mới. Chi tiết và hash ở thư mục bàn giao.
