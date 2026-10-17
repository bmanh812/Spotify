package vn.edu.music.server.track;

/** Canonical PCM metadata. WAV parsing and validation are intentionally not implemented yet. */
public record TrackMetadata(String trackId, String title, long durationMs, int sampleRate,
                            int channels, int sampleSizeBits, int frameSizeBytes,
                            long totalFrames, long totalPcmBytes, int chunkFrames, long chunkCount) {
}
