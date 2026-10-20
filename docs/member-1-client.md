# Tiến độ và quyết định thuộc TV1

Ngày bắt đầu: 20/10/2026. Owner: TV1. Protocol v1 và A01–A16 giữ nguyên.

## Phạm vi được phép thay đổi

TV1 triển khai client/network, client/room, client/audio, client/ui, ClientMain và test Client.
Không sửa server/**, client/sync, client/buffer, experiment hoặc cấu hình thuộc TV2/TV3.
PlaybackBuffer vật lý nằm ở client/audio theo ownership; client/buffer dành cho policy TV3.

## Quyết định triển khai TV1

- Một reader/socket; writer riêng với queue hữu hạn, serialize nguyên frame bằng common codec.
- Request phải qua validateClientRequest; input Server phải qua validateServerMessage hoặc AudioChunkCodec.
- Future kiểm tra requestId, expected response type và room scope. Push không hoàn tất request khác.
- Callback theo thứ tự trên worker riêng, không chạy UI hoặc audio write trong reader.
- Timeout không tự phát lại mutation bằng ID mới. Chưa tích hợp automatic retry khi chưa thống nhất retention với TV2.
- Một pipeline áp dụng full state; version thấp/bằng không overwrite. Lifecycle response chỉ bind/unbind khi khớp operation đang chờ.
- Swing chỉ cập nhật trên EDT. Kết nối/request/audio chạy ngoài EDT.
- AudioSink thay thế được trong test; buffer hữu hạn theo PCM frame, loại room/track/epoch cũ.
- Engine là thiết bị phát cục bộ. Việc chọn thời điểm phát/hội nhập timeline do TV3 cung cấp, không tự giả lập clock Server.

## Tiêu chí bàn giao

1. Transport và room/controller kiểm thử bằng socket thật với fixture chỉ ở src/test/client.
2. Buffer/engine kiểm thử bằng PCM xác định và fake sink; ghi riêng smoke thiết bị thật chưa chạy.
3. UI gọi controller, hiển thị lỗi NOT_IMPLEMENTED trung thực với Server bootstrap hiện tại.
4. Phần tích hợp Server thật và sync/policy chờ điều kiện trong member-1-coordination.md.

## Trạng thái bàn giao ngày 20/10/2026

| Task | Trạng thái | Bằng chứng và giới hạn |
|---|---|---|
| C1 Transport TCP | Verified độc lập | ProtocolClient: validation, concurrent writes, correlation, push, timeout, audio window, cancel và shutdown. Automatic retry chưa bật. |
| C2 Room gateway/state | Verified bằng fixture TCP | MusicClient và RoomStateStore: lifecycle, revision, catalog, error và terminal gate. Chưa nghiệm thu nghiệp vụ Server thật. |
| C3 Swing/controller | Verified bằng test + render | Connect/create/join/leave, lists, Host controls; EDT vẫn phản hồi khi Server fixture chậm. Chưa demo ba Client thật. |
| C4 Receiver/buffer/engine | Verified bằng fake sink + PCM TCP | Out-of-order/seek/epoch/generation, capacity, partial writes, device-open error. Hash SHA-256 PCM khớp nguồn fixture. |
| C5 Fixed buffer/prefetch vào Client chạy thật | Chờ TV3/TV2 | Vật lý buffer/IO đã có; chưa tự viết policy hoặc lịch thay cho TV3. |
| C6 Scheduled playback/late join/adaptive/drift | Dừng chờ hợp đồng TV3 | Chưa có interface/runtime TV3; không lấy clock local thay Server clock. |
| C7 Tích hợp thiết bị thật và nghiệm thu | Chờ Server/audio/policy | Chưa nghe thử WAV local, chưa xác nhận SourceDataLine trên thiết bị thật, 3 Client/2 phòng hoặc clean-machine. |
| C8 Docs TV1 | Hoàn tất đợt bàn giao này | API, quyết định TV1, cách chạy, kết quả test và câu hỏi phối hợp đã ghi riêng. |

Không đánh dấu toàn bộ TV1 Done. `AudioReceiver`, `PlaybackEngine` chưa được tự động nối vào giao diện;
chưa có background audio pump/prefetch/scheduled-start runtime. UI hiển thị rõ chờ tích hợp âm thanh.

## Xác minh

- Full Maven verify: 266 test, 0 failure, 0 error, 0 skipped; 36 test mới thuộc Client, 230 test sẵn có giữ nguyên.
- JDK 24 build target release 21 theo pom.xml; không sửa Java target hoặc dependency phiên bản.
- Các test TCP chạy trên loopback thật, fixture chỉ nằm trong src/test/java/vn/edu/music/client.
- Kiểm tra phần mềm audio dùng fake sink; không coi kết quả này là bằng chứng chất lượng âm thanh vật lý.
- RoomWindowTest render Host view vào target/client-ui-preview.png, đã xem bố cục; quyền Host/Member/UNKNOWN được test.
- RoomControllerTest giữ Server response để chứng minh EDT xử lý sự kiện khác mà không chờ socket.
- Không thay đổi server/**, client/sync, client/buffer, experiment, common, pom.xml hoặc config của TV2/TV3.

Máy hiện tại không có Maven trên PATH, Oracle java mặc định không được dùng cho build này.
Verify sử dụng JDK 24 và Maven đi kèm IntelliJ, cache dependency riêng ở target/maven-repository.
Cache/build output không được đưa vào source. Để tái hiện trên máy này:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' -B -o '-Dmaven.repo.local=target/maven-repository' verify
```

Lần đầu trên máy sạch cần bỏ `-o` để tải dependency. Nếu sandbox chặn localhost thì cần chạy test ngoài
sandbox giới hạn mạng; không tắt hoặc bỏ các test TCP để làm kết quả xanh.

Hướng dẫn chuẩn chạy Client ở [API bàn giao TV1](member-1-client-contract.md).
Nếu dùng đúng runtime/cache hiện tại, có thể chạy trực tiếp mà không tải thêm Maven plugin:

```powershell
$taskJacksonJars = Get-ChildItem 'target/maven-repository/com/fasterxml/jackson/core' -Recurse -Filter '*.jar'
$taskClientClasspath = 'target/classes;' + (($taskJacksonJars | ForEach-Object FullName) -join ';')
& 'C:\Program Files\Java\jdk-24\bin\java.exe' -cp $taskClientClasspath vn.edu.music.client.ClientMain
```

## Tài liệu cần chuyển cho thành viên còn lại

- [API và quyết định TV1 đã chốt](member-1-client-contract.md): để TV2/TV3 tích hợp với các lớp Client.
- [Điểm cần TV2/TV3 phản hồi](member-1-coordination.md): endpoint/fixtures, runtime limits, retention và hợp đồng sync/policy.

Chỉ tiếp tục các task chờ sau khi có phản hồi được người dùng cung cấp hoặc có interface/commit tương ứng.
Không gửi thông báo trực tiếp cho thành viên khác hoặc ghi rằng họ đã xác nhận khi chưa có phản hồi.
