# Buffer và synchronization dự kiến

Owner TV3; interfaces ghép với PlaybackBuffer/PlaybackEngine của TV1. Chưa có implementation.

- Fixed target ban đầu 1500 ms.
- Adaptive heuristic dự kiến: clamp(base + 2*SRTT + 4*RTTVariation, min, max).
- Thử base/min=500 ms, max=4000 ms; mọi hệ số phải configurable và được đánh giá.
- Input: app RTT/variation, chunk receive time, buffer occupancy, active-download throughput, rebuffer.
- Target tăng chỉ làm prefetch thêm, không đẩy playback lùi khỏi timeline chung.
- Sau underrun, chọn điểm hội nhập tương lai thay vì tiếp tục phát vị trí cũ.
- Local drift correction không tăng epoch chung của phòng.

RTTVariation không phải one-way packet jitter. Băng thông thấp hơn bitrate PCM kéo dài
không thể được khắc phục chỉ bằng tăng buffer. Không khẳng định heuristic là thuật toán chuẩn.
