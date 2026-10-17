# Phân công và ranh giới

| Owner | Sở hữu | Chưa triển khai |
|---|---|---|
| TV1 | common/protocol, client/network, client/room, client/audio, client/ui | Request futures, Swing, buffer và playback |
| TV2 | server/config, server/network, server/room, server/track, server/audio | Room, permissions, queue, timeline và streaming |
| TV3 | client/sync, client/buffer, experiment | RTT/clock, fixed/adaptive, drift, simulator và CSV |

Foundation hiện có của common chỉ là draft để build/test bootstrap. TV1 nhận ownership và
cùng TV2/TV3 hoàn thiện trước khi freeze. Không tự tạo DTO trùng ở các package khác.

TV2 implement Server timestamp handler theo contract do TV3 review; TV3 không sửa Server
business logic song song. TV1 cung cấp engine/buffer hooks; TV3 ghép policy, không viết Client riêng.

Mỗi PR nêu test và phạm vi. Main không force push. Integration thuộc cả ba người,
nhưng mỗi bug phải có một owner xử lý và scenario tái hiện.
