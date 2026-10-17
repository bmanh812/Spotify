# Roadmap nhóm

| Phase | Owner chính | Điều kiện nghiệm thu |
|---|---|---|
| 1 Scope và architecture | Cả nhóm | P0/P1, Host leave, queue empty và ranh giới được thống nhất |
| 2 Protocol v1 | TV1 + review TV2/3 | Payload, validation, framing, lỗi và golden fixtures pass |
| 3 Server room core | TV2 | 3 TCP Client join, 2 room cô lập |
| 4 Client core và UI | TV1 | Create/join/leave và state với Server thật |
| 5 Playback control | TV2 + TV1 | Host commands, roomVersion/streamEpoch đúng |
| 6 Audio streaming | TV2 + TV1 | Nhận và phát audio Server qua TCP |
| 7 Fixed buffer | TV3 + TV1 | Baseline đo được và buffer hữu hạn |
| 8 Server time sync | TV3 + TV1/2 | Clock/anchor và scheduled playback tích hợp |
| 9 Adaptive buffer | TV3 | Policy phản ứng với input thật, clamp/hysteresis đúng |
| 10 Drift correction | TV3 + TV1 | Gây lệch, đo và hội nhập lại timeline |
| 11 Network simulator | TV3 | Delay/jitter/bandwidth có seed và tác động lên audio thật |
| 12 Integration/experiment | Cả nhóm | Test P0, CSV gốc, chart và demo tái lập |

P1: reconnect/resume, host transfer, vote, MP3, UI nâng cao. Chưa triển khai P1.
Không gọi phase nào là DONE chỉ vì package hoặc interface đã có trong skeleton.
