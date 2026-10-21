package vn.edu.music.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.music.common.model.TrackMetadata;

import javax.sound.sampled.*;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LocalWavSourceTest {
    @TempDir Path directory;

    @Test void diagnosticToneStreamsAsPcmAndCannotOverwriteExistingFile() throws Exception {
        Path path = directory.resolve("tone.wav"); WavSmokeMain.writeTone(path);
        byte[] original = Files.readAllBytes(path);
        assertThrows(FileAlreadyExistsException.class, () -> WavSmokeMain.writeTone(path));
        assertArrayEquals(original, Files.readAllBytes(path));
        try (var source = LocalWavSource.open(path)) {
            assertEquals(500, source.track().durationMs()); assertEquals(22050, source.track().totalFrames());
            var first = source.next(); var last = source.next();
            assertEquals(44100, first.pcm().length); assertFalse(first.metadata().isLast());
            assertEquals(44100, last.pcm().length); assertTrue(last.metadata().isLast()); assertNull(source.next());
        }
    }

    @Test void riffJunkAndOddPaddingDoNotShiftPcm() throws Exception {
        Path original = directory.resolve("tone.wav"); WavSmokeMain.writeTone(original);
        byte[] wave = Files.readAllBytes(original);
        int offset = 12;
        ByteBuffer le = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        while (!new String(wave, offset, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("data")) {
            int size = le.getInt(offset + 4); offset += 8 + size + (size & 1);
        }
        byte[] padded = new byte[wave.length + 12];
        System.arraycopy(wave, 0, padded, 0, offset);
        byte[] junk = {'J', 'U', 'N', 'K', 3, 0, 0, 0, 1, 2, 3, 0};
        System.arraycopy(junk, 0, padded, offset, junk.length);
        System.arraycopy(wave, offset, padded, offset + 12, wave.length - offset);
        ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN).putInt(4, padded.length - 8);
        Path withJunk = directory.resolve("junk.wav"); Files.write(withJunk, padded);
        try (var source = LocalWavSource.open(original); var other = LocalWavSource.open(withJunk)) {
            assertArrayEquals(source.next().pcm(), other.next().pcm());
            assertArrayEquals(source.next().pcm(), other.next().pcm());
        }
    }

    @Test void wrongFormatAndTruncatedPcmAreRejected() throws Exception {
        Path wrong = directory.resolve("mono.wav");
        try (var input = new AudioInputStream(new ByteArrayInputStream(new byte[2205]), new AudioFormat(22050, 8, 1, false, false), 2205)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, wrong.toFile());
        }
        assertThrows(UnsupportedAudioFileException.class, () -> LocalWavSource.open(wrong));
        Path path = directory.resolve("tone.wav"); WavSmokeMain.writeTone(path);
        byte[] bytes = Files.readAllBytes(path); Files.write(path, Arrays.copyOf(bytes, bytes.length - 8));
        try (var source = LocalWavSource.open(path)) {
            source.next(); assertThrows(IOException.class, source::next);
        }
    }

    @Test void localSmokeRunsThroughRuntimeWithPartialWritesAndReportsProcessedPosition() throws Exception {
        class Sink implements AudioSink {
            long frames;
            int closed;
            boolean running;
            long queued;
            public void open(TrackMetadata track) { frames = 0; queued = 0; }
            public int availableBytes() { return 44100; }
            public long bufferedFrames() { return queued; }
            public long processedFrames() { return frames; }
            public int write(byte[] bytes, int offset, int length) {
                int count = Math.min(4000, length); if (running) frames += count / 4; else queued += count / 4; return count;
            }
            public void start() { running = true; frames += queued; queued = 0; }
            public void stop() { running = false; }
            public void flush() { queued = 0; }
            public void close() { closed++; }
        }
        Sink sink = new Sink();
        Path path = directory.resolve("tone.wav"); WavSmokeMain.writeTone(path);
        var result = WavSmokeMain.play(path, sink);
        assertEquals(500, result.actualPositionMs()); assertEquals(22050, result.totalFrames()); assertTrue(sink.closed > 0);
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, () -> WavSmokeMain.play(path, sink)));
    }
}
