# Protocol v1 — FROZEN FOR P0 IMPLEMENTATION

A01–A16 đã được người dùng phê duyệt và áp dụng ở Checkpoint 1C; Checkpoint 1D freeze contract này cho P0.
Giữ nguyên semantics A01–A16 và 22 MessageType. Không tự đổi contract khi triển khai runtime;
thay đổi contract phải được review và phê duyệt trước.
TV1 sở hữu common/Client; TV2 sở hữu Server; TV3 sở hữu clock estimation/buffer/experiment.

Phạm vi freeze: message catalog, identity, requestId/idempotency, roomVersion, streamEpoch,
playback timeline, EOF ordering, PING/PONG timestamps, PCM/audio, errors, state push/apply,
late response/retry và phân biệt wire limits / Server config / experiment config.
Schema và semantics chi tiết nằm trong tài liệu này, [RoomState](room-state.md) và [audio contract](audio-contract.md).

Đã implement: shared DTO, strict JSON/envelope, payload validator, binary audio codec và contract tests.
Chưa implement: identity binding, nghiệp vụ, result cache, writer queue, WAV reader, streaming, timer/sync hoặc Client.
Bootstrap vẫn trả NOT_IMPLEMENTED; chưa gọi payload validator trong dispatcher. Chặng TCP tiếp theo phải
tích hợp validation/error mapping. Contract tests không phải integration tests nghiệp vụ.
Domain audio service chỉ trả AudioChunkData, không sở hữu requestId; protocol layer tạo wire response.

## 1. Framing

```text
[bodyLength:int32 big-endian][frameType:uint8][payload:bodyLength-1 bytes]
1 <= bodyLength <= 65536, không bao gồm 4 byte prefix
0x01 CONTROL_JSON: JSON UTF-8 object
0x02 AUDIO_CHUNK: binary metadata + PCM, chỉ Server -> Client
```

Không newline framing/Base64 PCM. Mỗi socket một reader; writer serialize cả frame bằng lock.
Reader xử lý partial reads. EOF trước frame mới = disconnect; EOF giữa frame = lỗi.
Length <=0/quá trần, type lạ hoặc frame thiếu: đóng connection, không tiếp tục trên stream lệch.
Không allocate body vượt trần. FrameCodec cho phép cấu hình thấp hơn, không cao hơn trần v1;
deployment P0 dùng 65536 cho cả hai phía, chưa có negotiation. Control JSON tối đa 65535 byte.

JSON từ chối duplicate key, trailing JSON, unknown field, sai scalar type, float ở integer,
numeric string, primitive null và nesting depth >32. JsonMessageCodec chỉ parse envelope;
PayloadCodec.decode/ProtocolValidator.validate kiểm tra message đầy đủ. AUDIO_CHUNK không phải control JSON.

Frame/JSON hỏng không có envelope đáng tin cậy: đóng socket, không bịa requestId.
Nếu envelope hợp lệ nhưng payload sai: ERROR/INVALID_REQUEST, giữ requestId, không mutate.
Unknown type hoặc sai direction: UNSUPPORTED_MESSAGE nếu có envelope/requestId đáng tin cậy;
nếu không thì đóng socket. Không map parsing failure tùy tiện sang INTERNAL_ERROR.
JsonMessageCodec strict-parse JSON tree trước khi resolve type. Envelope đáng tin khi root là object,
không duplicate/trailing/unknown envelope fields; type là nonblank string, version là int32 integer,
requestId là canonical UUID, payload là object, roomId omitted/null hoặc ID hợp lệ.
Không echo ID từ JSON thiếu field, sai kiểu, duplicate key, sai ID hoặc frame hỏng.
ProtocolException.correlation() chứa context khi có thể trả ERROR an toàn; empty context thì đóng connection.
Known type được parse thành ProtocolMessage; validateClientRequest/validateServerMessage kiểm tra direction
và full payload. Generic validate/decode dùng cho contract DTO, không suy đoán direction.
Bootstrap chưa dùng các direction guard/error context này, vẫn giữ hành vi scaffold cũ cho tới Phase 2.

## 2. Envelope / correlation / push

