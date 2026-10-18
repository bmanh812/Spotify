# Checklist phần TV2 Server

Chỉ nền tảng bootstrap đã được tạo. Các checkbox dưới đây mô tả việc cần làm tiếp;
compile hoặc nhận NOT_IMPLEMENTED không đồng nghĩa đã có room/streaming.

## S0 Chuẩn bị và contract

- [x] Maven/Java 21, cấu hình TCP và logging.
- [x] Package Server, domain records và service interfaces.
- [x] Bootstrap TCP/framing với bounded workers và cleanup.
- [x] Checkpoint 1A verify 181 test; 1B review và các quyết định A01–A16 đã duyệt.
- [x] Checkpoint 1C áp dụng contract: typed errors, policies tách wire limits, domain audio và docs/tests.
- [x] Checkpoint 1D: Protocol v1 FROZEN FOR P0 IMPLEMENTATION, giữ nguyên A01–A16; không tự bắt đầu S1.
- [ ] Chuẩn bị 3 WAV hợp lệ và nguồn/quyền sử dụng.

## S1 Hoàn thiện tầng kết nối theo protocol đã freeze

- [ ] Tích hợp common payload validator vào dispatcher; bind client identity với socket.
- [ ] Connection registry; direct response/push đúng requestId.
- [ ] Writer queue hữu hạn, ưu tiên control, không block room lock.
- [ ] Timeout, malformed input, overload và shutdown tests.

## S2 Room core

- [ ] Implement RoomManager và RoomSession với per-room lock.
- [ ] Create/join/leave, code duy nhất, capacity và participant snapshot.
- [ ] Host rời đóng phòng; Member rời không ảnh hưởng phòng khác.
- [ ] Test 3 TCP Client cùng phòng và 2 phòng độc lập.

## S3 Track catalog và shared queue

- [ ] Implement TrackRepository, validate WAV/PCM và metadata.
- [ ] QueueItem ID, queue add, queue limits, track-not-found và empty queue.
- [ ] Bounded request-result cache cho mọi mutation theo protocol (không chỉ QUEUE_ADD).
- [ ] Retry idempotent; tăng roomVersion và broadcast full snapshot.

## S4 Playback authoritative

- [ ] Host-only PLAY/PAUSE/SEEK/NEXT; reject Member và stale version.
- [ ] Position/anchor/server time; scheduled start theo contract TV3.
- [ ] Serialize playback mutations với EOF: normalize due EOF trước rồi validate expectedRoomVersion.
- [ ] streamEpoch khi seek/đổi bài; EOF do Server quyết định.
- [ ] Test concurrency, duplicate request, stale state và queue empty.

## S5 Audio streaming

- [ ] Parse WAV, không giả định header 44 byte; index PCM theo sample frame.
- [ ] Implement AudioChunkService, validate membership/track/epoch/chunk index.
- [ ] Service trả AudioChunkData; handler tạo wire metadata/echo incoming requestId, không sinh ID trong service.
- [ ] Metadata + raw PCM binary; chunk cuối, seek offset, bounded request window.
- [ ] Hash PCM test và Client TV1 phát audio qua TCP thật.

## S6 Tích hợp và nghiệm thu

- [ ] Ghép Client thật, handler timestamps/metrics và periodic sync state.
- [ ] Slow Client không khóa Client khác; socket, stream và thread được giải phóng.
- [ ] Full suite, multi-room, seek khi chunk cũ về, close/disconnect và clean-machine.
- [ ] README, protocol/architecture khớp code và demo có log/version/epoch.

Nên hoàn thành từng chặng, chạy test và review trước khi qua chặng tiếp theo.
Chỉ tiếp tục S1 TCP/connection management khi có prompt mới sau Checkpoint 1D/freeze,
sau đó mới implement RoomManager. Không viết adaptive algorithm ở Server.
