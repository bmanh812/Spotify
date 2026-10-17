package vn.edu.music.server.audio;

import java.util.Objects;

/** Domain object only; binary audio metadata serialization is a pending shared-protocol task. */
public record AudioChunk(String roomId, String trackId, long streamEpoch, long chunkIndex,
                         long startFrame, int frameCount, boolean last, byte[] pcm) {
    public AudioChunk {
        pcm = Objects.requireNonNull(pcm, "pcm is required").clone();
    }

    @Override
    public byte[] pcm() {
        return pcm.clone();
    }
}
