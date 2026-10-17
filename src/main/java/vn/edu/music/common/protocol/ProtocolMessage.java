package vn.edu.music.common.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Draft envelope. roomId is null before room binding and for a connection-level PING. */
public record ProtocolMessage(
        MessageType type, int protocolVersion, String requestId,
        String roomId, Map<String, Object> payload
) {
    public static final int DRAFT_VERSION = 1;

    public ProtocolMessage {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(requestId, "requestId is required");
        if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException("requestId must be a canonical UUID");
        }
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(payload, "payload is required")));
    }

    public static ProtocolMessage request(MessageType type, String roomId, Map<String, Object> payload) {
        return new ProtocolMessage(type, DRAFT_VERSION, UUID.randomUUID().toString(), roomId, payload);
    }

    public static ProtocolMessage error(ProtocolMessage request, ErrorCode code, String message) {
        return new ProtocolMessage(MessageType.ERROR, DRAFT_VERSION, request.requestId(), request.roomId(),
                Map.of("code", code.name(), "message", message, "retryable", false));
    }
}
