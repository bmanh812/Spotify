package vn.edu.music.common.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Typed boundary; unlike JsonMessageCodec this validates the complete control message. */
public final class PayloadCodec {
    private static final ObjectMapper MAPPER = ContractJson.mapper();
    private PayloadCodec() { }

    public static Payloads.Payload decode(ProtocolMessage message) throws ProtocolException {
        return ProtocolValidator.validate(message);
    }

    public static ProtocolMessage message(MessageType type, String requestId, String roomId,
                                          Payloads.Payload payload) throws ProtocolException {
        Map<String, Object> map = MAPPER.convertValue(payload, new TypeReference<>() { });
        ProtocolMessage message = new ProtocolMessage(type, ProtocolLimits.VERSION, requestId, roomId, map);
        ProtocolValidator.validate(message);
        return message;
    }

    static Payloads.Payload parse(ProtocolMessage message) throws ProtocolException {
        Class<? extends Payloads.Payload> target = switch (message.type()) {
            case CREATE_ROOM -> Payloads.CreateRoom.class;
            case JOIN_ROOM -> Payloads.JoinRoom.class;
            case LEAVE_ROOM -> Payloads.LeaveRoom.class;
            case QUEUE_ADD -> Payloads.QueueAdd.class;
            case PLAY, PAUSE, NEXT_TRACK -> Payloads.PlaybackCommand.class;
            case SEEK -> Payloads.Seek.class;
            case AUDIO_CHUNK_REQUEST -> Payloads.ChunkRequest.class;
            case PING -> Payloads.Ping.class;
            case CLIENT_METRICS -> Payloads.ClientMetrics.class;
            case ROOM_CREATED, ROOM_STATE -> Payloads.MembershipState.class;
            case QUEUE_STATE, PLAYBACK_STATE, SYNC_STATE -> Payloads.StateUpdate.class;
            case LEAVE_ACK -> Payloads.LeaveAck.class;
            case PONG -> Payloads.Pong.class;
            case METRICS_ACK -> Payloads.MetricsAck.class;
            case ROOM_CLOSED -> Payloads.RoomClosed.class;
            case ERROR -> Payloads.ErrorPayload.class;
            case AUDIO_CHUNK -> throw new ProtocolException(ErrorCode.UNSUPPORTED_MESSAGE, "AUDIO_CHUNK requires a binary frame");
        };
        try {
            return MAPPER.convertValue(message.payload(), target);
        } catch (IllegalArgumentException error) {
            ProtocolException invalid = new ProtocolException("Invalid " + message.type() + " payload");
            invalid.initCause(error);
            throw invalid;
        }
    }
}
