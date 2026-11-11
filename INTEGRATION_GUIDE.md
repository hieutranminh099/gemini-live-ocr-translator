# Tích hợp code từ Codex CLI API hoặc AI khác

Mỗi AI làm trong phạm vi file và thư mục rõ ràng; một bên chịu trách nhiệm tích hợp. Patch/commit từ AI là đầu vào cần review. Quy tắc bảo vệ chức năng nằm tại [AGENTS.md](AGENTS.md); sơ đồ ở [ARCHITECTURE.md](ARCHITECTURE.md), vị trí ở [MODULE_INDEX.md](MODULE_INDEX.md), lệnh ở [TESTING.md](TESTING.md).

## Chuẩn bị và phân công

1. Ghi commit nền bằng `git rev-parse HEAD`, nhánh, `git status --short --branch` và `git worktree list`. Xác định file dirty, chủ sở hữu và phạm vi dự định sửa; không suy ra phiên CLI đã dừng chỉ vì Git sạch.
2. Kiểm tra tài liệu bàn giao/phiên bản nguồn. Không dùng bản APK đã cài hoặc source ở `build/` làm bằng chứng checkout hiện tại có cùng tính năng.
3. Giao mục tiêu, module, file được phép sửa, hợp đồng phải giữ, test chấp nhận và phần ngoài phạm vi. Các AI có thể khảo sát chỉ đọc song song; không cùng ghi một file hoặc dùng chung thư mục output test.
4. Với thay đổi kiến trúc/chồng lấn, dùng worktree mới từ **commit nền đã xác minh**, không tự checkout nhánh ở thư mục gốc. Không dùng worktree của người khác. Sơ đồ `codex-dev`/`gemini-review` hiện có nằm tại [GITHUB_COLLABORATION.md](docs/GITHUB_COLLABORATION.md).

Ví dụ khi cần tạo worktree Git thủ công, thay các placeholder bằng giá trị đã kiểm tra; không chạy nguyên mẫu:

```powershell
git worktree add -b codex/<task-name> <new-worktree-path> <verified-base-sha>
```

Chọn đường dẫn mới không tồn tại trong vùng làm việc được phép. Worktree chỉ có trạng thái commit, **không mang theo thay đổi chưa commit**; không tự commit/stash/copy dirty files để làm nền. Nếu tác vụ phụ thuộc chúng, cần phối hợp với bên đang sở hữu hoặc chờ bàn giao cụ thể. Tạo lại môi trường local theo TESTING, không sao chép khóa/DB/cache cá nhân.

## Gói bàn giao tối thiểu

Bên cung cấp code phải ghi rõ:

- Mục tiêu và trigger trước/sau; base SHA, nhánh, commit/patch nếu có.
- File/symbol sửa, API/protocol/schema/settings/dependency bị ảnh hưởng; lý do nếu có xóa hoặc di chuyển code.
- Bất biến được giữ, rủi ro tương thích/dữ liệu và cách phục hồi patch mà không reset công việc khác.
- Lệnh test thực chạy, số pass/fail/skip, môi trường và phần chưa kiểm. Chỉ đính log lỗi liên quan; giữ log đầy đủ ở output riêng.
- Tài liệu cần cập nhật; không kèm secrets, binary, runtime, dữ liệu người dùng hoặc toàn bộ ngữ cảnh chat.

## Kiểm tra và áp dụng

1. So base SHA với nhánh tích hợp. Nếu lệch, xem diff liên quan trước; không cherry-pick mù hoặc chọn toàn bộ ours/theirs khi conflict.
2. Review danh sách file và patch: ngoài phạm vi, mất chức năng, đổi API/namespace/checkpoint, bảo mật, dependency, cấu hình mặc định, file sinh ra và dữ liệu riêng. Đối chiếu đường Android/Windows nếu chạm hợp đồng chung.
3. Chỉ áp dụng trong worktree nhận riêng, không có thay đổi chồng lấn. Với patch text, có thể chạy `git apply --check <patch-file>` trước `git apply <patch-file>`; không dùng tùy chọn ép. Xung đột phải giải theo hành vi, không ghi đè dirty file.
4. Xem diff sau áp dụng để chắc chỉ có mục tiêu được giao. AI đề xuất không tự động trở thành patch được chấp nhận; code review và kiểm thử vẫn bắt buộc.
5. Chạy test module trước, rồi test tích hợp theo mức ảnh hưởng trong TESTING. Giữ dữ liệu test riêng; thiếu môi trường thì ghi rõ, không suy diễn pass từ lệnh build hoặc log cũ.
6. Kiểm tra chức năng cũ: freshness/cancel, namespace/cache, TM approval/privacy, Live priority, retry, pairing/TLS và client capability fallback theo thay đổi thực tế.
7. Cập nhật hàng MODULE_INDEX khi thêm/đổi vị trí; ARCHITECTURE khi đổi ranh giới/luồng; TESTING khi đổi lệnh/prerequisite. Chạy `python scripts/check_codex_docs.py` và `git diff --check`.
8. Kiểm tra lại Git và chỉ bàn giao diff thuộc tác vụ. Commit/push/merge/phát hành cần quyền đã có cho hành động đó; không gom công việc của phiên khác. Không xóa worktree còn chứa việc cần giữ.

## Cổng hoàn thành

Báo cáo phải phân biệt **đã chạy đạt**, **đã chạy lỗi**, **skip**, **chưa chạy** và lý do. Nếu phát hiện rủi ro kiến trúc lớn, nêu bằng chứng/file/test và kế hoạch chia patch trước khi triển khai. Không dùng một lượt kiểm thử nhỏ để tuyên bố toàn hệ thống hoặc thiết bị thật đã được nghiệm thu.

Đánh giá chi phí bằng cùng một tác vụ có phạm vi tương đương: token đo thực (nếu có), file thực mở, thời gian, số vòng sửa và kết quả test. Không có bộ đếm token hoặc đối chứng thì ghi “không đo được/chưa có đối chứng”; không suy ra mức tiết kiệm từ dung lượng source.
