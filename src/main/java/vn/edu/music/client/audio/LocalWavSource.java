package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import vn.edu.music.common.protocol.*;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Local diagnostic input only. Production room audio still comes exclusively from TCP. */
public final class LocalWavSource implements AutoCloseable {
    private final AudioInputStream input;
    private final TrackMetadata track;
    private long index;

    private LocalWavSource(AudioInputStream input, TrackMetadata track) { this.input = input; this.track = track; }

    public static LocalWavSource open(Path path) throws IOException, UnsupportedAudioFileException {
        if (!AudioFileFormat.Type.WAVE.equals(AudioSystem.getAudioFileFormat(path.toFile()).getType())) {
            throw new UnsupportedAudioFileException("Local smoke requires a WAV file");
        }
        AudioInputStream input = AudioSystem.getAudioInputStream(path.toFile());
        try {
            AudioFormat format = input.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleRate() != 44100
                    || format.getFrameRate() != 44100 || format.getSampleSizeInBits() != 16 || format.getChannels() != 2
                    || format.getFrameSize() != 4 || format.isBigEndian()) {
                throw new UnsupportedAudioFileException("Expected PCM signed 16-bit little-endian, stereo, 44100 Hz");
            }
            long frames = input.getFrameLength();
            if (frames < 45 || frames > Long.MAX_VALUE / 4) throw new IOException("Invalid or unknown WAV frame count");
            long duration = frames / 44100 * 1000 + frames % 44100 * 1000 / 44100;
            var track = new TrackMetadata("local-wav", "Local WAV smoke", duration, 44100, 2, 16, 4,
                    frames, frames * 4, 11025, 1 + (frames - 1) / 11025);
            ProtocolValidator.validateTrack(track);
            return new LocalWavSource(input, track);
        } catch (IOException | UnsupportedAudioFileException | RuntimeException e) { input.close(); throw e; }
    }

    public TrackMetadata track() { return track; }

    public AudioChunk next() throws IOException {
        if (index >= track.chunkCount()) return null;
        long start = index * track.chunkFrames();
        int frames = (int) Math.min(track.chunkFrames(), track.totalFrames() - start);
        byte[] pcm = input.readNBytes(frames * 4);
        if (pcm.length != frames * 4) throw new IOException("Truncated WAV PCM data");
        var metadata = new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1, UUID.randomUUID().toString(), "local-smoke",
                track.trackId(), 1, index, start, frames, index + 1 == track.chunkCount(), pcm.length);
        index++;
        return new AudioChunk(metadata, pcm);
    }

    @Override public void close() throws IOException { input.close(); }
}
