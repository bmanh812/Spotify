package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import javax.sound.sampled.*;

public final class SourceDataLineSink implements AudioSink {
    private SourceDataLine line;
    private volatile String description = "Not opened";

    @Override public void open(TrackMetadata track) throws LineUnavailableException {
        close();
        AudioFormat format = new AudioFormat(track.sampleRate(), track.sampleSizeBits(), track.channels(), true, false);
        SourceDataLine opened = AudioSystem.getSourceDataLine(format);
        try {
            opened.open(format, 17640); line = opened;
            description = opened.getClass().getName() + "; " + opened.getLineInfo() + "; bufferBytes=" + opened.getBufferSize();
        }
        catch (LineUnavailableException | RuntimeException e) { opened.close(); throw e; }
    }
    @Override public int availableBytes() { return line.available(); }
    @Override public long bufferedFrames() { return (line.getBufferSize() - line.available()) / 4; }
    @Override public long processedFrames() { return line.getLongFramePosition(); }
    public String description() { return description; }
    @Override public int write(byte[] pcm, int offset, int length) { return line.write(pcm, offset, length); }
    @Override public void start() { line.start(); }
    @Override public void stop() { if (line != null) line.stop(); }
    @Override public void flush() { if (line != null) line.flush(); }
    @Override public void close() {
        SourceDataLine closing = line;
        line = null;
        if (closing != null) {
            try { closing.stop(); }
            finally { try { closing.flush(); } finally { closing.close(); } }
        }
    }
}
