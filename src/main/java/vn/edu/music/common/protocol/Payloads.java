package vn.edu.music.common.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.music.common.model.RoomState;
import vn.edu.music.common.model.TrackMetadata;

import java.util.List;

/** Required fields are explicit; semantic checks live in ProtocolValidator. */
public final class Payloads {
    private Payloads() { }
    public interface Payload { }

    public record CreateRoom(@JsonProperty(required = true) String nickname) implements Payload { }
    public record JoinRoom(@JsonProperty(required = true) String nickname,
                           @JsonProperty(required = true) String roomCode) implements Payload { }
    public record LeaveRoom() implements Payload { }
    public record QueueAdd(@JsonProperty(required = true) String trackId,
                           @JsonProperty(required = true) long expectedRoomVersion) implements Payload { }
    public record PlaybackCommand(@JsonProperty(required = true) long expectedRoomVersion) implements Payload { }
    public record Seek(@JsonProperty(required = true) long positionMs,
                       @JsonProperty(required = true) long expectedRoomVersion) implements Payload { }
    public record ChunkRequest(@JsonProperty(required = true) String trackId,
                               @JsonProperty(required = true) long streamEpoch,
                               @JsonProperty(required = true) long chunkIndex) implements Payload { }
    public record Ping(@JsonProperty(required = true) long clientSendTimeMs) implements Payload { }
    public record ClientMetrics(@JsonProperty(required = true) long streamEpoch,
                                @JsonProperty(required = true) long bufferLevelMs,
                                @JsonProperty(required = true) long targetBufferMs,
                                @JsonProperty(required = true) double rttMs,
                                @JsonProperty(required = true) long driftMs) implements Payload { }

    /** Also used for recipient-specific ROOM_STATE pushes; catalog is always complete. */
    public record MembershipState(@JsonProperty(required = true) String clientId,
                                  @JsonProperty(required = true) RoomState state,
                                  @JsonProperty(required = true) List<TrackMetadata> tracks,
                                  @JsonProperty(required = true) long serverTimeMs) implements Payload {
        public MembershipState { tracks = List.copyOf(tracks); }
    }
    public record StateUpdate(@JsonProperty(required = true) RoomState state,
                              @JsonProperty(required = true) long serverTimeMs) implements Payload { }
    public record LeaveAck(@JsonProperty(required = true) String clientId,
                           @JsonProperty(required = true) long roomVersion,
                           @JsonProperty(required = true) boolean roomClosed) implements Payload { }
    public record Pong(@JsonProperty(required = true) long clientSendTimeMs,
                       @JsonProperty(required = true) long serverReceiveTimeMs,
                       @JsonProperty(required = true) long serverSendTimeMs) implements Payload { }
    public record MetricsAck(@JsonProperty(required = true) long streamEpoch,
                             @JsonProperty(required = true) long serverReceiveTimeMs) implements Payload { }
    public enum CloseReason { HOST_LEFT, SERVER_SHUTDOWN }
    public record RoomClosed(@JsonProperty(required = true) long roomVersion,
                             @JsonProperty(required = true) CloseReason reason,
                             @JsonProperty(required = true) long serverTimeMs) implements Payload { }
    public record ErrorPayload(@JsonProperty(required = true) ErrorCode code,
                               @JsonProperty(required = true) String message,
                               @JsonProperty(required = true) boolean retryable,
                               Long currentRoomVersion) implements Payload { }
}
