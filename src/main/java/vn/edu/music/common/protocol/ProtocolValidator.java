package vn.edu.music.common.protocol;

import vn.edu.music.common.model.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.IOException;

import static vn.edu.music.common.protocol.ProtocolLimits.*;

/** Stateless schema/invariant checks only; no membership lookup, mutation, cache or clock algorithm. */
public final class ProtocolValidator {
    private static final JsonMessageCodec ENVELOPES = new JsonMessageCodec();
    private ProtocolValidator() { }

    public static Payloads.Payload validate(ProtocolMessage message) throws ProtocolException {
        try {
            return validateContent(message);
        } catch (ProtocolException error) {
            throw error.withCorrelation(correlation(message));
        }
    }

    /** Contract-only direction guard; deliberately not wired into the bootstrap transport yet. */
    public static Payloads.Payload validateClientRequest(ProtocolMessage message) throws ProtocolException {
        return validateDirection(message, true);
    }

    public static Payloads.Payload validateServerMessage(ProtocolMessage message) throws ProtocolException {
        return validateDirection(message, false);
    }

    private static Payloads.Payload validateDirection(ProtocolMessage message, boolean clientToServer) throws ProtocolException {
        require(message != null, "Envelope is required");
        if (message.protocolVersion() != VERSION) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Expected protocolVersion=1", correlation(message));
        }
        if (message.type().isClientRequest() != clientToServer) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_MESSAGE, "Unsupported message direction", correlation(message));
        }
        return validate(message);
    }

    private static ProtocolException.Correlation correlation(ProtocolMessage message) {
        if (message == null) return null;
        try {
            return new ProtocolException.Correlation(message.requestId(), message.roomId());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static Payloads.Payload validateContent(ProtocolMessage message) throws ProtocolException {
        require(message != null, "Envelope is required");
        try {
            ENVELOPES.encode(message); // Count caps are Server policy; the actual wire ceiling is still enforced.
        } catch (ProtocolException error) {
            throw error;
        } catch (IOException error) {
            throw new ProtocolException("Envelope cannot be serialized");
        }
        if (message.protocolVersion() != VERSION) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Expected protocolVersion=1");
        }
        switch (message.type()) {
            case CREATE_ROOM, JOIN_ROOM, PING, PONG -> require(message.roomId() == null,
                    "This message must not carry roomId");
            case ERROR -> { if (message.roomId() != null) id(message.roomId(), "roomId"); }
            default -> id(message.roomId(), "roomId");
        }
        Payloads.Payload payload = PayloadCodec.parse(message);
        switch (payload) {
            case Payloads.CreateRoom p -> text(p.nickname(), "nickname", 48);
            case Payloads.JoinRoom p -> { text(p.nickname(), "nickname", 48); roomCode(p.roomCode()); }
            case Payloads.LeaveRoom ignored -> { }
            case Payloads.QueueAdd p -> { id(p.trackId(), "trackId"); revision(p.expectedRoomVersion()); }
            case Payloads.PlaybackCommand p -> revision(p.expectedRoomVersion());
            case Payloads.Seek p -> { nonnegative(p.positionMs(), "positionMs"); revision(p.expectedRoomVersion()); }
            case Payloads.ChunkRequest p -> {
                id(p.trackId(), "trackId"); positive(p.streamEpoch(), "streamEpoch");
                nonnegative(p.chunkIndex(), "chunkIndex");
            }
            case Payloads.Ping p -> timestamp(p.clientSendTimeMs());
            case Payloads.ClientMetrics p -> {
                nonnegative(p.streamEpoch(), "streamEpoch");
                nonnegative(p.bufferLevelMs(), "bufferLevelMs");
                positive(p.targetBufferMs(), "targetBufferMs");
                require(Double.isFinite(p.rttMs()) && p.rttMs() >= 0, "rttMs must be finite and nonnegative");
            }
            case Payloads.MembershipState p -> {
                sameRoom(message, p.state()); validateRoomState(p.state());
                id(p.clientId(), "clientId");
                require(p.state().participants().stream().anyMatch(member -> member.clientId().equals(p.clientId())),
                        "Recipient must be a participant");
                stateTime(p.state(), p.serverTimeMs()); validateStateAgainstCatalog(p.state(), p.tracks());
                if (message.type() == MessageType.ROOM_CREATED) {
                    require(p.state().roomVersion() == 1 && p.state().streamEpoch() == 0
                            && p.state().currentTrackId() == null && p.state().queue().isEmpty()
                            && p.state().participants().size() == 1 && p.state().hostClientId().equals(p.clientId()),
                            "ROOM_CREATED must contain a fresh room owned by the recipient");
                }
            }
            case Payloads.StateUpdate p -> {
                sameRoom(message, p.state()); validateRoomState(p.state()); stateTime(p.state(), p.serverTimeMs());
            }
            case Payloads.LeaveAck p -> { id(p.clientId(), "clientId"); revision(p.roomVersion()); }
            case Payloads.Pong p -> {
                timestamp(p.clientSendTimeMs()); timestamp(p.serverReceiveTimeMs()); timestamp(p.serverSendTimeMs());
                require(p.serverSendTimeMs() >= p.serverReceiveTimeMs(), "Server clock stepped backwards; discard sample");
            }
            case Payloads.MetricsAck p -> { nonnegative(p.streamEpoch(), "streamEpoch"); timestamp(p.serverReceiveTimeMs()); }
            case Payloads.RoomClosed p -> {
                revision(p.roomVersion()); require(p.reason() != null, "reason is required"); timestamp(p.serverTimeMs());
            }
            case Payloads.ErrorPayload p -> {
                require(p.code() != null, "code is required"); text(p.message(), "message", 256);
                if (p.currentRoomVersion() != null) revision(p.currentRoomVersion());
                // retryable is an instance-level safety decision; common cannot infer business outcome.
            }
            default -> throw new ProtocolException("Unrecognized payload");
        }
        return payload;
    }

    public static void validateRoomState(RoomState state) throws ProtocolException {
        require(state != null, "state is required");
        id(state.roomId(), "roomId"); roomCode(state.roomCode()); id(state.hostClientId(), "hostClientId");
        revision(state.roomVersion()); nonnegative(state.streamEpoch(), "streamEpoch");
        nonnegative(state.positionMs(), "positionMs"); timestamp(state.anchorServerTimeMs());
        require(state.playbackState() != null, "playbackState is required");
        require(!state.participants().isEmpty(), "Room must contain at least one participant");
        Set<String> members = new HashSet<>();
        for (Participant member : state.participants()) {
            require(member != null, "Null participant"); id(member.clientId(), "clientId");
            text(member.nickname(), "nickname", 48);
            require(members.add(member.clientId()), "Duplicate participant");
        }
        require(members.contains(state.hostClientId()), "Host must be a participant");
        Set<String> items = new HashSet<>();
        for (QueueItem item : state.queue()) {
            require(item != null, "Null queue item"); id(item.queueItemId(), "queueItemId");
            id(item.trackId(), "trackId"); id(item.addedByClientId(), "addedByClientId");
            require(items.add(item.queueItemId()), "Duplicate queueItemId");
        }
        if (state.currentTrackId() == null) {
            require(state.playbackState() == PlaybackState.PAUSED && state.positionMs() == 0,
                    "No current track requires PAUSED at position 0");
        } else {
            id(state.currentTrackId(), "currentTrackId"); positive(state.streamEpoch(), "streamEpoch");
        }
    }

    public static void validateCatalog(List<TrackMetadata> tracks) throws ProtocolException {
        require(tracks != null, "Catalog is required");
        Set<String> ids = new HashSet<>();
        for (TrackMetadata track : tracks) {
            validateTrack(track); require(ids.add(track.trackId()), "Duplicate trackId");
        }
    }

    public static void validateStateAgainstCatalog(RoomState state, List<TrackMetadata> tracks) throws ProtocolException {
        validateRoomState(state); validateCatalog(tracks);
        Set<String> ids = new HashSet<>(tracks.stream().map(TrackMetadata::trackId).toList());
        for (QueueItem item : state.queue()) require(ids.contains(item.trackId()), "Queue references unknown track");
        if (state.currentTrackId() != null) {
            TrackMetadata current = tracks.stream().filter(t -> t.trackId().equals(state.currentTrackId()))
                    .findFirst().orElseThrow(() -> new ProtocolException("Unknown currentTrackId"));
            require(state.positionMs() < current.durationMs(), "Current-track snapshot cannot anchor at permanent EOF");
        }
    }

    public static void validateTrack(TrackMetadata track) throws ProtocolException {
        require(track != null, "track is required"); id(track.trackId(), "trackId"); text(track.title(), "title", 128);
        require(track.sampleRate() == SAMPLE_RATE && track.channels() == CHANNELS
                && track.sampleSizeBits() == SAMPLE_SIZE_BITS && track.frameSizeBytes() == FRAME_SIZE_BYTES,
                "P0 requires PCM signed 16-bit LE, 44100 Hz, stereo");
        require(track.totalFrames() >= 45 && track.totalFrames() <= Long.MAX_VALUE / FRAME_SIZE_BYTES,
                "Track must contain at least 1 ms of PCM and fit int64 bytes");
        require(track.totalPcmBytes() == track.totalFrames() * FRAME_SIZE_BYTES, "totalPcmBytes mismatch");
        long duration = (track.totalFrames() / SAMPLE_RATE) * 1000
                + (track.totalFrames() % SAMPLE_RATE) * 1000 / SAMPLE_RATE;
        require(track.durationMs() == duration, "durationMs must be floor(totalFrames*1000/sampleRate)");
        require(track.chunkFrames() > 0 && track.chunkFrames() <= MAX_PCM_BYTES / FRAME_SIZE_BYTES,
                "chunkFrames exceeds PCM limit");
        require(track.chunkCount() == 1 + (track.totalFrames() - 1) / track.chunkFrames(), "chunkCount mismatch");
    }

    private static void sameRoom(ProtocolMessage message, RoomState state) throws ProtocolException {
        require(state != null && message.roomId().equals(state.roomId()), "Envelope/state roomId mismatch");
    }

    private static void stateTime(RoomState state, long serverTimeMs) throws ProtocolException {
        timestamp(serverTimeMs);
        require(state.playbackState() != PlaybackState.PAUSED || state.anchorServerTimeMs() <= serverTimeMs,
                "PAUSED anchor cannot be after the snapshot timestamp");
    }

    static void id(String value, String field) throws ProtocolException {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), field + " must be an opaque 1..64 ASCII ID");
    }
    static void roomCode(String value) throws ProtocolException {
        require(value != null && value.matches("[A-Z0-9]{6}"), "roomCode must contain 6 uppercase ASCII letters/digits");
    }
    static void text(String value, String field, int limit) throws ProtocolException {
        require(value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= limit
                && value.codePoints().noneMatch(Character::isISOControl), field + " is missing or invalid");
    }
    static void timestamp(long value) throws ProtocolException { positive(value, "epoch timestamp"); }
    static void revision(long value) throws ProtocolException { positive(value, "roomVersion"); }
    static void positive(long value, String field) throws ProtocolException { require(value > 0, field + " must be positive"); }
    static void nonnegative(long value, String field) throws ProtocolException { require(value >= 0, field + " must be nonnegative"); }
    static void require(boolean condition, String message) throws ProtocolException {
        if (!condition) throw new ProtocolException(message);
    }
}
