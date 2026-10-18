# Authoritative RoomState v1 — FROZEN FOR P0 IMPLEMENTATION

Thuộc Protocol v1 freeze ở Checkpoint 1D; giữ nguyên semantics A01–A16 đã duyệt và áp dụng ở 1C.

Shared records ở common/model, không phụ thuộc Server. Server interfaces chỉ dùng shared models;
chưa có RoomManager/PlaybackService implementation. Không tạo wire DTO trùng ở Client/Server.

## Fields — tất cả required

| Field | Type / invariant |
|---|---|
| roomId | opaque ID immutable |
| roomCode | 6 uppercase ASCII letters/digits |
| hostClientId | ID, phải nằm trong participants |
| participants | immutable nonempty list Participant(clientId,nickname), clientId unique; capacity là Server config |
| currentTrackId | ID hoặc null, vẫn phải xuất hiện trong JSON |
| playbackState | PAUSED hoặc PLAYING |
| positionMs | integer >=0, vị trí tại anchor, không phải byte đã tải |
| anchorServerTimeMs | epoch-ms >0 trong clock domain Server |
| roomVersion | integer >=1, revision full snapshot |
| streamEpoch | integer >=0; có currentTrackId thì >=1 |
| queue | immutable list QueueItem(queueItemId,trackId,addedByClientId); capacity là Server config |

Queue là bài đang chờ, không gồm current track. Track có thể lặp nhưng queueItemId phải unique.
addedByClientId có thể là member đã rời; không xóa queue theo member. Catalog immutable trong vòng đời Server.
Participant.clientId và hostClientId dùng cùng identity Server cấp khi accept; connectionId là internal alias,
không có participantId riêng. Identity tồn tại qua LEAVE/JOIN trên cùng socket, không xuyên socket mới.
Không track: PAUSED, positionMs=0; epoch có thể >0 nếu stream trước đã kết thúc.
Không thêm CLOSED vào playback enum: ROOM_CLOSED riêng là terminal event, mang final roomVersion.

## Timeline

```text
PAUSED:
  expectedPositionMs = positionMs
PLAYING:
  expectedPositionMs = min(durationMs,
                          positionMs + max(0, estimatedServerNowMs - anchorServerTimeMs))
Chỉ bắt đầu phát khi:
  playbackState == PLAYING AND estimatedServerNowMs >= anchorServerTimeMs
```

Công thức position+delta chỉ áp dụng sau anchor; không cho delta âm ở scheduled start.
positionMs là vị trí track tại anchorServerTimeMs. Trong PAUSED, anchor là lúc commit pause/seek/initial state,
position không tiếp tục tăng. serverTimeMs là lúc tạo snapshot, không thay thế anchor và không rebase timeline.
position=60000, anchor=12:00:00 -> tại 12:00:05 kỳ vọng 65000 ms nếu chưa EOF.
position=60000, anchor=12:00:10 -> tại 12:00:05 giữ 60000 nhưng chưa phát.
serverNow là thời gian Server Client ước lượng qua PONG, không phải local wall clock trực tiếp.

- PLAY từ PAUSED: anchor=serverNow+startLeadMs, ban đầu config 4500 ms.
- SEEK khi PLAYING: anchor tương lai mới; SEEK khi PAUSED: giữ PAUSED.
- NEXT khi PLAYING tiếp tục PLAYING với scheduled anchor; khi PAUSED giữ PAUSED ở bài mới.
- PAUSE trước anchor giữ position; sau anchor chụp expectedPosition nếu chưa EOF; tại EOF xử lý normalization bên dưới trước.
- PLAY đang PLAYING hoặc PAUSE đang PAUSED: no-op, không đổi anchor/version.
- PLAY không current track: pop queue head; queue trống -> EMPTY_QUEUE.
- NEXT cần queue head; trống -> EMPTY_QUEUE, không mutate.
- SEEK cần current track, 0<=positionMs<durationMs; cùng position vẫn tạo generation mới.

Server không có PlaybackEngine. Nó schedule EOF từ anchor + thời lượng còn lại; Pause/Seek/Next/close hủy/lập lại lịch.
Callback phải kiểm tra room/currentTrack/epoch **và generation của lịch**: pause/resume giữ epoch nhưng lịch cũ đã sai.
EOF có queue: pop head, đổi track, position=0, PLAYING với scheduled anchor mới.
EOF không queue: clear currentTrackId, PAUSED, position=0. Client không tự NEXT khi download hết chunk.
Clock projection/EOF scheduler là phase sau; ở đây chỉ chốt semantics.

### Due EOF và command ordering (A07)

Cached retry replay immutable response trước, không chạy requested mutation lần hai.
Với command mới được xử lý trong room, playback mutation và automatic EOF dùng cùng serialization/lock:

