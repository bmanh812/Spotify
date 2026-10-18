package vn.edu.music.server.audio;

import java.util.Objects;

/** Domain result, not a wire DTO: deliberately contains no requestId, protocol version or socket. */
public record AudioChunkData(String roomId, String trackId, long streamEpoch, long chunkIndex,
                             long startFrame, int frameCount, boolean isLast, byte[] pcm) {
    public AudioChunkData {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(trackId, "trackId");
        pcm = Objects.requireNonNull(pcm, "pcm").clone();
    }

    @Override
    public byte[] pcm() {
        return pcm.clone();
    }
}
