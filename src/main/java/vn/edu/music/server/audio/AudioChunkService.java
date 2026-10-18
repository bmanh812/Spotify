package vn.edu.music.server.audio;

import java.io.IOException;

/** Domain/audio boundary only; handler/protocol layer owns wire metadata and requestId correlation. */
public interface AudioChunkService {
    AudioChunkData read(String roomId, String clientId, String trackId,
                        long streamEpoch, long chunkIndex) throws IOException;
}
