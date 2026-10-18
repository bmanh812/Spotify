package vn.edu.music.common.protocol;

import java.util.Objects;

/** Immutable binary response; validation is performed by AudioChunkCodec. */
public record AudioChunk(AudioChunkMetadata metadata, byte[] pcm) {
    public AudioChunk {
        Objects.requireNonNull(metadata, "metadata is required");
        pcm = Objects.requireNonNull(pcm, "pcm is required").clone();
    }

    @Override
    public byte[] pcm() {
        return pcm.clone();
    }
}
