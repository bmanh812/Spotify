# Audio contract v1 — FROZEN FOR P0 IMPLEMENTATION

Thuộc Protocol v1 freeze ở Checkpoint 1D; giữ nguyên semantics A01–A16 đã duyệt và áp dụng ở 1C.

Chỉ shared serialization; chưa có WAV parser, disk reader, socket streaming hoặc playback engine.

## WAV / TrackMetadata

P0: RIFF/WAVE PCM signed 16-bit little-endian, 44100 Hz, stereo, interleaved L/R.
Không MP3/float/compressed/extensible WAV/transcoding. Server đọc RIFF chunks và padding,
không giả định header 44 byte; chỉ gửi PCM của data chunk, không gửi WAV header.
WAV sai format bị loại khỏi catalog khi scan, không đẩy lỗi decode xuống Client.

Tất cả TrackMetadata fields required:

| Field | Quy tắc |
|---|---|
| trackId | opaque ID ổn định trong catalog, không phải path |
| title | 1..128 Unicode code point, không blank/control/whitespace đầu-cuối |
| sampleRate / channels / sampleSizeBits / frameSizeBytes | integer 44100 / 2 / 16 / 4 |
| totalFrames | integer >=45, <=floor(Long.MAX_VALUE/4), tối thiểu 1 ms |
| totalPcmBytes | totalFrames*4, không tính header |
| durationMs | floor(totalFrames*1000/44100) |
| chunkFrames | integer 1..15360, fixed per track trong vòng đời Server |
| chunkCount | ceil(totalFrames/chunkFrames) |

Catalog immutable; max tracks là Server admission config, default 20, không phải common schema ceiling.
Duration/file-size và tổng dung lượng thực tế còn cần deployment limit,
không được hiểu int64 wire range là khuyến nghị nhận file khổng lồ.
audio.chunkDurationMs configurable: chunkFrames=floor(sampleRate*chunkDurationMs/1000), reject config vượt giới hạn.
250 ms -> 11025 frame -> 44100 PCM byte. Client lấy chunkFrames từ catalog, không hard-code 250 ms.

## Binary layout

```text
int32 BE bodyLength = 1 + 2 + metadataLength + pcmByteLength
uint8 frameType = 0x02
uint16 BE metadataLength (1..2048)
metadataLength bytes UTF-8 JSON AudioChunkMetadata
pcmByteLength bytes raw PCM little-endian
```

bodyLength <=65536; PCM <=61440 byte (=15360 frame).
Worst case 1+2+2048+61440=63491, trong trần. Không trailing padding/Base64.
metadataLength tính UTF-8 bytes, không char count. Outer integers BE, sample PCM **LE**.

Tất cả AudioChunkMetadata fields required:

| Field | Quy tắc |
|---|---|
| type / protocolVersion | AUDIO_CHUNK / integer 1 |
| requestId | UUID echo AUDIO_CHUNK_REQUEST |
| roomId / trackId | ID khớp active room/current track |
| streamEpoch | integer >=1; epoch=0 không có audio |
| chunkIndex | integer >=0, <chunkCount |
| startFrame | integer =chunkIndex*chunkFrames |
| frameCount | min(chunkFrames,totalFrames-startFrame), >0 |
| isLast | boolean =(startFrame+frameCount==totalFrames) |
| pcmByteLength | integer =frameCount*4, bằng số PCM byte thực nhận |

sampleRate/channels/bits/frameSize/totalFrames lấy từ immutable TrackMetadata, không lặp trong mỗi chunk.
Timeline dùng ms; sample offsets dùng PCM frame, không trộn hai đơn vị.

## Chunk cuối / seek

Chunk cuối có thể ngắn: gửi đúng frameCount/bytes, không zero-pad. Nếu chia hết chunkFrames,
chunk cuối vẫn isLast=true, không thêm empty chunk. isLast không có nghĩa Client đã phát xong.

```text
targetFrame = floor(positionMs * sampleRate / 1000)
chunkIndex = floor(targetFrame / chunkFrames)
startFrame = chunkIndex * chunkFrames
discardLeadingFrames = targetFrame - startFrame
fileOffset = dataChunkOffset + startFrame * frameSizeBytes
```

SEEK cần 0<=positionMs<durationMs. Client lấy chunk chứa target và bỏ prefix frame trước target;
không đổi chunk grid sau seek, không dùng bytes downloaded làm playback position. Tính toán tránh overflow int64.
Mỗi SEEK có epoch mới, kể cả cùng position; old in-flight chunk có thể tới, phải reject stale epoch.
PLAYBACK_STATE của SEEK và audio requests là các flow riêng, chưa implement trong phase này.

## Backpressure / validation

Pull AUDIO_CHUNK_REQUEST -> binary response cùng requestId; không audio push/PCM ACK.
Outstanding audio window là Server config, default 4/connection; vượt window -> RATE_LIMITED.
Client chịu resource policy/backoff; con số 4 không phải audio metadata schema invariant.
Audio read-only có thể retry cùng requestId nếu active room/track/epoch còn hợp lệ;
không replay cached PCM của epoch cũ sau seek.
Server check membership, rồi track/epoch, rồi index. Invalid index -> INVALID_REQUEST,
unknown catalog track -> TRACK_NOT_FOUND; current track/epoch khác -> STALE_STREAM.
Frame/metadata/length hỏng: Client không phát PCM và đóng connection; stale chunk đúng cấu trúc chỉ discard.
Không giữ room lock khi disk I/O/socket write. Bounded writer queue và control priority là phase sau.

AudioChunkCodec kiểm tra cấu trúc/length/frame alignment; validateAgainstTrack kiểm tra grid/index/final chunk.
Caller phải check active room/epoch và matching pending request; common codec không enforce membership runtime.

## Domain service và wire responsibility (A10)

AudioChunkService.read(roomId,clientId,trackId,streamEpoch,chunkIndex) trả AudioChunkData ở server/audio.
Domain record chứa roomId, trackId, streamEpoch, chunkIndex, startFrame, frameCount, isLast và immutable PCM bytes.
Không có requestId, MessageType, protocolVersion, socket hoặc ThreadLocal trong domain/audio boundary.
Handler/protocol layer nhận domain data rồi tạo AudioChunkMetadata + wire AudioChunk,
echo requestId của incoming request và derive pcmByteLength từ raw PCM length.
Không sinh response UUID trong AudioChunkService; không đưa wire identity vào file reader.
Chỉ interface/value record đã có; chưa có WAV reader, service implementation, streaming hoặc network adapter.

250 ms là configurable default, không wire constant; chunkFrames trong TrackMetadata là grid thực tế Client phải dùng.
Format không lặp trong chunk. P0 không có checksum per-chunk; PCM integrity/hash vẫn có thể dùng trong tests.

## Fixture

[audio-chunk-v1.json](examples/audio-chunk-v1.json): last chunk của synthetic track totalFrames=45,
chunkFrames=44; index=1, start=44, count=1, PCM hex 01020304.
Wrapper metadata/pcmHex chỉ để đọc fixture, không phải control JSON gửi TCP.
Test ghép ushort metadata length + JSON + PCM độc lập encoder và kiểm tra outer header riêng.
Đây không phải bài nhạc đã chuẩn bị; nhóm vẫn cần 3 WAV có quyền sử dụng cho integration.
