# Kế hoạch TV1 sau phản hồi của TV3

Ngày lập: 21/10/2026. Trạng thái cập nhật: đã triển khai I02–I04; I05 trở đi chờ gate như ghi cuối tài liệu.

## Mục tiêu và căn cứ

TV1 sẽ nối TCP Client, trạng thái phòng, receiver, buffer vật lý, engine và Swing thành luồng phát
có thể sử dụng policy/clock/resync của TV3. Protocol v1 và A01–A16 giữ nguyên.

Căn cứ: phản hồi TV3 ngày 21/10/2026 tại `C:/Users/abc/Downloads/member-3-response-to-member-1.md`,
[API TV1 hiện có](member-1-client-contract.md) và [tiến độ TV1](member-1-client.md).
Đã đọc phản hồi, nhưng checkout vẫn chỉ có package-info.java trong client/sync và client/buffer.
Không đánh đồng xác nhận thiết kế với việc đã nhận implementation.

Baseline trước đợt này: Client đã có transport, room gateway, Swing và các thành phần audio độc lập;
266 test đã qua ở lần verify trước. Lượt lập kế hoạch này không chạy lại test hoặc sửa production code.

## Ranh giới sở hữu

TV1 được sửa client/network, client/room, client/audio, client/ui, ClientMain, test Client và docs TV1.
TV1 thực thi IO, lịch gọi adapter và điều khiển thiết bị. TV1 không tự triển khai thuật toán để thay TV3.

TV3 cung cấp ClockSynchronizer, BufferController, PlaybackSynchronizer, các kiểu dữ liệu liên quan và
phần simulator/metrics/CSV thuộc experiment. TV2 cung cấp Server, timestamp handler, thư viện WAV,
room/queue/timeline, streaming, giới hạn và retention deployment.

