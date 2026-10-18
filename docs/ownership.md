# Phân công và ranh giới

| Owner | Sở hữu | Chưa triển khai |
|---|---|---|
| TV1 | common/model, common/protocol, client/network, client/room, client/audio, client/ui | Request futures, Swing, buffer và playback |
| TV2 | server/config, server/network, server/room, server/track, server/audio | Room, permissions, queue, timeline và streaming |
| TV3 | client/sync, client/buffer, experiment | RTT/clock, fixed/adaptive, drift, simulator và CSV |

Protocol v1 FROZEN FOR P0 IMPLEMENTATION ở Checkpoint 1D, giữ nguyên A01–A16 đã áp dụng ở 1C, với DTO/validator/codecs và tests.
TV1 nhận ownership, TV2/TV3 sử dụng contract thống nhất; không tự đổi semantics sau freeze.
Shared models đã chuyển khỏi Server sang common/model;
không tự tạo DTO trùng ở các package khác. Không suy ra nghiệp vụ/dedup/sync đã implement từ việc có common codec.

TV2 implement Server timestamp handler theo contract do TV3 review; TV3 không sửa Server
business logic song song. TV1 cung cấp engine/buffer hooks; TV3 ghép policy, không viết Client riêng.
AudioChunkData là Server-only domain result, không requestId; handler/protocol tạo wire AudioChunkMetadata và echo requestId.
Không đưa RoomSession, locks, registry, file handles hoặc schedulers vào common.
TV3 quản lý sync/metrics interval và experiment parameters; periodic SYNC_STATE 1 Hz không phải protocol requirement.

Mỗi PR nêu test và phạm vi. Main không force push. Integration thuộc cả ba người,
nhưng mỗi bug phải có một owner xử lý và scenario tái hiện.
