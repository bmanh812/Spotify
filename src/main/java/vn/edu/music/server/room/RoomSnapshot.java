package vn.edu.music.server.room;

import java.util.List;

/** Proposed domain snapshot, not a frozen wire DTO. Lists are copied before publishing. */
public record RoomSnapshot(String roomId, String roomCode, String hostClientId,
                           List<Participant> participants, String currentTrackId,
                           PlaybackState playbackState, long positionMs, long anchorServerTimeMs,
                           long roomVersion, long streamEpoch, List<QueueItem> queue) {
    public RoomSnapshot {
        participants = List.copyOf(participants);
        queue = List.copyOf(queue);
    }
}
