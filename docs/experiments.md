# Kế hoạch experiment

Chưa có run hoặc kết quả. Owner TV3, TV1/TV2 cung cấp integration hooks.

- A: Fixed vs Adaptive; giữ sync, track, bitrate, window và seed giống nhau.
- B: Immediate-ready vs Scheduled + correction; giữ fixed buffer giống nhau.
- C: 1/3/5/10 Client và multi-room; headless dùng chung core, ghi rõ không đo thiết bị thật.

Profile stable/medium/poor/changing và một profile insufficient-bandwidth riêng.
Lưu cấu hình hai chiều, seed, burst schedule, checksum track, commit/JDK/OS và số Client.

Metrics: startup delay, rebuffer count/duration, buffer level, app RTT/variation,
download throughput, absolute drift, pairwise skew, resync count và control latency.
Phân biệt thời gian chờ scheduled anchor, buffer-ready và first-play.

CSV/log gốc ở results/raw; summaries/charts ở results/processed. Không tạo số liệu giả,
không loại các run xấu âm thầm, không gọi software drift là độ lệch âm thanh vật lý tại loa.
