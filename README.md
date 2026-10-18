# Phòng nghe nhạc đồng bộ với bộ đệm thích ứng

Ứng dụng môn Lập trình mạng: nhiều Client tham gia phòng, dùng shared queue và nghe theo timeline
authoritative của Server. Đồng bộ phát và adaptive buffering là phần kỹ thuật cần triển khai/đánh giá.
Tên repository là `Spotify`; phạm vi sản phẩm là phòng nghe nhạc, không phải bản sao đầy đủ Spotify.

## Trạng thái hiện tại

**Protocol v1 = FROZEN FOR P0 IMPLEMENTATION (Checkpoint 1D).**
Giữ nguyên các quyết định A01–A16 đã duyệt; đây vẫn là bộ khung, không phải runtime nghiệp vụ hoàn chỉnh.

Đã có:

- Java 21/Maven, cấu hình và cấu trúc package theo ba phần việc.
- TCP Server khởi động được, thread pool/queue hữu hạn, đóng socket và shutdown.
- Framing `[4-byte big-endian bodyLength][1-byte frameType][payload]`.
- JSON envelope, 22 message giữ nguyên, typed payload/validator, safe correlation errors và dispatcher bootstrap.
- Shared RoomState/track/chunk model, binary audio codec; Server service interfaces chưa có implementation.
- Contract tests, examples executable, framing/envelope, cấu hình và TCP bootstrap thật.

**Chưa có** create/join room, queue, kiểm tra quyền Host, timeline, stream audio, Client Swing,
playback engine, fixed/adaptive buffer, clock sync, drift correction hoặc experiment.
Request hợp lệ hiện nhận `ERROR/NOT_IMPLEMENTED` cùng `requestId`; đây không phải xử lý nghiệp vụ.
Bootstrap chưa gọi payload validator; khi viết transport/handler tiếp phải tích hợp common validation.
Codec/direction guard có thể phân biệt UNSUPPORTED_MESSAGE có correlation với input hỏng phải đóng socket;
bootstrap chưa dùng error context này và vẫn giữ behavior scaffold, chưa là transport hoàn chỉnh theo contract.
`ClientMain` chỉ in thông báo placeholder. Không thể nghe nhạc với bản khung này.

## Yêu cầu và chạy

Cài JDK **21 trở lên**, Maven 3.9.x; kiểm tra `JAVA_HOME` và `mvn.cmd -version`.
Compiler target là Java 21, nên có thể build bằng JDK 25 nhưng JDK 8 không đủ.

```powershell
mvn.cmd verify
mvn.cmd compile exec:java '-Dexec.mainClass=vn.edu.music.server.ServerMain'
```

Hoặc chạy `./scripts/test.ps1`, `./scripts/run-server.ps1` trong PowerShell.
Mặc định bind `127.0.0.1:9090`; dừng Server bằng Ctrl+C. Nếu project khác dùng cổng này,
đổi `server.port` trong `config/application.properties` trước khi chạy.
Muốn thử trên LAN, đổi host thành `0.0.0.0` và cấu hình firewall phù hợp.

Các key room/track/audio/dedup/client/buffer/sync/metrics là configurable defaults dự kiến,
chưa được bootstrap sử dụng. TTL/retry và admission counts không còn là wire-schema constants.
SYNC_STATE không bắt buộc 1 Hz; CLIENT_METRICS optional. Defaults interval=0 nghĩa là tắt ở phase triển khai sau.
Thư viện WAV và dữ liệu experiment chưa có. Xem `tracks/README.md`.

## Cấu trúc

```text
config/                         Cấu hình chạy và tham số dự kiến
src/main/java/vn/edu/music/
  common/model/                 Shared RoomState, Participant, QueueItem, TrackMetadata
  common/protocol/              DTO/validation, JSON/frame/audio codec, frozen v1 cho P0
  server/config/                Cấu hình TCP Server
  server/network/               TCP lifecycle, handler, dispatcher
  server/room/                  Interface room/queue/playback
  server/track/                 Interface repository
  server/audio/                 Interface chunk service + domain AudioChunkData không có requestId
  client/{network,room,audio,ui} TV1 triển khai
  client/{sync,buffer}           TV3 triển khai và ghép với TV1
  experiment/                   TV3 triển khai
src/test/java/                  Unit và real-TCP bootstrap tests
tracks/                         WAV mẫu hợp lệ do nhóm chuẩn bị
docs/                           Frozen contract v1, kiến trúc, phân công và backlog
results/{raw,processed}/        Dữ liệu thực nghiệm sau này
```

## Phân công và bước tiếp theo

- **TV1:** common protocol, TCP Client, room state, Swing, buffer vật lý và playback engine.
- **TV2 — phần của bạn:** Server network, room/queue, Host permissions, timeline,
  track repository, audio chunk service, concurrency và server integration tests.
- **TV3:** RTT/clock, scheduled sync, fixed/adaptive policy, drift và experiment.

Các quyết định A01–A16 đã được phê duyệt và áp dụng trong `docs/protocol.md`.
Contract đã freeze cho P0; chỉ viết transport/handler tiếp khi có yêu cầu Phase 2 mới.
TV2 làm theo checklist `docs/member-2-server.md`; không cần triển khai mọi phase trong một lần.

## Tài liệu

- [Kiến trúc và lifecycle](docs/architecture.md)
- [Contract v1 frozen và Phase 1 freeze gate](docs/protocol.md)
- [RoomState, version, epoch và timeline](docs/room-state.md)
- [WAV/PCM và binary audio contract](docs/audio-contract.md)
- [Owner và ranh giới module](docs/ownership.md)
- [Checklist riêng TV2 Server](docs/member-2-server.md)
- [Kế hoạch theo phase và test](docs/roadmap.md)
- [Bộ đệm và đồng bộ dự kiến](docs/adaptive-buffer.md)
- [Thiết kế experiment](docs/experiments.md)

## Dependency đã pin

Jackson dùng BOM 2.21.7, JUnit Jupiter 5.14.3; đây là version cố định trong `pom.xml`,
không phải yêu cầu máy luôn dùng bản mới nhất.
Tham khảo [Jackson 2.21](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21)
và [JUnit 5.14.3](https://docs.junit.org/5.14.3/_exports/junit-user-guide-5.14.3.html).