Không sửa server/**, client/sync, client/buffer, experiment, common protocol hoặc cấu hình của thành viên khác.
Nếu cần thay hợp đồng liên module, dừng task phụ thuộc và ghi câu hỏi vào docs.

## Những quyết định TV3 đã xác nhận

| Nội dung | Giá trị hoặc trách nhiệm |
|---|---|
| Điều phối | Audio worker tuần tự do TV1 sở hữu; policy executor tuần tự, policy synchronous, không I/O |
| Clock | t1 thực gửi, t2/t3 Server, t4 receipt; elapsed monotonic; estimator do TV3 cung cấp |
| Buffer vật lý | 6000 ms cho cả Fixed và Adaptive |
| Fixed target | 1500 ms |
| Adaptive target | clamp(500 + 2*SRTT + 4*RTTVariation, 500, 4000) ms; TV3 tính |
| Watermarks | Low bằng target; high bằng target + thời lượng chunk theo catalog, clamp capacity |
| Lịch policy | Theo sự kiện và tick 100 ms khi playback hoạt động |
| Lịch đo | 500 ms; giá trị 0 tắt lịch lấy mẫu |
| PING khi bật sync | 1000 ms |
| Correction | Threshold 400 ms, cooldown 3000 ms; plan do TV3 cung cấp |
| Khôi phục | Hội nhập ở điểm tương lai; không tăng streamEpoch chung; chặn callback cũ bằng generation |
| Metrics | Sample và event riêng; không thêm field/message vào Protocol v1 |

Các giá trị trên là defaults tích hợp ban đầu, không phải kết quả hiệu năng đã đạt.

## Thứ tự công việc

| ID | Công việc TV1 | Phụ thuộc | Sản phẩm và điều kiện Done |
|---|---|---|---|
| I01 | Ghi nhận phản hồi, cập nhật danh sách câu hỏi và lập kế hoạch | Phản hồi TV3 | Hoàn tất tài liệu trong lượt này; giữ lại lịch sử baseline và phân biệt thiết kế/code |
| I02 | Chuẩn bị audio runtime tuần tự và lifecycle | Không cần thuật toán TV3 | Worker/queue hữu hạn, lifecycle token; reset/offer/pump/start/pause/close cùng worker; test shutdown và callback cũ |
| I03 | Bổ sung request handle cho audio trong MusicClient | I02; giữ nguyên wire | Hủy request audio tới được pending transport; capture room/track/epoch/generation lúc gửi; test timeout/cancel/disconnect; mutation không tự retry |
| I04 | Hoàn thiện thiết bị phát độc lập và kiểm tra WAV local | Phần audio TV1 hiện có | Công cụ smoke tách luồng phòng, đọc format thật, không sửa parser/catalog Server; ghi thiết bị/JDK/format và kết quả thực tế |
| I05 | Ghép ba API thật của TV3 và bridge timestamp/metrics | I02–I03; TV3 bàn giao Java API, kiểu dữ liệu và context | Build cùng module thật; PONG tới ClockSynchronizer tuần tự; không ghi estimator hoặc policy thay TV3 |
| I06 | Ghép prefetch dùng Fixed trước | I05; BufferController Fixed và Server fixture TCP | Capacity 6000 ms, window hữu hạn theo cấu hình tương thích TV2, không xin trùng chunk; hoạt động theo decision/hysteresis TV3; PCM liền mạch, không tràn RAM |
| I07 | Ghép scheduled start và điều khiển playback | I06; clock và timeline contract | Chỉ start khi clock hợp lệ, đến mốc và đủ PCM; PLAY/PAUSE/SEEK/NEXT áp dụng đúng epoch/generation; fake clock/sink tests |
| I08 | Thực thi ResyncPlan cho late join, underrun và drift | I07; PlaybackSynchronizer/context đã chốt | Hủy audio request cũ, reset tới targetFrame, prefetch và start theo mốc; plan cũ không tác động; không tăng epoch chung |
| I09 | Ghép Adaptive, status UI và nguồn dữ liệu đo | I08; Adaptive và metrics/event interface TV3 | Dùng cùng runtime/capacity; UI có SYNCING/BUFFERING/PLAYING/PAUSED/DISCONNECTED, RTT/target/level/drift; không bịa metric khi chưa có mẫu |
| I10 | Tích hợp Server thật và nghiệm thu TV1 | I09; TV2 S1–S5 và track hợp lệ | 3 Client/2 phòng, audio thật qua TCP, Host controls, seek/next/disconnect, thiết bị thật và clean-machine có bằng chứng |
| I11 | Bàn giao tài liệu và hỗ trợ experiment | I10; runner/simulator TV3 | API/docs khớp code, hướng dẫn chạy, checklist và log; TV3 xuất CSV/chart; TV1 không công bố cải thiện khi chưa đo |

I02–I04 có thể triển khai và kiểm thử độc lập trước khi nhận code TV3. I05 trở đi chỉ bắt đầu khi
đáp ứng gate tương ứng. Fixture giả chỉ đặt dưới src/test/java/vn/edu/music/client, không làm Server sản phẩm thay TV2.

## Các gate phải chốt trước khi ghép

### Gate G1 TV3 bàn giao API có thể compile

TV3 cung cấp file Java hoặc commit và cách khởi tạo/reset các policy, gồm:

- Package, kiểu dữ liệu/đơn vị, constructor và semantics invalid của ClockEstimate, BufferMetrics,
  BufferDecision và ResyncPlan; policy gọi tuần tự trên executor riêng như phản hồi.
- Cách policy lấy TrackMetadata, duration/remainingFrames, chunk duration, capacity và targetBufferMs.
  Các dữ liệu này cần cho high watermark và targetFrame/resyncLead nhưng chưa thể hiện đầy đủ ở chữ ký đề xuất.
- Cách nhận biết startup/late join/underrun và thời điểm hủy hoặc reset cooldown khi room/track/epoch đổi;
  policy không suy ra trạng thái đang chờ scheduled start chỉ từ engine.running=false.
- Cách dùng snapshot engine.sampledNanos để so actual/expected ở cùng mốc tham chiếu; không so mẫu ở hai thời điểm khác nhau.
- Cách xác định playable occupancy dùng app contiguous và line buffer; state lưu hysteresis thuộc BufferController.
- Adapter tiếp nhận sample/event cho experiment: chữ ký callback, thread, đơn vị và hành vi khi dữ liệu thiếu.

Không tạo bản record/class tương tự trong package TV1 rồi coi đó là API TV3 chính thức.
Test double chỉ kiểm tra điều phối và thực thi, không chứa thuật toán thay TV3.

### Gate G2 TV2 sẵn sàng cho môi trường tích hợp

TV2 cung cấp endpoint/commit chạy được, handlers create/join/leave/playback/chunk/PING/PONG,
ít nhất ba WAV hợp lệ và catalog. Xác nhận frame ceiling, audio request window và thông tin retention.
Client giữ default audio pending 4 cho test hiện tại; deployment cần phù hợp với giới hạn Server.
Automatic retry mutation vẫn tắt; xác nhận TV3 không thay được câu trả lời retention của TV2.

### Gate G3 Xác minh trước nghiệm thu

Compile/test riêng của mỗi bên không thay thế test ghép. Nghiệm thu cần code thật TV2/TV3, thiết bị audio
thật và log đo. Fake sink/fixture pass chỉ xác nhận hành vi phần mềm trong điều kiện test.

## Chi tiết triển khai phần TV1

### Audio runtime và vòng đời

- Tạo service điều phối audio trong client/audio, dùng engine hiện có; không đưa scheduler vào policy TV3.
- Queue lệnh hữu hạn, có cơ chế gộp tick để không tích lũy tick khi thiết bị chậm. Không tùy tiện bỏ control/lifecycle event.
- Tách tick policy 100 ms khỏi lịch pump thiết bị. TV1 chọn cadence pump theo availableBytes và ngân sách mỗi tick,
  thử ban đầu khoảng 10 ms, đo bằng fake sink và thiết bị thật; không mặc định bơm một lần/100 ms là đủ.
- Snapshot audio được chụp trên audio worker rồi gửi bất biến sang policy executor. Không để policy đọc thiết bị
  trong reader hoặc EDT; không chặn worker này để chờ Future từ worker kia.
- Khi room/track/epoch hoặc generation thay đổi, quyết định policy/timer cũ phải hết hiệu lực.
  Queue/member đổi version cùng stream không tự reset engine, nhưng lệnh policy vẫn phải kiểm tra context còn phù hợp.
- Khi leave/room close/disconnect/window close: hủy lịch và audio pending, chặn callback cũ, đóng engine/worker;
  chờ cleanup ngoài EDT trong giới hạn. Observer/scheduler của session cũ không được cập nhật session mới.

### Hủy và theo dõi request audio

MusicClient.chunk hiện trả Future qua whenComplete; cancel Future ngoài chưa bảo đảm cancel Future gốc của ProtocolClient.
I03 phải sửa sự lan truyền cancel ở lớp TV1 hoặc bàn giao request handle rõ ràng; có test pendingCount và response muộn.
Hủy Future chỉ hủy chờ cục bộ; không tuyên bố đã hủy thao tác/IO phía Server.

Theo dõi index đang chờ/đã nhận, ưu tiên lấp gap và không tải trùng. Chỉ request cho active room/track/epoch.
Buffer đủ high watermark thì dừng prefetch; không dùng RTT thấp hoặc request đã timeout để suy ra mạng vẫn đủ băng thông.
Nếu chunk không thể offer do thiếu capacity, phải có cách lấp gap/tải lại hữu hạn; không bỏ một index rồi chờ vĩnh viễn.

### Thực thi start và resync

TV3 tính clock/target/ResyncPlan; TV1 kiểm tra plan còn đúng context rồi thực thi trên audio worker.
Tới anchor mà chưa đủ dữ liệu thì yêu cầu TV3 tính plan tương lai mới, không tự phát muộn.
Clock invalid/hết hạn thì hiển thị SYNCING và chờ estimate hợp lệ, không fallback sang local epoch làm Server time.

Khởi động đủ buffer theo target hoặc phần track còn lại; không bắt track ngắn chờ target lớn hơn toàn bộ PCM.
TargetFrame ngoài current track không đưa vào reset; đợi/chốt lại context với policy và state Server.
Tại ranh giới EOF, Server quyết định bài kế; Client không tự NEXT để giải quyết một resync plan vượt cuối bài.
Pause không kích hoạt drift correction. Local correction đổi generation; Host seek/next dùng epoch do Server cấp.

### Metrics và UI

TV1 cung cấp snapshot/raw timestamps, bytes nhận, thời gian fetch và trạng thái hoạt động tải;
TV3 sở hữu phép tính policy/clock, schema/runner/export trong experiment.
Cần chốt activeDownload và chunkFetchMs tính theo request/nhận frame, đặc biệt khi có nhiều request in-flight;
không cộng thời gian overlap của các request rồi gọi đó là throughput toàn luồng.

Gửi BUFFERING_START/END, RESYNC_START/END kèm reason và context qua interface đã nhận từ TV3.
Không đếm startup, pause, seek hoặc EOF có chủ ý thành rebuffer. UI lấy sample/event của cùng session;
chưa có mẫu hợp lệ thì hiển thị chưa có dữ liệu. Cập nhật UI trên EDT, có thể gộp render metric.

## Kiểm thử và bằng chứng bàn giao

| Nhóm | Kịch bản bắt buộc |
|---|---|
| Lifecycle/audio worker | Worker tuần tự; close khi đang connect/pump/request; callback từ session cũ; queue đầy; không leak thread |
| Request/capacity | Cancel lan xuống transport, response muộn, window hữu hạn, gap/out-of-order/duplicate, capacity và partial write |
| Clock bridge | PING không chồng vô hạn; t1/t4 đúng nguồn; clock invalid/stale; TV3 estimator dùng fake clock |
| Scheduled start | Client nhận PLAY sớm/muộn, buffer-ready trước/sau anchor, track ngắn, pause trước anchor, clock mất hiệu lực |
| Resync | Late join/underrun/drift, bỏ plan/generation cũ, cooldown của TV3, thiếu bandwidth, target gần EOF |
| UI | Không block EDT, đúng Host/Member, trạng thái và metric nhất quán khi reconnect thủ công hoặc disconnect |
| Server thật | 3 Client cùng phòng, 2 phòng cô lập, Host leave, seek/next lúc chunk cũ đang về, PCM integrity |
| Thiết bị/đóng gói | WAV local/device smoke ghi rõ điều kiện; audio TCP thật; chạy README trên máy sạch |

Mỗi task cập nhật member-1-client.md với trạng thái Planned/In progress/Verified/Waiting,
test đã chạy và hạn chế. Chỉ công bố số test từ lần verify thực tế, không tự mang số baseline sang code mới.

## Lịch dự kiến theo điều kiện bàn giao

| Đợt | Nội dung | Ước lượng TV1 |
|---|---|---|
| A | I01–I04, triển khai phần độc lập và smoke thiết bị | Khoảng 1–2 ngày tập trung |
| B | I05–I07, sau G1 | Khoảng 1–2 ngày, cộng thời gian phối hợp sửa lỗi API |
| C | I08–I09, sau B và policy thật | Khoảng 1–2 ngày |
| D | I10–I11, sau G2 | Khoảng 1–2 ngày, tùy lỗi tích hợp và khả năng bố trí thiết bị |

Ngày 21/10 dùng để lập kế hoạch và chốt đầu vào. Có thể bắt đầu đợt A ngay khi chuyển sang yêu cầu triển khai.
Các mốc sau phụ thuộc thời điểm TV2/TV3 bàn giao; đây là ước lượng công việc, không cam kết đã đạt lịch cũ.
Ưu tiên fixed + scheduled playback trước adaptive + resync; giữ P1 ngoài đường găng.

## Điểm dừng

Nếu thiếu kiểu/context/API TV3, dừng I05–I09 và bổ sung câu hỏi G1; vẫn làm phần độc lập I02–I04.
Nếu Server chưa sẵn sàng, dừng I10, dùng fixture kiểm thử Client và ghi rõ giới hạn.
Không sửa phần thành viên khác để vượt gate.

## Cập nhật thực thi ngày 21 tháng 10

- I01: hoàn tất tài liệu và ghi nhận phản hồi thiết kế TV3.
- I02: đã có AudioRuntime, queue hữu hạn, coalesced pump tick, gate scope/token, cleanup và snapshot trên audio worker.
- I03: đã có AudioRequest theo generation/index và cancellation lan xuống transport, kể cả API chunk cũ.
- I04: có LocalWavSource/WavSmokeMain; đã chạy WAV tổng hợp qua SourceDataLine thật, DEVICE_API_PASS.
  Xác nhận nghe bằng tai của người dùng chưa ghi nhận, không coi smoke local là nghiệm thu nhiều Client.
- I05–I09: Waiting G1; package TV3 vẫn chưa có interface/implementation được bàn giao.
- I10: Waiting G2; Server vẫn bootstrap và chưa có WAV library/handlers.
- I11: docs phần runtime đã bàn giao; nghiệm thu/experiment cuối vẫn chờ I10 và module TV3.

API, kết quả test và device smoke nằm ở [runtime TV1](member-1-audio-runtime.md).
Bốn đầu vào còn cần TV3 được soạn tại [phản hồi tiếp cho TV3](member-1-followup-to-member-3.md).
