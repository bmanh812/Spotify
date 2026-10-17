package vn.edu.music.server.track;

import java.util.List;
import java.util.Optional;

/** TODO TV2: scan permitted sample WAV files, validate PCM and build the track catalog. */
public interface TrackRepository {
    List<TrackMetadata> list();

    Optional<TrackMetadata> find(String trackId);
}
