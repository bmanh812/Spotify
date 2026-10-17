# Kiến trúc đề xuất

Một Maven project, một package dùng chung. Chưa tách microservice hoặc database cho P0.

```text
Swing -> RoomController -> MusicClient -> TCP
                                         |
Server: MusicServer -> ClientHandler -> MessageDispatcher
                                         |
                            RoomManager / QueueService / PlaybackService
                                         |
                            TrackRepository -> AudioChunkService

Client: receiver -> PlaybackBuffer -> PlaybackEngine -> AudioSink
Policy: NetworkMonitor -> ClockSynchronizer / BufferController / PlaybackSynchronizer
```

Chỉ `MusicServer`, handler bootstrap, framing và JSON envelope đã có implementation.
Các interface Server là ranh giới dự kiến, chưa có implementation nghiệp vụ.

## Nguyên tắc khi triển khai tiếp

- Server là nguồn sự thật cho room, queue, track và timeline.
- Client tự ước lượng thời gian Server; không quyết định trạng thái phát chung.
- Một reader/socket; complete frame writes có khóa. Writer hiện ghi đồng bộ;
  writer queue ưu tiên control và slow-client isolation là việc còn phải làm.
- Thread pool và hàng đợi accept-task hiện có giới hạn. Quá tải bootstrap đóng kết nối mới;
  chưa phát `SERVER_BUSY` trước khi đọc được requestId.
- Business state cần per-room lock. Không giữ lock khi đọc audio hoặc ghi socket.
- roomVersion theo room state; streamEpoch dùng cho audio stale sau seek/đổi bài.
- Không sử dụng số byte tải được làm vị trí âm thanh đã phát.
- Buffer hữu hạn, request window hữu hạn, không tải cả bài vào RAM trước khi phát.

## Quy tắc nghiệp vụ đang chờ cả nhóm chốt

Host rời thì đóng phòng; Member rời thì cập nhật participants. P0 không tự reconnect hoặc chuyển Host.
Queue trống: PAUSED; PLAY chọn phần tử đầu nếu có; EOF do Server chuyển bài.
Room state ở RAM; track ở đĩa; restart không phục hồi phòng trong P0.

## Startup và shutdown hiện có

`ServerMain` đọc config, start listener, đợi accept thread. Shutdown đóng listener, socket đang mở,
hủy task còn chờ và đợi worker/accept thread trong giới hạn. Mỗi connection lỗi được đóng riêng.
Future room registry, authenticated connection binding và stream resources chưa được thêm.
