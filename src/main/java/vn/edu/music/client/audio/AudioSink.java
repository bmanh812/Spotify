package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import javax.sound.sampled.LineUnavailableException;

/** Device boundary. Calls are owned by the audio worker; implementations must preserve PCM frame alignment. */
public interface AudioSink extends AutoCloseable {
    void open(TrackMetadata track) throws LineUnavailableException;
    int availableBytes();
    long bufferedFrames();
    long processedFrames();
    int write(byte[] pcm, int offset, int length);
    void start();
    void stop();
    void flush();
    @Override void close();
}