| Field | Required | Quy tắc |
|---|---|---|
| type | có | enum MessageType, phân biệt hoa/thường |
| protocolVersion | có | JSON integer =1; khác version -> UNSUPPORTED_VERSION |
| requestId | có | UUID canonical 8-4-4-4-12; retry giữ nguyên chuỗi |
| roomId | tùy type | null/omitted cho CREATE_ROOM, JOIN_ROOM, PING, PONG; required cho message phòng; ERROR echo scope request |
| payload | có | object, chỉ các field được định nghĩa |

Direct response/ERROR echo requestId. Client kiểm tra expected type, requestId và room scope.
CREATE/JOIN response có roomId mới: đối chiếu pending request và payload.state.roomId trước khi bind.
Push chỉ gồm ROOM_STATE, QUEUE_STATE, PLAYBACK_STATE, SYNC_STATE, ROOM_CLOSED; mỗi push có UUID mới,
không reuse requestId cũ. Không thêm envelope field. SYNC_STATE/ROOM_CLOSED luôn là push.
Push vào event handler, không complete future không liên quan. AUDIO_CHUNK chỉ là direct response.

Trên connection actor, direct response đi trước push của cùng mutation. Không có total ordering giữa connections.
Snapshot thấp version hơn thì bỏ qua nhưng vẫn complete response future; bằng version không overwrite state.
SYNC_STATE bằng version vẫn là tín hiệu đo drift với timeline hiện tại. ROOM_CLOSED là terminal:
snapshot về muộn không được mở lại roomId đã đóng.

## 3. Identity / permissions

- Server cấp clientId opaque tại accept; connectionId là internal name/alias của cùng identity,
  không cần sinh thêm identity độc lập và không truyền connectionId trên wire.
