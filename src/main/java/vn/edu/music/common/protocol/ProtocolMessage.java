package vn.edu.music.common.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared v1 envelope; payload validation is explicit through PayloadCodec/ProtocolValidator. */
public record ProtocolMessage(
        @JsonProperty(required = true) MessageType type,
        @JsonProperty(required = true) int protocolVersion,
        @JsonProperty(required = true) String requestId,
        String roomId,
        @JsonProperty(required = true) Map<String, Object> payload
) {
    /** Compatibility name for the existing bootstrap, not a claim of group sign-off. */
    public static final int DRAFT_VERSION = ProtocolLimits.VERSION;

    public ProtocolMessage {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(requestId, "requestId is required");
        if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException("requestId must be a canonical UUID");
        }
        payload = freezeMap(Objects.requireNonNull(payload, "payload is required"), 0);
    }

    public static ProtocolMessage request(MessageType type, String roomId, Map<String, Object> payload) {
        return new ProtocolMessage(type, DRAFT_VERSION, UUID.randomUUID().toString(), roomId, payload);
    }

    public static ProtocolMessage error(ProtocolMessage request, ErrorCode code, String message) {
        return error(request, code, message, false);
    }

    /** The producer decides safety from the operation context, never from ErrorCode alone. */
    public static ProtocolMessage error(ProtocolMessage request, ErrorCode code, String message, boolean retryable) {
        return new ProtocolMessage(MessageType.ERROR, DRAFT_VERSION, request.requestId(), request.roomId(),
                Map.of("code", code.name(), "message", message, "retryable", retryable));
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON payload is too deeply nested or cyclic");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            copy.put(text, freeze(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON payload is too deeply nested or cyclic");
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map, depth);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(item -> freeze(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number && java.util.Set.of(Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, java.math.BigInteger.class, java.math.BigDecimal.class).contains(value.getClass())) {
            return value;
        }
        throw new IllegalArgumentException("Payload must contain JSON values only");
    }
}
