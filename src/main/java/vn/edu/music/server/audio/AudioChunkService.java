package vn.edu.music.server.audio;

import java.io.IOException;

/** TODO TV2: membership/epoch validation, frame-aligned disk reads and last-chunk handling. */
public interface AudioChunkService {
    AudioChunk read(String roomId, String clientId, String trackId,
                    long streamEpoch, long chunkIndex) throws IOException;
}