- clientId ổn định trong vòng đời socket; Participant dùng chính clientId, không thêm participantId.
- CREATE/JOIN thành công bind connection vào đúng một phòng; trả clientId trong membership response.
- Client không gửi actorId/clientId trong request; Server lấy actor từ connection, không tin nickname.
- Sau LEAVE, socket/clientId còn sống và có thể create/join mới. TCP đóng thì identity hết hiệu lực.
- P0 không login/resume token/reconnect/host transfer. Nickname có thể trùng.
- roomId immutable, Server cấp, không tái dùng. roomCode unique trong phòng mở, 6 ký tự `[A-Z0-9]`.
- ID thường: `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Nickname 1..48 Unicode code point; title 1..128;
  không blank/control/khoảng trắng đầu-cuối. Không tự normalize payload khác đi giữa các retry.
- CREATE/JOIN: unbound; LEAVE/QUEUE_ADD/AUDIO_CHUNK_REQUEST/CLIENT_METRICS: mọi member;
  PLAY/PAUSE/SEEK/NEXT_TRACK: chỉ Host. PING cho mọi connection, luôn roomId=null.
- Sai membership/scope -> NOT_IN_ROOM. CREATE/JOIN mới khi đang bind -> ALREADY_IN_ROOM.
- Host leave/disconnect: đóng phòng, push ROOM_CLOSED rồi unbind member còn lại; không chuyển Host.
  Member leave/disconnect: remove participant, push state, không ảnh hưởng phòng khác.

## 4. Payload catalog

**Tất cả field trong bảng required, non-null**, trừ optional ghi rõ. Integer dùng signed int64,
trừ count/format/byte length dùng int32. `R`=integer >=1; `T`=epoch milliseconds >0.
Shared [RoomState](room-state.md) và [TrackMetadata/audio](audio-contract.md) có schema riêng.

| Request | Payload | Response | Quyền / lỗi nghiệp vụ chính |
|---|---|---|---|
| CREATE_ROOM | nickname:string | ROOM_CREATED | unbound; ALREADY_IN_ROOM, SERVER_BUSY |
| JOIN_ROOM | nickname:string, roomCode:string | ROOM_STATE | unbound; ALREADY_IN_ROOM, ROOM_NOT_FOUND/CLOSED, ROOM_FULL |
| LEAVE_ROOM | `{}` | LEAVE_ACK | member; NOT_IN_ROOM |
| QUEUE_ADD | trackId:ID, expectedRoomVersion:R | QUEUE_STATE | member; TRACK_NOT_FOUND, STALE_STATE, RATE_LIMITED |
| PLAY | expectedRoomVersion:R | PLAYBACK_STATE | Host; NOT_HOST, STALE_STATE, EMPTY_QUEUE |
| PAUSE | expectedRoomVersion:R | PLAYBACK_STATE | Host; NOT_HOST, STALE_STATE |
| SEEK | positionMs:integer >=0, expectedRoomVersion:R | PLAYBACK_STATE | Host; NOT_HOST, STALE_STATE, INVALID_SEEK |
| NEXT_TRACK | expectedRoomVersion:R | PLAYBACK_STATE | Host; NOT_HOST, STALE_STATE, EMPTY_QUEUE |
| AUDIO_CHUNK_REQUEST | trackId:ID, streamEpoch:integer >=1, chunkIndex:integer >=0 | AUDIO_CHUNK binary | member; TRACK_NOT_FOUND, STALE_STREAM, INVALID_REQUEST, RATE_LIMITED |
| PING | clientSendTimeMs:T | PONG | mọi connection |
| CLIENT_METRICS | streamEpoch:integer >=0, bufferLevelMs:integer >=0, targetBufferMs:integer >0, rttMs:number finite >=0, driftMs:signed integer | METRICS_ACK | optional P0 telemetry của member; STALE_STREAM, RATE_LIMITED |

Mọi request còn có INVALID_REQUEST, UNSUPPORTED_VERSION/MESSAGE, SERVER_BUSY, INTERNAL_ERROR.
Mọi request phòng có NOT_IN_ROOM, ROOM_NOT_FOUND/CLOSED; mọi mutation có REQUEST_ID_CONFLICT.
R phải bằng roomVersion hiện tại; tra dedup trước check R. LEAVE không đòi R để có thể thoát với snapshot cũ.
Metrics epoch phải khớp current streamEpoch (0 khi chưa có track), không mutate room state.
Metrics không cần mutation-result dedup. Reporting interval/measurement policy thuộc TV3 config;
không đóng cứng trần 60 giây/±1 giờ vào wire schema. Chưa có sample hợp lệ thì không bịa report.

| Server message | Payload | Direct/push |
|---|---|---|
| ROOM_CREATED | clientId:ID, state:RoomState, tracks:TrackMetadata[], serverTimeMs:T | CREATE response |
| ROOM_STATE | clientId:ID, state:RoomState, tracks:TrackMetadata[], serverTimeMs:T | JOIN response hoặc recipient-specific membership push |
| LEAVE_ACK | clientId:ID, roomVersion:R, roomClosed:boolean | LEAVE response, roomId là phòng vừa rời |
| QUEUE_STATE | state:RoomState, serverTimeMs:T | QUEUE_ADD response hoặc push |
| PLAYBACK_STATE | state:RoomState, serverTimeMs:T | playback response hoặc push |
| SYNC_STATE | state:RoomState, serverTimeMs:T | optional recovery/resync push, không bắt buộc periodic 1 Hz, không tăng version |
| PONG | clientSendTimeMs:T, serverReceiveTimeMs:T, serverSendTimeMs:T | PING response, echo clientSendTimeMs |
| METRICS_ACK | streamEpoch:integer >=0, serverReceiveTimeMs:T | metrics response, echo epoch report hợp lệ |
| ROOM_CLOSED | roomVersion:R, reason:HOST_LEFT hoặc SERVER_SHUTDOWN, serverTimeMs:T | terminal push |
| ERROR | code:ErrorCode, message:string 1..256, retryable:boolean; optional currentRoomVersion:R hoặc null | direct response |
| AUDIO_CHUNK | metadata + raw PCM theo audio-contract | direct binary, không audio push |

Mọi STATE chứa full snapshot, không delta. Catalog immutable trong vòng đời Server,
gửi đầy đủ khi create/join và ROOM_STATE push; không cần thêm GET_TRACKS.
Client giữ catalog để contextual validation. Recipient clientId phải có trong participants.
currentTrackId bắt buộc xuất hiện nhưng được null. envelope.roomId phải bằng state.roomId.
serverTimeMs là lúc tạo snapshot/notification, không phải lúc byte cuối được ghi socket.
ROOM_CREATED phải chứa phòng mới: version=1, epoch=0, chưa có track/queue, chỉ có Host là recipient.
Với PAUSED, anchorServerTimeMs không được sau serverTimeMs của snapshot; PLAYING được có scheduled anchor tương lai.

LEAVE_ACK là response cho actor LEAVE, xác nhận membership kết thúc; ROOM_STATE tới member còn lại không thay ACK.
CLIENT_METRICS là optional telemetry. Nếu gửi thì có METRICS_ACK cùng requestId; không retry backlog telemetry.
SYNC_STATE chỉ là optional full-state resynchronization, không là nguồn authoritative thứ hai;
không bắt buộc Server push 1 Hz để Client tính expected position/drift từ timeline và clock estimation.

## 5. Idempotency — chỉ contract, chưa có cache implementation

Áp dụng CREATE/JOIN/LEAVE/QUEUE_ADD/PLAY/PAUSE/SEEK/NEXT_TRACK (8 mutation).

- Key `(connectionId,requestId)`, connectionId là alias của clientId ổn định của connection. roomId nằm trong fingerprint,
  không trong key: xử lý pre-join nhất quán và phát hiện reuse ID sang room khác.
- SHA-256 fingerprint của canonical `{type,protocolVersion,roomId,payload}`: sort object keys đệ quy,
  giữ array order/string nguyên văn, normalize integer theo giá trị; omitted roomId tương đương null.
- Cùng key/payload: replay đúng kết quả/snapshot/requestId cũ, không mutate, broadcast hoặc bind lần hai.
  Cached snapshot có thể cũ: Client complete future nhưng không rollback state.
- Cùng key nhưng khác type/room/payload: REQUEST_ID_CONFLICT, không overwrite entry cũ.
- Reserve entry trước mutation; duplicate concurrent đợi chung kết quả có timeout hoặc nhận SERVER_BUSY,
  tuyệt đối không execute mutation lần hai.
- TTL/capacity/retry window là configurable defaults, không phải wire-schema invariant.
  Defaults trong config: TTL 120000 ms, 256 entry/connection, 4096 toàn Server, Client retry window 60000 ms.
  TTL tính bằng monotonic clock từ khi terminal result hoàn tất; chỉ guarantee replay khi entry còn retained,
  trên cùng connection. Không hứa global minimum retention 120 giây như protocol invariant.
  Admission/eviction policy phải bounded; nên từ chối command mới trước mutation nếu retention budget đầy.
  Deployment phối hợp retry window với retention policy, không suy ra guarantee sau expiry/eviction.
- Cache success/terminal business error. Không cache transient SERVER_BUSY/RATE_LIMITED khi chưa mutate.
  INTERNAL_ERROR không auto-retry vì kết quả có thể mơ hồ; schema error reject trước mutation.
- Client retry giữ nguyên requestId/type/roomId/payload, trên cùng connection, trong retry/dedup window được cấu hình.
  Logical command mới dùng UUID mới. Quá cửa sổ: dừng retry, đợi state authoritative; không tự đổi ID cho NEXT/QUEUE_ADD.
- Không cam kết dedup sau khi entry không còn retained, Server restart hoặc connection mới. Không có durable exactly-once trong P0.
- CREATE/JOIN dùng roomId=null, actor cấp khi accept. Tra cache trước already-bound để retry JOIN không tạo member mới.
  LEAVE retry giữ roomId cũ, tra cache trước membership. Không retry membership command cũ sau một transition mới.

PING/CLIENT_METRICS/AUDIO_CHUNK_REQUEST không dùng mutation-result cache; audio read luôn kiểm tra active context.
Cache giữ immutable previous response (type/scope/requestId/payload), không chỉ revision hoặc RoomState trần.
Commit success và result phải nhất quán trước network send; lỗi gửi response không được tạo mutation mới.
Phase sau triển khai bounded cache và concurrency tests; không thêm RequestDeduplicator runtime ở chặng này.

## 6. Timestamp

PING: t1=clientSendTimeMs. Server t2=serverReceiveTimeMs ngay sau nhận frame hợp lệ;
t3=serverSendTimeMs trên writer, sau khi lấy PONG khỏi outbound queue, ngay trước serialize/write frame
(không phải TCP transmission completion). Không chụp t3 lúc enqueue rồi để response chờ lâu.
Client tự ghi t4=clientReceiveTimeMs khi nhận đủ response, lưu thêm monotonic send/receive duration.

Wire timestamp = epoch milliseconds. Server dùng cùng clock domain cho t2/t3/anchor/serverTimeMs.
ClockSource có thể có epochMillis() và monotonicNanos(); production có thể dùng System.currentTimeMillis()
và System.nanoTime(). Không bắt buộc xây clock framework hoặc epoch-projection algorithm ở checkpoint này.
Không truyền/so sánh nanoTime giữa process. Duration/deadline nội bộ dùng monotonic clock.
PONG yêu cầu t3>=t2; sample clock step/delay bất thường thì TV3 discard, không sửa room state.
TV3 triển khai công thức/filter RTT/offset, TV2 cung cấp timestamp. Không suy ra one-way delay chính xác
trên đường truyền bất đối xứng. Capture points theo A08 đã duyệt; TV3 triển khai estimator ở phase tương ứng.

## 7. Errors

| Code | Khi dùng |
|---|---|
| INVALID_REQUEST | schema/range sai, scope format sai, chunk index ngoài track |
| UNSUPPORTED_VERSION | protocolVersion khác 1 |
| UNSUPPORTED_MESSAGE | type/direction chưa được hỗ trợ, nếu envelope đủ tin cậy để correlate |
| FRAME_TOO_LARGE | vượt trần frame/control; framing lỗi thì đóng socket, không hứa gửi ERROR |
| ROOM_NOT_FOUND | roomId/code không tồn tại |
| ROOM_CLOSED | phòng đã đóng còn biết tombstone; đã dọn tombstone thì ROOM_NOT_FOUND |
| ROOM_FULL | vượt participant capacity cấu hình ở Server |
| NOT_IN_ROOM | actor chưa bind hoặc không thuộc roomId yêu cầu |
| ALREADY_IN_ROOM | CREATE/JOIN mới khi đang bind; cached retry replay trước |
| NOT_HOST | member gọi playback command |
| TRACK_NOT_FOUND | trackId ngoài catalog |
| INVALID_SEEK | chưa có track hoặc positionMs ngoài [0,durationMs) |
| STALE_STATE | expectedRoomVersion khác revision hiện tại |
| STALE_STREAM | track/epoch không khớp stream hiện tại |
| EMPTY_QUEUE | PLAY không current track lẫn queue, hoặc NEXT không có bài chờ |
| REQUEST_ID_CONFLICT | reuse requestId với type/scope/payload khác |
| RATE_LIMITED | queue/audio window hoặc resource/rate policy cấu hình ở Server |
| SERVER_BUSY | chưa nhận mutation do worker/cache/global resource đầy |
| INTERNAL_ERROR | lỗi bất ngờ, không lộ stacktrace/path, không auto-retry mutation |
| NOT_IMPLEMENTED | chỉ bootstrap, không dùng thay handler nghiệp vụ sau này |

retryable là thuộc tính ERROR instance/context, không phải thuộc tính cố định của ErrorCode.
Common validator không suy ra cờ từ code; producer chỉ đặt true khi retry SAME logical request là an toàn.
True không có nghĩa đổi requestId, guaranteed success hoặc kéo dài retry/cache lifetime.
Helper không truyền flag thì mặc định false bảo thủ; overload boolean cho producer quyết định tường minh.
STALE_STATE là completed business rejection, khác timeout/unknown outcome: nhận authoritative state rồi,
nếu người dùng vẫn muốn thao tác, tạo command mới với requestId mới và expectedRoomVersion mới.
currentRoomVersion optional, chỉ tiết lộ khi actor có quyền; STALE_STATE nên kèm revision hiện tại.
Với STALE_STATE của valid member: ERROR phải có currentRoomVersion, sau đó ROOM_STATE full snapshot.
Business rejection không apply requested mutation; due EOF có thể đã được normalize trước đó và tăng revision.
INTERNAL_ERROR không cho Client tự suy ra chưa mutate; không auto-resubmit command mới khi outcome không rõ.
Phase nghiệp vụ phải tránh partial commit và serialize transitions/cache result.

## 8. Examples / contract tests

- [21 control JSON examples](examples/control-v1.json), một example/type. Đây là ví dụ độc lập, không phải một phiên:
  UUID mẫu lặp để dễ đọc, không được reuse như vậy cho logical request khác nhau trên cùng connection.
- [AUDIO_CHUNK metadata + PCM hex](examples/audio-chunk-v1.json), xem [binary layout](audio-contract.md).
- [Invalid examples](examples/invalid-control-v1.json), suite kiểm tra tất cả bị reject.
- Tests kiểm tra thiếu từng required payload/state/track/audio field, mọi control type round-trip,
  strict scalar, room/catalog context, frame boundary, PCM alignment/final chunk và requestId.
- Codec pass không chứng minh quyền, dedup, clock hoặc playback runtime hoạt động.

## 9. Limits / config classification

- Wire invariants: body <=65536, control JSON <=65535, metadata <=2048, PCM <=61440 và PCM frame alignment.
- Server admission defaults: max tracks=20, max participants=10, max queue=100, chunk duration=250 ms,
  audio outstanding window=4, dedup TTL/capacity. Không là hard rejection của common schema.
- Client retry window default=60 s là configurable policy, không wire field/constant; guarantee chỉ same connection khi entry retained.
- TV3/experiment config: sync/metrics interval, start lead, buffer targets, drift threshold và adaptive parameters.
- Full snapshots vẫn phải fit actual frame ceiling: ProtocolValidator/JsonMessageCodec kiểm tra encoded bytes.
  Tăng config không cho phép gửi frame quá lớn; Server admission phải kiểm tra resulting snapshot trước commit.
- Bootstrap chỉ đọc TCP/protocol keys. Resource/Client/TV3 keys là reserved defaults cho phase tương ứng, chưa có runtime consumer.

## 10. State apply / push / late response

Chỉ có một logical state-apply pipeline cho ROOM_STATE/QUEUE_STATE/PLAYBACK_STATE/SYNC_STATE.
Message type mô tả nguyên nhân, không tạo nguồn authoritative riêng:

- CREATE/JOIN response: thiết lập membership + state khi khớp pending lifecycle operation.
- JOIN/LEAVE member change: ROOM_STATE tới member còn lại.
- Queue mutation: QUEUE_STATE; playback mutation/EOF: PLAYBACK_STATE.
- STALE_STATE recovery: ERROR(currentRoomVersion) rồi ROOM_STATE full snapshot tới valid member.
- SYNC_STATE: optional recovery/resync, không bắt buộc periodic.
- Host leave: Host nhận LEAVE_ACK(roomClosed=true), member còn lại nhận ROOM_CLOSED.

CREATE/JOIN/LEAVE response chỉ bind/unbind local membership nếu khớp relevant pending lifecycle operation.
Các full state khác chỉ apply vào currently active room/membership scope sau validation và revision check.
Snapshot thấp/equal revision không overwrite state; response future có thể vẫn được complete.
Sau LEAVE_ACK/ROOM_CLOSED, snapshot muộn của membership đã rời/đóng bị ignore; không rebind từ cached response.
Nếu rejoin cùng room, không dùng response của lifecycle operation cũ để thiết lập membership mới.

Timeout mutation = UNKNOWN outcome. Transport retry giữ SAME ID/payload/connection trong retry/dedup window;
không tự đổi ID để thực hiện lại NEXT/QUEUE_ADD. Late response chỉ complete pending operation nếu còn phù hợp;
full snapshot qua pipeline riêng chỉ khi active scope/revision phù hợp, không revive membership.
STALE_STATE = business rejection đã hoàn tất. Quy trình: ERROR -> ROOM_STATE -> apply ->
người dùng còn muốn thì NEW requestId + NEW expectedRoomVersion. Command mới đó không phải transport retry.

## 11. Phase 1 freeze gate — Checkpoint 1D

- [x] Catalog, DTO/validator/codecs và examples đồng bộ.
- [x] Version/epoch/timeline/retry/time/audio semantics được mô tả.
- [x] A01–A16 là các quyết định đã được phê duyệt, không hỏi lại như open questions.
- [x] Không implement room/cache/clock/streaming/connection business trong checkpoint này.
- [x] Protocol v1 = FROZEN FOR P0 IMPLEMENTATION; không thay semantics A01–A16.
- Mốc Phase 1 được commit sau final verify/self-review; không push hoặc tạo tag trong checkpoint này.
- Freeze contract không đồng nghĩa runtime đã hoàn thành; Phase 2 chỉ bắt đầu khi có yêu cầu mới.
