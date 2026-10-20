package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import vn.edu.music.common.protocol.*;

import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Physical PCM storage only. Buffer target, watermark and prefetch decisions belong to TV3. */
public final class PlaybackBuffer {
    public record Snapshot(long queuedFrames, long contiguousFrames, long capacityFrames, long nextFrame) {
        public long levelMs() { return queuedFrames * 1000 / ProtocolLimits.SAMPLE_RATE; }
    }

    private final long capacityFrames;
    private final NavigableMap<Long, byte[]> blocks = new TreeMap<>();
    private String roomId;
    private TrackMetadata track;
    private long epoch;
    private long nextFrame;
    private long queuedFrames;

    public PlaybackBuffer(int capacityMs) {
        if (capacityMs < 1 || capacityMs > 60000) throw new IllegalArgumentException("Capacity must be 1..60000 ms");
        capacityFrames = (long) capacityMs * ProtocolLimits.SAMPLE_RATE / 1000;
    }

    public synchronized void reset(String roomId, TrackMetadata track, long epoch, long targetFrame) throws ProtocolException {
        ProtocolValidator.validateTrack(track);
        if (roomId == null || !roomId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || epoch < 1
                || targetFrame < 0 || targetFrame >= track.totalFrames()) throw new ProtocolException("Invalid buffer context");
        this.roomId = roomId; this.track = track; this.epoch = epoch; this.nextFrame = targetFrame;
        blocks.clear(); queuedFrames = 0;
    }

    /** False means stale, duplicate/already consumed, or capacity exhausted. Never waits for space. */
    public synchronized boolean offer(AudioChunk chunk) throws ProtocolException {
        if (track == null) return false;
        var m = chunk.metadata();
        if (!roomId.equals(m.roomId()) || !track.trackId().equals(m.trackId()) || epoch != m.streamEpoch()) return false;
        AudioChunkCodec.validateMetadata(m, chunk.pcm().length);
        AudioChunkCodec.validateAgainstTrack(m, track);
        long end = m.startFrame() + m.frameCount();
        if (end <= nextFrame) return false;
        long start = Math.max(nextFrame, m.startFrame());
        if (blocks.containsKey(start)) return false;
        int count = (int) (end - start);
        if (count > capacityFrames - queuedFrames) return false;
        byte[] pcm = chunk.pcm();
        blocks.put(start, Arrays.copyOfRange(pcm, (int) (start - m.startFrame()) * 4, pcm.length));
        queuedFrames += count;
        return true;
    }

    /** Returns only contiguous frames, up to maxFrames. Empty means a gap/underrun, not necessarily EOF. */
    public synchronized byte[] poll(int maxFrames) {
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        byte[] data = blocks.remove(nextFrame);
        if (data == null) return new byte[0];
        int count = Math.min(maxFrames, data.length / 4);
        if (count * 4 < data.length) blocks.put(nextFrame + count, Arrays.copyOfRange(data, count * 4, data.length));
        nextFrame += count; queuedFrames -= count;
        return Arrays.copyOf(data, count * 4);
    }

    public synchronized Snapshot snapshot() {
        long contiguous = 0;
        long cursor = nextFrame;
        for (var block : blocks.tailMap(nextFrame, true).entrySet()) {
            if (block.getKey() != cursor) break;
            cursor += block.getValue().length / 4;
            contiguous = cursor - nextFrame;
        }
        return new Snapshot(queuedFrames, contiguous, capacityFrames, nextFrame);
    }

    public synchronized void clear() { blocks.clear(); queuedFrames = 0; track = null; roomId = null; }
}
