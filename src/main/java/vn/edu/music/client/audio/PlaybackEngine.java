package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import vn.edu.music.common.protocol.AudioChunk;
import vn.edu.music.common.protocol.ProtocolException;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;

/** Local device mechanics; TV3 decides when to start/pump/reset. All methods serialize on this engine. */
public final class PlaybackEngine implements AutoCloseable {
    public record Snapshot(String roomId, String trackId, long streamEpoch, long generation,
                           long actualPositionMs, long appBufferFrames, long appContiguousFrames,
                           long capacityFrames, long lineBufferFrames,
                           boolean running, boolean underrun, long sampledNanos) { }

    private final AudioSink sink;
    private final PlaybackBuffer buffer;
    private TrackMetadata track;
    private String roomId;
    private long epoch;
    private long generation;
    private long baseFrame;
    private long processedOrigin;
    private byte[] staged = new byte[0];
    private int stagedOffset;
    private boolean running;
    private boolean closed;

    public PlaybackEngine(AudioSink sink, int capacityMs) {
        this.sink = java.util.Objects.requireNonNull(sink);
        buffer = new PlaybackBuffer(capacityMs);
    }

    public synchronized long reset(String roomId, TrackMetadata track, long epoch, long targetFrame)
            throws ProtocolException, LineUnavailableException {
        workerOnly(); ensureOpen();
        // Validate before stopping a working device.
        PlaybackBuffer validation = new PlaybackBuffer(1);
        validation.reset(roomId, track, epoch, targetFrame);
        running = false;
        sink.stop(); sink.flush();
        buffer.clear(); staged = new byte[0]; stagedOffset = 0;
        this.track = null;
        try {
            sink.open(track);
            buffer.reset(roomId, track, epoch, targetFrame);
            this.roomId = roomId; this.track = track; this.epoch = epoch; this.baseFrame = targetFrame;
            processedOrigin = sink.processedFrames();
            return ++generation;
        } catch (LineUnavailableException | RuntimeException e) { sink.close(); throw e; }
    }

    public synchronized boolean offer(AudioChunk chunk, long expectedGeneration) throws ProtocolException {
        ensureOpen();
        if (track == null || generation != expectedGeneration) return false;
        // Staged PCM is also application buffer; it must consume the same physical capacity budget.
        long stagedFrames = (staged.length - stagedOffset) / 4;
        var b = buffer.snapshot();
        if (chunk.metadata().frameCount() > b.capacityFrames() - b.queuedFrames() - stagedFrames) return false;
        return buffer.offer(chunk);
    }

    public synchronized void start(long expectedGeneration) {
        workerOnly(); ensureReady();
        if (generation != expectedGeneration) return;
        sink.start(); running = true;
    }

    public synchronized void pause() { workerOnly(); if (closed) return; sink.stop(); running = false; }

    /** Bounded work per tick. Partial/zero writes retain the remaining PCM for the next tick. */
    public synchronized int pump(int maxFrames, long expectedGeneration) {
        workerOnly(); ensureReady();
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        if (generation != expectedGeneration) return 0;
        int available = sink.availableBytes() / 4;
        int budget = Math.min(maxFrames, available);
        if (budget <= 0) return 0;
        if (stagedOffset == staged.length) { staged = buffer.poll(budget); stagedOffset = 0; }
        int bytes = (int) Math.min((long) budget * 4, staged.length - stagedOffset);
        if (bytes == 0) return 0;
        try {
            int written = sink.write(staged, stagedOffset, bytes);
            if (written < 0 || written > bytes || written % 4 != 0) throw new IllegalStateException("AudioSink returned invalid write length");
            stagedOffset += written;
            return written / 4;
        } catch (RuntimeException e) { running = false; sink.stop(); throw e; }
    }

    public synchronized Snapshot snapshot() {
        var b = buffer.snapshot();
        long lineFrames = track == null || closed ? 0 : sink.bufferedFrames();
        long progressed = track == null ? 0 : Math.max(0, sink.processedFrames() - processedOrigin);
        long frame = track == null ? 0 : baseFrame + Math.min(track.totalFrames() - baseFrame, progressed);
        long ms = (frame / 44100) * 1000 + (frame % 44100) * 1000 / 44100;
        long stagedFrames = (staged.length - stagedOffset) / 4;
        boolean eof = track != null && b.nextFrame() >= track.totalFrames() && stagedFrames == 0;
        return new Snapshot(roomId, track == null ? null : track.trackId(), epoch, generation, ms,
                b.queuedFrames() + stagedFrames, b.contiguousFrames() + stagedFrames, b.capacityFrames(), lineFrames, running,
                running && !eof && b.contiguousFrames() + stagedFrames == 0 && lineFrames == 0, System.nanoTime());
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Engine closed"); }
    private void ensureReady() { ensureOpen(); if (track == null) throw new IllegalStateException("No track loaded"); }
    private static void workerOnly() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Audio device work must run outside EDT");
    }

    /** Release a stream without permanently closing the engine; next reset may open a new stream. */
    public synchronized void unload() {
        workerOnly(); ensureOpen();
        running = false; generation++;
        buffer.clear(); staged = new byte[0]; stagedOffset = 0;
        try { sink.close(); }
        finally { track = null; roomId = null; epoch = 0; baseFrame = 0; processedOrigin = 0; }
    }

    @Override public synchronized void close() {
        workerOnly();
        if (closed) return;
        closed = true; running = false; generation++;
        buffer.clear(); staged = new byte[0]; stagedOffset = 0;
        try { sink.close(); }
        finally { track = null; roomId = null; epoch = 0; baseFrame = 0; processedOrigin = 0; }
    }
}
