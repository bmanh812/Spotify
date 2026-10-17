# Phòng nghe nhạc đồng bộ với bộ đệm thích ứng

Ứng dụng môn Lập trình mạng: nhiều Client tham gia phòng, dùng shared queue và nghe theo timeline
authoritative của Server. Đồng bộ phát và adaptive buffering là phần kỹ thuật cần triển khai/đánh giá.
Tên repository là `Spotify`; phạm vi sản phẩm là phòng nghe nhạc, không phải bản sao đầy đủ Spotify.

## Trạng thái hiện tại

**Đây là bộ khung để bắt đầu code. Protocol vẫn là draft.**

Đã có:

- Java 21/Maven, cấu hình và cấu trúc package theo ba phần việc.
- TCP Server khởi động được, thread pool/queue hữu hạn, đóng socket và shutdown.
- Framing `[4-byte big-endian bodyLength][1-byte frameType][payload]`.
- JSON envelope, message/error catalog dự kiến và dispatcher bootstrap.
- Model snapshot phòng/track/chunk cùng interface cho phần Server.
- Test cho framing, envelope, cấu hình, dữ liệu snapshot và TCP bootstrap thật.

**Chưa có** create/join room, queue, kiểm tra quyền Host, timeline, stream audio, Client Swing,
playback engine, fixed/adaptive buffer, clock sync, drift correction hoặc experiment.
Request hợp lệ hiện nhận `ERROR/NOT_IMPLEMENTED` cùng `requestId`; đây không phải xử lý nghiệp vụ.
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

Các key room/audio/buffer/sync trong config là tham số dự kiến, chưa được bootstrap sử dụng.
Thư viện WAV và dữ liệu experiment chưa có. Xem `tracks/README.md`.

## Cấu trúc

```text
config/                         Cấu hình chạy và tham số dự kiến
src/main/java/vn/edu/music/
  common/protocol/              Draft DTO, JSON và frame codec
  server/config/                Cấu hình TCP Server
  server/network/               TCP lifecycle, handler, dispatcher
  server/room/                  Model và interface room/queue/playback
  server/track/                 Metadata và interface repository
  server/audio/                 Chunk model và interface service
  client/{network,room,audio,ui} TV1 triển khai
  client/{sync,buffer}           TV3 triển khai và ghép với TV1
  experiment/                   TV3 triển khai
src/test/java/                  Unit và real-TCP bootstrap tests
tracks/                         WAV mẫu hợp lệ do nhóm chuẩn bị
docs/                           Kiến trúc, draft protocol, phân công và backlog
results/{raw,processed}/        Dữ liệu thực nghiệm sau này
```

## Phân công và bước tiếp theo

- **TV1:** common protocol, TCP Client, room state, Swing, buffer vật lý và playback engine.
- **TV2 — phần của bạn:** Server network, room/queue, Host permissions, timeline,
  track repository, audio chunk service, concurrency và server integration tests.
- **TV3:** RTT/clock, scheduled sync, fixed/adaptive policy, drift và experiment.

Trước tiên cả nhóm review `docs/protocol.md`. Sau khi freeze mới viết các handler phòng.
TV2 làm theo checklist `docs/member-2-server.md`; không cần triển khai mọi phase trong một lần.

## Tài liệu

- [Kiến trúc và lifecycle](docs/architecture.md)
- [Protocol draft và việc cần chốt](docs/protocol.md)
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
