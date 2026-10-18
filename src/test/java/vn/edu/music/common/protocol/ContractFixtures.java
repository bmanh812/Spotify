package vn.edu.music.common.protocol;

import vn.edu.music.common.model.*;
import java.util.List;

final class ContractFixtures {
    static final String ID = "12345678-1234-1234-1234-123456789abc";
    static final long TIME = 1792000000000L;
    static final TrackMetadata TRACK = new TrackMetadata("t1", "Tone", 1000, 44100, 2, 16, 4,
            44100, 176400, 11025, 4);
    static final RoomState STATE = new RoomState("r1", "ABC123", "c1",
            List.of(new Participant("c1", "Minh")), "t1", PlaybackState.PLAYING, 0, TIME,
            2, 1, List.of());
    static final RoomState INITIAL = new RoomState("r1", "ABC123", "c1", STATE.participants(), null,
            PlaybackState.PAUSED, 0, TIME, 1, 0, List.of());

    static ProtocolMessage message(MessageType type) throws ProtocolException {
        String room = switch (type) {
            case CREATE_ROOM, JOIN_ROOM, PING, PONG, ERROR -> null;
            default -> "r1";
        };
        Payloads.Payload payload = switch (type) {
            case CREATE_ROOM -> new Payloads.CreateRoom("Minh");
            case JOIN_ROOM -> new Payloads.JoinRoom("Minh", "ABC123");
            case LEAVE_ROOM -> new Payloads.LeaveRoom();
            case QUEUE_ADD -> new Payloads.QueueAdd("t1", 2);
            case PLAY, PAUSE, NEXT_TRACK -> new Payloads.PlaybackCommand(2);
            case SEEK -> new Payloads.Seek(500, 2);
            case AUDIO_CHUNK_REQUEST -> new Payloads.ChunkRequest("t1", 1, 0);
            case PING -> new Payloads.Ping(TIME);
            case CLIENT_METRICS -> new Payloads.ClientMetrics(1, 500, 1500, 20.5, -10);
            case ROOM_CREATED -> new Payloads.MembershipState("c1", INITIAL, List.of(TRACK), TIME);
            case ROOM_STATE -> new Payloads.MembershipState("c1", STATE, List.of(TRACK), TIME);
            case QUEUE_STATE, PLAYBACK_STATE, SYNC_STATE -> new Payloads.StateUpdate(STATE, TIME);
            case LEAVE_ACK -> new Payloads.LeaveAck("c1", 3, true);
            case PONG -> new Payloads.Pong(TIME, TIME + 10, TIME + 11);
            case METRICS_ACK -> new Payloads.MetricsAck(1, TIME);
            case ROOM_CLOSED -> new Payloads.RoomClosed(3, Payloads.CloseReason.HOST_LEFT, TIME);
            case ERROR -> new Payloads.ErrorPayload(ErrorCode.INVALID_REQUEST, "Invalid payload", false, null);
            case AUDIO_CHUNK -> throw new IllegalArgumentException("Binary only");
        };
        return PayloadCodec.message(type, ID, room, payload);
    }
}
