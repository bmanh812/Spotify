# Điểm cần thống nhất giữa TV1 TV2 và TV3

Ngày: 20/10/2026. Tài liệu bàn giao để các thành viên phản hồi; không ghi nhận như đã được họ phê duyệt.
Các câu hỏi dưới đây là hợp đồng tích hợp/runtime, không mở lại A01–A16 hoặc wire protocol đã freeze.

## TV2 xác nhận

1. Khi nào Server S1–S5 sẵn sàng để test create/join/leave, Host commands, catalog và audio TCP?
   Hiện BootstrapDispatcher trả NOT_IMPLEMENTED. TV1 dùng fixture trong test, không sửa Server.
2. Deployment dùng maxOutstandingChunks=4 và frame ceiling=65536 như defaults hay giới hạn thấp hơn?
   Không có wire negotiation; hai phía cần cấu hình tương thích khi tích hợp.
3. Xác nhận retention/admission cache thực tế và retry window phối hợp trước khi bật tự retry mutation.
   TV1 hiện không auto-retry; timeout mutation được hiển thị UNKNOWN, cần disconnect để giải phóng binding nếu lifecycle chưa rõ.
4. Cung cấp ít nhất ba WAV hợp lệ, catalog và endpoint chạy thử; TV2 giữ ownership parser/source/license.

## TV3 xác nhận trước khi ghép phát đồng bộ

1. Kiểu Java, threading và ownership của ClockSynchronizer, BufferController, PlaybackSynchronizer chưa có trong source.
   TV1 bàn giao engine/buffer tại client/audio; TV3 không cần viết lại engine hoặc TCP Client.
2. Đề xuất adapter gọi engine reset(room,track,epoch,targetFrame), offer(chunk), pump(maxFrames), start/pause,
   snapshot và close; reset/offer/pump/control phải serialize trên cùng audio worker.
3. TV3 chọn điểm hội nhập tương lai, lấy Server time ước lượng và quyết định prefetch; TV1 thực thi IO/audio.
   Cần thống nhất lịch callback, target/capacity, low/high watermark, local generation, cancel in-flight và resync event.
4. Thống nhất snapshot: actualPosition theo processed frames, app buffer riêng line buffer; timestamp monotonic;
   trạng thái thiếu PCM và dữ liệu chưa sẵn sàng. Không tính bytes downloaded thành vị trí đã phát.
5. Xác nhận cách đo t1/t4 và adapter nhận timestamp transport trước khi viết estimator/metrics reporting.

TV1 đã bàn giao chữ ký thật tại [member-1-client-contract.md](member-1-client-contract.md).
Incoming.request chứa PING với t1 chụp tại writer; receivedEpochMs là t4 và sentNanos/receivedNanos đo elapsed cục bộ.
Đây là dữ liệu đầu vào, chưa có thuật toán clock estimation. Snapshot engine đã có appContiguousFrames/capacityFrames
để policy không coi out-of-order PCM là dữ liệu có thể phát ngay.

## Phần dừng chờ phản hồi

Không triển khai thuật toán TV3; không nối buffer policy, scheduled start, late join hoặc drift correction
bằng clock local thay cho Server clock. Không công bố audio end-to-end hoặc nghiệm thu 3 Client/2 phòng
khi Server chưa implement. Sau phản hồi, ghi quyết định và test ghép trước khi chuyển trạng thái Verified.

Ngoài điểm phối hợp liên thành viên, TV1 còn phải chạy smoke WAV/SourceDataLine thật và clean-machine trước nghiệm thu cuối.
Test fake sink/fixture hiện tại không thay các bước này. Chưa có lời xác nhận từ TV2 hoặc TV3 trong đợt bàn giao này.

## Mẫu phản hồi

TV2: endpoint/commit; frame/window limits; dedup retention/retry policy; WAV fixtures; thời điểm test ghép.

TV3: interface/commit; thread callback; clock domain; target/capacity/watermarks; resync/cancel; metrics schema.
