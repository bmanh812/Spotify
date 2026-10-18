package vn.edu.music.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Full authoritative snapshot shared by all modules. See docs/room-state.md. */
public record RoomState(
        @JsonProperty(required = true) String roomId,
        @JsonProperty(required = true) String roomCode,
        @JsonProperty(required = true) String hostClientId,
        @JsonProperty(required = true) List<Participant> participants,
        @JsonProperty(required = true) String currentTrackId,
        @JsonProperty(required = true) PlaybackState playbackState,
        @JsonProperty(required = true) long positionMs,
        @JsonProperty(required = true) long anchorServerTimeMs,
        @JsonProperty(required = true) long roomVersion,
        @JsonProperty(required = true) long streamEpoch,
        @JsonProperty(required = true) List<QueueItem> queue) {
    public RoomState {
        participants = List.copyOf(participants);
        queue = List.copyOf(queue);
    }
}