1. Normalize timeline và process due EOF trước.
2. EOF transition đổi roomVersion/streamEpoch.
3. Sau đó validate expectedRoomVersion của command.
4. Command revision cũ -> STALE_STATE; gửi ERROR(currentRoomVersion) rồi ROOM_STATE tới valid member.

Không publish resumable snapshot có currentTrackId!=null và PAUSED tại permanent position==durationMs.
Stored positionMs của current-track snapshot phải <durationMs ở cả PAUSED lẫn PLAYING.
Projected expectedPosition có thể chạm duration trong lúc Client chờ transition Server, không biến nó thành anchor mới.
Không resume PLAY từ một anchor đã hết bài. Normalize EOF trước khi kiểm tra command tránh race PAUSE/PLAY với timer.
Business rejection không apply command, nhưng room có thể đã tăng revision do independent due EOF.
Không implement scheduler/locks/normalization algorithm ở Checkpoint 1C.

## roomVersion

- CREATE: state version=1, epoch=0, no track, Host là participant đầu.
- Mỗi atomic transition thực sự đổi state tăng đúng 1: join, leave/member disconnect, queue add,
  play/pause có thay đổi, seek, next, auto-EOF, terminal close.
- Một transition pop queue+đổi track+timeline chỉ tăng một lần.
- Host leave/disconnect: final revision=old+1, không gửi intermediate snapshot thiếu Host.
  Host leave là một close transition, không cộng riêng LEAVE +1 rồi CLOSE +1.
- No-op play/pause, retry/error, chunk/PING/metrics/SYNC, thời gian trôi: không tăng.
- expectedRoomVersion phải bằng current version, sau lookup dedup. Không dùng >= để accept command.
- Không reset/wrap trong cùng roomId; chạm int64 ceiling phải dừng mutation an toàn.
- SYNC gửi lại nguyên anchor/version, không rebase snapshot chỉ để báo vị trí mới.

## streamEpoch

- Initial=0. Chọn current track lần đầu: +1.
- +1 khi SEEK, NEXT/đổi track, auto-EOF đổi/clear track vì audio generation cũ mất hiệu lực.
- NEXT sang cùng trackId do queue lặp vẫn tăng epoch.
- PLAY/PAUSE cùng track, queue/member thay đổi: không tăng; chunk cùng bài còn hợp lệ khi resume.
- Local drift correction không tăng epoch chung. Client không tự thay authoritative epoch.
- Chunk phải khớp active roomId, trackId và epoch trước khi phát; không chỉ check trackId.
- Chunk epoch lớn hơn state: chờ snapshot trong bounded window hoặc bỏ đi, không tự apply chunk thành state.
- roomVersion mới không đồng nghĩa stream mới; không flush audio chỉ vì một member join.

## Validation boundary

Common validator kiểm tra schema/IDs/limits/uniqueness/Host membership/no-track/revision/epoch và matching roomId.
validateStateAgainstCatalog kiểm tra current/queued track và position/duration. Caller phải gọi với catalog đang giữ
trước khi apply state. Quyền actor, version transition, dedup, concurrency và terminal lifecycle cần runtime tests
ở phase sau; không thể chứng minh chỉ từ một DTO.
Giới hạn 10 member/100 queue là configurable Server defaults, không phải common schema ceiling.
Wire-boundary validation kiểm tra encoded full message vẫn fit frame limit.

## Single state-apply pipeline và membership gate (A15–A16)

ROOM_STATE, QUEUE_STATE, PLAYBACK_STATE, SYNC_STATE đều chứa cùng authoritative full RoomState.
CREATE/JOIN response chỉ thiết lập membership/state khi khớp pending lifecycle operation.
LEAVE_ACK chỉ unbind membership tương ứng khi khớp pending LEAVE; roomClosed=true cho Host leave.
ROOM_STATE tới member còn lại thông báo membership change, không thay LEAVE_ACK cho actor.
QUEUE_STATE cho queue change; PLAYBACK_STATE cho playback/EOF; SYNC_STATE optional recovery/resync,
không yêu cầu periodic 1 Hz và không tạo nguồn authoritative thứ hai.

Full snapshot chỉ apply vào currently active room/membership scope, sau validation và revision checks.
Sau LEAVE_ACK/ROOM_CLOSED, ignore late snapshot của membership đã rời/đóng. Cached lifecycle response
không được rebind/unbind membership mới, kể cả roomId trùng do rejoin.
Timeout mutation là UNKNOWN: retry same ID/payload/connection trong configured retention/retry window.
STALE_STATE là completed rejection: ERROR -> ROOM_STATE -> apply; nếu user vẫn muốn thì command mới,
requestId mới và expectedRoomVersion mới. Chưa có Client state-apply implementation trong checkpoint này.
