# TV1 đề nghị TV3 bàn giao API để ghép runtime

Ngày: 21/10/2026. Căn cứ: phản hồi TV3 ngày 21/10 tại C:/Users/abc/Downloads/member-3-response-to-member-1.md.
Tài liệu để người dùng chuyển cho TV3; chưa gửi trực tiếp và chưa có phản hồi bổ sung.

## Phần đã rõ và không hỏi lại

TV1 ghi nhận ownership, audio/policy worker, t1–t4 và monotonic elapsed, capacity 6000 ms,
fixed 1500 ms, adaptive 500–4000 ms, watermarks theo chunk, các interval và local generation/resync.
Protocol v1/A01–A16 giữ nguyên. TV1 không triển khai clock hoặc policy thay TV3.

TV1 đã thêm AudioRuntime và AudioRequest; chữ ký thật, threading, cancellation và device smoke ở
[member-1-audio-runtime.md](member-1-audio-runtime.md). Buffer/engine vẫn nằm trong client/audio.

## Bốn đầu vào cần TV3 trả lời để mở gate I05

1. **Java API bàn giao:** cung cấp commit/file Java với package, field types/units, constructor/factory của
   ClockSynchronizer, BufferController, PlaybackSynchronizer và ClockEstimate/BufferMetrics/BufferDecision/ResyncPlan.
   Chốt cách nhận biết ClockEstimate invalid/stale và phương thức reset state khi đổi connection/room/track/epoch.
   Nếu mới có interfaces/records, ghi rõ phần nào đã compile và phần nào chưa implement.

2. **Context policy còn thiếu ở chữ ký:** BufferController nhận chunk duration/capacity từ constructor hay context mỗi track?
   PlaybackSynchronizer nhận TrackMetadata/duration, targetBufferMs và trạng thái startup/late join/đang chờ anchor bằng API nào?
   Cần các đầu vào này để tính high watermark, remaining PCM, resyncLead và targetFrame đúng track.
   TV1 không tự bổ sung record chính thức vào package TV3 khi chưa có quyết định.

3. **Mốc lấy mẫu và vòng đời plan:** xác nhận expectedPosition được chiếu về engine.sampledNanos khi tính drift;
   cách cooldown/plan reset khi pause/seek/next/leave, và cách xử lý plan tới/sau EOF hoặc clock hết hạn.
   TV1 sẽ không start plan cũ hoặc tự NEXT. Token TV1 hiện gồm revision, engineGeneration và StreamScope;
   adapter phải giữ token từ lúc lập request/decision và kiểm tra lại trước thực thi.

4. **Metrics/event adapter:** cung cấp interface nhận sample/event từ TV1 sang experiment của TV3, gồm đơn vị,
   thread và missing-data semantics. Chốt chunkFetchMs/activeDownloadThroughput khi nhiều request overlap,
   và cách gửi BUFFERING/RESYNC start/end mà không đếm startup/seek/EOF thành rebuffer.

## TV1 sẽ làm khi nhận đủ đầu vào

Ghép API thật trên policy executor tuần tự, đưa quyết định sang AudioRuntime, triển khai Fixed/prefetch
và scheduled start trước rồi mới Resync/Adaptive/UI metrics. Có test fake clock/sink và test ghép module thật.
TV1 chỉ yêu cầu thêm thông tin hoặc API của TV3; không yêu cầu TV3 viết TCP/audio engine.

Server bootstrap hiện vẫn NOT_IMPLEMENTED; endpoint/chunk/PONG/WAV/retention và nghiệm thu nhiều Client
vẫn cần TV2 bàn giao riêng. Xác nhận thiết kế từ TV3 không thay thế điều kiện Server này.
