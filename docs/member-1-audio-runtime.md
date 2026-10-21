# Runtime audio TV1 và request handle

Ngày: 21/10/2026. Phạm vi: I02, I03 và I04 của [kế hoạch tích hợp](member-1-integration-plan.md).
Đây là API cơ chế thực thi của TV1; không phải implementation policy hoặc clock của TV3.

## AudioRuntime

Mỗi MusicClient sở hữu một AudioRuntime. Connect chưa mở thiết bị; chỉ reset mới mở line.
Default settings: capacity 6000 ms, queue lệnh 64, pump interval 10 ms, tối đa 2048 frame mỗi pump.
Khoảng pump 10 ms là cấu hình của TV1, tách khỏi tick policy 100 ms do TV3 đề xuất.

StreamScope gồm roomId, TrackMetadata và streamEpoch. MusicClient cập nhật scope sau khi áp dụng state hợp lệ.
Scope giống nhau thì giữ engine/buffer, kể cả roomVersion đổi do queue/member. Scope đổi làm token cũ hết hiệu lực
ngay, hủy các handle audio còn chờ và enqueue unload. Leave/ROOM_CLOSED/no track/UNKNOWN membership cũng unload.
Disconnect hoặc close kết thúc runtime; kết nối thủ công mới nhận runtime mới.

Các thao tác public:

| API | Kết quả và trách nhiệm |
|---|---|
| updateScope(scope hoặc null) | Future xác nhận cleanup stream cũ; không tự tải chunk hoặc chọn vị trí phát |
| reset(expectedScope,targetFrame) | Future<Token>; reset vị trí do caller cung cấp, cấp revision/generation mới |
| offer(token,chunk) | Future<Boolean>; false nếu stale, trùng hoặc hết chỗ; caller xử lý gap/retry hữu hạn |
| pump(token) | Future<Integer>; bơm PCM trong giới hạn, hỗ trợ prime line khi chưa start |
| start(token), pause(token) | Future<Boolean>; chỉ thực thi khi token còn đúng; start không tự kiểm clock/anchor |
| reserveRequest(token,index,requestId) | AudioRequest đã reserve; không tự gửi mạng; chặn index trùng đang pending |
| snapshot() | Future<State> mới lấy trên audio worker |
| latest() | State cache, có thể cũ; không dùng thay isCurrent(token) trước quyết định thực thi |
| isCurrent(token), pendingRequests() | Kiểm tra gate và số handle Client đang chờ |
| close(), awaitTermination(timeoutMs) | Close không chặn; barrier cleanup chỉ chạy ngoài EDT/audio worker |

Token gồm revision của runtime, engineGeneration và scope. Revision làm callback/lệnh cũ mất hiệu lực trước
khi cleanup vật lý tới lượt worker. Snapshot State chứa revision, token, engine snapshot, closed và failure.
Không dùng revision này thay roomVersion hoặc streamEpoch authoritative.

Toàn bộ reset/offer/pump/start/pause/unload/close và fresh snapshot chạy trên một worker client-audio.
Timer chỉ enqueue pump, không chạm thiết bị. Chỉ có tối đa một tick đang queue/chạy để tránh backlog.
Query/offer gặp queue đầy trả failed Future để caller xử lý; control/reset/scope transition không nhận được
vì queue đầy sẽ fail-closed runtime, settle các Future đang queue và đóng thiết bị.

Lệnh đã admission vẫn có thể thực thi dù caller cancel Future đợi kết quả; việc hủy audio IO dùng AudioRequest,
scope/reset/close. Không suy ra cancel Future control đã rollback thiết bị. Callback Future không được block chờ
lệnh khác trên cùng audio worker. Khi teardown, lệnh chưa chạy được kết thúc bằng CancellationException.

PlaybackEngine.unload() giải phóng stream để có thể reset lại; close() kết thúc engine vĩnh viễn.
Mọi getter thiết bị trong runtime đều chạy trên audio worker. Pause giữ PCM; reset/unload loại dữ liệu cũ.
SourceDataLineSink.close cố gắng stop/flush/close và bỏ reference line ngay cả khi driver ném lỗi.

## Request audio

MusicClient.requestAudio(token,index) tạo AudioRequest chứa requestId, index và token đã capture.
Hàm từ chối stale scope, membership chưa rõ hoặc index đang pending trước khi gửi thêm request tương ứng.
ProtocolClient tiếp tục giới hạn tổng pending/window theo ClientSettings; runtime có budget 64 handle.

- response() trả Future<ProtocolClient.Incoming>, complete khi nhận binary response hợp lệ, chưa có nghĩa đã vào engine.
- cancel() hoặc cancel response Future lan xuống Future transport. Hủy trước attach cũng hủy transport khi attach.
- Reset, đổi scope hoặc close hủy handle cũ. Audio response muộn không được hoàn tất request mới.
- Sau receipt, adapter enqueue offer(handle.token(), incoming.audio()) và kiểm tra kết quả; không lấy generation mới
  cho response cũ. Runtime không tự offer hoặc chọn index tải tiếp để tránh chạy policy thay TV3.
