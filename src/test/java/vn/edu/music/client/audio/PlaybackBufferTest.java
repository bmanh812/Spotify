package vn.edu.music.client.audio;

import org.junit.jupiter.api.Test;
import vn.edu.music.common.protocol.*;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

class PlaybackBufferTest {
    @Test void restoresOutOfOrderChunksWithoutSkippingGapsAndPreservesAllPcm() throws Exception {
        var buffer = new PlaybackBuffer(1500); buffer.reset("r1", TRACK, 1, 0);
        assertTrue(buffer.offer(chunk(1, 1))); assertEquals(0, buffer.snapshot().contiguousFrames());
        assertEquals(0, buffer.poll(100).length);
        assertTrue(buffer.offer(chunk(0, 1))); assertFalse(buffer.offer(chunk(0, 1)));
        assertTrue(buffer.offer(chunk(3, 1))); assertTrue(buffer.offer(chunk(2, 1)));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) expected.write(chunk(i, 1).pcm());
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        while (buffer.snapshot().queuedFrames() > 0) actual.write(buffer.poll(1024));
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        assertEquals(TRACK.totalFrames(), buffer.snapshot().nextFrame());
    }

    @Test void enforcesCapacityAndRejectsStaleAudioWithoutBlocking() throws Exception {
        var buffer = new PlaybackBuffer(250); buffer.reset("r1", TRACK, 1, 0);
        assertFalse(buffer.offer(chunk(0, 2))); assertTrue(buffer.offer(chunk(0, 1)));
        assertFalse(buffer.offer(chunk(1, 1))); assertEquals(250, buffer.snapshot().levelMs());
        buffer.poll(11025); assertTrue(buffer.offer(chunk(1, 1)));
        buffer.reset("r1", TRACK, 2, 0); assertEquals(0, buffer.snapshot().queuedFrames());
        assertFalse(buffer.offer(chunk(0, 1)));
    }

    @Test void seekTrimsPrefixToExactFrameAndDuplicatesCannotReplayConsumedAudio() throws Exception {
        var buffer = new PlaybackBuffer(500); buffer.reset("r1", TRACK, 1, 12345);
        assertFalse(buffer.offer(chunk(0, 1))); assertTrue(buffer.offer(chunk(1, 1)));
        assertArrayEquals(Arrays.copyOfRange(chunk(1, 1).pcm(), (12345 - 11025) * 4, 44100), buffer.poll(11025));
        assertFalse(buffer.offer(chunk(1, 1)));
    }

    @Test void supportsExactLastPartialChunk() throws Exception {
        var track = new vn.edu.music.common.model.TrackMetadata("t1", "Short", 250, 44100, 2, 16, 4, 11026, 44104, 11025, 2);
        var buffer = new PlaybackBuffer(500); buffer.reset("r1", track, 1, 11025);
        var m = new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1, id(), "r1", "t1", 1, 1, 11025, 1, true, 4);
        assertTrue(buffer.offer(new AudioChunk(m, new byte[]{1, 2, 3, 4})));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.poll(10)); assertEquals(0, buffer.snapshot().queuedFrames());
    }

    @Test void rejectsMalformedActiveChunkBeforeItReachesDevice() throws Exception {
        var buffer = new PlaybackBuffer(500); buffer.reset("r1", TRACK, 1, 0);
        assertThrows(ProtocolException.class, () -> buffer.offer(new AudioChunk(chunk(0, 1).metadata(), new byte[3])));
        assertEquals(0, buffer.snapshot().queuedFrames());
    }
}
