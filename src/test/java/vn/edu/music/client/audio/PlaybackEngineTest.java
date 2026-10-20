package vn.edu.music.client.audio;

import org.junit.jupiter.api.Test;
import vn.edu.music.common.model.TrackMetadata;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

class PlaybackEngineTest {
    static class FakeSink implements AudioSink {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        long processed;
        long queued;
        int writeLimit = 400;
        boolean running;
        boolean closed;
        boolean failOpen;
        public void open(TrackMetadata track) throws LineUnavailableException {
            if (failOpen) throw new LineUnavailableException("No test device");
            processed = 0; queued = 0; written.reset(); closed = false;
        }
        public int availableBytes() { return 44100; }
        public long bufferedFrames() { return queued; }
        public long processedFrames() { return processed; }
        public int write(byte[] data, int offset, int length) {
            int count = Math.min(writeLimit, length); written.write(data, offset, count); queued += count / 4;
            return count;
        }
        public void start() { running = true; }
        public void stop() { running = false; }
        public void flush() { queued = 0; }
        public void close() { closed = true; running = false; }
        void consume(int count) { assertTrue(count <= queued); processed += count; queued -= count; }
    }

    @Test void partialWritesKeepAllPcmAndPositionUsesProcessedFrames() throws Exception {
        FakeSink sink = new FakeSink();
        try (var engine = new PlaybackEngine(sink, 500)) {
            long generation = engine.reset("r1", TRACK, 1, 0);
            assertTrue(engine.offer(chunk(0, 1), generation)); engine.start(generation);
            for (int i = 0; i < 200 && sink.written.size() < 44100; i++) engine.pump(1024, generation);
            assertArrayEquals(chunk(0, 1).pcm(), sink.written.toByteArray());
            assertEquals(0, engine.snapshot().actualPositionMs());
            sink.consume(4410); assertEquals(100, engine.snapshot().actualPositionMs());
            assertEquals(0, engine.snapshot().appBufferFrames()); assertEquals(6615, engine.snapshot().lineBufferFrames());
        }
        assertTrue(sink.closed);
    }

    @Test void seekFlushesAndRejectsOldCallbacksEvenWithSameEpoch() throws Exception {
        FakeSink sink = new FakeSink();
        try (var engine = new PlaybackEngine(sink, 500)) {
            long old = engine.reset("r1", TRACK, 1, 0); engine.offer(chunk(0, 1), old); engine.pump(100, old);
            long next = engine.reset("r1", TRACK, 1, 12345); // Local resync, authoritative epoch unchanged.
            assertFalse(engine.offer(chunk(1, 1), old)); assertEquals(0, engine.pump(100, old));
            engine.start(old); assertFalse(sink.running);
            assertTrue(engine.offer(chunk(1, 1), next));
            while (engine.snapshot().appBufferFrames() > 0) engine.pump(1024, next);
            assertArrayEquals(Arrays.copyOfRange(chunk(1, 1).pcm(), (12345 - 11025) * 4, 44100), sink.written.toByteArray());
            assertEquals(12345L * 1000 / 44100, engine.snapshot().actualPositionMs());
        }
    }

    @Test void zeroWritePauseResumeAndUnderrunAreObservable() throws Exception {
        FakeSink sink = new FakeSink();
        try (var engine = new PlaybackEngine(sink, 500)) {
            long generation = engine.reset("r1", TRACK, 1, 0); engine.start(generation);
            assertTrue(engine.snapshot().underrun());
            engine.offer(chunk(0, 1), generation); sink.writeLimit = 0;
            assertEquals(0, engine.pump(1024, generation)); assertEquals(11025, engine.snapshot().appBufferFrames());
            engine.pause(); assertFalse(engine.snapshot().running()); assertFalse(engine.snapshot().underrun());
            sink.writeLimit = 400; engine.start(generation); assertTrue(engine.pump(1024, generation) > 0);
        }
    }

    @Test void deviceFailureClosesResourcesAndEdtCannotWriteAudio() throws Exception {
        FakeSink sink = new FakeSink(); sink.failOpen = true;
        try (var engine = new PlaybackEngine(sink, 500)) {
            assertThrows(LineUnavailableException.class, () -> engine.reset("r1", TRACK, 1, 0));
            assertTrue(sink.closed); assertNull(engine.snapshot().trackId());
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, () -> engine.reset("r1", TRACK, 1, 0)));
        }
    }

    @Test void stagedPcmAlsoCountsAgainstBufferCapacity() throws Exception {
        FakeSink sink = new FakeSink(); sink.writeLimit = 0;
        try (var engine = new PlaybackEngine(sink, 250)) {
            long generation = engine.reset("r1", TRACK, 1, 0); engine.offer(chunk(0, 1), generation);
            engine.pump(11025, generation);
            assertFalse(engine.offer(chunk(1, 1), generation)); assertEquals(11025, engine.snapshot().appBufferFrames());
        }
    }
}