- Hủy cục bộ không hủy IO Server. Không retry mutation, không đổi wire schema.
- chunk(index) cũ vẫn dùng được và nay cancel Future cũng lan xuống pending transport; nó là API đọc độc lập,
  không có reservation theo runtime generation như requestAudio.

observe/observeIncoming của MusicClient trả AutoCloseable để adapter hủy đăng ký khi teardown.
MusicClient.close đóng cả transport/runtime; awaitTermination chờ cả hai trong timeout chung, ngoài EDT.
MusicClient áp PAUSED lên runtime hiện có; PLAYING không tự start bằng clock local.
Room state và audio scope được cập nhật trong cùng gate trước khi công bố snapshot hoặc gọi observer initial;
adapter không nhìn thấy state mới với audio scope chưa được cập nhật. Observer phải ngắn và không chờ Future trong callback.

## Còn chờ TV3 trước khi nối giao diện phát đồng bộ

TV3 cung cấp điểm bắt đầu/ResyncPlan và BufferDecision. TV1 thực thi reset, request, offer và start trên worker.
Caller chỉ start sau khi clock hợp lệ, đủ playable PCM và đến anchor; runtime không tính hoặc thay policy đó.
Các nội dung còn thiếu được liệt kê tại [phản hồi tiếp cho TV3](member-1-followup-to-member-3.md).
Giao diện hiện vẫn hiển thị chờ tích hợp; không có prefetch/scheduled/Adaptive runtime tự động.

## WAV local và kiểm tra thiết bị

LocalWavSource chỉ dùng cho diagnostic; phòng nhạc thật tiếp tục nhận PCM qua TCP.
Nó đọc AudioSystem/AudioInputStream, xác minh WAV PCM signed 16-bit LE, stereo 44100 Hz,
đọc chunk tuần tự và phát hiện PCM thiếu. Test có RIFF JUNK/padding, không giả định header 44 byte.
Không sửa TrackRepository, parser hoặc assets của TV2; WAV diagnostic nằm dưới target, không ở tracks.

WavSmokeMain hỗ trợ --tone để tạo WAV 440 Hz nhỏ 500 ms và --wav cho clip có sẵn tối đa 10 giây.
--tone dùng CREATE_NEW, không ghi đè file cũ. Công cụ dùng AudioRuntime/engine/SourceDataLineSink thật,
có deadline phát và cleanup; không mô phỏng scheduled start, không phải một Client mạng khác.

Ví dụ trên máy hiện tại sau khi build:

```powershell
$taskJacksonJars = Get-ChildItem 'target/maven-repository/com/fasterxml/jackson/core' -Recurse -Filter '*.jar'
$taskClientClasspath = 'target/classes;' + (($taskJacksonJars | ForEach-Object FullName) -join ';')
& 'C:\Program Files\Java\jdk-24\bin\java.exe' -cp $taskClientClasspath vn.edu.music.client.audio.WavSmokeMain --tone 'target/new-client-smoke.wav'
```

Dùng --wav với đường dẫn đã có để chạy lại mà không tạo mới. Report ghi ở target/client-device-smoke.md.

Kết quả thực tế ngày 21/10/2026 lúc 18:13:48 giờ Việt Nam:

- Windows 11, Java 24.0.2; có mixer Speakers (Realtek(R) Audio) và Primary Sound Driver.
- Line com.sun.media.sound.DirectAudioDevice$DirectSDL, buffer 17640 byte.
- WAV target/client-smoke-20261021.wav: 22050 frame, 500 ms, đúng format yêu cầu.
- DEVICE_API_PASS: vị trí thiết bị xử lý đạt 500 ms, buffer app/line hết; elapsed tổng 1813 ms gồm mở/prime/phát.
- Chưa ghi nhận xác nhận nghe bằng tai của người dùng; không xem elapsed tổng là network latency hoặc software drift.
- Không khẳng định kết quả này chứng minh đồng bộ nhiều Client, chất lượng âm thanh vật lý hoặc experiment đã hoàn tất.

Sau khi hoàn thiện đường cleanup, chạy lại cùng WAV lúc 18:21:03 ngày 21/10: DEVICE_API_PASS,
22050 frame/500 ms, elapsed tổng 1918 ms, cleanup không báo timeout. Report target được cập nhật bởi lượt chạy sau.

## Test cuối đợt

Maven verify ngày 21/10 lúc 18:22: BUILD SUCCESS, 283 test, 0 failure/error/skipped.
17 test mới so với baseline 266 gồm 9 test runtime, 4 test local WAV và 4 scenario TCP MusicClient.
Các scenario kiểm tra cancellation/pending, generation/epoch, leave/create room mới, scope publication,
queue overload, timer backlog, partial writes, lỗi open/close device và shutdown.
Phần Server/common và các package TV3 không thay đổi trong đợt này.
