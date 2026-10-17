# Protocol draft v1 — CHƯA FREEZE

Owner TV1; TV2 review Server; TV3 review timestamp, metric và epoch.
Mã hiện có chỉ kiểm tra frame và JSON/envelope. Chưa có validator payload từng message.
Không dùng file này để tuyên bố Phase 2 đã hoàn thành.

## Framing hiện có

```text
[bodyLength: int32 big-endian][frameType: uint8][payload]
bodyLength bao gồm frameType, không bao gồm 4 byte length.
1 <= bodyLength <= protocol.maxFrameBytes (mặc định 65536).
0x01 CONTROL_JSON   0x02 AUDIO_CHUNK
```

Reader hỗ trợ partial reads và nhiều frame liên tiếp. EOF trước frame mới trả null;
EOF giữa header/body là lỗi. Client bootstrap chỉ được gửi control, không gửi PCM.
Một socket có một reader; `ClientConnection` serialize complete control writes bằng write lock.

## Control envelope hiện có

```json
{
  "type": "CREATE_ROOM",
  "protocolVersion": 1,
  "requestId": "10000000-0000-0000-0000-000000000001",
  "roomId": null,
  "payload": {"nickname": "Host A"}
}
```

`type`, `protocolVersion`, `requestId`, `roomId`, `payload` là field dự kiến.
requestId phải là UUID canonical. roomId null trước bind; payload là object.
JSON field lạ, duplicate key và trailing JSON bị codec từ chối.
Kiểm tra required/optional theo từng message và room binding vẫn là TODO.

Response cùng requestId; push có UUID mới. Chưa có push hoặc correlation Client implementation.
`BootstrapDispatcher` trả `ERROR/NOT_IMPLEMENTED` cho mọi request nghiệp vụ, kể cả PING.
Ví dụ bootstrap response:

```json
{
  "type": "ERROR",
  "protocolVersion": 1,
  "requestId": "10000000-0000-0000-0000-000000000001",
  "roomId": null,
  "payload": {
    "code": "NOT_IMPLEMENTED",
    "message": "CREATE_ROOM is not implemented in the project skeleton",
    "retryable": false
  }
}
```

NOT_IMPLEMENTED là scaffold code, phải review trước khi freeze protocol thực tế.
Frame/JSON hỏng không có requestId đáng tin cậy thì đóng socket, không bịa requestId để trả lỗi.

## Catalog đề xuất

| Request | Payload cần thống nhất | Response dự kiến |
|---|---|---|
| CREATE_ROOM | nickname | ROOM_CREATED |
| JOIN_ROOM | nickname, roomCode | ROOM_STATE |
| LEAVE_ROOM | room từ envelope | LEAVE_ACK |
| QUEUE_ADD | trackId, expectedRoomVersion | QUEUE_STATE |
| PLAY / PAUSE / NEXT_TRACK | expectedRoomVersion | PLAYBACK_STATE |
| SEEK | positionMs, expectedRoomVersion | PLAYBACK_STATE |
| AUDIO_CHUNK_REQUEST | trackId, streamEpoch, chunkIndex | AUDIO_CHUNK binary |
| PING | clientSendTimeMs | PONG |
| CLIENT_METRICS | epoch, bufferLevelMs, targetBufferMs, rttMs, driftMs | METRICS_ACK |

Push dự kiến: ROOM_STATE, PLAYBACK_STATE, QUEUE_STATE, SYNC_STATE, ROOM_CLOSED.
Room snapshot nên đầy đủ để version mới của queue không làm mất playback update đến chậm.
PONG dự kiến echo client send timestamp và thêm server receive/send timestamps.

## Audio binary đề xuất, chưa implement

`[metadataLength:uint16 big-endian][metadata JSON UTF-8][PCM raw]`.
Metadata cần requestId, roomId, trackId, streamEpoch, chunkIndex, startFrame, frameCount và isLast.
Không Base64 audio. Giới hạn metadata, frame alignment, EOF và codec round-trip cần test riêng.
`AudioChunk` hiện là domain model; chưa dùng nó như wire schema đã chốt.

## Checklist freeze

- [ ] Required/optional/type/range cho từng payload và timestamp unit/clock domain.
- [ ] Role và bound-session identity; correlation, push, retry và idempotency.
- [ ] roomVersion/streamEpoch, stale response và chunk cancellation.
- [ ] Error mapping và giới hạn frame/metadata/request window.
- [ ] JSON example mỗi message; binary golden fixture; payload validation tests.
- [ ] TV1, TV2, TV3 review rồi mới gắn tag protocol-v1.
