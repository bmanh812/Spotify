# API Client do TV1 chốt để bàn giao

Ngày: 20/10/2026. Đây là quyết định ở phạm vi TV1. Không thay đổi Protocol v1, không xác nhận thay TV2/TV3.
Các adapter liên module chỉ ghép sau phản hồi trong [tài liệu phối hợp](member-1-coordination.md).

## Transport

`ProtocolClient.connect(host, port, ClientSettings, Listener)` thực hiện kết nối TCP; gọi ngoài EDT.
`request(ProtocolMessage)` trả `CompletableFuture<Incoming>`; validate request trước khi enqueue.
`close()` idempotent, đóng socket và kết thúc pending; `awaitTermination(timeoutMs)` là barrier cho background/test.

`Incoming` có control hoặc audio, request tương quan hoặc null cho push, receivedEpochMs,
receivedNanos và sentNanos. Cả hai timestamp nano chỉ thuộc Client; không truyền qua wire/so trực tiếp với Server.
PING được chụp lại clientSendTimeMs khi writer lấy request ra khỏi queue, trước serialize/write;
đọc t1 thực gửi từ Incoming.request, t4 từ receivedEpochMs, elapsed từ receivedNanos-sentNanos.
TV3 quyết định lọc clock jump, offset/RTT và smoothing. Không có estimator trong TV1.

Defaults TV1: connect timeout 3000 ms, request timeout 5000 ms, pending 64, outbound queue 64,
event queue 128 và audio pending 4. Có thể thay qua ClientSettings; audio window không vượt maxPending.
Server deployment có thể cần giảm window; không có negotiation mới trên wire.

Một reader, một writer, một event worker và một deadline worker mỗi connection. Complete frame writes không xen byte.
Listener gọi theo thứ tự trên event worker; state apply trước khi complete response future.
Listener/future continuation phải ngắn, không block chờ một request khác trên cùng worker.
Audio adapter enqueue sang audio worker; Swing enqueue sang EDT. Queue event đầy thì disconnect thay vì tăng RAM vô hạn.

Direct response kiểm tra requestId, type, room scope; PONG kiểm tra echo; audio kiểm tra track/epoch/index.
Payload sai, framing lỗi hoặc response tương quan sai đóng connection. ERROR hợp lệ thành ServerErrorException,
không tự disconnect. UUID push không hoàn tất request không liên quan. EOF vẫn giao các complete frame đã queue trước thông báo disconnect.

Không auto-retry. UUID đã dùng không được gửi lại bằng API hiện tại, kể cả sau timeout. Ledger bounded 65536 UUID/session;
chạm giới hạn thì từ chối request mới, yêu cầu disconnect thủ công. Đây là policy TV1, không wire constant hoặc reconnect recovery.
Tự reconnect không được triển khai. Timeout/cancel loại pending; audio/direct response muộn bị bỏ, không làm rebind membership.
Pending count đo request Client đang chờ, không phải số request Server còn đang xử lý sau timeout.
`ProtocolClient` cho phép cancel Future; `MusicClient` chưa cung cấp cancel/retry nghiệp vụ hoặc bảo đảm hủy mutation phía Server.

## Gateway và pipeline trạng thái

`MusicClient`: create, join, leave, addTrack, playback(PLAY/PAUSE/SEEK/NEXT_TRACK), chunk(index), ping,
view, observe, observeIncoming, close. Các action trả Future; lỗi validation/precondition cũng trả failed Future.

`RoomStateStore.View`: clientId, state nullable, catalog immutable, lifecyclePending, uncertain; isHost là tiện ích UI.
CREATE/JOIN/LEAVE không chồng nhau. Chỉ lifecycle response khớp operation đang chờ được bind/unbind.
Membership push không tự bind Client đang ngoài phòng. Sau LEAVE/ROOM_CLOSED/disconnect, snapshot cũ không hồi sinh phòng.

Mọi state được contextual validate với catalog. State cùng room chỉ áp dụng khi roomVersion mới hơn;
catalog immutable, epoch không rollback. Queue/member đổi version không mặc nhiên flush audio.
SYNC_STATE cùng version vẫn được observeIncoming chuyển tới adapter TV3, không overwrite snapshot.
STALE_STATE trả lỗi cho người dùng; full ROOM_STATE tiếp theo qua cùng pipeline. Không tự phát lại command.

Timeout mutation đánh dấu uncertain, khóa action phòng cho tới disconnect. Không tự coi UNKNOWN là thất bại chắc chắn.
Business ERROR đã hoàn tất thì bỏ lifecyclePending, giữ kết nối để người dùng thực hiện action mới khi phù hợp.
Đây là cách bảo thủ hiện tại; retry/recovery nâng cấp chờ retention deployment TV2.

## Receiver và bộ đệm vật lý

`PlaybackBuffer(capacityMs)` ở client/audio, không nằm trong package policy của TV3.
Capacity 1..60000 ms là giới hạn tài nguyên TV1. `reset(roomId,track,epoch,targetFrame)` bỏ context cũ.
`offer(AudioChunk)` trả false khi stale/trùng/đã tiêu thụ/hết chỗ; malformed active chunk ném ProtocolException.
`poll(maxFrames)` chỉ lấy PCM liên tục, frame-aligned; gap trả mảng rỗng, không suy ra EOF.
Seek trim prefix tới đúng sample frame. Out-of-order chunk lưu theo offset; không phát qua gap.
Snapshot phân biệt tổng queuedFrames, contiguousFrames, capacityFrames và nextFrame.

`AudioReceiver.accept(Incoming, requestGeneration)` gọi trên audio worker, kiểm tra room/track/epoch/catalog
rồi chuyển vào engine. Generation phải được capture lúc lập yêu cầu/callback, không lấy generation mới tại lúc callback cũ trả về.
Receiver chưa tự đăng ký vào MusicClient hoặc tự chạy prefetch; TV1 sẽ ghép sau khi thống nhất policy/lịch TV3.

## Engine và thiết bị

`PlaybackEngine(AudioSink,capacityMs)` hỗ trợ:

- reset(roomId,track,epoch,targetFrame): stop/flush/reopen thiết bị, clear buffer, trả local generation mới.
- offer(chunk,expectedGeneration): loại callback cũ, không block chờ buffer trống.
- pump(maxFrames,expectedGeneration): ghi một phần PCM trong giới hạn availableBytes; giữ phần chưa ghi khi partial/zero write.
- start(expectedGeneration), pause(), snapshot(), close().

TV3 adapter và TV1 IO phải serialize reset/offer/pump/start/pause/close trên cùng audio worker.
Engine có synchronized bảo vệ thêm, nhưng không dùng lock đó để thay lịch điều phối.
Device calls bị cấm trên EDT. Snapshot/offer không thực hiện blocking device write.
Snapshot getter của sink cần nhanh; không có scheduler riêng trong engine.

Snapshot gồm room/track/epoch/generation, actualPositionMs, appBufferFrames, appContiguousFrames,
capacityFrames, lineBufferFrames, running, underrun, sampledNanos.
App buffer bao gồm staged PCM chưa được sink nhận; line buffer báo riêng để không đếm đôi.
actualPosition = seek base + frame sink đã xử lý, clamp cuối bài; không dựa trên bytes tải/ghi.
`running` là ý định thiết bị đang start; `underrun` phải được adapter sử dụng để hiển thị BUFFERING và ghi event.
Engine không tự NEXT khi hết PCM; Server quyết định EOF. Pause giữ PCM; seek/local resync dùng reset/generation.
Chưa có scheduled playback, clock estimation, adaptive policy, correction, rebuffer controller hoặc metrics reporting.

`SourceDataLineSink`: PCM signed 16-bit LE, stereo 44100 Hz theo metadata đã validate, line buffer yêu cầu 17640 bytes
(khoảng 100 ms; thiết bị có thể cấp khác). Lỗi mở line truyền cho caller; không làm chết reader TCP.
Fake sink chứng minh hành vi phần mềm, không chứng minh chất lượng phát loa hoặc độ trễ vật lý.

## Giao diện và chạy

RoomController ở client/ui quản lý background commands; RoomStateStore ở client/room quản lý state.
RoomWindow chỉ thao tác Swing trên EDT. Có connect/create/join/leave, participants, catalog, queue,
Host playback và seek theo giây. Member vẫn thêm queue; UI khóa playback của Member nhưng Server vẫn phải kiểm quyền.

Chạy với JDK 21+ và Maven trên PATH:

```powershell
./scripts/run-client.ps1
```

Hoặc:

```powershell
mvn.cmd compile exec:java '-Dexec.mainClass=vn.edu.music.client.ClientMain'
```

Server bootstrap hiện trả NOT_IMPLEMENTED; Client hiển thị nguyên mã lỗi, không dựng phòng/nhạc giả trong UI.
UI hiện chưa phát audio hoặc hiển thị RTT/drift; có nhãn chờ tích hợp. Fixtures chỉ tồn tại trong test.
README chung là trạng thái checkpoint trước TV1; tài liệu này và member-1-client.md mô tả phần Client mới,
không cập nhật thay tài liệu tiến độ Server/TV3.
